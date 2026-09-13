// Phase 32 - Windows history keyring gate.
//
// The keyring is the map from a chat to the roots its archives were sealed under. Getting it wrong
// is not a cosmetic bug: minting a fresh root over a live keyring orphans every message already
// archived, permanently. So the rules under test here are mostly about what the code must REFUSE to
// do when it cannot tell what state it is in.
//
// The wire format is Android's (data/encryption/history/HistoryKeyring.kt) and the fixture bytes
// below were computed independently from that written specification, not by serialising this
// implementation. The Android unit test CrossPlatformKeyringFixtureTest asserts the identical hex,
// so both platforms are checked against the same third value rather than against each other.
//
// Every value here is synthetic: a counting-pattern root, literal identifiers, a fixed synthetic
// master key. No production account, key, or archive appears in this file or its output.

#include "../src/crypto/historykeyring.h"
#include "../src/crypto/historykeyringstore.h"
#include "../src/crypto/historykeyringtransport.h"
#include "../src/crypto/historycrypto.h"
#include "../src/crypto/vaultcrypto.h"

#include <QByteArray>
#include <QHash>
#include <QJsonObject>
#include <QTextStream>
#include <sodium.h>

static int g_failures = 0;
static int g_checks = 0;

static void check(bool ok, const QString& what) {
    ++g_checks;
    QTextStream out(stdout);
    out << (ok ? "  PASS  " : "  FAIL  ") << what << Qt::endl;
    if (!ok) ++g_failures;
}

static void section(const char* title) {
    QTextStream(stdout) << "-- " << title << " --" << Qt::endl;
}

// ------------------------------------------------------------------ fixtures

static const char* kChat = "00000000-0000-0000-0000-000000000001";
static const char* kOwner = "fixture-user";

// Independently computed from the Android format specification.
static const char* kFixtureHex =
    "01"
    "00000001"
    "00000024"
    "30303030303030302d303030302d303030302d303030302d303030303030303030303031"
    "00000001"
    "20"
    "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

static QByteArray fixtureRoot() {
    QByteArray r(HistoryCrypto::ROOT_BYTES, 0);
    for (int i = 0; i < r.size(); ++i) r[i] = static_cast<char>(i);
    return r;
}

static QByteArray fixtureBytes() { return QByteArray::fromHex(QByteArray(kFixtureHex)); }

// ------------------------------------------------------- in-memory test store
//
// Models the three durable slots and, crucially, the three read outcomes. Tests drive faults
// through the `fail*` flags rather than by corrupting real storage.

class FakeStore : public HistoryKeyringStore {
public:
    QHash<QString, QByteArray> keyring, cache, generation;
    bool failCacheRead = false;
    bool failKeyringRead = false;
    bool failCacheWrite = false;

    bool saveKeyring(const QString& o, const QByteArray& s) override { keyring[o] = s; return true; }
    SlotRead loadKeyring(const QString& o) override {
        if (failKeyringRead) return SlotRead::failed();
        return keyring.contains(o) ? SlotRead::ok(keyring[o]) : SlotRead::absent();
    }
    bool deleteKeyring(const QString& o) override { keyring.remove(o); return true; }

    bool saveCache(const QString& o, const QByteArray& p) override {
        if (failCacheWrite) return false;
        cache[o] = p; return true;
    }
    SlotRead loadCache(const QString& o) override {
        if (failCacheRead) return SlotRead::failed();
        return cache.contains(o) ? SlotRead::ok(cache[o]) : SlotRead::absent();
    }
    bool deleteCache(const QString& o) override { cache.remove(o); return true; }

    bool saveGeneration(const QString& o, qint64 g) override {
        generation[o] = QByteArray::number(g); return true;
    }
    SlotRead loadGeneration(const QString& o) override {
        return generation.contains(o) ? SlotRead::ok(generation[o]) : SlotRead::absent();
    }
    bool deleteGeneration(const QString& o) override { generation.remove(o); return true; }
};

// A synthetic master key. The vault is supplied as a seal/open pair exactly as Android supplies it,
// so MK never crosses the repository boundary.
static QByteArray syntheticMk() {
    QByteArray mk(32, 0);
    for (int i = 0; i < 32; ++i) mk[i] = static_cast<char>(0x70 + i);
    return mk;
}

static QByteArray keyringAad(const QString& owner) {
    return QStringLiteral("history-keyring|%1|%2|%3")
        .arg(owner).arg(1).arg(QLatin1String(VaultCrypto::SUITE_VAULT_AEAD)).toUtf8();
}

static HistoryKeyringRepository::SealFn sealer(const QByteArray& mk, const QString& owner) {
    return [mk, owner](const QByteArray& plain, QByteArray& out) {
        out = VaultCrypto::sealXChaCha(mk, plain, keyringAad(owner));
        return !out.isEmpty();
    };
}
static HistoryKeyringRepository::OpenFn opener(const QByteArray& mk, const QString& owner) {
    return [mk, owner](const QByteArray& sealed, QByteArray& out) {
        out = VaultCrypto::openXChaCha(mk, sealed, keyringAad(owner));
        return !out.isEmpty();
    };
}

int main() {
    if (sodium_init() < 0) {
        QTextStream(stdout) << "  FAIL  libsodium failed to initialise" << Qt::endl;
        return 1;
    }
    QTextStream out(stdout);
    out << "Phase 32 - Windows history keyring" << Qt::endl;

    const QString owner = QString::fromLatin1(kOwner);
    const QString chat = QString::fromLatin1(kChat);
    const QByteArray mk = syntheticMk();

    // =================================================== cross-platform format
    section("cross-platform serialisation (independent fixture)");
    {
        HistoryKeyring k;
        QString err;
        HistoryRootEntry e{ chat, 1, fixtureRoot() };
        check(HistoryKeyring::of({ e }, k, &err), QStringLiteral("keyring accepts the fixture entry"));
        check(k.encode().toHex() == QByteArray(kFixtureHex),
              QStringLiteral("Windows serialisation == independent fixture bytes"));

        HistoryKeyring d;
        check(HistoryKeyring::decode(fixtureBytes(), d, &err),
              QStringLiteral("Windows deserialises the independent fixture"));
        HistoryRootEntry got;
        check(d.latest(chat, got), QStringLiteral("fixture yields a root for the chat"));
        check(got.root == fixtureRoot(), QStringLiteral("root bytes identical"));
        check(got.rootVersion == 1, QStringLiteral("rootVersion identical"));
        check(got.chatId == chat, QStringLiteral("chat association identical"));
        check(d == k, QStringLiteral("decoded fixture equals the constructed keyring"));
        check(d.encode() == fixtureBytes(), QStringLiteral("re-encode is byte-stable"));

        // An empty keyring is a legitimate value; Android asserts the identical encoding.
        HistoryKeyring empty;
        check(HistoryKeyring::of({}, empty, &err), QStringLiteral("an empty keyring is valid"));
        check(empty.encode().toHex() == QByteArray("0100000000"),
              QStringLiteral("an empty keyring encodes to just the header"));
        HistoryKeyring emptyBack;
        check(HistoryKeyring::decode(empty.encode(), emptyBack, &err) && emptyBack.isEmpty(),
              QStringLiteral("the empty encoding round-trips"));
    }

    // Canonical ordering: the same set must encode identically regardless of insertion order.
    {
        QByteArray rA(32, 'A'), rB(32, 'B'), rC(32, 'C');
        HistoryKeyring one, two;
        HistoryKeyring::of({ { QStringLiteral("b-chat"), 1, rB },
                             { QStringLiteral("a-chat"), 2, rC },
                             { QStringLiteral("a-chat"), 1, rA } }, one);
        HistoryKeyring::of({ { QStringLiteral("a-chat"), 1, rA },
                             { QStringLiteral("b-chat"), 1, rB },
                             { QStringLiteral("a-chat"), 2, rC } }, two);
        check(one.encode() == two.encode(),
              QStringLiteral("serialisation is canonical, not insertion-ordered"));
        check(one.entries().first().chatId == QStringLiteral("a-chat") &&
              one.entries().first().rootVersion == 1,
              QStringLiteral("entries sort by (chatId, rootVersion)"));
        check(one.latestVersion(QStringLiteral("a-chat")) == 2,
              QStringLiteral("latest() picks the highest version, not the last inserted"));
        HistoryRootEntry old;
        check(one.find(QStringLiteral("a-chat"), 1, old) && old.root == rA,
              QStringLiteral("older versions stay addressable by (chatId, rootVersion)"));
    }

    // =================================================================== negative
    section("malformed input is refused, never silently accepted");
    {
        HistoryKeyring k;
        const QByteArray f = fixtureBytes();

        check(!HistoryKeyring::decode(QByteArray(), k), QStringLiteral("empty input fails"));
        check(!HistoryKeyring::decode(f.left(f.size() - 1), k), QStringLiteral("truncated fails"));
        check(!HistoryKeyring::decode(f + QByteArray(1, 'x'), k),
              QStringLiteral("trailing bytes fail"));

        QByteArray badVer = f; badVer[0] = static_cast<char>(0);
        check(!HistoryKeyring::decode(badVer, k), QStringLiteral("format version 0 fails"));
        badVer[0] = static_cast<char>(2);
        check(!HistoryKeyring::decode(badVer, k), QStringLiteral("format version 2 fails"));

        // rootVersion 0 and a negative rootVersion, patched in place at offset 45.
        QByteArray v0 = f;
        v0[45] = 0; v0[46] = 0; v0[47] = 0; v0[48] = 0;
        check(!HistoryKeyring::decode(v0, k), QStringLiteral("rootVersion 0 fails"));
        QByteArray vNeg = f;
        vNeg[45] = static_cast<char>(0xFF); vNeg[46] = static_cast<char>(0xFF);
        vNeg[47] = static_cast<char>(0xFF); vNeg[48] = static_cast<char>(0xFF);
        check(!HistoryKeyring::decode(vNeg, k), QStringLiteral("negative rootVersion fails"));

        // An absurd entry count must be rejected before allocating.
        QByteArray huge = f;
        huge[1] = static_cast<char>(0x7F); huge[2] = static_cast<char>(0xFF);
        huge[3] = static_cast<char>(0xFF); huge[4] = static_cast<char>(0xFF);
        check(!HistoryKeyring::decode(huge, k), QStringLiteral("absurd entry count fails"));

        // Structural rules enforced at construction as well as decode.
        check(!HistoryKeyring::of({ { QString(), 1, fixtureRoot() } }, k),
              QStringLiteral("empty chatId is refused"));
        check(!HistoryKeyring::of({ { chat, 0, fixtureRoot() } }, k),
              QStringLiteral("rootVersion 0 is refused"));
        check(!HistoryKeyring::of({ { chat, -1, fixtureRoot() } }, k),
              QStringLiteral("negative rootVersion is refused"));
        check(!HistoryKeyring::of({ { chat, 1, QByteArray(31, 'r') } }, k),
              QStringLiteral("a 31-byte root is refused"));
        check(!HistoryKeyring::of({ { chat, 1, QByteArray(33, 'r') } }, k),
              QStringLiteral("a 33-byte root is refused"));
        check(!HistoryKeyring::of({ { chat, 1, QByteArray() } }, k),
              QStringLiteral("a missing root is refused"));
        check(!HistoryKeyring::of({ { chat, 1, fixtureRoot() }, { chat, 1, QByteArray(32, 'z') } }, k),
              QStringLiteral("duplicate (chatId, rootVersion) is refused"));
        check(HistoryKeyring::of({ { chat, 1, fixtureRoot() }, { chat, 2, QByteArray(32, 'z') } }, k),
              QStringLiteral("two versions of one chat are allowed"));
    }

    // ============================================================== persistence
    section("persistence, restart, and vault-locked behaviour");
    {
        FakeStore store;
        HistoryKeyringRepository repo(&store, sealer(mk, owner), opener(mk, owner));

        HistoryRootEntry first;
        QString err;
        check(repo.ensureRoot(owner, chat, first, &err),
              QStringLiteral("create: ensureRoot mints a root"));
        check(first.rootVersion == 1, QStringLiteral("create: the first root is version 1"));
        check(first.root.size() == 32, QStringLiteral("create: the root is 32 bytes"));
        check(store.keyring.contains(owner) && store.cache.contains(owner),
              QStringLiteral("create: both durable copies were written"));

        const qint64 revAfterCreate = repo.revision();
        HistoryRootEntry again;
        check(repo.ensureRoot(owner, chat, again, &err),
              QStringLiteral("repeat: ensureRoot succeeds again"));
        check(again.root == first.root, QStringLiteral("repeat: the same root comes back"));
        check(again.rootVersion == 1, QStringLiteral("repeat: the version did not increment"));
        check(repo.revision() == revAfterCreate,
              QStringLiteral("repeat: nothing was persisted a second time"));

        // Simulated process restart: drop everything in memory, keep the durable copies.
        repo.forgetInMemory();
        HistoryRootEntry afterRestart;
        check(repo.ensureRoot(owner, chat, afterRestart, &err),
              QStringLiteral("restart: the keyring reloads"));
        check(afterRestart.root == first.root,
              QStringLiteral("restart: the exact same root is recovered"));
        check(repo.revision() == revAfterCreate,
              QStringLiteral("restart: reloading persisted nothing new"));

        // Cold start with a LOCKED vault. The cache is the only copy readable without MK, and
        // serving an existing root from it is precisely what avoids a password prompt on restart.
        HistoryKeyringRepository locked(&store, nullptr, nullptr);
        HistoryRootEntry fromCache;
        check(locked.ensureRoot(owner, chat, fromCache, &err),
              QStringLiteral("vault locked + existing root: served from the cache"));
        check(fromCache.root == first.root,
              QStringLiteral("vault locked + existing root: bytes are identical"));

        // But minting a NEW root while locked must fail - that is a durable write.
        HistoryRootEntry denied;
        check(!locked.ensureRoot(owner, QStringLiteral("some-other-chat"), denied, &err),
              QStringLiteral("vault locked + missing root: refuses to mint"));

        // Rotation adds a version and keeps the old one.
        HistoryRootEntry rotated;
        check(repo.rotate(owner, chat, rotated, &err), QStringLiteral("rotate: succeeds"));
        check(rotated.rootVersion == 2, QStringLiteral("rotate: version becomes 2"));
        check(rotated.root != first.root, QStringLiteral("rotate: the new root differs"));
        HistoryKeyring afterRotate;
        repo.load(owner, afterRotate, &err);
        HistoryRootEntry stillThere;
        check(afterRotate.find(chat, 1, stillThere) && stillThere.root == first.root,
              QStringLiteral("rotate: version 1 remains addressable"));
        HistoryRootEntry newLatest;
        check(afterRotate.latest(chat, newLatest) && newLatest.rootVersion == 2,
              QStringLiteral("rotate: latest is now version 2"));
    }

    // The fail-closed cases: the whole point of the class.
    section("fail-closed: unknown state must never become an empty keyring");
    {
        // Unreadable cache with no authoritative copy: cannot tell "fresh" from "broken".
        FakeStore s1;
        s1.failCacheRead = true;
        HistoryKeyringRepository r1(&s1, sealer(mk, owner), opener(mk, owner));
        HistoryKeyring k1;
        QString e1;
        check(!r1.load(owner, k1, &e1),
              QStringLiteral("unreadable cache + no recovery copy: refuses to mint"));

        // Genuinely fresh device: both copies absent is a legitimate empty keyring.
        FakeStore s2;
        HistoryKeyringRepository r2(&s2, sealer(mk, owner), opener(mk, owner));
        HistoryKeyring k2;
        check(r2.load(owner, k2, &e1) && k2.isEmpty(),
              QStringLiteral("fresh device: an empty keyring is correct"));

        // Stored but unreadable authoritative copy is fatal, not empty.
        FakeStore s3;
        HistoryKeyringRepository r3(&s3, sealer(mk, owner), opener(mk, owner));
        HistoryRootEntry tmp;
        r3.ensureRoot(owner, chat, tmp, nullptr);
        s3.failKeyringRead = true;
        s3.cache.remove(owner);
        HistoryKeyringRepository r3b(&s3, sealer(mk, owner), opener(mk, owner));
        HistoryKeyring k3;
        check(!r3b.load(owner, k3, &e1),
              QStringLiteral("unreadable authoritative copy: fails rather than emptying"));

        // Wrong master key: the sealed blob will not open, and there is no evidence it is foreign.
        FakeStore s4;
        HistoryKeyringRepository r4(&s4, sealer(mk, owner), opener(mk, owner));
        r4.ensureRoot(owner, chat, tmp, nullptr);
        s4.cache.remove(owner);
        QByteArray wrongMk = mk; wrongMk[0] = static_cast<char>(0x00);
        HistoryKeyringRepository r4b(&s4, sealer(wrongMk, owner), opener(wrongMk, owner));
        HistoryKeyring k4;
        check(!r4b.load(owner, k4, &e1),
              QStringLiteral("wrong master key: fails closed"));

        // Wrong AAD context (another account's domain) must not open either.
        HistoryKeyringRepository r4c(&s4, sealer(mk, QStringLiteral("someone-else")),
                                     opener(mk, QStringLiteral("someone-else")));
        HistoryKeyring k4c;
        check(!r4c.load(owner, k4c, &e1),
              QStringLiteral("wrong AAD context: fails closed"));

        // A cache belonging to a DIFFERENT account is as good as absent - nothing of ours to orphan.
        FakeStore s5;
        s5.cache[owner] = HistoryKeyringCacheFormat::encode(
            QStringLiteral("another-user"), 1, fixtureBytes());
        HistoryKeyringRepository r5(&s5, sealer(mk, owner), opener(mk, owner));
        HistoryKeyring k5;
        check(r5.load(owner, k5, &e1) && k5.isEmpty(),
              QStringLiteral("foreign cache + no authoritative copy: empty keyring is correct"));

        // A STALE cache must not shadow the authoritative copy.
        FakeStore s6;
        HistoryKeyringRepository r6(&s6, sealer(mk, owner), opener(mk, owner));
        HistoryRootEntry real;
        r6.ensureRoot(owner, chat, real, nullptr);
        s6.cache[owner] = HistoryKeyringCacheFormat::encode(owner, 99, fixtureBytes());
        HistoryKeyringRepository r6b(&s6, sealer(mk, owner), opener(mk, owner));
        HistoryRootEntry served;
        check(r6b.ensureRoot(owner, chat, served, &e1) && served.root == real.root,
              QStringLiteral("stale cache stamp: falls through to the authoritative copy"));
    }

    // Cache-format decoding rules.
    section("cache framing");
    {
        const QByteArray blob = HistoryKeyringCacheFormat::encode(owner, 7, fixtureBytes());
        auto d = HistoryKeyringCacheFormat::decode(blob, owner);
        check(d.kind == HistoryKeyringCacheFormat::Kind::Owned,
              QStringLiteral("own account decodes as Owned"));
        check(d.generation == 7, QStringLiteral("generation stamp round-trips"));
        check(d.keyringBytes == fixtureBytes(), QStringLiteral("payload round-trips"));

        auto foreign = HistoryKeyringCacheFormat::decode(blob, QStringLiteral("other"));
        check(foreign.kind == HistoryKeyringCacheFormat::Kind::ForAnotherUser,
              QStringLiteral("another account decodes as ForAnotherUser"));

        // A legacy unbound cache leads with 1 and must be Unusable, not silently adopted.
        auto legacy = HistoryKeyringCacheFormat::decode(fixtureBytes(), owner);
        check(legacy.kind == HistoryKeyringCacheFormat::Kind::Unusable,
              QStringLiteral("legacy unbound cache is Unusable, not absent"));
        auto empty = HistoryKeyringCacheFormat::decode(QByteArray(), owner);
        check(empty.kind == HistoryKeyringCacheFormat::Kind::Unusable,
              QStringLiteral("empty cache blob is Unusable"));
    }

    // ================================================================ transport
    section("server keyring transport");
    {
        // A local boundary standing in for the server, implementing the documented contract.
        int storedVersion = 0;
        QByteArray storedCt;
        int lastStatus = 200;
        auto server = [&](const QString& path, const QByteArray& body,
                          const char* method) -> QPair<int, QJsonObject> {
            if (path != QString::fromLatin1(HistoryKeyringTransport::PATH)) return { 404, {} };
            if (lastStatus != 200) return { lastStatus, QJsonObject() };
            if (qstrcmp(method, "GET") == 0) {
                if (storedVersion == 0) return { 404, {} };
                QJsonObject o;
                o.insert(QStringLiteral("version"), storedVersion);
                o.insert(QStringLiteral("ciphertext_b64"), VaultCrypto::b64(storedCt));
                return { 200, o };
            }
            if (qstrcmp(method, "PUT") == 0) {
                const QJsonObject in = QJsonDocument::fromJson(body).object();
                const int expected = in.value(QStringLiteral("expected_version")).toInt();
                const QByteArray ct =
                    VaultCrypto::unb64(in.value(QStringLiteral("ciphertext_b64")).toString());
                if (ct.isEmpty()) return { 400, {} };
                if (expected != storedVersion) {
                    QJsonObject c;
                    c.insert(QStringLiteral("server_version"), storedVersion);
                    return { 409, c };
                }
                storedCt = ct;
                storedVersion += 1;
                QJsonObject o;
                o.insert(QStringLiteral("version"), storedVersion);
                o.insert(QStringLiteral("created"), expected == 0);
                return { 200, o };
            }
            if (qstrcmp(method, "DELETE") == 0) {
                storedVersion = 0; storedCt.clear();
                return { 200, {} };
            }
            return { 405, {} };
        };
        HistoryKeyringTransport tx(server);

        // Nothing stored yet: absence must be distinguishable from an empty keyring.
        check(tx.get().outcome == HistoryKeyringTransport::Outcome::Absent,
              QStringLiteral("GET with nothing stored reports Absent, not empty"));

        // Full round trip: generate -> persist -> seal for recovery -> PUT -> GET -> open.
        FakeStore store;
        HistoryKeyringRepository repo(&store, sealer(mk, owner), opener(mk, owner));
        HistoryRootEntry minted;
        repo.ensureRoot(owner, chat, minted, nullptr);
        HistoryKeyring local;
        repo.load(owner, local, nullptr);

        // The recovery blob uses its own AAD domain, distinct from the local keyring's.
        const QByteArray recoveryAad =
            QStringLiteral("history_keyring_recovery_v1|%1|%2")
                .arg(owner, QLatin1String(VaultCrypto::SUITE_VAULT_AEAD)).toUtf8();
        const QByteArray sealedForServer =
            VaultCrypto::sealXChaCha(mk, local.encode(), recoveryAad);

        auto p = tx.put(0, sealedForServer);
        check(p.outcome == HistoryKeyringTransport::Outcome::Ok,
              QStringLiteral("PUT create succeeds"));
        check(p.version == 1 && p.created, QStringLiteral("PUT create returns version 1, created"));

        auto f = tx.get();
        check(f.outcome == HistoryKeyringTransport::Outcome::Ok, QStringLiteral("GET succeeds"));
        check(f.version == 1, QStringLiteral("GET returns the stored version"));
        check(f.sealed == sealedForServer, QStringLiteral("GET returns the exact sealed bytes"));

        const QByteArray reopened = VaultCrypto::openXChaCha(mk, f.sealed, recoveryAad);
        HistoryKeyring recovered;
        check(HistoryKeyring::decode(reopened, recovered, nullptr),
              QStringLiteral("round trip: the fetched keyring decodes"));
        HistoryRootEntry rt;
        check(recovered.latest(chat, rt) && rt.root == minted.root && rt.rootVersion == 1,
              QStringLiteral("round trip: same root and version survive the server"));

        // The recovery domain must not be interchangeable with the local one.
        check(VaultCrypto::openXChaCha(mk, f.sealed, keyringAad(owner)).isEmpty(),
              QStringLiteral("a recovery blob cannot be opened as a local keyring"));

        // Optimistic concurrency.
        auto stale = tx.put(0, sealedForServer);
        check(stale.outcome == HistoryKeyringTransport::Outcome::Conflict,
              QStringLiteral("a stale expected_version conflicts"));
        check(stale.serverVersion == 1, QStringLiteral("conflict reports the server version"));
        auto replace = tx.put(1, sealedForServer);
        check(replace.outcome == HistoryKeyringTransport::Outcome::Ok && replace.version == 2 &&
                  !replace.created,
              QStringLiteral("naming the current version replaces and increments"));

        // Error mapping.
        lastStatus = 401;
        check(tx.get().outcome == HistoryKeyringTransport::Outcome::Unauthorized,
              QStringLiteral("401 maps to Unauthorized"));
        lastStatus = 413;
        check(tx.put(2, sealedForServer).outcome == HistoryKeyringTransport::Outcome::Rejected,
              QStringLiteral("413 maps to Rejected"));
        lastStatus = 500;
        check(tx.get().outcome == HistoryKeyringTransport::Outcome::TransportError,
              QStringLiteral("500 maps to TransportError"));
        lastStatus = 200;

        // Malformed payloads.
        HistoryKeyringTransport bad([](const QString&, const QByteArray&, const char*) {
            QJsonObject o;
            o.insert(QStringLiteral("version"), 1);
            o.insert(QStringLiteral("ciphertext_b64"), QStringLiteral("!!!not base64!!!"));
            return QPair<int, QJsonObject>{ 200, o };
        });
        check(bad.get().outcome == HistoryKeyringTransport::Outcome::Malformed,
              QStringLiteral("malformed base64 in a 200 is Malformed, not Absent"));

        HistoryKeyringTransport noBody([](const QString&, const QByteArray&, const char*) {
            return QPair<int, QJsonObject>{ 200, QJsonObject() };
        });
        check(noBody.get().outcome == HistoryKeyringTransport::Outcome::Malformed,
              QStringLiteral("a 200 with no ciphertext is Malformed"));

        check(tx.remove() == HistoryKeyringTransport::Outcome::Ok,
              QStringLiteral("DELETE succeeds"));
        HistoryKeyringTransport gone([](const QString&, const QByteArray&, const char*) {
            return QPair<int, QJsonObject>{ 404, QJsonObject() };
        });
        check(gone.remove() == HistoryKeyringTransport::Outcome::Ok,
              QStringLiteral("DELETE is idempotent when nothing is stored"));
    }

    // ================================================================= security
    section("security invariants");
    {
        FakeStore store;
        HistoryKeyringRepository repo(&store, sealer(mk, owner), opener(mk, owner));

        HistoryRootEntry a, b;
        repo.ensureRoot(owner, QStringLiteral("chat-a"), a, nullptr);
        repo.ensureRoot(owner, QStringLiteral("chat-b"), b, nullptr);
        check(a.root != b.root, QStringLiteral("two newly generated roots differ"));

        // Independently generated, not derived: a second device with identical identifiers must
        // still produce a different root.
        FakeStore other;
        HistoryKeyringRepository repo2(&other, sealer(mk, owner), opener(mk, owner));
        HistoryRootEntry a2;
        repo2.ensureRoot(owner, QStringLiteral("chat-a"), a2, nullptr);
        check(a2.root != a.root,
              QStringLiteral("roots are random, not derived from user/chat identity"));

        HistoryRootEntry stable1, stable2;
        repo.ensureRoot(owner, QStringLiteral("chat-a"), stable1, nullptr);
        repo.ensureRoot(owner, QStringLiteral("chat-a"), stable2, nullptr);
        check(stable1.root == a.root && stable2.root == a.root,
              QStringLiteral("repeated retrieval returns identical bytes"));
        check(stable2.rootVersion == 1, QStringLiteral("repeated ensureRoot does not bump version"));

        // The authoritative blob must not carry the root in the clear.
        const QByteArray sealedBlob = store.keyring.value(owner);
        check(!sealedBlob.isEmpty() && !sealedBlob.contains(a.root),
              QStringLiteral("the sealed keyring does not contain the root in cleartext"));
        check(!sealedBlob.contains(b.root),
              QStringLiteral("the sealed keyring hides every root, not just the first"));

        // The cache deliberately holds the plain encoding: it is protected by the OS keystore at
        // rest, which is the documented cost of cold-start access. Pin that it is the encoding and
        // account-bound, so a future change cannot quietly widen what is exposed.
        auto decodedCache = HistoryKeyringCacheFormat::decode(store.cache.value(owner), owner);
        check(decodedCache.kind == HistoryKeyringCacheFormat::Kind::Owned,
              QStringLiteral("the cache is account-bound"));
        check(HistoryKeyringCacheFormat::decode(store.cache.value(owner),
                                                QStringLiteral("intruder")).kind ==
                  HistoryKeyringCacheFormat::Kind::ForAnotherUser,
              QStringLiteral("another account cannot adopt this cache"));
    }

    out << (g_failures == 0 ? "ALL PASS" : "FAILURES")
        << "  checks=" << g_checks << " failures=" << g_failures << Qt::endl;
    return g_failures == 0 ? 0 : 1;
}
