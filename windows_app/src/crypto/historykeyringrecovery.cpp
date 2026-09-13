#include "historykeyringrecovery.h"

#include <sodium.h>

HistoryKeyringRecovery::HistoryKeyringRecovery(HistoryKeyringTransport transport,
                                               HistoryKeyringRepository* repository,
                                               OpenRecoveryFn openRecovery,
                                               OwnerFn owner)
    : m_transport(std::move(transport)),
      m_repository(repository),
      m_openRecovery(std::move(openRecovery)),
      m_owner(std::move(owner)) {}

HistoryKeyringRecovery::Result HistoryKeyringRecovery::recover() const {
    Result out;
    if (!m_repository || !m_openRecovery || !m_owner) {
        out.outcome = Outcome::TransportError;
        out.error = QStringLiteral("recovery is not wired");
        return out;
    }
    const QString owner = m_owner();
    if (owner.isEmpty()) {
        out.outcome = Outcome::Unauthorized;
        out.error = QStringLiteral("no signed-in account");
        return out;
    }

    const HistoryKeyringTransport::Fetch fetched = m_transport.get();
    switch (fetched.outcome) {
    case HistoryKeyringTransport::Outcome::Ok:
        break;
    case HistoryKeyringTransport::Outcome::Absent:
        // Nothing stored is a legitimate state, not a failure: an account that has never published
        // has nothing to recover, and the local keyring is correct exactly as it is.
        out.outcome = Outcome::NothingStored;
        return out;
    case HistoryKeyringTransport::Outcome::Unauthorized:
        out.outcome = Outcome::Unauthorized;
        out.error = QStringLiteral("the keyring endpoint refused this session or device");
        return out;
    case HistoryKeyringTransport::Outcome::Malformed:
        out.outcome = Outcome::Malformed;
        out.error = QStringLiteral("the stored blob is not usable ciphertext");
        return out;
    default:
        out.outcome = Outcome::TransportError;
        out.error = QStringLiteral("the keyring could not be fetched");
        return out;
    }

    QByteArray plain;
    if (!m_openRecovery(fetched.sealed, plain)) {
        // Two causes, deliberately not distinguished to the caller: a locked vault, and a blob
        // sealed under a master key this account no longer has. Both leave the keyring untouched,
        // and guessing between them would mean reporting a locked vault as tampering.
        out.outcome = m_repository ? Outcome::Undecryptable : Outcome::Locked;
        out.error = QStringLiteral("the recovery blob did not open");
        return out;
    }

    HistoryKeyring recovered;
    QString decodeError;
    const bool decoded = HistoryKeyring::decode(plain, recovered, &decodeError);
    // The plaintext keyring is root material. It is wiped as soon as it has been parsed, whether or
    // not parsing succeeded.
    sodium_memzero(plain.data(), static_cast<size_t>(plain.size()));
    if (!decoded) {
        out.outcome = Outcome::Malformed;
        out.error = decodeError;
        return out;
    }
    out.recoveredEntries = recovered.entries().size();

    bool imported = false;
    QString mergeError;
    if (!m_repository->importFromRecovery(owner, recovered, &imported, &mergeError)) {
        out.outcome = Outcome::Conflict;
        out.error = mergeError;
        return out;
    }

    out.outcome = Outcome::Recovered;
    out.imported = imported;
    return out;
}
