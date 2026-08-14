package e2ee

// Cryptographic suite and KDF identifiers (wire / vault metadata).
// Never infer algorithms from implicit behavior.
const (
	SuiteVaultAEAD     = "suite:aead-xchacha20poly1305-v1"
	SuiteNaclBox       = "suite:nacl-box-xsalsa20poly1305-v1"
	SuiteNaclSecretBox = "suite:nacl-secretbox-xsalsa20poly1305-v1"
	KDFArgon2id        = "kdf:argon2id-v1"
	KDFHKDFSHA256      = "kdf:hkdf-sha256-v1"

	ProtocolVersionV1 = 1
	ProtocolVersionV2 = 2
	VaultFormatV1     = 1

	MasterKeyBytes = 32
	VEKBytes       = 32

	// SuiteEphBoxV2: direct-message ephemeral crypto_box (sender FS).
	SuiteEphBoxV2 = "suite:nacl-eph-box-xsalsa20poly1305-v2"

	// SuiteDoubleRatchetV3: direct-message Double Ratchet (see ratchet.go).
	ProtocolVersionV3 = 3
)

// DefaultArgon2idParams are a starting point for desktop/dev.
// Mobile clients may raise memory; document any divergence in suite params.
type Argon2idParams struct {
	MemoryKiB uint32 `json:"m" cbor:"1,keyasint"`
	Time      uint32 `json:"t" cbor:"2,keyasint"`
	Threads   uint8  `json:"p" cbor:"3,keyasint"`
	KeyLen    uint32 `json:"dklen" cbor:"4,keyasint"`
}

func DefaultArgon2idParams() Argon2idParams {
	// Threads must stay 1: libsodium crypto_pwhash hardcodes parallelism to 1.
	// Using p!=1 here would make Go KEKs diverge from Android/Windows.
	return Argon2idParams{
		MemoryKiB: 64 * 1024, // 64 MiB
		Time:      3,
		Threads:   1,
		KeyLen:    32,
	}
}