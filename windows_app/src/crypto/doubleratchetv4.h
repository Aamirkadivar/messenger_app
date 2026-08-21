#ifndef DOUBLERATCHETV4_H
#define DOUBLERATCHETV4_H

#include <QByteArray>
#include <QHash>
#include <QString>
#include <cstdint>

// Direct-chat X3DH-lite two-root Double Ratchet (encryption_version=4).
// Byte-for-byte port of back-end/e2ee/ratchet_x3dh.go and the Android
// DoubleRatchetV4.kt. See docs/e2ee-protocol-v4.md and
// test-vectors/e2ee/x3dh-*.json.
//
// Glare-safe replacement for v3: the initial root is derived symmetrically from
// both identity keys (no initiator/responder role), and the sending/receiving
// roots are kept separate so concurrent chains cannot corrupt each other.
class DoubleRatchetV4 {
public:
    struct Session {
        QByteArray rk0;   // immutable symmetric root
        QByteArray rks, cks;  // sending root + chain key
        QByteArray rkr, ckr;  // receiving root + chain key
        QByteArray dhsSk, dhsPk;  // current sending ephemeral
        QByteArray dhr;           // peer's current ephemeral (receiving)
        QByteArray peerIdent;     // peer identity public (first-send target)
        QByteArray identSk;       // our identity secret (opens peer INITIAL)
        quint32 ns = 0, nr = 0, pn = 0;
        bool sentFirst = false, recvFirst = false, turnPending = false;
        // True once the sending chain is rooted at rk0 (INITIAL-only mode).
        // Defaults false so sessions serialized by older builds re-root on
        // their first send after upgrade.
        bool initChain = false;
        quint64 seq = 0;
        QHash<QString, QByteArray> skipped;

        QString toJson() const;
        static bool fromJson(const QString& json, Session& out);
    };

    // Deterministic symmetric root from the two identity keys (order-free).
    static QByteArray symmetricRoot(const QByteArray& myIkSk,
                                    const QByteArray& myIkPk,
                                    const QByteArray& peerIkPk);

    // Role-free init: both peers call it with their own identity keypair and the
    // peer's identity public key.
    static bool initSession(const QByteArray& myIkPk,
                            const QByteArray& myIkSk,
                            const QByteArray& peerIkPk,
                            Session& out);

    static QByteArray encrypt(Session& st, const QByteArray& plain);
    // Transactional: advances a copy and commits only if authentication passes,
    // returning {} on any failure.
    static QByteArray decrypt(Session& st, const QByteArray& payload);

    // Embedded-vector self-check (RK0 + frozen Go transcript + glare). Logs and
    // returns true on success. Used to verify the port against the reference.
    static bool selfTest();
};

#endif
