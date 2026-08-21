package e2ee

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"io"

	"golang.org/x/crypto/curve25519"
	"golang.org/x/crypto/hkdf"
	"golang.org/x/crypto/nacl/box"
	"golang.org/x/crypto/nacl/secretbox"
)

const (
	SuiteDoubleRatchetV3 = "suite:nacl-dr-xsalsa20poly1305-v3"
	drRootInfo           = "messenger-dr-root-v1"
	MaxDRSkip            = 200
	drHeaderLen          = 40 // dh(32) || n(4) || pn(4)
)

// RatchetState is a pairwise Double Ratchet using X25519 + HKDF-SHA256 + secretbox.
// This is the legacy v3 handshake; the glare-safe replacement is SessionV4 in
// ratchet_x3dh.go.
type RatchetState struct {
	DHsSk   []byte
	DHsPk   []byte
	DHr     []byte
	RK      []byte
	CKs     []byte
	CKr     []byte
	Ns      uint32
	Nr      uint32
	PN      uint32
	Skipped map[string][]byte
}

func drHKDF(ikm, salt, info []byte, n int) ([]byte, error) {
	if len(salt) == 0 {
		salt = make([]byte, 32)
	}
	r := hkdf.New(sha256.New, ikm, salt, info)
	out := make([]byte, n)
	if _, err := io.ReadFull(r, out); err != nil {
		return nil, err
	}
	return out, nil
}

func kdfRK(rk, dh []byte) (newRK, ck []byte, err error) {
	out, err := drHKDF(dh, rk, []byte(drRootInfo), 64)
	if err != nil {
		return nil, nil, err
	}
	return out[:32], out[32:], nil
}

func kdfCK(ck []byte) (nextCK, mk []byte) {
	mac := hmac.New(sha256.New, ck)
	mac.Write([]byte{0x01})
	mk = mac.Sum(nil)
	mac.Reset()
	mac.Write([]byte{0x02})
	nextCK = mac.Sum(nil)
	return nextCK, mk
}

func x25519DH(sk, pk []byte) ([]byte, error) {
	if len(sk) != 32 || len(pk) != 32 {
		return nil, fmt.Errorf("x25519: bad key size")
	}
	return curve25519.X25519(sk, pk)
}

func generateDRKeyPair() (pk, sk []byte, err error) {
	pub, priv, err := box.GenerateKey(rand.Reader)
	if err != nil {
		return nil, nil, err
	}
	return pub[:], priv[:], nil
}

func skipKeyID(dh []byte, n uint32) string {
	return hex.EncodeToString(dh) + ":" + fmt.Sprintf("%d", n)
}

// InitAlice prepares the first sending chain (ephemeral DH vs peer identity).
func InitAlice(theirIK []byte) (*RatchetState, error) {
	pk, sk, err := generateDRKeyPair()
	if err != nil {
		return nil, err
	}
	dh, err := x25519DH(sk, theirIK)
	if err != nil {
		return nil, err
	}
	rk, cks, err := kdfRK(make([]byte, 32), dh)
	if err != nil {
		return nil, err
	}
	return &RatchetState{
		DHsSk:   sk,
		DHsPk:   pk,
		DHr:     append([]byte(nil), theirIK...),
		RK:      rk,
		CKs:     cks,
		Skipped: map[string][]byte{},
	}, nil
}

// InitBob uses the local identity keypair so Alice's first header can be opened.
func InitBob(myIKPk, myIKSk []byte) *RatchetState {
	return &RatchetState{
		DHsSk:   append([]byte(nil), myIKSk...),
		DHsPk:   append([]byte(nil), myIKPk...),
		RK:      make([]byte, 32),
		Skipped: map[string][]byte{},
	}
}

func (s *RatchetState) skipMessageKeys(until uint32) error {
	if len(s.CKr) != 32 || len(s.DHr) != 32 {
		if until == 0 {
			return nil
		}
		return fmt.Errorf("dr: skip with no recv chain")
	}
	if until <= s.Nr {
		return nil
	}
	if until-s.Nr > MaxDRSkip {
		return fmt.Errorf("dr: skip too large")
	}
	if s.Skipped == nil {
		s.Skipped = map[string][]byte{}
	}
	for s.Nr < until {
		var mk []byte
		s.CKr, mk = kdfCK(s.CKr)
		s.Skipped[skipKeyID(s.DHr, s.Nr)] = mk
		s.Nr++
	}
	return nil
}

func (s *RatchetState) dhRatchet(theirDH []byte) error {
	s.PN = s.Ns
	s.Ns = 0
	s.Nr = 0
	s.DHr = append([]byte(nil), theirDH...)
	dh, err := x25519DH(s.DHsSk, s.DHr)
	if err != nil {
		return err
	}
	s.RK, s.CKr, err = kdfRK(s.RK, dh)
	if err != nil {
		return err
	}
	pk, sk, err := generateDRKeyPair()
	if err != nil {
		return err
	}
	s.DHsSk, s.DHsPk = sk, pk
	dh2, err := x25519DH(s.DHsSk, s.DHr)
	if err != nil {
		return err
	}
	s.RK, s.CKs, err = kdfRK(s.RK, dh2)
	return err
}

func (s *RatchetState) Encrypt(plain []byte) ([]byte, error) {
	if len(s.CKs) != 32 || len(s.DHsPk) != 32 {
		return nil, fmt.Errorf("dr: not ready to send")
	}
	var mk []byte
	s.CKs, mk = kdfCK(s.CKs)
	n := s.Ns
	s.Ns++
	var nonce [24]byte
	if _, err := io.ReadFull(rand.Reader, nonce[:]); err != nil {
		return nil, err
	}
	var key [32]byte
	copy(key[:], mk)
	ct := secretbox.Seal(nil, plain, &nonce, &key)
	out := make([]byte, drHeaderLen+24+len(ct))
	copy(out[0:32], s.DHsPk)
	binary.BigEndian.PutUint32(out[32:36], n)
	binary.BigEndian.PutUint32(out[36:40], s.PN)
	copy(out[40:64], nonce[:])
	copy(out[64:], ct)
	return out, nil
}

func parseDR(payload []byte) (dh []byte, n, pn uint32, nonce, ct []byte, err error) {
	if len(payload) < drHeaderLen+24+secretbox.Overhead {
		return nil, 0, 0, nil, nil, fmt.Errorf("dr: short")
	}
	return payload[0:32],
		binary.BigEndian.Uint32(payload[32:36]),
		binary.BigEndian.Uint32(payload[36:40]),
		payload[40:64],
		payload[64:],
		nil
}

func (s *RatchetState) Decrypt(payload []byte) ([]byte, error) {
	dh, n, pn, nonceB, ct, err := parseDR(payload)
	if err != nil {
		return nil, err
	}
	id := skipKeyID(dh, n)
	if mk, ok := s.Skipped[id]; ok {
		delete(s.Skipped, id)
		return openMK(mk, nonceB, ct)
	}
	if len(s.DHr) != 32 || !bytesEqual(s.DHr, dh) {
		if err := s.skipMessageKeys(pn); err != nil && len(s.CKr) == 32 {
			return nil, err
		}
		if err := s.dhRatchet(dh); err != nil {
			return nil, err
		}
	}
	if err := s.skipMessageKeys(n); err != nil {
		return nil, err
	}
	var mk []byte
	s.CKr, mk = kdfCK(s.CKr)
	s.Nr++
	return openMK(mk, nonceB, ct)
}

func openMK(mk, nonceB, ct []byte) ([]byte, error) {
	var nonce [24]byte
	copy(nonce[:], nonceB)
	var key [32]byte
	copy(key[:], mk)
	plain, ok := secretbox.Open(nil, ct, &nonce, &key)
	if !ok {
		return nil, fmt.Errorf("dr: open failed")
	}
	return plain, nil
}

func bytesEqual(a, b []byte) bool {
	if len(a) != len(b) {
		return false
	}
	var v byte
	for i := range a {
		v |= a[i] ^ b[i]
	}
	return v == 0
}
