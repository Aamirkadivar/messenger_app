// Phase 34 - history keyring repository lifecycle.
//
// Phase 32 built the repository and proved its policy against injected fakes. Phase 33 bound the
// transport to the real session. What was still unproven is the join: that the repository behaves
// correctly when its seal/open pair is the REAL master-key boundary, with a vault that genuinely
// locks and unlocks, an account that changes, and a process that restarts.
//
// The vault stand-in below mirrors AuthService exactly: the same AAD domain
// (history-keyring|<userId>|1|<suite>), the same fail-closed rule when no session master key is
// held, and the same absence of any accessor returning the key. If AuthService's rules and these
// diverge, these tests stop meaning anything - so they are written against the same construction
// rather than a simplified one.
//
// All material is synthetic: a counting-pattern master key, literal account ids, CSPRNG roots that
// are never printed. Nothing here touches a real account, the production registry slot, or a server.
//
// Modes: default runs the deterministic suite. "write"/"read"/"purge" drive the real DPAPI-backed
// registry store across separate processes for the restart state.

#include "../src/crypto/historykeyring.h"
#include "../src/crypto/historykeyringstore.h"
#include "../src/crypto/historycrypto.h"
#include "../src/crypto/vaultcrypto.h"

#include <QByteArray>
#include <QCryptographicHash>
#include <QHash>
#include <memory>
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
static void section(const char* t) { QTextStream(stdout) << "-- " << t << " --" << Qt::endl; }

static const char* kUserA = "aaaaaaaa-0000-4000-8000-00000000000a";
static const char* kUserB = "bbbbbbbb-0000-4000-8000-00000000000b";
static const char* kChat  = "00000000-0000-0000-0000-000000000001";
static const char* kTestRegistryPath =
    "HKEY_CURRENT_USER\\Software\\MessengerApp\\HistoryKeyringP34";

// ---------------------------------------------------------------- vault stand-in
//
// Exactly AuthService's boundary: hands out ciphertext or plaintext, never the key.

class FakeVault {
public:
    void unlock(const QString& userId) {
        m_userId = userId;
        m_mk.resize(32);
        for (int i = 0; i < 32; ++i) m_mk[i] = static_cast<char>(0x70 + i);
    }
    /** Vault lock: the master key is dropped. Mirrors AuthService::clearVaultSession(). */
    void lock() { m_mk.clear(); }
    bool isUnlocked() const { return !m_mk.isEmpty(); }

    static QByteArray aad(const QString& userId) {
        return QStringLiteral("history-keyring|%1|%2|%3")
            .arg(userId).arg(1).arg(QLatin1String(VaultCrypto::SUITE_VAULT_AEAD)).toUtf8();
    }

    bool seal(const QByteArray& plain, QByteArray& out) const {
        if (m_mk.isEmpty() || m_userId.isEmpty()) return false;
        out = VaultCrypto::sealXChaCha(m_mk, plain, aad(m_userId));
        return !out.isEmpty();
    }
    bool open(const QByteArray& sealed, QByteArray& out) const {
        if (m_mk.isEmpty() || m_userId.isEmpty()) return false;
        out = VaultCrypto::openXChaCha(m_mk, sealed, aad(m_userId));
        return !out.isEmpty();
    }

private:
    QByteArray m_mk;
    QString m_userId;
};

// ---------------------------------------------------------------- in-memory store

class FakeStore : public HistoryKeyringStore {
public:
    QHash<QString, QByteArray> keyring, cache, generation;

    bool saveKeyring(const QString& o, const QByteArray& s) override { keyring[o] = s; return true; }
    SlotRead loadKeyring(const QString& o) override {
        return keyring.contains(o) ? SlotRead::ok(keyring[o]) : SlotRead::absent();
    }
    bool deleteKeyring(const QString& o) override { keyring.remove(o); return true; }
    bool saveCache(const QString& o, const QByteArray& p) override { cache[o] = p; return true; }
    SlotRead loadCache(const QString& o) override {
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

// Builds a repository wired the way AuthService::historyKeyring() wires one.
static std::unique_ptr<HistoryKeyringRepository> makeRepo(HistoryKeyringStore* store,
                                                          const FakeVault* vault,
                                                          int* rootDraws = nullptr) {
    HistoryKeyringRepository::RandomRootFn rng = nullptr;
    if (rootDraws) {
        rng = [rootDraws]() {
            ++(*rootDraws);
            QByteArray r(HistoryCrypto::ROOT_BYTES, 0);
            randombytes_buf(r.data(), static_cast<size_t>(r.size()));
            return r;
        };
    }
    return std::make_unique<HistoryKeyringRepository>(
        store,
        [vault](const QByteArray& p, QByteArray& o) { return vault->seal(p, o); },
        [vault](const QByteArray& s, QByteArray& o) { return vault->open(s, o); },
        rng,
        [vault]() { return vault->isUnlocked(); });
}

static QByteArray sha(const QByteArray& b) {
    return QCryptographicHash::hash(b, QCryptographicHash::Sha256).toHex();
}

// ---------------------------------------------------------------- restart modes

static int restartMode(const QString& mode) {
    QTextStream out(stdout);
    const QString owner = QString::fromLatin1(kUserA);
    const QString chat = QString::fromLatin1(kChat);

    DpapiProtector protector;
    RegistryHistoryKeyringStore store(&protector, QString::fromLatin1(kTestRegistryPath));

    if (mode == QStringLiteral("purge")) { store.purge(owner); out << "PURGED" << Qt::endl; return 0; }

    FakeVault vault;
    vault.unlock(owner);
    auto repo = makeRepo(&store, &vault);

    if (mode == QStringLiteral("write")) {
        store.purge(owner);
        HistoryRootEntry e;
        QString err;
        if (!repo->ensureRoot(owner, chat, e, &err)) { out << "WRITE_FAILED " << err << Qt::endl; return 1; }
        out << "VERSION=" << e.rootVersion << Qt::endl;
        out << "ROOT_SHA256=" << sha(e.root) << Qt::endl;
        return 0;
    }

    // read: a brand-new process with nothing in memory.
    HistoryRootEntry e;
    QString err;
    if (!repo->ensureRoot(owner, chat, e, &err)) { out << "READ_FAILED " << err << Qt::endl; return 1; }
    out << "VERSION=" << e.rootVersion << Qt::endl;
    out << "ROOT_SHA256=" << sha(e.root) << Qt::endl;
    out << "MINTED_NEW=" << (repo->revision() == 0 ? "NO" : "YES") << Qt::endl;

    // And with the vault LOCKED, an existing root must still be served from the protected cache.
    FakeVault locked;
    auto lockedRepo = makeRepo(&store, &locked);
    HistoryRootEntry le;
    if (!lockedRepo->ensureRoot(owner, chat, le, &err)) { out << "LOCKED_FAILED " << err << Qt::endl; return 1; }
    out << "LOCKED_ROOT_SHA256=" << sha(le.root) << Qt::endl;
    return 0;
}

int main(int argc, char** argv) {
    if (sodium_init() < 0) return 2;
    QTextStream out(stdout);

    const QString mode = argc > 1 ? QString::fromLatin1(argv[1]) : QString();
    if (mode == QStringLiteral("write") || mode == QStringLiteral("read") ||
        mode == QStringLiteral("purge")) {
        return restartMode(mode);
    }

    out << "Phase 34 - history keyring repository lifecycle" << Qt::endl;
    const QString a = QString::fromLatin1(kUserA);
    const QString b = QString::fromLatin1(kUserB);
    const QString chat = QString::fromLatin1(kChat);

    // =========================================== State 1: authenticated + unlocked
    section("State 1: authenticated, vault unlocked");
    FakeStore store;
    FakeVault vault;
    vault.unlock(a);
    int draws = 0;
    auto repo = makeRepo(&store, &vault, &draws);

    HistoryRootEntry first;
    QString err;
    check(repo->ensureRoot(a, chat, first, &err), QStringLiteral("ensureRoot creates a root"));
    check(first.root.size() == 32, QStringLiteral("root is 32 bytes"));
    check(first.rootVersion == 1, QStringLiteral("rootVersion starts at 1"));
    check(first.chatId == chat, QStringLiteral("root is bound to the requested chat"));
    check(store.keyring.contains(a) && store.cache.contains(a),
          QStringLiteral("both durable copies were written"));
    check(store.generation.value(a) == QByteArray("1"),
          QStringLiteral("keyring generation advanced to 1"));
    const qint64 revAfterCreate = repo->revision();
    check(revAfterCreate == 1, QStringLiteral("revision advanced exactly once"));

    // The sealed copy must not contain the root, and must be openable only through the vault.
    const QByteArray sealedA = store.keyring.value(a);
    check(!sealedA.contains(first.root),
          QStringLiteral("the MK-sealed keyring contains no cleartext root"));

    section("repeated ensureRoot is a no-op");
    {
        const int drawsBefore = draws;
        HistoryRootEntry again;
        check(repo->ensureRoot(a, chat, again, &err), QStringLiteral("second ensureRoot succeeds"));
        check(again.root == first.root, QStringLiteral("same root returned"));
        check(again.rootVersion == 1, QStringLiteral("version did not advance"));
        check(repo->revision() == revAfterCreate, QStringLiteral("nothing was re-persisted"));
        check(draws == drawsBefore, QStringLiteral("no new root material was drawn"));
        check(store.generation.value(a) == QByteArray("1"),
              QStringLiteral("generation did not advance"));
    }

    section("reload from durable state");
    {
        repo->forgetInMemory();
        HistoryKeyring loaded;
        check(repo->load(a, loaded, &err), QStringLiteral("keyring reloads"));
        HistoryRootEntry e;
        check(loaded.latest(chat, e) && e.root == first.root,
              QStringLiteral("reloaded root is byte-identical"));
    }

    // =========================================== State 2: locked + existing root
    section("State 2: vault locked, root already exists");
    {
        FakeVault locked; // never unlocked: no master key at all
        auto lockedRepo = makeRepo(&store, &locked);
        HistoryRootEntry e;
        check(lockedRepo->ensureRoot(a, chat, e, &err),
              QStringLiteral("an existing root is served with the vault locked"));
        check(e.root == first.root, QStringLiteral("locked read returns identical bytes"));
        check(e.rootVersion == 1, QStringLiteral("locked read returns the right version"));
        check(lockedRepo->revision() == 0, QStringLiteral("locked read persisted nothing"));
    }

    // =========================================== State 3: locked + missing root
    section("State 3: vault locked, root missing - must refuse");
    {
        FakeVault locked;
        int lockedDraws = 0;
        auto lockedRepo = makeRepo(&store, &locked, &lockedDraws);

        const QByteArray keyringBefore = store.keyring.value(a);
        const QByteArray cacheBefore = store.cache.value(a);
        const QByteArray genBefore = store.generation.value(a);

        HistoryRootEntry denied;
        const bool minted = lockedRepo->ensureRoot(a, QStringLiteral("unseen-chat"), denied, &err);
        check(!minted, QStringLiteral("ensureRoot refuses to mint while locked"));
        check(lockedDraws == 0, QStringLiteral("NO root material was generated"));
        check(store.keyring.value(a) == keyringBefore,
              QStringLiteral("local authoritative copy unchanged"));
        check(store.cache.value(a) == cacheBefore, QStringLiteral("local cache unchanged"));
        check(store.generation.value(a) == genBefore, QStringLiteral("generation unchanged"));
        check(lockedRepo->revision() == 0, QStringLiteral("no durable change was recorded"));
        check(!locked.isUnlocked(), QStringLiteral("the vault was not silently unlocked"));
    }

    // =========================================== State 4: unlock, then ensureRoot
    section("State 4: unlock then ensureRoot succeeds");
    {
        FakeVault v2;
        auto r2 = makeRepo(&store, &v2);
        HistoryRootEntry denied;
        check(!r2->ensureRoot(a, QStringLiteral("later-chat"), denied, &err),
              QStringLiteral("refused while still locked"));

        v2.unlock(a); // explicit unlock
        HistoryRootEntry made;
        check(r2->ensureRoot(a, QStringLiteral("later-chat"), made, &err),
              QStringLiteral("succeeds after explicit unlock"));
        check(made.root.size() == 32, QStringLiteral("new root is 32 bytes"));
        check(made.rootVersion == 1, QStringLiteral("new chat starts at version 1"));
        check(made.root != first.root, QStringLiteral("a different chat gets a different root"));
        check(store.generation.value(a) == QByteArray("2"),
              QStringLiteral("generation advanced to 2"));
        check(r2->revision() == 1, QStringLiteral("exactly one durable change"));
    }

    // =========================================== rotation keeps history
    section("rotation adds a version and keeps the old one");
    {
        HistoryRootEntry rotated;
        check(repo->rotate(a, chat, rotated, &err), QStringLiteral("rotate succeeds when unlocked"));
        check(rotated.rootVersion == 2, QStringLiteral("rotation advances to version 2"));
        HistoryKeyring k;
        repo->load(a, k, &err);
        HistoryRootEntry old;
        check(k.find(chat, 1, old) && old.root == first.root,
              QStringLiteral("version 1 remains addressable after rotation"));
        HistoryRootEntry idem;
        check(repo->ensureRoot(a, chat, idem, &err) && idem.rootVersion == 2,
              QStringLiteral("ensureRoot now returns the rotated version, without rotating again"));
    }

    // =========================================== State 6: logout
    section("State 6: logout clears account-bound memory");
    {
        // AuthService::clearVaultSession() drops the opened keyring and the master key together.
        repo->forgetInMemory();
        vault.lock();

        // With the vault locked, the durable cache still serves this account's own root - that is
        // the intended restart behaviour and is not a logout leak.
        auto afterLogout = makeRepo(&store, &vault);
        HistoryRootEntry e;
        check(afterLogout->ensureRoot(a, chat, e, &err),
              QStringLiteral("the same account can still read its own cached root"));
        check(!vault.isUnlocked(), QStringLiteral("logout left the vault locked"));
    }

    // =========================================== cross-account isolation
    section("account isolation: B must not reach A's keyring");
    {
        FakeVault vb;
        vb.unlock(b);
        auto repoB = makeRepo(&store, &vb);

        HistoryKeyring kb;
        check(repoB->load(b, kb, &err), QStringLiteral("B loads a keyring"));
        check(kb.isEmpty(), QStringLiteral("B starts with its OWN empty keyring namespace"));
        HistoryRootEntry probe;
        check(!kb.latest(chat, probe), QStringLiteral("B cannot see A's root for the same chat"));

        // B's own root is independent.
        HistoryRootEntry rb;
        check(repoB->ensureRoot(b, chat, rb, &err), QStringLiteral("B can create its own root"));
        check(rb.root != first.root, QStringLiteral("B's root differs from A's"));
        check(store.keyring.contains(a) && store.keyring.contains(b),
              QStringLiteral("the two accounts occupy separate durable slots"));

        // The cryptographic half: A's sealed blob must not open under B's vault, because the AAD
        // binds the account. A structural slot separation alone would not be enough.
        QByteArray stolen;
        check(!vb.open(sealedA, stolen),
              QStringLiteral("B's vault cannot open A's sealed keyring (AAD binds the account)"));

        // Where the isolation actually comes from, stated exactly rather than optimistically.
        //
        // The cold-start cache is readable WITHOUT the vault - that is its whole purpose - so it is
        // scoped by the account id the caller supplies plus DPAPI at rest, not by which vault
        // happens to be unlocked. Hand a repository A's id and it will serve A's cache even under
        // B's vault. That is by design and matches Android, but it means the account id must never
        // come from a caller's choosing.
        //
        // Android removes the choice structurally: its repository reads currentUserId() itself. The
        // Windows repository takes owner as a parameter, so the equivalent guarantee has to live at
        // the application boundary - AuthService::ensureHistoryRoot()/loadHistoryKeyring() pass
        // m_currentUserId and offer no way to name another account. These two checks pin both
        // halves: the cache is owner-addressed, and the authoritative copy is vault-bound.
        auto confused = makeRepo(&store, &vb);
        HistoryKeyring ka;
        check(confused->load(a, ka, &err) && ka.latest(chat, probe),
              QStringLiteral("cache path is owner-addressed and vault-independent (documented)"));

        // The authoritative copy is the half that IS cryptographically bound. With no usable cache,
        // B's vault cannot recover A's keyring at all.
        FakeStore authOnly;
        authOnly.keyring[a] = store.keyring.value(a); // A's MK-sealed blob, no cache, no marker
        auto sealedOnly = makeRepo(&authOnly, &vb);
        HistoryKeyring blocked;
        check(!sealedOnly->load(a, blocked, &err),
              QStringLiteral("B's vault cannot open A's MK-sealed keyring - fails closed"));
    }

    // =========================================== fail-closed still holds
    section("fail-closed behaviour is unchanged");
    {
        FakeStore fresh;
        FakeVault v;
        v.unlock(a);
        auto r = makeRepo(&fresh, &v);
        HistoryKeyring k;
        check(r->load(a, k, &err) && k.isEmpty(),
              QStringLiteral("a genuinely fresh account loads an empty keyring"));
        check(!r->load(QString(), k, &err),
              QStringLiteral("no account identity is a failure, not an empty keyring"));
    }

    out << (g_failures == 0 ? "ALL PASS" : "FAILURES")
        << "  checks=" << g_checks << " failures=" << g_failures << Qt::endl;
    return g_failures == 0 ? 0 : 1;
}
