#ifndef HISTORYKEYRINGRECOVERY_H
#define HISTORYKEYRINGRECOVERY_H

#include "historykeyring.h"
#include "historykeyringstore.h"
#include "historykeyringtransport.h"

#include <QString>
#include <functional>

/**
 * Recovers this account's history keyring from the server onto a device that has none.
 *
 * WHY THIS EXISTS. The keyring is device-local: it lives in a DPAPI-wrapped registry slot sealed
 * under the master key. Losing the device therefore lost every archive that device's roots could
 * open - the archive ciphertext survived on the server and became permanently undecryptable. The
 * server holds one opaque blob per account, sealed under MK, and this is what turns a recovered MK
 * back into roots.
 *
 * WHAT MAKES IT SAFE. The blob is sealed under MK with its OWN aad domain
 * (`history_keyring_recovery_v1|userId`), distinct from the device-local copy's - so a recovery
 * blob can never be opened as a local keyring, a vault body, or an archive record. The server can
 * decrypt none of it, and the master key never crosses this class: sealing and opening are injected
 * as functions, exactly as HistoryKeyringRepository takes them.
 *
 * MERGE, NEVER REPLACE. The recovered keyring is merged as a union
 * (HistoryKeyringRepository::importFromRecovery), so roots this device minted while the server was
 * unreachable survive. A same-slot/different-key conflict fails the import rather than picking a
 * winner.
 *
 * FAIL-CLOSED. A locked vault, an absent blob, a transport failure, a blob that will not open, or a
 * conflicting merge all leave the local keyring exactly as it was. Absence is NOT failure: an
 * account that has never published simply has nothing to recover, and reporting that as an error
 * would make a first run look broken.
 *
 * ONE-SHOT BY DEFAULT. Recovery is convergent - repeating it merges the same roots and changes
 * nothing - so the caller runs it once per session. There is no scheduler, queue, or retry here.
 */
class HistoryKeyringRecovery {
public:
    /** Opens a recovery blob under MK. False when the vault is locked. MK never crosses this. */
    using OpenRecoveryFn = std::function<bool(const QByteArray& sealed, QByteArray& plainOut)>;
    using OwnerFn = std::function<QString()>;

    enum class Outcome {
        Recovered,     ///< A blob was fetched, opened, and merged; roots may have been added.
        NothingStored, ///< The account has no recovery blob. Not an error.
        Locked,        ///< The vault is locked, so the blob cannot be opened.
        Unauthorized,  ///< The session or device is not authorized for the keyring endpoint.
        Undecryptable, ///< A blob exists but did not open - wrong MK, or tampered.
        Malformed,     ///< It opened but is not a valid keyring encoding.
        Conflict,      ///< The merge refused: same (chat, version), different root.
        TransportError,///< No response, or an unexpected status.
    };

    struct Result {
        Outcome outcome = Outcome::TransportError;
        /** True only when roots were actually added to the local keyring. */
        bool imported = false;
        /** Entries in the recovered keyring, for diagnostics. Never the roots themselves. */
        int recoveredEntries = 0;
        QString error;

        /** Whether the local keyring is now at least as complete as the server's. */
        bool ok() const { return outcome == Outcome::Recovered || outcome == Outcome::NothingStored; }
    };

    HistoryKeyringRecovery(HistoryKeyringTransport transport,
                           HistoryKeyringRepository* repository,
                           OpenRecoveryFn openRecovery,
                           OwnerFn owner);

    /** Fetches, opens, and merges. Never throws; every failure is an Outcome. */
    Result recover() const;

private:
    HistoryKeyringTransport m_transport;
    HistoryKeyringRepository* m_repository = nullptr;
    OpenRecoveryFn m_openRecovery;
    OwnerFn m_owner;
};

#endif // HISTORYKEYRINGRECOVERY_H
