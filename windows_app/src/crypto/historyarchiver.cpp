#include "historyarchiver.h"

#include <sodium.h>

namespace {

void setError(QString* error, const QString& message) {
    if (error) *error = message;
}

HistoryCrypto::Context contextFor(const QString& userId, const ArchiveMessage& msg, int rootVersion) {
    HistoryCrypto::Context ctx;
    ctx.userId = userId;
    ctx.chatId = msg.chatId;
    ctx.messageId = msg.messageId;
    ctx.rootVersion = rootVersion;
    ctx.protocolVersion = HistoryCrypto::PROTOCOL_VERSION;
    return ctx;
}

} // namespace

HistoryArchiver::HistoryArchiver(EnsureRootFn ensureRoot, FindRootFn findRoot, OwnerFn owner)
    : m_ensureRoot(std::move(ensureRoot)),
      m_findRoot(std::move(findRoot)),
      m_owner(std::move(owner)) {}

bool HistoryArchiver::sealInternal(const ArchiveMessage& msg,
                                   const QByteArray* nonce,
                                   ArchiveRecord& out,
                                   QString* error) {
    if (!m_owner || !m_ensureRoot) {
        setError(error, QStringLiteral("history archiver is not wired to a session"));
        return false;
    }

    const QString owner = m_owner();
    if (owner.isEmpty()) {
        setError(error, QStringLiteral("no signed-in account"));
        return false;
    }
    // The caller names an account; it is checked, not trusted. Without this a caller could ask for
    // one account's root while another is signed in, which is exactly the confused-deputy the
    // keyring layer already refuses.
    if (msg.userId != owner) {
        setError(error, QStringLiteral("message does not belong to the signed-in account"));
        return false;
    }

    // Android's MessageArchiver.validate, unchanged: identifiers must be present. No additional
    // rules are invented here.
    if (msg.userId.isEmpty()) { setError(error, QStringLiteral("userId must not be empty")); return false; }
    if (msg.chatId.isEmpty()) { setError(error, QStringLiteral("chatId must not be empty")); return false; }
    if (msg.messageId.isEmpty()) { setError(error, QStringLiteral("messageId must not be empty")); return false; }

    // The root comes from the keyring, under the signed-in account. A locked vault with no existing
    // root fails here, before anything is generated or written - the Phase 34 guarantee.
    HistoryRootEntry entry;
    if (!m_ensureRoot(msg.chatId, entry, error)) return false;
    if (entry.root.size() != HistoryCrypto::ROOT_BYTES || entry.rootVersion < 1) {
        setError(error, QStringLiteral("history keyring returned an unusable root"));
        return false;
    }

    const HistoryCrypto::Context ctx = contextFor(owner, msg, entry.rootVersion);
    const QByteArray sealed = nonce
        ? HistoryCrypto::sealWithNonce(entry.root, ctx, msg.plaintext, *nonce)
        : HistoryCrypto::seal(entry.root, ctx, msg.plaintext);
    if (sealed.isEmpty()) {
        setError(error, QStringLiteral("archive sealing failed"));
        return false;
    }

    out.messageId = msg.messageId;
    out.chatId = msg.chatId;
    out.rootVersion = entry.rootVersion;
    out.protocolVersion = HistoryCrypto::PROTOCOL_VERSION;
    out.ciphertextB64 = HistoryCrypto::toWireB64(sealed);
    return out.isValid();
}

bool HistoryArchiver::seal(const ArchiveMessage& msg, ArchiveRecord& out, QString* error) {
    return sealInternal(msg, nullptr, out, error);
}

bool HistoryArchiver::sealWithNonce(const ArchiveMessage& msg,
                                    const QByteArray& nonce,
                                    ArchiveRecord& out,
                                    QString* error) {
    if (nonce.size() != HistoryCrypto::NONCE_BYTES) {
        setError(error, QStringLiteral("nonce must be exactly %1 bytes")
                            .arg(HistoryCrypto::NONCE_BYTES));
        return false;
    }
    return sealInternal(msg, &nonce, out, error);
}

bool HistoryArchiver::open(const ArchiveRecord& record, QByteArray& plainOut, QString* error) {
    if (!m_owner || !m_findRoot) {
        setError(error, QStringLiteral("history archiver is not wired to a session"));
        return false;
    }
    if (!record.isValid()) {
        setError(error, QStringLiteral("archive record is incomplete"));
        return false;
    }

    const QString owner = m_owner();
    if (owner.isEmpty()) {
        setError(error, QStringLiteral("no signed-in account"));
        return false;
    }

    // The record names the version it was sealed under; a device that no longer holds that root
    // cannot reopen it, and must say so rather than silently trying a different one.
    HistoryRootEntry entry;
    if (!m_findRoot(record.chatId, record.rootVersion, entry, error)) return false;
    if (entry.root.size() != HistoryCrypto::ROOT_BYTES) {
        setError(error, QStringLiteral("history keyring returned an unusable root"));
        return false;
    }

    ArchiveMessage identity;
    identity.chatId = record.chatId;
    identity.messageId = record.messageId;
    HistoryCrypto::Context ctx = contextFor(owner, identity, record.rootVersion);
    ctx.protocolVersion = record.protocolVersion;

    const QByteArray plain =
        HistoryCrypto::openFromWireB64(entry.root, ctx, record.ciphertextB64);
    if (plain.isEmpty() && !record.ciphertextB64.isEmpty()) {
        // An empty result is authentication failure. A legitimately empty plaintext is
        // indistinguishable here, so it is reported as a failure rather than guessed at; the
        // archive path never seals an empty message body.
        setError(error, QStringLiteral("archive record did not open"));
        return false;
    }
    plainOut = plain;
    return true;
}
