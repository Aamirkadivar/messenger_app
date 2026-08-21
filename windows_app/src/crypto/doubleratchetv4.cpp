#include "doubleratchetv4.h"
#include <QJsonDocument>
#include <QJsonObject>
#include <QLoggingCategory>
#include <cstring>
#include <sodium.h>

// Constants identical to ratchet.go / ratchet_x3dh.go / DoubleRatchetV4.kt.
static const char kRootInfo[] = "messenger-dr-root-v1";
static const char kX3dhInfo[] = "messenger-x3dh-lite-v1";
static const int kMaxSkip = 200;
// New rk0-rooted sending chain (fresh ephemeral) every this many messages, so
// the stateless decrypt walk stays short.
static const int kChainRotate = 1000;
static const int kHeaderLen = 41; // type(1) || dh(32) || n(4) || pn(4)
static const char kTypeInitial = 0x01;
static const char kTypeNormal = 0x02;

// ---- primitives (mirror doubleratchet.cpp; duplicated to stay self-contained) ----

static QByteArray hmacSha256(const QByteArray& key, const QByteArray& data) {
    unsigned char out[crypto_auth_hmacsha256_BYTES];
    crypto_auth_hmacsha256_state st;
    crypto_auth_hmacsha256_init(&st, reinterpret_cast<const unsigned char*>(key.constData()),
                                static_cast<size_t>(key.size()));
    crypto_auth_hmacsha256_update(&st, reinterpret_cast<const unsigned char*>(data.constData()),
                                  static_cast<unsigned long long>(data.size()));
    crypto_auth_hmacsha256_final(&st, out);
    return QByteArray(reinterpret_cast<const char*>(out), crypto_auth_hmacsha256_BYTES);
}

static QByteArray hkdf(const QByteArray& ikm, QByteArray salt, const QByteArray& info, int n) {
    if (salt.isEmpty()) salt = QByteArray(32, '\0');
    const QByteArray prk = hmacSha256(salt, ikm);
    QByteArray t;
    QByteArray out;
    int i = 1;
    while (out.size() < n) {
        QByteArray block = t + info + QByteArray(1, static_cast<char>(i));
        t = hmacSha256(prk, block);
        out += t;
        i++;
    }
    return out.left(n);
}

static QByteArray sha256(const QByteArray& d) {
    unsigned char out[crypto_hash_sha256_BYTES];
    crypto_hash_sha256(out, reinterpret_cast<const unsigned char*>(d.constData()),
                       static_cast<unsigned long long>(d.size()));
    return QByteArray(reinterpret_cast<const char*>(out), crypto_hash_sha256_BYTES);
}

static bool kdfRk(const QByteArray& rk, const QByteArray& dh, QByteArray& newRk, QByteArray& ck) {
    QByteArray salt = rk.size() == 32 ? rk : QByteArray(32, '\0');
    const QByteArray out = hkdf(dh, salt, QByteArray(kRootInfo), 64);
    if (out.size() != 64) return false;
    newRk = out.left(32);
    ck = out.mid(32, 32);
    return true;
}

static void kdfCk(const QByteArray& ck, QByteArray& next, QByteArray& mk) {
    mk = hmacSha256(ck, QByteArray(1, '\x01'));
    next = hmacSha256(ck, QByteArray(1, '\x02'));
}

static QByteArray x25519(const QByteArray& sk, const QByteArray& pk) {
    if (sk.size() != 32 || pk.size() != 32) return {};
    QByteArray q(32, '\0');
    if (crypto_scalarmult(reinterpret_cast<unsigned char*>(q.data()),
                          reinterpret_cast<const unsigned char*>(sk.constData()),
                          reinterpret_cast<const unsigned char*>(pk.constData())) != 0) {
        return {};
    }
    return q;
}

static bool newKp(QByteArray& pk, QByteArray& sk) {
    pk = QByteArray(crypto_box_PUBLICKEYBYTES, '\0');
    sk = QByteArray(crypto_box_SECRETKEYBYTES, '\0');
    return crypto_box_keypair(reinterpret_cast<unsigned char*>(pk.data()),
                              reinterpret_cast<unsigned char*>(sk.data())) == 0;
}

static bool bytesLess(const QByteArray& a, const QByteArray& b) {
    const int n = qMin(a.size(), b.size());
    for (int i = 0; i < n; ++i) {
        const int ai = static_cast<unsigned char>(a[i]);
        const int bi = static_cast<unsigned char>(b[i]);
        if (ai != bi) return ai < bi;
    }
    return a.size() < b.size();
}

static QString skipId(const QByteArray& dh, quint32 n) {
    return QString::fromLatin1(dh.toHex()) + QLatin1Char(':') + QString::number(n);
}

static void putBe32(QByteArray& b, int off, quint32 v) {
    b[off] = char((v >> 24) & 0xff);
    b[off + 1] = char((v >> 16) & 0xff);
    b[off + 2] = char((v >> 8) & 0xff);
    b[off + 3] = char(v & 0xff);
}

static quint32 getBe32(const QByteArray& b, int off) {
    return (quint32(uchar(b[off])) << 24) | (quint32(uchar(b[off + 1])) << 16) |
           (quint32(uchar(b[off + 2])) << 8) | quint32(uchar(b[off + 3]));
}

static QByteArray secretboxOpen(const QByteArray& ct, const QByteArray& nonce, const QByteArray& mk, bool& ok) {
    ok = false;
    if (ct.size() < crypto_secretbox_MACBYTES) return {};
    QByteArray plain(ct.size() - crypto_secretbox_MACBYTES, '\0');
    if (crypto_secretbox_open_easy(reinterpret_cast<unsigned char*>(plain.data()),
                                   reinterpret_cast<const unsigned char*>(ct.constData()),
                                   static_cast<unsigned long long>(ct.size()),
                                   reinterpret_cast<const unsigned char*>(nonce.constData()),
                                   reinterpret_cast<const unsigned char*>(mk.constData())) != 0) {
        return {};
    }
    ok = true;
    return plain;
}

// ---- protocol ----

QByteArray DoubleRatchetV4::symmetricRoot(const QByteArray& myIkSk,
                                          const QByteArray& myIkPk,
                                          const QByteArray& peerIkPk) {
    if (myIkSk.size() != 32 || myIkPk.size() != 32 || peerIkPk.size() != 32) return {};
    const QByteArray dh = x25519(myIkSk, peerIkPk);
    if (dh.isEmpty()) return {};
    QByteArray lo, hi;
    if (bytesLess(myIkPk, peerIkPk)) { lo = myIkPk; hi = peerIkPk; }
    else { lo = peerIkPk; hi = myIkPk; }
    return hkdf(dh, sha256(lo + hi), QByteArray(kX3dhInfo), 32);
}

bool DoubleRatchetV4::initSession(const QByteArray& myIkPk,
                                  const QByteArray& myIkSk,
                                  const QByteArray& peerIkPk,
                                  Session& out) {
    const QByteArray rk0 = symmetricRoot(myIkSk, myIkPk, peerIkPk);
    if (rk0.size() != 32) return false;
    out = Session{};
    out.rk0 = rk0;
    out.rks = rk0;
    out.rkr = rk0;
    out.peerIdent = peerIkPk;
    out.identSk = myIkSk;
    return true;
}

QByteArray DoubleRatchetV4::encrypt(Session& st, const QByteArray& plain) {
    if (st.rk0.size() != 32) return {};
    // Every send is an INITIAL-type message on a chain rooted at the immutable
    // rk0 (fresh ephemeral, chain index restarts at 0). DH-turn (NORMAL-type)
    // chains gave per-message forward secrecy, but their message keys are
    // consumed on first read — and this product re-downloads ciphertext from
    // the server and re-decrypts it on every chat reopen, so every NORMAL row
    // became "encrypted message" forever once its key was used. rk0-rooted
    // chains are re-derivable any time from the identity key (see
    // decryptInitialStateless). The ephemeral rotates every kChainRotate
    // messages to bound the stateless chain walk.
    //
    // initChain is false for sessions serialized by older builds, which may
    // sit mid NORMAL-era chain: re-root once on the first send after upgrade.
    if (!st.sentFirst || !st.initChain || st.ns >= quint32(kChainRotate)) {
        QByteArray pk, sk;
        if (!newKp(pk, sk)) return {};
        const QByteArray dh = x25519(sk, st.peerIdent);
        if (dh.isEmpty()) return {};
        st.pn = st.ns;
        st.ns = 0;
        QByteArray nrk, nck;
        if (!kdfRk(st.rk0, dh, nrk, nck)) return {};
        st.rks = nrk;
        st.cks = nck;
        st.dhsSk = sk;
        st.dhsPk = pk;
        st.sentFirst = true;
        st.turnPending = false;
        st.initChain = true;
    }
    const char msgType = kTypeInitial;
    if (st.cks.size() != 32 || st.dhsPk.size() != 32) return {};
    QByteArray next, mk;
    kdfCk(st.cks, next, mk);
    st.cks = next;
    const quint32 n = st.ns;
    st.ns++;
    QByteArray nonce(24, '\0');
    randombytes_buf(nonce.data(), 24);
    QByteArray ct(plain.size() + crypto_secretbox_MACBYTES, '\0');
    if (crypto_secretbox_easy(reinterpret_cast<unsigned char*>(ct.data()),
                              reinterpret_cast<const unsigned char*>(plain.constData()),
                              static_cast<unsigned long long>(plain.size()),
                              reinterpret_cast<const unsigned char*>(nonce.constData()),
                              reinterpret_cast<const unsigned char*>(mk.constData())) != 0) {
        return {};
    }
    QByteArray out(kHeaderLen + 24 + ct.size(), '\0');
    out[0] = msgType;
    memcpy(out.data() + 1, st.dhsPk.constData(), 32);
    putBe32(out, 33, n);
    putBe32(out, 37, st.pn);
    memcpy(out.data() + 41, nonce.constData(), 24);
    memcpy(out.data() + 65, ct.constData(), ct.size());
    return out;
}

static bool skipRecv(DoubleRatchetV4::Session& st, quint32 until) {
    if (st.ckr.size() != 32 || st.dhr.size() != 32) return until == 0;
    if (until <= st.nr) return true;
    if (until - st.nr > quint32(kMaxSkip)) return false;
    while (st.nr < until) {
        QByteArray next, mk;
        kdfCk(st.ckr, next, mk);
        st.ckr = next;
        st.skipped.insert(skipId(st.dhr, st.nr), mk);
        st.nr++;
    }
    return true;
}

static bool recvRatchet(DoubleRatchetV4::Session& st, char msgType, const QByteArray& theirDh, quint32 pn) {
    if (!skipRecv(st, pn)) return false;
    QByteArray ourSk, root;
    if (msgType == kTypeInitial && !st.recvFirst) {
        ourSk = st.identSk;
        root = st.rk0;
    } else {
        if (st.dhsSk.size() != 32) return false;
        ourSk = st.dhsSk;
        root = st.rkr;
    }
    const QByteArray dh = x25519(ourSk, theirDh);
    if (dh.isEmpty()) return false;
    QByteArray nrk, nck;
    if (!kdfRk(root, dh, nrk, nck)) return false;
    st.rkr = nrk;
    st.ckr = nck;
    st.dhr = theirDh;
    st.nr = 0;
    st.recvFirst = true;
    st.turnPending = true;
    return true;
}

static bool decryptInto(DoubleRatchetV4::Session& st, const QByteArray& payload, QByteArray& out) {
    if (payload.size() < kHeaderLen + 24 + crypto_secretbox_MACBYTES) return false;
    const char msgType = payload[0];
    const QByteArray dh = payload.mid(1, 32);
    const quint32 n = getBe32(payload, 33);
    const quint32 pn = getBe32(payload, 37);
    const QByteArray nonce = payload.mid(41, 24);
    const QByteArray ct = payload.mid(65);

    const QString id = skipId(dh, n);
    if (st.skipped.contains(id)) {
        const QByteArray mk = st.skipped.take(id);
        bool ok = false;
        out = secretboxOpen(ct, nonce, mk, ok);
        return ok;
    }

    if (st.dhr.size() != 32 || st.dhr != dh) {
        if (!recvRatchet(st, msgType, dh, pn)) return false;
    }
    if (!skipRecv(st, n)) return false;
    QByteArray next, mk;
    kdfCk(st.ckr, next, mk);
    st.ckr = next;
    st.nr++;
    bool ok = false;
    out = secretboxOpen(ct, nonce, mk, ok);
    return ok;
}

// Stateless re-read of an INITIAL-type message. INITIAL chains derive from the
// immutable rk0 + DH(identSk, header eph), so any such message can be opened
// from scratch at any time — no session state consulted, nothing committed.
// This is what makes history refetch idempotent: the live ratchet consumes a
// message key exactly once, but rows are re-decrypted on every repaint and on
// every reopen, and the second attempt used to fail with the key gone. It is
// also the only way to read one's own fan-out blob back (the sending session
// cannot decrypt its own output).
static bool decryptInitialStateless(const DoubleRatchetV4::Session& st,
                                    const QByteArray& payload, QByteArray& out) {
    if (payload.size() < kHeaderLen + 24 + crypto_secretbox_MACBYTES) return false;
    if (payload[0] != kTypeInitial) return false;
    if (st.rk0.size() != 32 || st.identSk.size() != 32) return false;
    const QByteArray dh = payload.mid(1, 32);
    const quint32 n = getBe32(payload, 33);
    // Chain walk is just HMACs; cap it so a corrupt header can't spin us.
    if (n > 4096) return false;
    const QByteArray shared = x25519(st.identSk, dh);
    if (shared.isEmpty()) return false;
    QByteArray rk, ck;
    if (!kdfRk(st.rk0, shared, rk, ck)) return false;
    QByteArray next, mk;
    for (quint32 i = 0;; ++i) {
        kdfCk(ck, next, mk);
        ck = next;
        if (i == n) break;
    }
    const QByteArray nonce = payload.mid(41, 24);
    const QByteArray ct = payload.mid(65);
    bool ok = false;
    out = secretboxOpen(ct, nonce, mk, ok);
    return ok;
}

QByteArray DoubleRatchetV4::decrypt(Session& st, const QByteArray& payload) {
    QByteArray out;
    // INITIAL messages derive entirely from rk0 + the identity key, so open
    // them statelessly and commit nothing: repeated reads (history refetch,
    // repaints, own fan-out blob) then always succeed, and the session can
    // never be poisoned by reading old rows.
    if (decryptInitialStateless(st, payload, out)) return out;
    // Legacy NORMAL-type rows (or INITIAL under a stale-identity session):
    // classic transactional ratchet path.
    Session trial = st; // deep copy (QByteArray/QHash are copy-on-write)
    if (decryptInto(trial, payload, out)) {
        st = trial; // commit only after authentication succeeds
        return out;
    }
    return {};
}

// ---- serialization ----

QString DoubleRatchetV4::Session::toJson() const {
    QJsonObject o;
    o.insert(QStringLiteral("v"), 4);
    o.insert(QStringLiteral("rk0"), QString::fromLatin1(rk0.toHex()));
    o.insert(QStringLiteral("rks"), QString::fromLatin1(rks.toHex()));
    o.insert(QStringLiteral("cks"), QString::fromLatin1(cks.toHex()));
    o.insert(QStringLiteral("rkr"), QString::fromLatin1(rkr.toHex()));
    o.insert(QStringLiteral("ckr"), QString::fromLatin1(ckr.toHex()));
    o.insert(QStringLiteral("dhs_sk"), QString::fromLatin1(dhsSk.toHex()));
    o.insert(QStringLiteral("dhs_pk"), QString::fromLatin1(dhsPk.toHex()));
    o.insert(QStringLiteral("dhr"), QString::fromLatin1(dhr.toHex()));
    o.insert(QStringLiteral("peer_ident"), QString::fromLatin1(peerIdent.toHex()));
    o.insert(QStringLiteral("ident_sk"), QString::fromLatin1(identSk.toHex()));
    o.insert(QStringLiteral("ns"), int(ns));
    o.insert(QStringLiteral("nr"), int(nr));
    o.insert(QStringLiteral("pn"), int(pn));
    o.insert(QStringLiteral("sent_first"), sentFirst);
    o.insert(QStringLiteral("recv_first"), recvFirst);
    o.insert(QStringLiteral("turn_pending"), turnPending);
    o.insert(QStringLiteral("init_chain"), initChain);
    o.insert(QStringLiteral("seq"), qint64(seq));
    QJsonObject sk;
    for (auto it = skipped.constBegin(); it != skipped.constEnd(); ++it)
        sk.insert(it.key(), QString::fromLatin1(it.value().toHex()));
    o.insert(QStringLiteral("skipped"), sk);
    return QString::fromUtf8(QJsonDocument(o).toJson(QJsonDocument::Compact));
}

bool DoubleRatchetV4::Session::fromJson(const QString& json, Session& out) {
    const QJsonObject o = QJsonDocument::fromJson(json.toUtf8()).object();
    if (o.value(QStringLiteral("v")).toInt() != 4) return false;
    out = Session{};
    out.rk0 = QByteArray::fromHex(o.value(QStringLiteral("rk0")).toString().toLatin1());
    out.rks = QByteArray::fromHex(o.value(QStringLiteral("rks")).toString().toLatin1());
    out.cks = QByteArray::fromHex(o.value(QStringLiteral("cks")).toString().toLatin1());
    out.rkr = QByteArray::fromHex(o.value(QStringLiteral("rkr")).toString().toLatin1());
    out.ckr = QByteArray::fromHex(o.value(QStringLiteral("ckr")).toString().toLatin1());
    out.dhsSk = QByteArray::fromHex(o.value(QStringLiteral("dhs_sk")).toString().toLatin1());
    out.dhsPk = QByteArray::fromHex(o.value(QStringLiteral("dhs_pk")).toString().toLatin1());
    out.dhr = QByteArray::fromHex(o.value(QStringLiteral("dhr")).toString().toLatin1());
    out.peerIdent = QByteArray::fromHex(o.value(QStringLiteral("peer_ident")).toString().toLatin1());
    out.identSk = QByteArray::fromHex(o.value(QStringLiteral("ident_sk")).toString().toLatin1());
    out.ns = quint32(o.value(QStringLiteral("ns")).toInt());
    out.nr = quint32(o.value(QStringLiteral("nr")).toInt());
    out.pn = quint32(o.value(QStringLiteral("pn")).toInt());
    out.sentFirst = o.value(QStringLiteral("sent_first")).toBool();
    out.recvFirst = o.value(QStringLiteral("recv_first")).toBool();
    out.turnPending = o.value(QStringLiteral("turn_pending")).toBool();
    out.initChain = o.value(QStringLiteral("init_chain")).toBool(false);
    out.seq = quint64(o.value(QStringLiteral("seq")).toVariant().toULongLong());
    const QJsonObject sk = o.value(QStringLiteral("skipped")).toObject();
    for (auto it = sk.begin(); it != sk.end(); ++it)
        out.skipped.insert(it.key(), QByteArray::fromHex(it.value().toString().toLatin1()));
    return out.rk0.size() == 32 && out.peerIdent.size() == 32 && out.identSk.size() == 32;
}

// ---- self-test against the Go/Kotlin vectors ----

bool DoubleRatchetV4::selfTest() {
    auto h = [](const char* s) { return QByteArray::fromHex(QByteArray(s)); };
    const QByteArray aPk = h("a19126c482d962e1bd6250037cbb70db92cc906ecdbc1018fc37fb8c6061522e");
    const QByteArray aSk = h("a0a9aeb7bc858a9398e1e6eff4fdc2cbd0d9de272c353a030811161f646d727b");
    const QByteArray bPk = h("a825f4f280990025a16e35322c14affe12bf4fbebd85704d0064b6357b2f573c");
    const QByteArray bSk = h("b3babda4af9699808bf2f5fce7eed1d8c3cacd343f2629101b02050c777e6168");
    const QByteArray rk0 = h("4a635dfcf3f667ad0ec5421ff2face9f1b3192b7489647a6a5e05af45b1af57f");

    bool pass = true;

    // 1. Symmetric root matches the Go vector from either side.
    if (symmetricRoot(aSk, aPk, bPk) != rk0 || symmetricRoot(bSk, bPk, aPk) != rk0) {
        qWarning("[v4-selftest] RK0 mismatch");
        pass = false;
    }

    // 2. Decrypt the frozen Go A->B transcript (delivered out of order).
    struct M { const char* plain; const char* ct; };
    const M transcript[] = {
        {"m0", "010214df1e3076858b1ac0a78436041b7a04c067fc7954740f82263d5fc7abbc390000000000000000a11859ccb6674f7c6c3965a7b14688388238e6d6fb8a0565b03b0b0c282216236e080d342788b40666f9"},
        {"m2", "010214df1e3076858b1ac0a78436041b7a04c067fc7954740f82263d5fc7abbc390000000200000000eba77f90d77589b14dea2cd098c582b7c18dce2282c2564ee3f695f3b76a3704802dda4939779af53419"},
        {"m4", "010214df1e3076858b1ac0a78436041b7a04c067fc7954740f82263d5fc7abbc39000000040000000041412aed12416d14e2cf57ddd08d4b37f13da123e108252383d0d8fe8bf05c0cd7f2ac0b7cfe67c9a6da"},
        {"m1", "010214df1e3076858b1ac0a78436041b7a04c067fc7954740f82263d5fc7abbc3900000001000000007b2d134ac20055192f56a0843ec7c93c99e6a12d926cdf2b1d41b14aa49dc8f8d0897e18288cffb18dd5"},
        {"m3", "010214df1e3076858b1ac0a78436041b7a04c067fc7954740f82263d5fc7abbc39000000030000000082972a77ddecd52dfb2b715fab9bda2cd758fcc16901e144735dcb7f33bcafbb76eb1480f7df3dc88838"},
    };
    Session b;
    if (!initSession(bPk, bSk, aPk, b)) { qWarning("[v4-selftest] initSession B failed"); pass = false; }
    for (const auto& m : transcript) {
        const QByteArray got = decrypt(b, h(m.ct));
        if (got != QByteArray(m.plain)) {
            qWarning("[v4-selftest] transcript %s mismatch (got '%s')", m.plain, got.constData());
            pass = false;
        }
    }

    // 3. Live glare round-trip (both send before either receives) + continuation.
    Session ga, gb;
    initSession(aPk, aSk, bPk, ga);
    initSession(bPk, bSk, aPk, gb);
    const QByteArray a1 = encrypt(ga, QByteArray("A1"));
    const QByteArray b1 = encrypt(gb, QByteArray("B1"));
    if (decrypt(gb, a1) != QByteArray("A1") || decrypt(ga, b1) != QByteArray("B1")) {
        qWarning("[v4-selftest] glare initial failed");
        pass = false;
    }
    if (decrypt(gb, encrypt(ga, QByteArray("A2"))) != QByteArray("A2") ||
        decrypt(ga, encrypt(gb, QByteArray("B2"))) != QByteArray("B2")) {
        qWarning("[v4-selftest] glare continuation failed");
        pass = false;
    }

    // 3a. Sends stay INITIAL-type even after receiving (no NORMAL turns), and
    // every message stays re-readable after its first decrypt.
    const QByteArray a3 = encrypt(ga, QByteArray("A3"));
    if (a3.isEmpty() || a3[0] != kTypeInitial) {
        qWarning("[v4-selftest] post-receive send is not INITIAL");
        pass = false;
    }
    if (decrypt(gb, a3) != QByteArray("A3") || decrypt(gb, a3) != QByteArray("A3")) {
        qWarning("[v4-selftest] INITIAL re-read after turn failed");
        pass = false;
    }

    // 3b. History re-reads: decrypting a transcript row a second time (repaint,
    // reopen) must still work even though the live key was consumed.
    if (decrypt(b, h(transcript[0].ct)) != QByteArray(transcript[0].plain)) {
        qWarning("[v4-selftest] stateless INITIAL re-read failed");
        pass = false;
    }

    // 3c. Own fan-out blob: sealed with a throwaway self-session (peer = own
    // identity), it must decrypt repeatedly from any state holding the keys.
    Session selfOut;
    initSession(aPk, aSk, aPk, selfOut);
    const QByteArray selfBlob = encrypt(selfOut, QByteArray("mine"));
    Session selfIn;
    initSession(aPk, aSk, aPk, selfIn);
    if (decrypt(selfIn, selfBlob) != QByteArray("mine") ||
        decrypt(selfIn, selfBlob) != QByteArray("mine")) {
        qWarning("[v4-selftest] self-blob decrypt failed");
        pass = false;
    }

    // 4. Tampered ciphertext must fail without desyncing the session.
    Session ta, tb;
    initSession(aPk, aSk, bPk, ta);
    initSession(bPk, bSk, aPk, tb);
    QByteArray good = encrypt(ta, QByteArray("real"));
    QByteArray bad = good;
    bad[bad.size() - 1] = char(bad[bad.size() - 1] ^ 0xff);
    if (!decrypt(tb, bad).isEmpty()) { qWarning("[v4-selftest] tampered opened"); pass = false; }
    if (decrypt(tb, good) != QByteArray("real")) { qWarning("[v4-selftest] good after tamper failed"); pass = false; }

    if (pass) qInfo("[v4-selftest] PASS: RK0 + transcript + glare + tamper");
    else qWarning("[v4-selftest] FAIL");
    return pass;
}
