#include "historycrypto.h"
#include "vaultcrypto.h"

#include <sodium.h>

QByteArray HistoryCrypto::u32be(quint32 v) {
    QByteArray out(4, 0);
    out[0] = static_cast<char>((v >> 24) & 0xFF);
    out[1] = static_cast<char>((v >> 16) & 0xFF);
    out[2] = static_cast<char>((v >> 8) & 0xFF);
    out[3] = static_cast<char>(v & 0xFF);
    return out;
}

QByteArray HistoryCrypto::lp(const QByteArray& b) {
    return u32be(static_cast<quint32>(b.size())) + b;
}

QByteArray HistoryCrypto::lp(const QString& s) {
    // UTF-8, exactly as given. No trimming or normalisation: the other platform binds these bytes.
    return lp(s.toUtf8());
}

QByteArray HistoryCrypto::aad(const Context& ctx) {
    return lp(QByteArray(AAD_LABEL))
        + u32be(static_cast<quint32>(ctx.protocolVersion))
        + lp(ctx.userId)
        + lp(ctx.chatId)
        + lp(ctx.messageId)
        + u32be(static_cast<quint32>(ctx.rootVersion));
}

QByteArray HistoryCrypto::deriveMessageKey(const QByteArray& historyRoot,
                                           const QString& messageId,
                                           int rootVersion) {
    // A wrong-sized root is a programming error, not an input to guess at. Refuse rather than
    // derive a key that would silently differ from the other platform's.
    if (historyRoot.size() != ROOT_BYTES) return {};

    const QByteArray info = lp(QByteArray(MESSAGE_KEY_LABEL))
        + lp(messageId)
        + u32be(static_cast<quint32>(rootVersion));

    return VaultCrypto::hkdfSha256(historyRoot,
                                   QByteArray(KDF_SALT_LABEL),
                                   info,
                                   MESSAGE_KEY_BYTES);
}

QByteArray HistoryCrypto::sealWithNonce(const QByteArray& historyRoot,
                                        const Context& ctx,
                                        const QByteArray& plaintext,
                                        const QByteArray& nonce) {
    if (nonce.size() != NONCE_BYTES) return {};
    QByteArray key = deriveMessageKey(historyRoot, ctx.messageId, ctx.rootVersion);
    if (key.size() != MESSAGE_KEY_BYTES) return {};

    const QByteArray ad = aad(ctx);
    QByteArray cipher(plaintext.size() + TAG_BYTES, 0);
    unsigned long long clen = 0;
    const int rc = crypto_aead_xchacha20poly1305_ietf_encrypt(
        reinterpret_cast<unsigned char*>(cipher.data()), &clen,
        reinterpret_cast<const unsigned char*>(plaintext.constData()),
        static_cast<unsigned long long>(plaintext.size()),
        reinterpret_cast<const unsigned char*>(ad.constData()),
        static_cast<unsigned long long>(ad.size()),
        nullptr,
        reinterpret_cast<const unsigned char*>(nonce.constData()),
        reinterpret_cast<const unsigned char*>(key.constData()));

    // The derived key is short-lived; do not leave it sitting in this frame. Hygiene, not erasure -
    // a copy may already exist elsewhere - but it costs nothing and mirrors Android's
    // HistoryCrypto.bestEffortWipe on the same value.
    sodium_memzero(key.data(), static_cast<size_t>(key.size()));
    if (rc != 0) return {};

    cipher.resize(static_cast<int>(clen));
    return nonce + cipher;
}

QByteArray HistoryCrypto::seal(const QByteArray& historyRoot,
                               const Context& ctx,
                               const QByteArray& plaintext) {
    // The nonce is drawn here and handed to the deterministic path, so the production and fixture
    // paths run the same code and cannot drift apart in framing, AAD, or key derivation.
    QByteArray nonce(NONCE_BYTES, 0);
    randombytes_buf(nonce.data(), static_cast<size_t>(nonce.size()));
    return sealWithNonce(historyRoot, ctx, plaintext, nonce);
}

QString HistoryCrypto::toWireB64(const QByteArray& wire) {
    if (wire.isEmpty()) return {};
    return QString::fromLatin1(wire.toBase64());
}

QByteArray HistoryCrypto::fromWireB64(const QString& b64) {
    if (b64.isEmpty()) return {};
    // Strict: Qt's default decoder SKIPS characters outside the alphabet, so "!!!not base64!!!"
    // would decode to shorter plausible bytes instead of failing. That would surface later as an
    // unopenable archive rather than as the corrupt input it is. Local on purpose - the shared
    // VaultCrypto::unb64 has many other callers and is out of scope here.
    const auto r = QByteArray::fromBase64Encoding(
        b64.toLatin1(), QByteArray::Base64Encoding | QByteArray::AbortOnBase64DecodingErrors);
    if (r.decodingStatus != QByteArray::Base64DecodingStatus::Ok) return {};
    return r.decoded;
}

QByteArray HistoryCrypto::openFromWireB64(const QByteArray& historyRoot,
                                          const Context& ctx,
                                          const QString& b64) {
    const QByteArray wire = fromWireB64(b64);
    if (wire.isEmpty()) return {};
    return open(historyRoot, ctx, wire);
}

QByteArray HistoryCrypto::open(const QByteArray& historyRoot,
                               const Context& ctx,
                               const QByteArray& sealed) {
    const QByteArray key = deriveMessageKey(historyRoot, ctx.messageId, ctx.rootVersion);
    if (key.size() != MESSAGE_KEY_BYTES) return {};

    // Every failure - wrong key, wrong context, tampering, truncation - lands here as an empty
    // result. The AEAD tag is the only thing consulted; there is no second attempt.
    return VaultCrypto::openXChaCha(key, sealed, aad(ctx));
}
