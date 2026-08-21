package e2ee

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"fmt"
	"io"

	"golang.org/x/crypto/nacl/secretbox"
)

// X3DH-lite two-root Double Ratchet (encryption_version=4).
//
// Why this replaces v3: the v3 handshake (InitAlice/InitBob) chose
// initiator/responder by "who sends first". When both peers send before
// receiving (glare) both became initiators with independent random roots and
// neither could decrypt the other — the production failure this fixes.
//
// Two design choices make v4 glare-proof:
//
//  1. SYMMETRIC ROOT. Both sides derive the same initial root from the two
//     long-term identity keys, with no negotiation:
//
//     RK0 = HKDF-SHA256(
//     ikm  = X25519(myIdentitySk, peerIdentityPk),          // symmetric DH
//     salt = SHA256( min(idA,idB) || max(idA,idB) ),         // order-free
//     info = "messenger-x3dh-lite-v1")
//
//     The identity key plays the signed-prekey role. Primitives are the same
//     audited HKDF / X25519 / secretbox already in use — no new construction.
//
//  2. TWO ROOTS. The sending chain (RKs) and receiving chain (RKr) evolve
//     independently, each seeded from RK0. v3 shared one mutable root between
//     directions, so two concurrent glare chains corrupted each other. With
//     separate roots, my RKs mirrors the peer's RKr (identical DH inputs) and
//     vice-versa, so the chains always agree regardless of message ordering.
//
// Message framing (a leading type byte distinguishes it from v3):
//
//	type(1) || dh_pk(32) || n(4,BE) || pn(4,BE) || nonce(24) || secretbox(ct)
//
// type = INITIAL while the sender has never received (its chain is rooted on the
// peer identity); NORMAL afterwards. A receiver opening an INITIAL message
// derives its receiving chain from RK0 with its own identity secret, converging
// even if it already sent.
//
// Forward secrecy: per-message symmetric ratchet (kdfCK, one-way) plus a DH
// ratchet on every direction turn (fresh X25519 ephemeral). RK0 itself is only
// as strong as the identity keys — a documented, accepted property of a
// prekey-less handshake (see docs/e2ee-protocol-v3.md); the ratchet heals it
// forward after the first turn in each direction.
const (
	SuiteDRX3DHLiteV4 = "suite:dr-x25519-x3dhlite-v4"
	ProtocolVersionV4 = 4

	x3dhRootInfo  = "messenger-x3dh-lite-v1"
	x3dhHeaderLen = 41 // type(1) || dh(32) || n(4) || pn(4)

	msgTypeInitial byte = 0x01
	msgTypeNormal  byte = 0x02
)

// SessionV4 is a role-free two-root ratchet session. It is deliberately its own
// type (not RatchetState) so the legacy v3 code and its test vectors are
// untouched.
type SessionV4 struct {
	RK0 []byte // immutable symmetric root

	RKs []byte // sending root
	CKs []byte // sending chain key
	RKr []byte // receiving root
	CKr []byte // receiving chain key

	DHsSk []byte // our current sending ephemeral secret
	DHsPk []byte // our current sending ephemeral public
	DHr   []byte // peer's current ephemeral public (receiving)

	PeerIdent []byte // peer identity public (target of our very first send)
	IdentSk   []byte // our identity secret (opens the peer's INITIAL message)

	Ns uint32
	Nr uint32
	PN uint32

	Skipped map[string][]byte

	SentFirst   bool
	RecvFirst   bool
	TurnPending bool // received since our last send → next send DH-ratchets
}

func symmetricRoot(myIKsk, myIKpk, peerIKpk []byte) ([]byte, error) {
	if len(myIKsk) != 32 || len(myIKpk) != 32 || len(peerIKpk) != 32 {
		return nil, fmt.Errorf("x3dh: identity keys must be 32 bytes")
	}
	dh, err := x25519DH(myIKsk, peerIKpk)
	if err != nil {
		return nil, err
	}
	lo, hi := myIKpk, peerIKpk
	if bytesLess(hi, lo) {
		lo, hi = hi, lo
	}
	h := sha256.New()
	h.Write(lo)
	h.Write(hi)
	return drHKDF(dh, h.Sum(nil), []byte(x3dhRootInfo), 32)
}

func bytesLess(a, b []byte) bool {
	n := len(a)
	if len(b) < n {
		n = len(b)
	}
	for i := 0; i < n; i++ {
		if a[i] != b[i] {
			return a[i] < b[i]
		}
	}
	return len(a) < len(b)
}

// InitSession builds a session symmetrically from our identity keypair and the
// peer's identity public key. Both peers call it the same way.
func InitSession(myIKpk, myIKsk, peerIKpk []byte) (*SessionV4, error) {
	rk0, err := symmetricRoot(myIKsk, myIKpk, peerIKpk)
	if err != nil {
		return nil, err
	}
	return &SessionV4{
		RK0:       append([]byte(nil), rk0...),
		RKs:       append([]byte(nil), rk0...),
		RKr:       append([]byte(nil), rk0...),
		PeerIdent: append([]byte(nil), peerIKpk...),
		IdentSk:   append([]byte(nil), myIKsk...),
		Skipped:   map[string][]byte{},
	}, nil
}

// EncryptMsg seals plaintext, performing a sending DH ratchet on the first send
// and on the first send after each received turn.
func (s *SessionV4) EncryptMsg(plain []byte) ([]byte, error) {
	if len(s.RK0) != 32 {
		return nil, fmt.Errorf("x3dh: session not initialised")
	}
	if !s.SentFirst || s.TurnPending {
		// Target of the DH: the peer's current ephemeral once we've received,
		// otherwise the peer identity (that is what makes the first message
		// INITIAL and openable via the peer's identity secret).
		target := s.PeerIdent
		if s.RecvFirst {
			target = s.DHr
		}
		pk, sk, err := generateDRKeyPair()
		if err != nil {
			return nil, err
		}
		dh, err := x25519DH(sk, target)
		if err != nil {
			return nil, err
		}
		s.PN = s.Ns
		s.Ns = 0
		s.RKs, s.CKs, err = kdfRK(s.RKs, dh)
		if err != nil {
			return nil, err
		}
		s.DHsSk, s.DHsPk = sk, pk
		s.SentFirst = true
		s.TurnPending = false
	}

	msgType := msgTypeNormal
	if !s.RecvFirst {
		msgType = msgTypeInitial
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

	out := make([]byte, x3dhHeaderLen+24+len(ct))
	out[0] = msgType
	copy(out[1:33], s.DHsPk)
	binary.BigEndian.PutUint32(out[33:37], n)
	binary.BigEndian.PutUint32(out[37:41], s.PN)
	copy(out[41:65], nonce[:])
	copy(out[65:], ct)
	return out, nil
}

func parseX3DH(payload []byte) (msgType byte, dh []byte, n, pn uint32, nonce, ct []byte, err error) {
	if len(payload) < x3dhHeaderLen+24+secretbox.Overhead {
		return 0, nil, 0, 0, nil, nil, fmt.Errorf("x3dh: short")
	}
	return payload[0],
		payload[1:33],
		binary.BigEndian.Uint32(payload[33:37]),
		binary.BigEndian.Uint32(payload[37:41]),
		payload[41:65],
		payload[65:],
		nil
}

func cloneBytes(b []byte) []byte {
	if b == nil {
		return nil
	}
	return append([]byte(nil), b...)
}

// clone deep-copies the session so a decrypt attempt can advance the ratchet on
// a throwaway copy and only commit if the AEAD tag verifies.
func (s *SessionV4) clone() *SessionV4 {
	c := *s
	c.RK0 = cloneBytes(s.RK0)
	c.RKs = cloneBytes(s.RKs)
	c.CKs = cloneBytes(s.CKs)
	c.RKr = cloneBytes(s.RKr)
	c.CKr = cloneBytes(s.CKr)
	c.DHsSk = cloneBytes(s.DHsSk)
	c.DHsPk = cloneBytes(s.DHsPk)
	c.DHr = cloneBytes(s.DHr)
	c.PeerIdent = cloneBytes(s.PeerIdent)
	c.IdentSk = cloneBytes(s.IdentSk)
	c.Skipped = make(map[string][]byte, len(s.Skipped))
	for k, v := range s.Skipped {
		c.Skipped[k] = cloneBytes(v)
	}
	return &c
}

// DecryptMsg opens a message transactionally: the ratchet is advanced on a clone
// and committed back only when authentication succeeds, so a forged or tampered
// message can never desync the session (§34/§36-K).
func (s *SessionV4) DecryptMsg(payload []byte) ([]byte, error) {
	trial := s.clone()
	pt, err := trial.decryptInto(payload)
	if err != nil {
		return nil, err
	}
	*s = *trial
	return pt, nil
}

func (s *SessionV4) decryptInto(payload []byte) ([]byte, error) {
	msgType, dh, n, pn, nonceB, ct, err := parseX3DH(payload)
	if err != nil {
		return nil, err
	}

	if mk, ok := s.Skipped[skipKeyID(dh, n)]; ok {
		delete(s.Skipped, skipKeyID(dh, n))
		return openMK(mk, nonceB, ct)
	}

	newChain := len(s.DHr) != 32 || !bytesEqual(s.DHr, dh)
	if newChain {
		if err := s.recvRatchet(msgType, dh, pn); err != nil {
			return nil, err
		}
	}
	if err := s.skipRecv(n); err != nil {
		return nil, err
	}
	var mk []byte
	s.CKr, mk = kdfCK(s.CKr)
	s.Nr++
	return openMK(mk, nonceB, ct)
}

// recvRatchet starts a new receiving chain for a freshly seen peer ephemeral.
// INITIAL messages seed from RK0 with our identity secret (glare-safe); NORMAL
// turns seed from RKr with our current sending ephemeral.
func (s *SessionV4) recvRatchet(msgType byte, theirDh []byte, pn uint32) error {
	// Drain the previous receiving chain up to pn so out-of-order stragglers on
	// the old chain remain openable.
	if err := s.skipRecv(pn); err != nil {
		return err
	}

	var ourSk, root []byte
	if msgType == msgTypeInitial && !s.RecvFirst {
		ourSk = s.IdentSk
		root = s.RK0
	} else {
		if len(s.DHsSk) != 32 {
			return fmt.Errorf("x3dh: normal recv before any send")
		}
		ourSk = s.DHsSk
		root = s.RKr
	}
	dh, err := x25519DH(ourSk, theirDh)
	if err != nil {
		return err
	}
	newRKr, ckr, err := kdfRK(root, dh)
	if err != nil {
		return err
	}
	s.RKr = newRKr
	s.CKr = ckr
	s.DHr = append([]byte(nil), theirDh...)
	s.Nr = 0
	s.RecvFirst = true
	s.TurnPending = true
	return nil
}

func (s *SessionV4) skipRecv(until uint32) error {
	if len(s.CKr) != 32 || len(s.DHr) != 32 {
		if until == 0 {
			return nil
		}
		return fmt.Errorf("x3dh: skip with no recv chain")
	}
	if until <= s.Nr {
		return nil
	}
	if until-s.Nr > MaxDRSkip {
		return fmt.Errorf("x3dh: skip too large")
	}
	for s.Nr < until {
		var mk []byte
		s.CKr, mk = kdfCK(s.CKr)
		s.Skipped[skipKeyID(s.DHr, s.Nr)] = mk
		s.Nr++
	}
	return nil
}
