#ifndef HISTORYCRYPTO_H
#define HISTORYCRYPTO_H

#include <QByteArray>
#include <QString>

/**
 * Windows side of the Layer B history-archive protocol.
 *
 * Deliberately a pure adapter. It owns no vault state, no history roots, no HTTP, no database and
 * no messages - it only turns (root, identity, plaintext) into an archive record and back, using
 * the primitives already in VaultCrypto. Nothing here is a new cryptographic construction.
 *
 * The wire format is defined by the Android implementation and must match it byte-for-byte, because
 * an archive sealed on one platform is opened on the other:
 *
 *     key   = HKDF-SHA256(ikm = root(32),
 *                         salt = "messenger/history-kdf-salt/v1",
 *                         info = lp("messenger/history-message-key/v1") || lp(messageId)
 *                                || u32be(rootVersion),
 *                         len  = 32)
 *
 *     aad   = lp("messenger/history-aad/v1") || u32be(protocolVersion)
 *             || lp(userId) || lp(chatId) || lp(messageId) || u32be(rootVersion)
 *
 *     record = nonce(24) || XChaCha20-Poly1305-IETF(key, plaintext, aad)   [tag(16) trailing]
 *
 * `lp(x)` is u32be(byteLength) followed by the bytes, UTF-8 for strings. The explicit lengths are
 * what stop a value containing a separator from impersonating a different field split: with them,
 * no field content can forge a different parse.
 *
 * Identifiers are used exactly as given - never trimmed, lowercased, or normalised - because the
 * other platform binds the raw bytes.
 */
class HistoryCrypto {
public:
    static constexpr int PROTOCOL_VERSION = 1;
    static constexpr int ROOT_BYTES = 32;
    static constexpr int MESSAGE_KEY_BYTES = 32;
    static constexpr int NONCE_BYTES = 24;
    static constexpr int TAG_BYTES = 16;

    static constexpr const char* AAD_LABEL = "messenger/history-aad/v1";
    static constexpr const char* MESSAGE_KEY_LABEL = "messenger/history-message-key/v1";
    static constexpr const char* KDF_SALT_LABEL = "messenger/history-kdf-salt/v1";

    /** Everything bound into one archived message. */
    struct Context {
        QString userId;
        QString chatId;
        QString messageId;
        int rootVersion = 1;
        int protocolVersion = PROTOCOL_VERSION;
    };

    /** 4-byte big-endian. */
    static QByteArray u32be(quint32 v);

    /** u32be(length) followed by the bytes. An empty value contributes its length and nothing else. */
    static QByteArray lp(const QByteArray& b);
    static QByteArray lp(const QString& s);

    /** The associated data bound to an archive record. */
    static QByteArray aad(const Context& ctx);

    /** The 32-byte per-message archive key. Empty on a wrong-sized root. */
    static QByteArray deriveMessageKey(const QByteArray& historyRoot,
                                       const QString& messageId,
                                       int rootVersion);

    /**
     * Seals one message with a CALLER-SUPPLIED nonce. Returns nonce || ciphertext || tag.
     *
     * Deterministic by construction, which is what makes cross-platform vectors reproducible: the
     * same six inputs always yield the same bytes. That is also why it is dangerous in production -
     * reusing a nonce under one key destroys the AEAD's guarantees - so ordinary callers must use
     * seal(), which draws a fresh nonce. This overload exists for fixtures and for a caller that
     * already owns a unique-nonce discipline.
     *
     * Empty on any refusal: wrong-sized root, wrong-sized nonce, or an AEAD failure. Never partial.
     */
    static QByteArray sealWithNonce(const QByteArray& historyRoot,
                                    const Context& ctx,
                                    const QByteArray& plaintext,
                                    const QByteArray& nonce);

    /**
     * Seals one message. Returns nonce || ciphertext || tag, or empty on failure.
     * Not deterministic: the nonce is fresh per call, which is required.
     */
    static QByteArray seal(const QByteArray& historyRoot,
                           const Context& ctx,
                           const QByteArray& plaintext);

    /**
     * The Base64 wire form of a record. Standard alphabet, padded, no line wrapping.
     *
     * fromWireB64 is STRICT: Qt's default decoder silently skips characters outside the alphabet,
     * which would turn a corrupt record into shorter plausible bytes and surface later as an
     * unopenable archive rather than the transport error it is. Kept local to this file - the shared
     * VaultCrypto::unb64 has many other callers and is not this phase's business.
     * Returns empty on malformed input.
     */
    static QString toWireB64(const QByteArray& wire);
    static QByteArray fromWireB64(const QString& b64);

    /** Convenience: decode the Base64 wire form and open it. Empty on ANY failure. */
    static QByteArray openFromWireB64(const QByteArray& historyRoot,
                                      const Context& ctx,
                                      const QString& b64);

    /**
     * Opens a sealed record. Returns empty on ANY failure - wrong root, wrong root version, wrong
     * context, tampered ciphertext, tampered tag, or a truncated record. There is deliberately no
     * fallback path and no error detail: a caller learns only that it did not open.
     */
    static QByteArray open(const QByteArray& historyRoot,
                           const Context& ctx,
                           const QByteArray& sealed);
};

#endif // HISTORYCRYPTO_H
