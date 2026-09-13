#ifndef HISTORYARCHIVER_H
#define HISTORYARCHIVER_H

#include "historycrypto.h"
#include "historykeyring.h"

#include <QByteArray>
#include <QString>
#include <functional>

/**
 * One message handed in for archiving. Explicit input only - the archiver never goes looking for a
 * message in a cache, a database, or a service.
 */
struct ArchiveMessage {
    QString userId;
    QString chatId;
    QString messageId;
    QByteArray plaintext;
};

/**
 * One sealed archive, in memory.
 *
 * Deliberately minimal: exactly what is needed to identify the record and reopen it later. Android
 * carries the same three crypto-relevant fields on the wire (`root_version`, `protocol_version`,
 * `ciphertext_b64`) plus the message id; `chatId` is here because reopening needs to look the root
 * up by chat.
 *
 * There is no upload status, server revision, database id, timestamp, cache state, or sync
 * metadata, and none may be added here - this represents one sealed object, not an archive
 * management system.
 *
 * `userId` is NOT stored. It is bound into the AAD at sealing time and supplied again from the
 * signed-in session when reopening, so putting it in the record would add a second, forgeable copy
 * of something the ciphertext is already authenticated against.
 */
struct ArchiveRecord {
    QString messageId;
    QString chatId;
    int rootVersion = 0;
    int protocolVersion = HistoryCrypto::PROTOCOL_VERSION;
    QString ciphertextB64;

    bool isValid() const {
        return !messageId.isEmpty() && !chatId.isEmpty() && rootVersion >= 1 &&
               protocolVersion >= 1 && !ciphertextB64.isEmpty();
    }
};

/**
 * Composes the proven keyring (Phase 34) with the proven sealing primitive (Phase 35).
 *
 * Orchestration only. It implements no cryptography of its own - no HKDF, HMAC, AEAD, Base64, or
 * nonce generation - and holds no master key, no network client, no storage, and no message source.
 * Its whole job is: take an explicit message, get the right root for the current account, call
 * HistoryCrypto, and hand back a record.
 *
 * ACCOUNT AUTHORITY. The root is obtained through ports the session owns, and the caller-supplied
 * `userId` is checked against the authoritative signed-in account rather than trusted. A caller
 * therefore cannot name another account to reach its roots - which is the same guarantee
 * AuthService::ensureHistoryRoot provides at the keyring layer, carried forward here.
 *
 * Mirrors Android's MessageArchiver, minus the parts that belong to later phases: no persistence,
 * no upload, no feature flag, no message-flow caller.
 */
class HistoryArchiver {
public:
    /** Root for a chat under the current account, minting at version 1 only when permitted. */
    using EnsureRootFn =
        std::function<bool(const QString& chatId, HistoryRootEntry& out, QString* error)>;
    /** An exact (chatId, rootVersion) lookup - needed to reopen a record sealed under an older root. */
    using FindRootFn = std::function<bool(const QString& chatId, int rootVersion,
                                          HistoryRootEntry& out, QString* error)>;
    /** The authoritative signed-in account id. Never caller-supplied. */
    using OwnerFn = std::function<QString()>;

    HistoryArchiver(EnsureRootFn ensureRoot, FindRootFn findRoot, OwnerFn owner);

    /**
     * Seals one message and returns the record. The production path: the nonce is fresh per call.
     *
     * Fails closed, changing nothing, when the account is unknown, the supplied userId is not the
     * signed-in one, any identifier is empty, the vault is locked and the chat has no root yet, or
     * the AEAD refuses.
     */
    bool seal(const ArchiveMessage& msg, ArchiveRecord& out, QString* error = nullptr);

    /**
     * Deterministic variant, for fixtures only.
     *
     * Reusing a nonce under one key destroys the AEAD's guarantees, so nothing in production may
     * call this. It exists so a cross-platform vector can be reproduced exactly.
     */
    bool sealWithNonce(const ArchiveMessage& msg,
                       const QByteArray& nonce,
                       ArchiveRecord& out,
                       QString* error = nullptr);

    /**
     * Reopens a record, using the root version the record names. Returns the exact original
     * plaintext, or fails - never a partial or unauthenticated result.
     */
    bool open(const ArchiveRecord& record, QByteArray& plainOut, QString* error = nullptr);

private:
    bool sealInternal(const ArchiveMessage& msg,
                      const QByteArray* nonce,
                      ArchiveRecord& out,
                      QString* error);

    EnsureRootFn m_ensureRoot;
    FindRootFn m_findRoot;
    OwnerFn m_owner;
};

#endif // HISTORYARCHIVER_H
