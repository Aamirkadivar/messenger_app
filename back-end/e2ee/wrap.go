package e2ee

import "fmt"

// WrapKey AEAD-encrypts keyMaterial under wrappingKey with AAD.
func WrapKey(wrappingKey, keyMaterial, aad []byte) ([]byte, error) {
	if len(keyMaterial) == 0 {
		return nil, fmt.Errorf("empty key material")
	}
	return SealXChaCha(wrappingKey, keyMaterial, aad)
}

// UnwrapKey AEAD-decrypts a wrap produced by WrapKey.
func UnwrapKey(wrappingKey, sealed, aad []byte) ([]byte, error) {
	return OpenXChaCha(wrappingKey, sealed, aad)
}