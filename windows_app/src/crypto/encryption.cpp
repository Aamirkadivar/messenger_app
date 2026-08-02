#include "encryption.h"
#include <QDebug>
#include <QtGlobal>

bool Encryption::m_initialized = false;

Encryption::Encryption() {
}

Encryption::~Encryption() {
}

bool Encryption::init() {
    if (m_initialized) {
        return true;
    }
    if (sodium_init() == -1) {
        qWarning() << "Failed to initialize libsodium";
        return false;
    }
    m_initialized = true;
    return true;
}

QByteArray Encryption::generateKey(int length) {
    if (length <= 0) {
        length = 32; // default key size
    }
    QByteArray key(length, '\0');
    crypto_secretbox_keygen(reinterpret_cast<unsigned char*>(key.data()));
    return key.left(crypto_secretbox_keybytes());
}

QByteArray Encryption::generateNonce() {
    QByteArray nonce(crypto_secretbox_noncebytes(), '\0');
    // Use randombytes for nonce generation
    randombytes_buf(nonce.data(), nonce.size());
    return nonce;
}

QByteArray Encryption::hexToByteArray(const QString& hex) {
    return QByteArray::fromHex(hex.toUtf8());
}

QString Encryption::byteArrayToHex(const QByteArray& data) {
    return data.toHex();
}

Encryption::KeyPair Encryption::generateEd25519KeyPair() {
    KeyPair keyPair;
    keyPair.publicKey.resize(crypto_sign_ed25519_PUBLICKEYBYTES);
    keyPair.secretKey.resize(crypto_sign_ed25519_SECRETKEYBYTES);

    if (crypto_sign_ed25519_keypair(
            reinterpret_cast<unsigned char*>(keyPair.publicKey.data()),
            reinterpret_cast<unsigned char*>(keyPair.secretKey.data())) != 0) {
        qWarning() << "Failed to generate Ed25519 key pair";
        return KeyPair();
    }
    return keyPair;
}

QByteArray Encryption::deriveSharedKey(const QByteArray& serverPublicKey, const QByteArray& clientSecretKey) {
    // Use X25519 for ECDH key exchange
    QByteArray sharedKey(crypto_kx_SESSIONKEYBYTES, '\0');

    // Convert to bytes for computation
    unsigned char server_pk[crypto_kx_PUBLICKEYBYTES];
    unsigned char client_sk[crypto_kx_SECRETKEYBYTES];

    if (serverPublicKey.size() != crypto_kx_PUBLICKEYBYTES ||
        clientSecretKey.size() != crypto_kx_SECRETKEYBYTES) {
        qWarning() << "Invalid key size for X25519 key derivation";
        return QByteArray();
    }

    memcpy(server_pk, serverPublicKey.data(), crypto_kx_PUBLICKEYBYTES);
    memcpy(client_sk, clientSecretKey.data(), crypto_kx_SECRETKEYBYTES);

    // Use crypto_kx to derive shared keys (client side)
    unsigned char client_rx[crypto_kx_SESSIONKEYBYTES];
    unsigned char client_tx[crypto_kx_SESSIONKEYBYTES];
    if (crypto_kx_client_session_keys(client_rx, client_tx, server_pk, client_sk, server_pk) != 0) {
        qWarning() << "Failed to derive shared key via crypto_kx";
        return QByteArray();
    }

    // Use client_tx as the shared key (outgoing encryption key)
    sharedKey.resize(crypto_kx_SESSIONKEYBYTES);
    memcpy(sharedKey.data(), client_tx, crypto_kx_SESSIONKEYBYTES);

    return sharedKey;
}

QByteArray Encryption::encryptMessage(const QString& message, const QByteArray& sharedKey) {
    if (sharedKey.size() != crypto_secretbox_keybytes()) {
        qWarning() << "Invalid key size for encryption";
        return QByteArray();
    }

    QByteArray messageBytes = message.toUtf8();
    int messageLen = messageBytes.size();

    // Generate nonce
    QByteArray nonce(crypto_secretbox_noncebytes(), '\0');
    randombytes_buf(nonce.data(), nonce.size());

    // Allocate space for ciphertext (message + MAC)
    QByteArray ciphertext(messageLen + crypto_secretbox_macbytes(), '\0');

    // Encrypt
    if (crypto_secretbox_easy(
            reinterpret_cast<unsigned char*>(ciphertext.data()) + crypto_secretbox_macbytes(),
            reinterpret_cast<const unsigned char*>(messageBytes.data()),
            messageLen,
            reinterpret_cast<const unsigned char*>(nonce.data()),
            reinterpret_cast<const unsigned char*>(sharedKey.data())) != 0) {
        qWarning() << "Failed to encrypt message";
        return QByteArray();
    }

    // Prepend nonce to ciphertext: nonce + ciphertext
    QByteArray result = nonce + ciphertext;
    return result;
}

QString Encryption::decryptMessage(const QByteArray& encryptedData, const QByteArray& sharedKey) {
    if (encryptedData.size() < crypto_secretbox_noncebytes() + crypto_secretbox_macbytes()) {
        qWarning() << "Encrypted data too short";
        return QString();
    }

    if (sharedKey.size() != crypto_secretbox_keybytes()) {
        qWarning() << "Invalid key size for decryption";
        return QString();
    }

    // Extract nonce
    QByteArray nonce = encryptedData.left(crypto_secretbox_noncebytes());

    // Extract ciphertext
    QByteArray ciphertext = encryptedData.mid(crypto_secretbox_noncebytes());

    // Allocate space for plaintext
    QByteArray plaintext(ciphertext.size(), '\0');

    // Decrypt
    if (crypto_secretbox_open_easy(
            reinterpret_cast<unsigned char*>(plaintext.data()),
            reinterpret_cast<const unsigned char*>(ciphertext.data()),
            ciphertext.size(),
            reinterpret_cast<const unsigned char*>(nonce.data()),
            reinterpret_cast<const unsigned char*>(sharedKey.data())) != 0) {
        qWarning() << "Failed to decrypt message";
        return QString();
    }

    return QString::fromUtf8(plaintext);
}

QByteArray Encryption::signMessage(const QByteArray& message, const QByteArray& secretKey) {
    if (secretKey.size() < crypto_sign_ed25519_SECRETKEYBYTES) {
        qWarning() << "Invalid secret key size for signing";
        return QByteArray();
    }

    QByteArray signature(crypto_sign_ed25519_BYTES, '\0');
    unsigned long long sigLen = 0;

    if (crypto_sign_ed25519(
            reinterpret_cast<unsigned char*>(signature.data()),
            &sigLen,
            reinterpret_cast<const unsigned char*>(message.data()),
            static_cast<unsigned long long>(message.size()),
            reinterpret_cast<const unsigned char*>(secretKey.data())) != 0) {
        qWarning() << "Failed to sign message";
        return QByteArray();
    }

    signature.resize(sigLen);
    return signature;
}

bool Encryption::verifySignature(const QByteArray& message,
                                 const QByteArray& signature,
                                 const QByteArray& publicKey) {
    if (publicKey.size() != crypto_sign_ed25519_PUBLICKEYBYTES ||
        signature.size() != crypto_sign_ed25519_BYTES) {
        qWarning() << "Invalid signature or public key size";
        return false;
    }

    unsigned long recoveredLen = 0;
    int result = crypto_sign_ed25519_verify_detached(
        reinterpret_cast<const unsigned char*>(signature.data()),
        reinterpret_cast<const unsigned char*>(message.data()),
        message.size(),
        reinterpret_cast<const unsigned char*>(publicKey.data()));

    return result == 0;
}

bool Encryption::boxKeyPair(QString& publicHex, QString& privateHex) {
    if (!init()) return false;
    unsigned char pk[crypto_box_PUBLICKEYBYTES];
    unsigned char sk[crypto_box_SECRETKEYBYTES];
    if (crypto_box_keypair(pk, sk) != 0) {
        qWarning() << "boxKeyPair: crypto_box_keypair failed";
        return false;
    }
    publicHex = QString::fromUtf8(QByteArray(reinterpret_cast<char*>(pk), sizeof(pk)).toHex());
    privateHex = QString::fromUtf8(QByteArray(reinterpret_cast<char*>(sk), sizeof(sk)).toHex());
    return true;
}

QString Encryption::boxEncrypt(const QString& message,
                               const QString& recipientPublicHex,
                               const QString& senderPrivateHex) {
    if (!init()) return QString();

    QByteArray pk = QByteArray::fromHex(recipientPublicHex.toUtf8());
    QByteArray sk = QByteArray::fromHex(senderPrivateHex.toUtf8());
    if (pk.size() != crypto_box_PUBLICKEYBYTES || sk.size() != crypto_box_SECRETKEYBYTES) {
        qWarning() << "boxEncrypt: invalid key size" << pk.size() << sk.size();
        return QString();
    }

    QByteArray msg = message.toUtf8();
    QByteArray nonce(crypto_box_NONCEBYTES, '\0');
    randombytes_buf(nonce.data(), nonce.size());

    QByteArray cipher(msg.size() + crypto_box_MACBYTES, '\0');
    if (crypto_box_easy(
            reinterpret_cast<unsigned char*>(cipher.data()),
            reinterpret_cast<const unsigned char*>(msg.constData()),
            static_cast<unsigned long long>(msg.size()),
            reinterpret_cast<const unsigned char*>(nonce.constData()),
            reinterpret_cast<const unsigned char*>(pk.constData()),
            reinterpret_cast<const unsigned char*>(sk.constData())) != 0) {
        qWarning() << "boxEncrypt: crypto_box_easy failed";
        return QString();
    }

    return QString::fromUtf8((nonce + cipher).toHex());
}

QString Encryption::boxDecrypt(const QString& payloadHex,
                               const QString& otherPublicHex,
                               const QString& myPrivateHex) {
    if (!init()) return QString();

    QByteArray payload = QByteArray::fromHex(payloadHex.toUtf8());
    QByteArray pk = QByteArray::fromHex(otherPublicHex.toUtf8());
    QByteArray sk = QByteArray::fromHex(myPrivateHex.toUtf8());
    if (pk.size() != crypto_box_PUBLICKEYBYTES || sk.size() != crypto_box_SECRETKEYBYTES) {
        return QString();
    }
    if (payload.size() < crypto_box_NONCEBYTES + crypto_box_MACBYTES) {
        return QString();
    }

    QByteArray nonce = payload.left(crypto_box_NONCEBYTES);
    QByteArray cipher = payload.mid(crypto_box_NONCEBYTES);
    QByteArray plain(cipher.size() - crypto_box_MACBYTES, '\0');

    if (crypto_box_open_easy(
            reinterpret_cast<unsigned char*>(plain.data()),
            reinterpret_cast<const unsigned char*>(cipher.constData()),
            static_cast<unsigned long long>(cipher.size()),
            reinterpret_cast<const unsigned char*>(nonce.constData()),
            reinterpret_cast<const unsigned char*>(pk.constData()),
            reinterpret_cast<const unsigned char*>(sk.constData())) != 0) {
        return QString();
    }

    return QString::fromUtf8(plain);
}

QByteArray Encryption::boxEncryptBytes(const QByteArray& plain,
                                       const QString& recipientPublicHex,
                                       const QString& senderPrivateHex) {
    if (!init()) return QByteArray();

    QByteArray pk = QByteArray::fromHex(recipientPublicHex.toUtf8());
    QByteArray sk = QByteArray::fromHex(senderPrivateHex.toUtf8());
    if (pk.size() != crypto_box_PUBLICKEYBYTES || sk.size() != crypto_box_SECRETKEYBYTES) {
        qWarning() << "boxEncryptBytes: invalid key size" << pk.size() << sk.size();
        return QByteArray();
    }

    QByteArray nonce(crypto_box_NONCEBYTES, '\0');
    randombytes_buf(nonce.data(), nonce.size());

    QByteArray cipher(plain.size() + crypto_box_MACBYTES, '\0');
    if (crypto_box_easy(
            reinterpret_cast<unsigned char*>(cipher.data()),
            reinterpret_cast<const unsigned char*>(plain.constData()),
            static_cast<unsigned long long>(plain.size()),
            reinterpret_cast<const unsigned char*>(nonce.constData()),
            reinterpret_cast<const unsigned char*>(pk.constData()),
            reinterpret_cast<const unsigned char*>(sk.constData())) != 0) {
        qWarning() << "boxEncryptBytes: crypto_box_easy failed";
        return QByteArray();
    }

    return nonce + cipher;
}

QByteArray Encryption::boxDecryptBytes(const QByteArray& payload,
                                       const QString& otherPublicHex,
                                       const QString& myPrivateHex) {
    if (!init()) return QByteArray();

    QByteArray pk = QByteArray::fromHex(otherPublicHex.toUtf8());
    QByteArray sk = QByteArray::fromHex(myPrivateHex.toUtf8());
    if (pk.size() != crypto_box_PUBLICKEYBYTES || sk.size() != crypto_box_SECRETKEYBYTES) {
        return QByteArray();
    }
    if (payload.size() < crypto_box_NONCEBYTES + crypto_box_MACBYTES) {
        return QByteArray();
    }

    QByteArray nonce = payload.left(crypto_box_NONCEBYTES);
    QByteArray cipher = payload.mid(crypto_box_NONCEBYTES);
    QByteArray plain(cipher.size() - crypto_box_MACBYTES, '\0');

    if (crypto_box_open_easy(
            reinterpret_cast<unsigned char*>(plain.data()),
            reinterpret_cast<const unsigned char*>(cipher.constData()),
            static_cast<unsigned long long>(cipher.size()),
            reinterpret_cast<const unsigned char*>(nonce.constData()),
            reinterpret_cast<const unsigned char*>(pk.constData()),
            reinterpret_cast<const unsigned char*>(sk.constData())) != 0) {
        return QByteArray();
    }

    return plain;
}

QString Encryption::secretBoxGenerateKey() {
    if (!init()) return QString();
    QByteArray key(crypto_secretbox_KEYBYTES, '\0');
    randombytes_buf(key.data(), key.size());
    return QString::fromUtf8(key.toHex());
}

QByteArray Encryption::secretBoxEncryptBytes(const QByteArray& plain, const QString& keyHex) {
    if (!init()) return QByteArray();

    QByteArray key = QByteArray::fromHex(keyHex.toUtf8());
    if (key.size() != crypto_secretbox_KEYBYTES) {
        qWarning() << "secretBoxEncryptBytes: invalid key size" << key.size();
        return QByteArray();
    }

    QByteArray nonce(crypto_secretbox_NONCEBYTES, '\0');
    randombytes_buf(nonce.data(), nonce.size());

    QByteArray cipher(plain.size() + crypto_secretbox_MACBYTES, '\0');
    if (crypto_secretbox_easy(
            reinterpret_cast<unsigned char*>(cipher.data()),
            reinterpret_cast<const unsigned char*>(plain.constData()),
            static_cast<unsigned long long>(plain.size()),
            reinterpret_cast<const unsigned char*>(nonce.constData()),
            reinterpret_cast<const unsigned char*>(key.constData())) != 0) {
        qWarning() << "secretBoxEncryptBytes: crypto_secretbox_easy failed";
        return QByteArray();
    }

    return nonce + cipher;
}

QByteArray Encryption::secretBoxDecryptBytes(const QByteArray& payload, const QString& keyHex) {
    if (!init()) return QByteArray();

    QByteArray key = QByteArray::fromHex(keyHex.toUtf8());
    if (key.size() != crypto_secretbox_KEYBYTES) {
        return QByteArray();
    }
    if (payload.size() < crypto_secretbox_NONCEBYTES + crypto_secretbox_MACBYTES) {
        return QByteArray();
    }

    QByteArray nonce = payload.left(crypto_secretbox_NONCEBYTES);
    QByteArray cipher = payload.mid(crypto_secretbox_NONCEBYTES);
    QByteArray plain(cipher.size() - crypto_secretbox_MACBYTES, '\0');

    if (crypto_secretbox_open_easy(
            reinterpret_cast<unsigned char*>(plain.data()),
            reinterpret_cast<const unsigned char*>(cipher.constData()),
            static_cast<unsigned long long>(cipher.size()),
            reinterpret_cast<const unsigned char*>(nonce.constData()),
            reinterpret_cast<const unsigned char*>(key.constData())) != 0) {
        return QByteArray();
    }

    return plain;
}

QString Encryption::bytesToHex(const QByteArray& bytes) {
    return QString::fromUtf8(bytes.toHex());
}

QByteArray Encryption::hexToBytes(const QString& hex) {
    return QByteArray::fromHex(hex.toUtf8());
}

// Encrypt a message directly for a recipient's public key using a sealed box.
// No prior key exchange is needed; the ephemeral key is embedded in the ciphertext.
bool Encryption::encryptMessageDirect(const QByteArray& message,
                                      const QByteArray& publicKey,
                                      QByteArray& encryptedOut,
                                      QByteArray& nonceOut)
{
    if (publicKey.size() != crypto_box_PUBLICKEYBYTES) {
        qWarning() << "encryptMessageDirect: invalid public key size";
        return false;
    }

    QByteArray ciphertext(message.size() + crypto_box_SEALBYTES, '\0');
    if (crypto_box_seal(
            reinterpret_cast<unsigned char*>(ciphertext.data()),
            reinterpret_cast<const unsigned char*>(message.constData()),
            static_cast<unsigned long long>(message.size()),
            reinterpret_cast<const unsigned char*>(publicKey.constData())) != 0) {
        qWarning() << "encryptMessageDirect: crypto_box_seal failed";
        return false;
    }

    encryptedOut = ciphertext;
    nonceOut.clear();
    return true;
}
