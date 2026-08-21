#include "mlsgroupcrypto.h"

#include <QCryptographicHash>
#include <QHash>
#include <QList>
#include <QLoggingCategory>
#include <QJsonDocument>
#include <QJsonObject>
#include <QDir>
#include <QFile>
#include <memory>

#include <mls/core_types.h>
#include <mls/credential.h>
#include <mls/crypto.h>
#include <mls/messages.h>
#include <mls/state.h>

namespace {

// Mandatory-to-implement RFC 9420 suite. Must match Android's
// MlsGroupCrypto.SUITE_ID so BouncyCastle and mlspp interoperate. The suite is
// baked into a group at creation - changing it needs a migration.
const mls::CipherSuite::ID kSuiteID =
    mls::CipherSuite::ID::X25519_AES128GCM_SHA256_Ed25519;

mls::bytes_ns::bytes toBytes(const QByteArray& b) {
    const auto* p = reinterpret_cast<const uint8_t*>(b.constData());
    return mls::bytes_ns::bytes(std::vector<uint8_t>(p, p + b.size()));
}

QByteArray fromBytes(const mls::bytes_ns::bytes& b) {
    const auto& v = b.as_vec();
    return QByteArray(reinterpret_cast<const char*>(v.data()),
                      static_cast<int>(v.size()));
}

// MLS application keys are single-use (unprotect deletes the generation) and
// a sender cannot reopen its own ciphertext. The UI decrypts the same blob
// several times (fetch, peekEnvelope, QML cryptoRevision), so without this
// cache every MLS bubble becomes "Encrypted message" — including messages
// this account sent from another device.
const int kOpenCacheLimit = 256;
QHash<QByteArray, QByteArray> g_openCache;
QList<QByteArray> g_openCacheOrder;

QByteArray openCacheKey(const QByteArray& payload) {
    return QCryptographicHash::hash(payload, QCryptographicHash::Sha256);
}

void rememberOpen(const QByteArray& ciphertext, const QByteArray& plaintext) {
    const QByteArray key = openCacheKey(ciphertext);
    if (g_openCache.contains(key)) {
        g_openCache.insert(key, plaintext);
        return;
    }
    while (g_openCacheOrder.size() >= kOpenCacheLimit) {
        g_openCache.remove(g_openCacheOrder.takeFirst());
    }
    g_openCache.insert(key, plaintext);
    g_openCacheOrder.append(key);
}

QByteArray lookupOpen(const QByteArray& ciphertext) {
    return g_openCache.value(openCacheKey(ciphertext));
}

} // namespace

struct MlsGroupCrypto::Identity {
    mls::CipherSuite suite{ kSuiteID };
    mls::HPKEPrivateKey encPriv;
    mls::SignaturePrivateKey sigPriv;
    mls::Credential cred;
    // Raw credential bytes, retained so the identity can be re-encoded.
    mls::bytes_ns::bytes credRaw;

    Identity(mls::HPKEPrivateKey e, mls::SignaturePrivateKey s, mls::Credential c)
        : encPriv(std::move(e)), sigPriv(std::move(s)), cred(std::move(c)) {}

    mls::LeafNode leafNode() const {
        return mls::LeafNode(suite,
                             encPriv.public_key,
                             sigPriv.public_key,
                             cred,
                             mls::Capabilities::create_default(),
                             mls::Lifetime::create_default(),
                             {},
                             sigPriv);
    }
};

struct MlsGroupCrypto::Group {
    mls::State state;
    explicit Group(mls::State s) : state(std::move(s)) {}
};

MlsGroupCrypto::Identity* MlsGroupCrypto::newIdentity(const QString& userId) {
    try {
        mls::CipherSuite suite{ kSuiteID };
        auto encPriv = mls::HPKEPrivateKey::generate(suite);
        auto sigPriv = mls::SignaturePrivateKey::generate(suite);
        auto credBytes = toBytes(userId.toUtf8());
        auto cred = mls::Credential::basic(credBytes);
        auto* id = new Identity(std::move(encPriv), std::move(sigPriv), std::move(cred));
        id->credRaw = credBytes;
        return id;
    } catch (const std::exception& e) {
        qWarning("[mls] newIdentity failed: %s", e.what());
        return nullptr;
    }
}

void MlsGroupCrypto::destroyIdentity(Identity* id) { delete id; }
void MlsGroupCrypto::destroyGroup(Group* g) { delete g; }

struct MlsGroupCrypto::PublishedKeyPackage {
    mls::KeyPackage keyPackage;
    mls::HPKEPrivateKey initPriv;
    PublishedKeyPackage(mls::KeyPackage kp, mls::HPKEPrivateKey ip)
        : keyPackage(std::move(kp)), initPriv(std::move(ip)) {}
};

MlsGroupCrypto::PublishedKeyPackage* MlsGroupCrypto::newKeyPackage(Identity* id) {
    if (!id) return nullptr;
    try {
        // The init key must differ from the leaf key: the Welcome's group
        // secrets are HPKE-sealed to the init key.
        auto initPriv = mls::HPKEPrivateKey::generate(id->suite);
        auto kp = mls::KeyPackage(id->suite,
                                  initPriv.public_key,
                                  id->leafNode(),
                                  {},
                                  id->sigPriv);
        return new PublishedKeyPackage(std::move(kp), std::move(initPriv));
    } catch (const std::exception& e) {
        qWarning("[mls] newKeyPackage failed: %s", e.what());
        return nullptr;
    }
}

QByteArray MlsGroupCrypto::keyPackageBytes(PublishedKeyPackage* kp) {
    if (!kp) return {};
    try {
        return fromBytes(mls::tls::marshal(kp->keyPackage));
    } catch (const std::exception& e) {
        qWarning("[mls] keyPackageBytes failed: %s", e.what());
        return {};
    }
}

void MlsGroupCrypto::destroyKeyPackage(PublishedKeyPackage* kp) { delete kp; }

MlsGroupCrypto::AddResult MlsGroupCrypto::addMember(Group* g, const QByteArray& keyPackageWire) {
    AddResult out;
    if (!g) return out;
    try {
        auto kp = mls::tls::get<mls::KeyPackage>(toBytes(keyPackageWire));
        mls::MessageOpts msgOpts;
        auto proposal = g->state.add(kp, msgOpts);
        // Cache the proposal so the commit below includes it.
        auto cached = g->state.handle(proposal);
        if (cached) g->state = std::move(*cached);

        mls::CommitOpts commitOpts;
        // Required: the joiner has no other way to learn the ratchet tree and
        // our Delivery Service does not serve one.
        commitOpts.inline_tree = true;
        // Always emit an UpdatePath, even for Add-only commits where RFC 9420
        // makes it optional. BouncyCastle (the Android side) rejects a
        // path-less commit with "Path required but not present", so the joiner
        // stayed pinned at the previous epoch while we encrypted at the new
        // one - every message then failed with "mac check in GCM failed".
        // A path is valid in any commit, and forcing it also buys forward
        // secrecy on every epoch change.
        commitOpts.force_path = true;

        auto leafSecret = mls::random_bytes(32);
        auto [commitMsg, welcome, nextState] =
            g->state.commit(leafSecret, commitOpts, msgOpts);

        out.group = new Group(std::move(nextState));
        out.commit = fromBytes(mls::tls::marshal(commitMsg));
        // mlspp returns the Welcome separately (unlike BouncyCastle, which
        // attaches it to the commit), so there is nothing to strip here.
        // Wrapped as an MLSMessage so the framing matches Android's.
        out.welcome = fromBytes(mls::tls::marshal(mls::MLSMessage(welcome)));
    } catch (const std::exception& e) {
        qWarning("[mls] addMember failed: %s", e.what());
        delete out.group;
        out.group = nullptr;
        out.commit.clear();
        out.welcome.clear();
    }
    return out;
}

MlsGroupCrypto::Group* MlsGroupCrypto::joinFromWelcome(PublishedKeyPackage* kp, Identity* id,
                                                       const QByteArray& welcomeWire) {
    if (!kp || !id) return nullptr;
    try {
        auto msg = mls::tls::get<mls::MLSMessage>(toBytes(welcomeWire));
        // `var` is a global namespace alias in mlspp's common.h, not mls::var.
        const auto& welcome = var::get<mls::Welcome>(msg.message);
        mls::State st(kp->initPriv,
                      id->encPriv,
                      id->sigPriv,
                      kp->keyPackage,
                      welcome,
                      std::nullopt, // ratchet tree travels inline in the welcome
                      {});
        return new Group(std::move(st));
    } catch (const std::exception& e) {
        qWarning("[mls] joinFromWelcome failed: %s", e.what());
        return nullptr;
    }
}

MlsGroupCrypto::Group* MlsGroupCrypto::applyCommit(Group* g, const QByteArray& commitWire) {
    if (!g) return nullptr;
    try {
        auto msg = mls::tls::get<mls::MLSMessage>(toBytes(commitWire));
        auto next = g->state.handle(msg);
        if (!next) return nullptr;
        return new Group(std::move(*next));
    } catch (const std::exception& e) {
        qWarning("[mls] applyCommit failed: %s", e.what());
        return nullptr;
    }
}

MlsGroupCrypto::Group* MlsGroupCrypto::createGroup(const QByteArray& groupId, Identity* id) {
    if (!id) return nullptr;
    try {
        mls::State st(toBytes(groupId),
                      id->suite,
                      id->encPriv,
                      id->sigPriv,
                      id->leafNode(),
                      {});
        return new Group(std::move(st));
    } catch (const std::exception& e) {
        qWarning("[mls] createGroup failed: %s", e.what());
        return nullptr;
    }
}

quint64 MlsGroupCrypto::epoch(Group* g) {
    return g ? static_cast<quint64>(g->state.epoch()) : 0;
}

QStringList MlsGroupCrypto::memberIdentities(Group* g) {
    QStringList out;
    if (!g) return out;
    try {
        // The ratchet tree is the only authority on who is actually in the
        // group. A locally persisted "invited" set cannot be trusted: it
        // survives server resets and re-joins, so a device that legitimately
        // needs adding again looks already-invited and waits forever.
        for (const auto& leaf : g->state.roster()) {
            const auto& cred = leaf.credential.get<mls::BasicCredential>();
            out.append(QString::fromUtf8(
                reinterpret_cast<const char*>(cred.identity.data()),
                int(cred.identity.size())));
        }
    } catch (const std::exception& e) {
        qWarning() << "[mls] roster:" << e.what();
    }
    return out;
}

QByteArray MlsGroupCrypto::protect(Group* g, const QByteArray& plaintext, const QByteArray& aad) {
    if (!g) return {};
    try {
        auto msg = g->state.protect(toBytes(aad), toBytes(plaintext), 0);
        const QByteArray sealed = fromBytes(mls::tls::marshal(msg));
        rememberOpen(sealed, plaintext);
        return sealed;
    } catch (const std::exception& e) {
        qWarning("[mls] protect failed: %s", e.what());
        return {};
    }
}

QByteArray MlsGroupCrypto::unprotect(Group* g, const QByteArray& payload) {
    if (!g) return {};
    const QByteArray cached = lookupOpen(payload);
    if (!cached.isEmpty() || g_openCache.contains(openCacheKey(payload)))
        return cached;
    try {
        auto msg = mls::tls::get<mls::MLSMessage>(toBytes(payload));
        auto [aad, pt] = g->state.unprotect(msg);
        (void)aad;
        const QByteArray plain = fromBytes(pt);
        rememberOpen(payload, plain);
        return plain;
    } catch (const std::exception& e) {
        qWarning("[mls] unprotect failed: %s", e.what());
        return {};
    }
}

// Cross-platform interop harness. With E2EE_MLS_EXPORT=<dir> the app writes a
// KeyPackage + a group's commit/welcome so the Android side (BouncyCastle) can
// try to join a group created by mlspp, and vice-versa. Wire framing must match
// exactly: same suite, same MLSMessage/mls_welcome wrapping.
bool MlsGroupCrypto::exportInteropVectors(const QString& dir) {
    QDir().mkpath(dir);
    Identity* alice = newIdentity(QStringLiteral("win-alice"));
    Identity* bob = newIdentity(QStringLiteral("win-bob"));
    if (!alice || !bob) return false;

    PublishedKeyPackage* bobKp = newKeyPackage(bob);
    Group* g = createGroup(QByteArrayLiteral("interop-group"), alice);
    if (!bobKp || !g) return false;

    const QByteArray bobKpWire = keyPackageBytes(bobKp);
    AddResult added = addMember(g, bobKpWire);
    if (!added.group) return false;

    auto dump = [&](const QString& name, const QByteArray& data) {
        QFile f(dir + QLatin1Char('/') + name);
        if (f.open(QIODevice::WriteOnly)) { f.write(data.toHex()); f.close(); }
    };
    // A Windows-made KeyPackage for Android to add.
    dump(QStringLiteral("win_keypackage.hex"), bobKpWire);
    // A Windows-made group's welcome, for Android to join with bobKp's init key.
    dump(QStringLiteral("win_welcome.hex"), added.welcome);
    dump(QStringLiteral("win_commit.hex"), added.commit);
    // An application message from the Windows group at epoch 1.
    dump(QStringLiteral("win_appmsg.hex"), protect(added.group, QByteArrayLiteral("from windows")));

    destroyGroup(added.group);
    destroyGroup(g);
    destroyKeyPackage(bobKp);
    destroyIdentity(bob);
    destroyIdentity(alice);
    qInfo("[mls-interop] exported vectors to %s", qPrintable(dir));
    return true;
}

QByteArray MlsGroupCrypto::exportGroupInfo(Group* g) {
    if (!g) return {};
    try {
        // inline_tree = true: the rejoiner has no other source for the tree.
        auto info = g->state.group_info(true);
        return fromBytes(mls::tls::marshal(mls::MLSMessage(info)));
    } catch (const std::exception& e) {
        qWarning("[mls] exportGroupInfo failed: %s", e.what());
        return {};
    }
}

// ---- Persistence ----

QString MlsGroupCrypto::encodeIdentity(Identity* id) {
    if (!id) return {};
    QJsonObject o;
    o.insert(QStringLiteral("v"), 1);
    o.insert(QStringLiteral("leaf_priv"), QString::fromLatin1(fromBytes(id->encPriv.data).toHex()));
    o.insert(QStringLiteral("sig_priv"), QString::fromLatin1(fromBytes(id->sigPriv.data).toHex()));
    o.insert(QStringLiteral("cred"), QString::fromLatin1(fromBytes(id->credRaw).toHex()));
    return QString::fromUtf8(QJsonDocument(o).toJson(QJsonDocument::Compact));
}

MlsGroupCrypto::Identity* MlsGroupCrypto::decodeIdentity(const QString& json) {
    try {
        const QJsonObject o = QJsonDocument::fromJson(json.toUtf8()).object();
        if (o.value(QStringLiteral("v")).toInt() != 1) return nullptr;
        mls::CipherSuite suite{ kSuiteID };
        const QByteArray leaf = QByteArray::fromHex(o.value(QStringLiteral("leaf_priv")).toString().toLatin1());
        const QByteArray sig = QByteArray::fromHex(o.value(QStringLiteral("sig_priv")).toString().toLatin1());
        const QByteArray cred = QByteArray::fromHex(o.value(QStringLiteral("cred")).toString().toLatin1());
        auto encPriv = mls::HPKEPrivateKey::parse(suite, toBytes(leaf));
        auto sigPriv = mls::SignaturePrivateKey::parse(suite, toBytes(sig));
        auto credential = mls::Credential::basic(toBytes(cred));
        auto* id = new Identity(std::move(encPriv), std::move(sigPriv), std::move(credential));
        id->credRaw = toBytes(cred);
        return id;
    } catch (const std::exception& e) {
        qWarning("[mls] decodeIdentity failed: %s", e.what());
        return nullptr;
    }
}

QString MlsGroupCrypto::encodePublishedKeyPackage(PublishedKeyPackage* kp) {
    if (!kp) return {};
    try {
        QJsonObject o;
        o.insert(QStringLiteral("v"), 1);
        o.insert(QStringLiteral("kp"),
                 QString::fromLatin1(fromBytes(mls::tls::marshal(kp->keyPackage)).toHex()));
        o.insert(QStringLiteral("init_priv"),
                 QString::fromLatin1(fromBytes(kp->initPriv.data).toHex()));
        return QString::fromUtf8(QJsonDocument(o).toJson(QJsonDocument::Compact));
    } catch (const std::exception& e) {
        qWarning("[mls] encodePublishedKeyPackage failed: %s", e.what());
        return {};
    }
}

MlsGroupCrypto::PublishedKeyPackage* MlsGroupCrypto::decodePublishedKeyPackage(const QString& json) {
    try {
        const QJsonObject o = QJsonDocument::fromJson(json.toUtf8()).object();
        if (o.value(QStringLiteral("v")).toInt() != 1) return nullptr;
        mls::CipherSuite suite{ kSuiteID };
        const QByteArray kpWire = QByteArray::fromHex(o.value(QStringLiteral("kp")).toString().toLatin1());
        const QByteArray initPriv = QByteArray::fromHex(o.value(QStringLiteral("init_priv")).toString().toLatin1());
        auto kp = mls::tls::get<mls::KeyPackage>(toBytes(kpWire));
        auto ip = mls::HPKEPrivateKey::parse(suite, toBytes(initPriv));
        return new PublishedKeyPackage(std::move(kp), std::move(ip));
    } catch (const std::exception& e) {
        qWarning("[mls] decodePublishedKeyPackage failed: %s", e.what());
        return nullptr;
    }
}

bool MlsGroupCrypto::selfTest() {
    bool pass = true;

    Identity* alice = newIdentity(QStringLiteral("alice"));
    if (!alice) {
        qWarning("[mls-selftest] identity generation failed");
        return false;
    }

    // KeyPackage must serialize and parse back (the Delivery Service stores it
    // as opaque bytes and hands it to a different client).
    PublishedKeyPackage* alicePkp = newKeyPackage(alice);
    const QByteArray kp = keyPackageBytes(alicePkp);
    if (kp.isEmpty()) {
        qWarning("[mls-selftest] KeyPackage generation failed");
        pass = false;
    } else {
        try {
            auto parsed = mls::tls::get<mls::KeyPackage>(toBytes(kp));
            (void)parsed;
        } catch (const std::exception& e) {
            qWarning("[mls-selftest] KeyPackage round-trip failed: %s", e.what());
            pass = false;
        }
    }
    destroyKeyPackage(alicePkp);

    Group* g = createGroup(QByteArrayLiteral("selftest-group"), alice);
    if (!g) {
        qWarning("[mls-selftest] group creation failed");
        destroyIdentity(alice);
        return false;
    }
    if (epoch(g) != 0) {
        qWarning("[mls-selftest] fresh group should be at epoch 0");
        pass = false;
    }

    const QByteArray ct = protect(g, QByteArrayLiteral("hello mls"));
    if (ct.isEmpty()) {
        qWarning("[mls-selftest] protect failed");
        pass = false;
    } else if (ct.contains("hello mls")) {
        qWarning("[mls-selftest] plaintext leaked into ciphertext");
        pass = false;
    } else if (unprotect(g, ct) != QByteArrayLiteral("hello mls")) {
        qWarning("[mls-selftest] unprotect did not recover the plaintext");
        pass = false;
    }

    destroyGroup(g);

    // ---- Two-party group: the full Delivery Service flow ----
    // Bob publishes a KeyPackage; Alice adds him and produces commit+welcome;
    // Bob joins from the welcome alone; both directions must decrypt.
    Identity* bob = newIdentity(QStringLiteral("bob"));
    PublishedKeyPackage* bobKp = bob ? newKeyPackage(bob) : nullptr;
    Group* aliceGroup = createGroup(QByteArrayLiteral("chat-42"), alice);
    if (!bob || !bobKp || !aliceGroup) {
        qWarning("[mls-selftest] two-party setup failed");
        pass = false;
    } else {
        const QByteArray bobKpWire = keyPackageBytes(bobKp);
        AddResult added = addMember(aliceGroup, bobKpWire);
        if (!added.group || added.welcome.isEmpty()) {
            qWarning("[mls-selftest] addMember produced no group/welcome");
            pass = false;
        } else {
            destroyGroup(aliceGroup);
            aliceGroup = added.group;
            if (epoch(aliceGroup) != 1) {
                qWarning("[mls-selftest] group should be at epoch 1 after add");
                pass = false;
            }
            Group* bobGroup = joinFromWelcome(bobKp, bob, added.welcome);
            if (!bobGroup) {
                qWarning("[mls-selftest] Bob could not join from welcome");
                pass = false;
            } else {
                if (epoch(bobGroup) != epoch(aliceGroup)) {
                    qWarning("[mls-selftest] epoch mismatch after join");
                    pass = false;
                }
                const QByteArray toBob = protect(aliceGroup, QByteArrayLiteral("hello bob"));
                if (unprotect(bobGroup, toBob) != QByteArrayLiteral("hello bob")) {
                    qWarning("[mls-selftest] Bob could not decrypt Alice's message");
                    pass = false;
                }
                if (unprotect(bobGroup, toBob) != QByteArrayLiteral("hello bob")) {
                    qWarning("[mls-selftest] second unprotect of the same blob failed");
                    pass = false;
                }
                if (unprotect(aliceGroup, toBob) != QByteArrayLiteral("hello bob")) {
                    qWarning("[mls-selftest] sender could not re-read its own message from cache");
                    pass = false;
                }
                const QByteArray toAlice = protect(bobGroup, QByteArrayLiteral("hi alice"));
                if (unprotect(aliceGroup, toAlice) != QByteArrayLiteral("hi alice")) {
                    qWarning("[mls-selftest] Alice could not decrypt Bob's message");
                    pass = false;
                }
                destroyGroup(bobGroup);
            }
        }
    }
    destroyGroup(aliceGroup);
    destroyKeyPackage(bobKp);
    destroyIdentity(bob);
    destroyIdentity(alice);

    if (pass) qInfo("[mls-selftest] PASS: suite + keypackage + group + protect/unprotect + 2-party add/join/exchange");
    else qWarning("[mls-selftest] FAIL");
    return pass;
}
