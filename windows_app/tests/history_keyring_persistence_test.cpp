// Phase 32 - real cross-process persistence for the Windows history keyring.
//
// The in-memory suite (history_keyring_test) proves the policy; this proves the durability, because
// "survives a restart" is the one claim a same-process fake cannot make. The binary is run three
// times by the harness - write, read, purge - so the read genuinely happens in a different process
// with a cold cache, against real DPAPI and a real registry hive.
//
// It writes to a THROWAWAY subkey (…\MessengerApp\HistoryKeyringTest), never the production one,
// and never constructs CredentialManager, whose destructor rewrites the user's real credential
// file. The purge mode removes everything it created.
//
// The root itself is never printed. The modes emit a SHA-256 of the root so the harness can compare
// the two processes without either one disclosing key material.

#include "../src/crypto/historykeyring.h"
#include "../src/crypto/historykeyringstore.h"
#include "../src/crypto/vaultcrypto.h"

#include <QByteArray>
#include <QCryptographicHash>
#include <QString>
#include <QTextStream>
#include <sodium.h>

static const char* kTestRegistryPath =
    "HKEY_CURRENT_USER\\Software\\MessengerApp\\HistoryKeyringTest";
static const char* kOwner = "phase32-persistence-fixture";
static const char* kChat = "00000000-0000-0000-0000-000000000001";

static QByteArray syntheticMk() {
    QByteArray mk(32, 0);
    for (int i = 0; i < 32; ++i) mk[i] = static_cast<char>(0x70 + i);
    return mk;
}

static QByteArray keyringAad(const QString& owner) {
    return QStringLiteral("history-keyring|%1|%2|%3")
        .arg(owner).arg(1).arg(QLatin1String(VaultCrypto::SUITE_VAULT_AEAD)).toUtf8();
}

int main(int argc, char** argv) {
    if (sodium_init() < 0) return 2;
    QTextStream out(stdout);

    const QString mode = argc > 1 ? QString::fromLatin1(argv[1]) : QStringLiteral("write");
    const QString owner = QString::fromLatin1(kOwner);
    const QString chat = QString::fromLatin1(kChat);
    const QByteArray mk = syntheticMk();

    DpapiProtector protector;
    RegistryHistoryKeyringStore store(&protector, QString::fromLatin1(kTestRegistryPath));

    if (mode == QStringLiteral("purge")) {
        store.purge(owner);
        out << "PURGED" << Qt::endl;
        return 0;
    }

    auto seal = [mk, owner](const QByteArray& plain, QByteArray& o) {
        o = VaultCrypto::sealXChaCha(mk, plain, keyringAad(owner));
        return !o.isEmpty();
    };
    auto open = [mk, owner](const QByteArray& sealed, QByteArray& o) {
        o = VaultCrypto::openXChaCha(mk, sealed, keyringAad(owner));
        return !o.isEmpty();
    };

    if (mode == QStringLiteral("write")) {
        store.purge(owner); // start from a known-clean slot
        HistoryKeyringRepository repo(&store, seal, open);
        HistoryRootEntry e;
        QString err;
        if (!repo.ensureRoot(owner, chat, e, &err)) {
            out << "WRITE_FAILED " << err << Qt::endl;
            return 1;
        }
        out << "VERSION=" << e.rootVersion << Qt::endl;
        out << "ROOT_SHA256="
            << QCryptographicHash::hash(e.root, QCryptographicHash::Sha256).toHex() << Qt::endl;

        // The registry must hold no cleartext root.
        const SlotRead auth = store.loadKeyring(owner);
        out << "AUTH_SEALED_HAS_ROOT_CLEARTEXT="
            << ((auth.status == SlotStatus::Ok && auth.bytes.contains(e.root)) ? "YES" : "NO")
            << Qt::endl;
        return 0;
    }

    if (mode == QStringLiteral("read")) {
        // A brand-new process, a brand-new repository, nothing in memory.
        HistoryKeyringRepository repo(&store, seal, open);
        HistoryRootEntry e;
        QString err;
        if (!repo.ensureRoot(owner, chat, e, &err)) {
            out << "READ_FAILED " << err << Qt::endl;
            return 1;
        }
        out << "VERSION=" << e.rootVersion << Qt::endl;
        out << "ROOT_SHA256="
            << QCryptographicHash::hash(e.root, QCryptographicHash::Sha256).toHex() << Qt::endl;
        out << "MINTED_NEW=" << (repo.revision() == 0 ? "NO" : "YES") << Qt::endl;

        // And the cold-start case that matters most: a LOCKED vault must still serve an existing
        // root, because the cache is the only copy readable without the master key.
        HistoryKeyringRepository lockedRepo(&store, nullptr, nullptr);
        HistoryRootEntry locked;
        if (!lockedRepo.ensureRoot(owner, chat, locked, &err)) {
            out << "LOCKED_READ_FAILED " << err << Qt::endl;
            return 1;
        }
        out << "LOCKED_ROOT_SHA256="
            << QCryptographicHash::hash(locked.root, QCryptographicHash::Sha256).toHex() << Qt::endl;

        // A locked vault must still refuse to MINT for a chat with no root.
        HistoryRootEntry denied;
        const bool minted = lockedRepo.ensureRoot(owner, QStringLiteral("unseen-chat"), denied, &err);
        out << "LOCKED_MINT_REFUSED=" << (minted ? "NO" : "YES") << Qt::endl;
        return 0;
    }

    out << "unknown mode" << Qt::endl;
    return 2;
}
