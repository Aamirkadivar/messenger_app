#ifndef ENCRYPTION_H
#define ENCRYPTION_H

#include <QString>
#include <QByteArray>
#include <sodium.h>

class Encryption {
public:
    Encryption();
    ~Encryption();

    // Initialize sodium library
    static bool init();

    // Generate a random key of specified length
    static QByteArray generateKey(int length = 32);

    // Generate a random nonce
    static QByteArray generateNonce();

    // Convert hex string to QByteArray
    static QByteArray hexToByteArray(const QString& hex);

    // Convert QByteArray to hex string
    static QString byteArrayToHex(const QByteArray& data);

    // Ed25519 key pair generation
    struct KeyPair {
        QByteArray publicKey;    // 32 bytes
        QByteArray secretKey;    // 64 bytes (includes public key)
    };

    // Generate Ed25519 key pair for E2EE
    static KeyPair generateEd25519KeyPair();

    // Derive shared key using X25519 (ECDH)
    // publicKey: sender's public key (32 bytes)
    // secretKey: receiver's secret key (from their key pair)
    static QByteArray deriveSharedKey(const QByteArray& publicKey, const QByteArray& secretKey);

    // Encrypt a message using xsalsa20poly1305 (secretbox)
    // Returns encrypted message with nonce prepended: nonce(24 bytes) + ciphertext
    static QByteArray encryptMessage(const QString& message,
                                     const QByteArray& sharedKey);

    // Encrypt for E2EE using sender's public key - returns encrypted data and nonce
    // publicKey: sender's Ed25519 public key (32 bytes)
    static bool encryptMessageDirect(const QByteArray& message,
                                     const QByteArray& publicKey,
                                     QByteArray& encryptedOut,
                                     QByteArray& nonceOut);

    // Decrypt a message with xsalsa20poly1305 (secretbox)
    // Expects nonce prepended: nonce(24 bytes) + ciphertext
    static QString decryptMessage(const QByteArray& encryptedData,
                                  const QByteArray& sharedKey);

    // Sign a message using ed25519
    static QByteArray signMessage(const QByteArray& message, const QByteArray& secretKey);

    // Verify a signature
    static bool verifySignature(const QByteArray& message,
                                const QByteArray& signature,
                                const QByteArray& publicKey);

    // ---- Real E2EE via NaCl crypto_box (X25519 + XSalsa20-Poly1305) ----
    // These interoperate byte-for-byte with libsodium/lazysodium crypto_box on
    // other platforms and with Go's nacl/box. Keys are 32-byte hex strings.

    // Generate an X25519 key pair for crypto_box. Returns hex-encoded keys.
    static bool boxKeyPair(QString& publicHex, QString& privateHex);

    // Encrypt for a recipient. Output wire format: hex(nonce[24] || ciphertext).
    // Returns an empty string on failure.
    static QString boxEncrypt(const QString& message,
                              const QString& recipientPublicHex,
                              const QString& senderPrivateHex);

    // Decrypt a payload produced by boxEncrypt. For a direct chat the "other"
    // key is the other participant's public key regardless of who sent it.
    // Returns an empty string on failure.
    static QString boxDecrypt(const QString& payloadHex,
                              const QString& otherPublicHex,
                              const QString& myPrivateHex);

    // Binary variant of boxEncrypt/boxDecrypt for file payloads (voice notes).
    // Same construction and wire layout (nonce || ciphertext) as the hex
    // functions, but raw bytes: a voice note is hundreds of KB and hex would
    // double it for no benefit, since the payload travels as a binary upload
    // body rather than embedded in JSON.
    static QByteArray boxEncryptBytes(const QByteArray& plain,
                                      const QString& recipientPublicHex,
                                      const QString& senderPrivateHex);
    static QByteArray boxDecryptBytes(const QByteArray& payload,
                                      const QString& otherPublicHex,
                                      const QString& myPrivateHex);

    // ---- Symmetric secretbox (XSalsa20-Poly1305) for group "Sender Keys" ----
    // Direct chats use crypto_box (X25519 ECDH) because there are exactly two
    // parties to derive a shared secret between. A group has no single
    // "other side" - the WhatsApp/Signal answer is a Sender Key: each member
    // picks their own random symmetric key for messages *they* send, and
    // hands a copy to every other member individually (via crypto_box, one
    // recipient at a time). Everyone else just needs that one key to open
    // that sender's group messages, with no group-wide shared secret to leak
    // or agree on. Keys are 32-byte hex strings, same convention as the
    // crypto_box keys above.

    // Generates a fresh random sender key (crypto_secretbox_KEYBYTES, hex-encoded).
    static QString secretBoxGenerateKey();

    // Wire format: nonce[24] || ciphertext (raw bytes - group message bodies
    // travel as binary the same way voice/file attachments already do).
    static QByteArray secretBoxEncryptBytes(const QByteArray& plain, const QString& keyHex);
    static QByteArray secretBoxDecryptBytes(const QByteArray& payload, const QString& keyHex);

    // Convert bytes to hex for display (inline for convenience)
    static QString bytesToHex(const QByteArray& bytes);

    // Convert hex string to bytes (inline for convenience)
    static QByteArray hexToBytes(const QString& hex);

private:
    static bool m_initialized;
};

#endif // ENCRYPTION_H