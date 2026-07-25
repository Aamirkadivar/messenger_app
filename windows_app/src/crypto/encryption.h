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

    // Convert bytes to hex for display (inline for convenience)
    static QString bytesToHex(const QByteArray& bytes);

    // Convert hex string to bytes (inline for convenience)
    static QByteArray hexToBytes(const QString& hex);

private:
    static bool m_initialized;
};

#endif // ENCRYPTION_H