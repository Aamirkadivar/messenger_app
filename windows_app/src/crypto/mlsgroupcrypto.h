#ifndef MLSGROUPCRYPTO_H
#define MLSGROUPCRYPTO_H

#include <QByteArray>
#include <QString>
#include <QStringList>

// MLS (RFC 9420) group messaging for Windows, backed by cisco/mlspp.
//
// Counterpart of the Android MlsGroupCrypto.kt (BouncyCastle). Both sit on the
// same mandatory-to-implement cipher suite so the two clients interoperate:
//   MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519
//
// Every cryptographic operation - TreeKEM, the key schedule, HPKE - comes from
// mlspp. Nothing here is hand-rolled; that is the point of adopting MLS through
// a maintained implementation rather than writing one (see
// docs/e2ee-protocol-v4.md for what hand-rolling cost on direct messages).
//
// The backend is an untrusted Delivery Service: it stores KeyPackages and
// relays Commit/Welcome/application blobs as opaque bytes, and enforces commit
// ordering per epoch. It never runs TreeKEM and never sees group secrets.
//
// The mlspp types are deliberately kept out of this header (opaque pimpl) so
// the rest of the Qt app does not need mlspp's headers or its C++17 template
// machinery on every translation unit.
class MlsGroupCrypto {
public:
    // Opaque handles. Owned by the caller; free with destroy*.
    struct Identity;
    struct Group;
    struct PublishedKeyPackage;

    // Creates a fresh MLS identity (leaf HPKE keypair + signature keypair)
    // credentialed with userId. One per device per group.
    static Identity* newIdentity(const QString& userId);
    static void destroyIdentity(Identity* id);

    // A KeyPackage plus the init private key that opens a Welcome addressed to
    // it. Only keyPackageBytes() leaves the device; the init key stays local.
    static PublishedKeyPackage* newKeyPackage(Identity* id);
    static QByteArray keyPackageBytes(PublishedKeyPackage* kp);
    static void destroyKeyPackage(PublishedKeyPackage* kp);

    // Result of adding a member: new group state plus the blobs the Delivery
    // Service relays (commit -> existing members, welcome -> the joiner only).
    struct AddResult {
        Group* group = nullptr;
        QByteArray commit;
        QByteArray welcome;
    };
    static AddResult addMember(Group* g, const QByteArray& keyPackageWire);

    // Joins a group from a relayed Welcome. kp must be the KeyPackage that was
    // added — its init private key decrypts the group secrets.
    static Group* joinFromWelcome(PublishedKeyPackage* kp, Identity* id,
                                  const QByteArray& welcomeWire);

    // Applies a commit relayed from another member. Returns nullptr on failure.
    static Group* applyCommit(Group* g, const QByteArray& commitWire);

    // Creates a group with this member as the only participant.
    static Group* createGroup(const QByteArray& groupId, Identity* id);
    static void destroyGroup(Group* g);

    // Current epoch (the Delivery Service's ordering fence).
    static quint64 epoch(Group* g);
    // Credential strings of every current leaf ("userId|deviceId"). The tree
    // is the only trustworthy answer to "is this device already a member?".
    static QStringList memberIdentities(Group* g);

    // Application message encrypt/decrypt. Returns {} on failure.
    static QByteArray protect(Group* g, const QByteArray& plaintext,
                              const QByteArray& aad = QByteArray());
    static QByteArray unprotect(Group* g, const QByteArray& payload);

    // Publishes the current GroupInfo so a member who lost local state can
    // rejoin by external commit. inline_tree embeds the ratchet tree, which the
    // rejoiner needs and the Delivery Service does not serve separately.
    static QByteArray exportGroupInfo(Group* g);

    // ---- Persistence ----
    // Neither mlspp nor BouncyCastle can serialize a live MLS group, so a
    // restart REBUILDS from these serializable inputs plus the commits the
    // Delivery Service retains. Mirrors the Android MlsGroupCrypto encoders so
    // both platforms persist equivalent material.
    static QString encodeIdentity(Identity* id);
    static Identity* decodeIdentity(const QString& json);
    static QString encodePublishedKeyPackage(PublishedKeyPackage* kp);
    static PublishedKeyPackage* decodePublishedKeyPackage(const QString& json);

    // Verifies mlspp is present and functional on this build: suite lookup,
    // KeyPackage round-trip, group creation, protect/unprotect. Logs and
    // returns true on success.
    static bool selfTest();

    // Writes hex-encoded interop vectors (KeyPackage, welcome, commit, app
    // message) so the Android/BouncyCastle side can verify wire compatibility.
    static bool exportInteropVectors(const QString& dir);
};

#endif
