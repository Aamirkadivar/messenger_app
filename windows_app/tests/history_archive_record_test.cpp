// Phase 36 - single-message archive record: keyring and sealing, composed.
//
// Phase 34 proved the keyring lifecycle; Phase 35 proved the sealing primitive against independent
// vectors. Neither proved that they are wired to each other correctly - a composition can be wrong
// while both halves are right, by passing the wrong root version, the wrong account, or a root
// obtained outside the account boundary.
//
// The decisive test here is the deterministic one: the root is NOT handed to the sealer directly.
// It is obtained through the Phase 34 repository, and the resulting ciphertext is compared against
// the Phase 35 vector that was computed by an independent implementation. If the composition took
// the wrong root, the wrong version, or the wrong identity, those bytes could not match.
//
// The vault stand-in mirrors AuthService exactly - same AAD domain, same fail-closed rule when no
// session master key is held, no accessor returning the key - so these tests keep meaning something
// about the real wiring rather than about a simplified one.
//
// Everything is synthetic and in memory. No account, database, network, cache, or message flow is
// touched, and nothing is persisted. No key, root, or plaintext is printed.

#include "../src/crypto/historyarchiver.h"
#include "../src/crypto/historykeyringstore.h"
#include "../src/crypto/historycrypto.h"
#include "../src/crypto/vaultcrypto.h"

#include <QByteArray>
#include <QHash>
#include <QTextStream>
#include <memory>
#include <sodium.h>

static int g_failures = 0;
static int g_checks = 0;
static void check(bool ok, const QString& what) {
    ++g_checks;
    QTextStream out(stdout);
    out << (ok ? "  PASS  " : "  FAIL  ") << what << Qt::endl;
    if (!ok) ++g_failures;
}
static void section(const char* t) { QTextStream(stdout) << "-- " << t << " --" << Qt::endl; }

// ------------------------------------------------------- Phase 35 fixture 2, reused verbatim

static const char* kUserA = "9f1c0d4e-0000-4000-8000-0000000000a1";
static const char* kUserB = "0c4d8e12-0000-4000-8000-0000000000b7";
static const char* kChat  = "3b7e5f21-0000-4000-8000-0000000000c2";
static const char* kMsg   = "7d2a91b0-0000-4000-8000-0000000000e3";
static const char* kNonceHex = "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7";
static const char* kPlainHex =
    "503335206172636869766520666978747572653a2068c3a96c6c6f2077c3b6726c6420e29c9320"
    "e697a5e69cace8aa9e20ce95cebbcebbceb7cebdceb9cebaceac000001fffe807f20656e64";
// Established in Phase 35 by an independent XChaCha20-Poly1305 implementation, never by this code.
static const char* kExpectedWireB64 =
    "oKGio6SlpqeoqaqrrK2ur7CxsrO0tba3h641gShq9PbpgibJgl1KbsA5WVozb7pWlzuY6B7HDRWkGWxlzIVsLR"
    "dDXKmPjgWah3zyVGw/I/L7L8aAhB2NlGIM6yAzb1Lzu39sI83Oi8x7oo5GUA+BTTrkTxw=";

static QByteArray hex(const char* h) { return QByteArray::fromHex(QByteArray(h).simplified()); }

/** The exact root the Phase 35 vector was computed under: 00 01 .. 1f. */
static QByteArray fixtureRoot() {
    QByteArray r(HistoryCrypto::ROOT_BYTES, 0);
    for (int i = 0; i < r.size(); ++i) r[i] = static_cast<char>(i);
    return r;
}

// ------------------------------------------------------------------ session stand-ins

class FakeVault {
public:
    void unlock(const QString& userId) {
        m_userId = userId;
        m_mk.resize(32);
        for (int i = 0; i < 32; ++i) m_mk[i] = static_cast<char>(0x70 + i);
    }
    void lock() { m_mk.clear(); }
    bool isUnlocked() const { return !m_mk.isEmpty(); }
    static QByteArray aad(const QString& u) {
        return QStringLiteral("history-keyring|%1|%2|%3")
            .arg(u).arg(1).arg(QLatin1String(VaultCrypto::SUITE_VAULT_AEAD)).toUtf8();
    }
    bool seal(const QByteArray& p, QByteArray& o) const {
        if (m_mk.isEmpty() || m_userId.isEmpty()) return false;
        o = VaultCrypto::sealXChaCha(m_mk, p, aad(m_userId));
        return !o.isEmpty();
    }
    bool open(const QByteArray& s, QByteArray& o) const {
        if (m_mk.isEmpty() || m_userId.isEmpty()) return false;
        o = VaultCrypto::openXChaCha(m_mk, s, aad(m_userId));
        return !o.isEmpty();
    }
private:
    QByteArray m_mk;
    QString m_userId;
};

class FakeStore : public HistoryKeyringStore {
public:
    QHash<QString, QByteArray> k, c, g;
    bool saveKeyring(const QString& o, const QByteArray& s) override { k[o] = s; return true; }
    SlotRead loadKeyring(const QString& o) override {
        return k.contains(o) ? SlotRead::ok(k[o]) : SlotRead::absent(); }
    bool deleteKeyring(const QString& o) override { k.remove(o); return true; }
    bool saveCache(const QString& o, const QByteArray& p) override { c[o] = p; return true; }
    SlotRead loadCache(const QString& o) override {
        return c.contains(o) ? SlotRead::ok(c[o]) : SlotRead::absent(); }
    bool deleteCache(const QString& o) override { c.remove(o); return true; }
    bool saveGeneration(const QString& o, qint64 v) override { g[o] = QByteArray::number(v); return true; }
    SlotRead loadGeneration(const QString& o) override {
        return g.contains(o) ? SlotRead::ok(g[o]) : SlotRead::absent(); }
    bool deleteGeneration(const QString& o) override { g.remove(o); return true; }
};

/**
 * A session: repository + archiver bound exactly as AuthService binds them, including the account
 * authority. `rootDraws` counts CSPRNG root generation so a refused mint can be proven to draw none.
 */
struct Session {
    FakeStore store;
    FakeVault vault;
    QString owner;
    int rootDraws = 0;
    std::unique_ptr<HistoryKeyringRepository> repo;

    void start(const QString& account, bool unlocked, const QByteArray& pinnedRoot = QByteArray()) {
        owner = account;
        if (unlocked) vault.unlock(account);
        HistoryKeyringRepository::RandomRootFn rng = [this, pinnedRoot]() {
            ++rootDraws;
            if (!pinnedRoot.isEmpty()) return pinnedRoot;
            QByteArray r(HistoryCrypto::ROOT_BYTES, 0);
            randombytes_buf(r.data(), static_cast<size_t>(r.size()));
            return r;
        };
        repo = std::make_unique<HistoryKeyringRepository>(
            &store,
            [this](const QByteArray& p, QByteArray& o) { return vault.seal(p, o); },
            [this](const QByteArray& s, QByteArray& o) { return vault.open(s, o); },
            rng,
            [this]() { return vault.isUnlocked(); });
    }

    HistoryArchiver archiver() {
        return HistoryArchiver(
            [this](const QString& chatId, HistoryRootEntry& out, QString* err) {
                return repo->ensureRoot(owner, chatId, out, err);
            },
            [this](const QString& chatId, int rootVersion, HistoryRootEntry& out, QString* err) {
                HistoryKeyring keyring;
                if (!repo->load(owner, keyring, err)) return false;
                if (!keyring.find(chatId, rootVersion, out)) {
                    if (err) *err = QStringLiteral("root version not held");
                    return false;
                }
                return true;
            },
            [this]() { return owner; });
    }
};

static ArchiveMessage messageFor(const QString& owner, const QByteArray& plain) {
    ArchiveMessage m;
    m.userId = owner;
    m.chatId = QString::fromLatin1(kChat);
    m.messageId = QString::fromLatin1(kMsg);
    m.plaintext = plain;
    return m;
}

int main() {
    if (sodium_init() < 0) return 2;
    QTextStream out(stdout);
    out << "Phase 36 - single-message archive record" << Qt::endl;

    const QString userA = QString::fromLatin1(kUserA);
    const QString userB = QString::fromLatin1(kUserB);
    const QByteArray plain = hex(kPlainHex);
    const QByteArray nonce = hex(kNonceHex);
    QString err;

    // =============================================== deterministic composition proof
    section("keyring root -> sealing -> record reproduces the independent Phase 35 vector");
    {
        Session s;
        s.start(userA, /*unlocked=*/true, fixtureRoot());
        auto arch = s.archiver();

        ArchiveRecord rec;
        check(arch.sealWithNonce(messageFor(userA, plain), nonce, rec, &err),
              QStringLiteral("seal succeeds through the full composition"));
        check(s.rootDraws == 1, QStringLiteral("the root came from the keyring, minted once"));
        check(rec.rootVersion == 1, QStringLiteral("record carries rootVersion 1"));
        check(rec.protocolVersion == 1, QStringLiteral("record carries protocolVersion 1"));
        check(rec.messageId == QString::fromLatin1(kMsg), QStringLiteral("record carries the message id"));
        check(rec.chatId == QString::fromLatin1(kChat), QStringLiteral("record carries the chat id"));
        check(rec.isValid(), QStringLiteral("record is complete"));
        check(rec.ciphertextB64 == QString::fromLatin1(kExpectedWireB64),
              QStringLiteral("ciphertext equals the independently established Phase 35 vector"));

        QByteArray back;
        check(arch.open(rec, back, &err) && back == plain,
              QStringLiteral("the record reopens to the exact original plaintext"));
    }

    // =============================================== fresh nonce (production path)
    section("production path draws a fresh nonce every time");
    {
        Session s;
        s.start(userA, true, fixtureRoot());
        auto arch = s.archiver();
        const auto msg = messageFor(userA, plain);

        ArchiveRecord a, b;
        check(arch.seal(msg, a, &err) && arch.seal(msg, b, &err),
              QStringLiteral("the same message seals twice"));
        check(a.ciphertextB64 != b.ciphertextB64,
              QStringLiteral("two seals of one message differ"));
        check(a.ciphertextB64 != QString::fromLatin1(kExpectedWireB64) &&
                  b.ciphertextB64 != QString::fromLatin1(kExpectedWireB64),
              QStringLiteral("neither reuses the deterministic fixture nonce"));
        check(a.rootVersion == b.rootVersion && a.rootVersion == 1,
              QStringLiteral("both name the same root version"));

        QByteArray pa, pb;
        check(arch.open(a, pa, &err) && pa == plain, QStringLiteral("record A opens"));
        check(arch.open(b, pb, &err) && pb == plain, QStringLiteral("record B opens"));
    }

    // =============================================== root version behaviour
    section("root creation, reuse and rotation");
    {
        Session s;
        s.start(userA, true);
        auto arch = s.archiver();

        ArchiveRecord first;
        check(arch.seal(messageFor(userA, plain), first, &err) && first.rootVersion == 1,
              QStringLiteral("first seal creates and names root version 1"));
        const int drawsAfterFirst = s.rootDraws;

        ArchiveRecord second;
        check(arch.seal(messageFor(userA, plain), second, &err) && second.rootVersion == 1,
              QStringLiteral("second seal reuses root version 1"));
        check(s.rootDraws == drawsAfterFirst,
              QStringLiteral("reuse draws no new root material"));

        // Legitimate rotation through the keyring, not through the archiver.
        HistoryRootEntry rotated;
        check(s.repo->rotate(userA, QString::fromLatin1(kChat), rotated, &err) &&
                  rotated.rootVersion == 2,
              QStringLiteral("keyring rotation produces version 2"));

        ArchiveRecord afterRotation;
        check(arch.seal(messageFor(userA, plain), afterRotation, &err) &&
                  afterRotation.rootVersion == 2,
              QStringLiteral("a new seal names the latest root version"));
        check(afterRotation.ciphertextB64 != first.ciphertextB64,
              QStringLiteral("the rotated root produces different ciphertext"));

        // Old records stay openable: rotation adds a version, it does not retire one.
        QByteArray old;
        check(arch.open(first, old, &err) && old == plain,
              QStringLiteral("a record sealed under version 1 still opens after rotation"));

        // A record naming a version this device does not hold must fail, not fall back.
        ArchiveRecord bogus = first;
        bogus.rootVersion = 99;
        QByteArray none;
        check(!arch.open(bogus, none, &err),
              QStringLiteral("a record naming an unheld root version fails closed"));
    }

    // =============================================== locked / unlocked
    section("vault state: the Phase 34 invariant carried into archiving");
    {
        // Case A: unlocked + missing root -> root created, message sealed.
        Session a;
        a.start(userA, true);
        ArchiveRecord ra;
        check(a.archiver().seal(messageFor(userA, plain), ra, &err) && ra.rootVersion == 1,
              QStringLiteral("A. unlocked + missing root: root created, message sealed"));

        // Case B: locked + existing root -> still sealable, per the Phase 34 cold-start contract.
        a.vault.lock();
        a.repo->forgetInMemory();
        ArchiveRecord rb;
        const int drawsBefore = a.rootDraws;
        check(a.archiver().seal(messageFor(userA, plain), rb, &err) && rb.rootVersion == 1,
              QStringLiteral("B. locked + existing root: existing root used, message sealed"));
        check(a.rootDraws == drawsBefore, QStringLiteral("B. no root material was drawn"));
        QByteArray pb;
        check(a.archiver().open(rb, pb, &err) && pb == plain,
              QStringLiteral("B. the locked-state record opens"));

        // Case C: locked + missing root -> fail closed, mint nothing.
        Session c;
        c.start(userA, /*unlocked=*/false);
        ArchiveMessage unseen = messageFor(userA, plain);
        unseen.chatId = QStringLiteral("11111111-0000-4000-8000-000000000fff");
        ArchiveRecord rc;
        check(!c.archiver().seal(unseen, rc, &err),
              QStringLiteral("C. locked + missing root: refused"));
        check(c.rootDraws == 0, QStringLiteral("C. NO root material was generated"));
        check(c.store.k.isEmpty() && c.store.c.isEmpty() && c.store.g.isEmpty(),
              QStringLiteral("C. nothing was persisted"));
        check(!c.vault.isUnlocked(), QStringLiteral("C. the vault was not silently unlocked"));
        check(rc.ciphertextB64.isEmpty(), QStringLiteral("C. no record was produced"));
    }

    // =============================================== cross-account isolation
    section("account isolation");
    {
        Session sa; sa.start(userA, true, fixtureRoot());
        auto archA = sa.archiver();
        ArchiveRecord recA;
        check(archA.sealWithNonce(messageFor(userA, plain), nonce, recA, &err),
              QStringLiteral("A seals a record"));

        // B has its own store and its own vault: a separate account in every sense.
        Session sb; sb.start(userB, true, fixtureRoot());
        auto archB = sb.archiver();

        // B cannot reach A's root through the application boundary - the owner is not a parameter
        // B controls; it comes from B's own session.
        HistoryKeyring kb;
        check(sb.repo->load(userB, kb, &err) && kb.isEmpty(),
              QStringLiteral("B starts with its own empty keyring namespace"));
        QByteArray nothing;
        check(!archB.open(recA, nothing, &err),
              QStringLiteral("B cannot open A's record: B holds no root for that chat"));

        // Even once B has a root for the same chat - and even the SAME root bytes - A's record
        // still will not open, because the AAD binds the account.
        ArchiveRecord recB;
        check(archB.sealWithNonce(messageFor(userB, plain), nonce, recB, &err),
              QStringLiteral("B seals its own record for the same chat and message id"));
        check(recB.ciphertextB64 != recA.ciphertextB64,
              QStringLiteral("identical root, chat, message and nonce still differ by account"));
        check(!archB.open(recA, nothing, &err),
              QStringLiteral("B still cannot open A's record"));

        QByteArray pa;
        check(archA.open(recA, pa, &err) && pa == plain,
              QStringLiteral("A remains able to open A's record"));

        // No caller-controlled owner bypass: naming another account is refused outright.
        ArchiveRecord stolen;
        check(!archB.seal(messageFor(userA, plain), stolen, &err),
              QStringLiteral("B cannot seal a message claiming to be A"));
        check(err.contains(QStringLiteral("signed-in account")),
              QStringLiteral("the refusal is an account-authority refusal"));
    }

    // =============================================== round trip across byte classes
    section("round trip over ASCII, UTF-8, NUL and high-bit bytes");
    {
        Session s; s.start(userA, true);
        auto arch = s.archiver();

        struct Case { const char* name; QByteArray body; };
        const QList<Case> cases = {
            { "plain ASCII", QByteArray("simple ascii message") },
            { "multibyte UTF-8", QString::fromUtf8("héllo wörld ✓ 日本語 Ελληνικά").toUtf8() },
            { "embedded NUL bytes", QByteArray("before\x00\x00after", 13) },
            { "high-bit bytes", QByteArray::fromHex("fffe807f00010203") },
            { "the Phase 35 mixed fixture", plain },
        };
        for (const Case& c : cases) {
            ArchiveRecord r;
            QByteArray back;
            const bool ok = arch.seal(messageFor(userA, c.body), r, &err) &&
                            arch.open(r, back, &err) && back == c.body;
            check(ok, QStringLiteral("round trip: %1").arg(QLatin1String(c.name)));
        }
    }

    // =============================================== failure behaviour
    section("failures are closed and produce nothing");
    {
        Session s; s.start(userA, true, fixtureRoot());
        auto arch = s.archiver();

        ArchiveRecord good;
        arch.sealWithNonce(messageFor(userA, plain), nonce, good, &err);

        ArchiveRecord r;
        ArchiveMessage m = messageFor(userA, plain);

        m.messageId.clear();
        check(!arch.seal(m, r, &err), QStringLiteral("empty messageId is refused"));
        m = messageFor(userA, plain); m.chatId.clear();
        check(!arch.seal(m, r, &err), QStringLiteral("empty chatId is refused"));
        m = messageFor(userA, plain); m.userId.clear();
        check(!arch.seal(m, r, &err), QStringLiteral("empty userId is refused"));

        check(!arch.sealWithNonce(messageFor(userA, plain), QByteArray(23, 'n'), r, &err),
              QStringLiteral("a wrong-sized nonce is refused"));

        // Corrupted ciphertext must not open, and must not leak a partial body.
        ArchiveRecord tampered = good;
        QByteArray raw = HistoryCrypto::fromWireB64(tampered.ciphertextB64);
        raw[40] = static_cast<char>(raw[40] ^ 0x01);
        tampered.ciphertextB64 = HistoryCrypto::toWireB64(raw);
        QByteArray leaked;
        check(!arch.open(tampered, leaked, &err),
              QStringLiteral("corrupted ciphertext fails to open"));
        check(leaked.isEmpty(), QStringLiteral("no partial plaintext escaped"));

        ArchiveRecord badB64 = good;
        badB64.ciphertextB64 = QStringLiteral("!!!not base64!!!");
        check(!arch.open(badB64, leaked, &err) && leaked.isEmpty(),
              QStringLiteral("malformed Base64 fails to open"));

        ArchiveRecord incomplete;
        check(!arch.open(incomplete, leaked, &err),
              QStringLiteral("an incomplete record is refused"));

        ArchiveRecord wrongMsg = good;
        wrongMsg.messageId = QStringLiteral("7d2a91b0-0000-4000-8000-0000000000ff");
        check(!arch.open(wrongMsg, leaked, &err) && leaked.isEmpty(),
              QStringLiteral("a record re-labelled with another message id fails"));

        ArchiveRecord wrongProto = good;
        wrongProto.protocolVersion = 2;
        check(!arch.open(wrongProto, leaked, &err),
              QStringLiteral("a record re-labelled with another protocol version fails"));

        // No session at all.
        HistoryArchiver unwired(nullptr, nullptr, nullptr);
        check(!unwired.seal(messageFor(userA, plain), r, &err),
              QStringLiteral("an unwired archiver refuses to seal"));
        check(!unwired.open(good, leaked, &err),
              QStringLiteral("an unwired archiver refuses to open"));
    }

    out << (g_failures == 0 ? "ALL PASS" : "FAILURES")
        << "  checks=" << g_checks << " failures=" << g_failures << Qt::endl;
    return g_failures == 0 ? 0 : 1;
}
