#ifndef DEVICEENROLLMENT_H
#define DEVICEENROLLMENT_H

#include <QByteArray>
#include <QJsonObject>
#include <QPair>
#include <QString>
#include <functional>

/**
 * Windows client for the Phase 44 device proof-of-possession enrolment.
 *
 * WHY THIS EXISTS. Before Phase 44 a device registered itself by asserting an id: an account token
 * plus any string produced a trusted device, and an unknown id presented on any protected route was
 * auto-registered. Phase 43 proved that authorized nothing - a stolen token read the MK-sealed
 * history keyring from an identity invented seconds earlier, and evaded revocation by inventing
 * another. The server now demands proof that the caller holds the private key it is registering,
 * and binds the authenticated SESSION to the device that proved itself.
 *
 * WHAT THE PROOF IS. The registered device key is a NaCl crypto_box (X25519) key - an encryption
 * key, not a signing key - so possession is proven by DECRYPTING rather than signing. The server
 * seals 32 random bytes to the claimed public key under an ephemeral sender key (crypto_box_easy,
 * the same construction as the pairing payload) and the client returns the plaintext. Only the
 * holder of the private half can produce it, the value is single-use, and it is bound to
 * (account, deviceId, publicKey) so a proof cannot be redeemed for a different key or identity.
 *
 * WHICH PRIVATE KEY. The account identity keypair, which lives inside the vault
 * (`identity_priv_hex`) and is therefore available to any device that can unlock it. Proving
 * possession of it proves "this caller has unlocked this account's vault", which is exactly the
 * property that makes an account token alone insufficient. Device DISTINCTNESS is not carried by
 * the key - it is carried by the server-side session binding, which the caller cannot forge.
 *
 * PROTOCOL ONLY. It owns no HTTP client and no key storage. The exchange is injected with the same
 * shape as the application's one authenticated JSON path, and the private key is supplied by a
 * getter, so nothing here persists, logs, or copies key material.
 *
 * Server contract (back-end/handlers/device_proof.go):
 *
 *   POST /e2ee/devices/challenge  {device_id, public_key}
 *                               -> 201 {challenge_id, sender_pub_hex, sealed_b64, expires_at}
 *                               -> 403 identity already bound to a different key, or revoked
 *   POST /e2ee/devices           {device_id, name, platform, public_key, challenge_id, proof_b64}
 *                               -> 201 created | 200 re-registered (idempotent on the same session)
 *                               -> 400 no challenge | 403 proof failed / revoked / session bound
 */
class DeviceEnrollment {
public:
    /** Same shape as the application's one authenticated JSON exchange. */
    using ExchangeFn = std::function<QPair<int, QJsonObject>(const QString& path,
                                                             const QByteArray& body,
                                                             const char* method)>;
    /**
     * The DEVICE keypair (K_device), as lowercase hex.
     *
     * Phase 67: this is no longer the account-wide identity key. It is generated on
     * this machine, stored in its own DPAPI slot, and never written into or read
     * back from the vault - so unlocking, relocking or re-syncing the vault cannot
     * replace it, and enrolment no longer waits for the vault at all.
     */
    using IdentityKeyFn = std::function<QString()>;

    static constexpr const char* CHALLENGE_PATH = "/e2ee/devices/challenge";
    static constexpr const char* REGISTER_PATH = "/e2ee/devices";
    /** The server's challenge size; a proof of any other length is not worth sending. */
    static constexpr int CHALLENGE_BYTES = 32;

    enum class Outcome {
        Ok,
        Locked,         ///< No device private key available - K_device has not been generated yet.
        Refused,        ///< The server rejected the challenge or the proof (403).
        Rejected,       ///< 400 - malformed request.
        Unauthorized,   ///< 401 - the session is gone.
        Malformed,      ///< A 2xx whose payload did not parse, or an unopenable challenge.
        TransportError, ///< No response, 5xx, or an unexpected status.
    };

    struct Result {
        Outcome outcome = Outcome::TransportError;
        bool created = false; ///< true on first enrolment, false when re-registering.
        /** True only when the device is verified and this session is bound to it. */
        bool verified() const { return outcome == Outcome::Ok; }
    };

    DeviceEnrollment(ExchangeFn exchange, IdentityKeyFn publicHex, IdentityKeyFn privateHex);

    /**
     * Runs challenge -> local decrypt -> registration.
     *
     * Fails closed at every step: a locked vault, a refused challenge, an unopenable box, or a
     * refused registration all leave the device unverified and the session unbound. Nothing is
     * retried here - the caller decides.
     */
    Result enroll(const QString& deviceId, const QString& name, const QString& platform) const;

private:
    ExchangeFn m_exchange;
    IdentityKeyFn m_publicHex;
    IdentityKeyFn m_privateHex;
};

#endif // DEVICEENROLLMENT_H
