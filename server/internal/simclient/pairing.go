package simclient

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"strings"

	"golang.org/x/crypto/curve25519"
	"golang.org/x/crypto/hkdf"
)

// Pairing adds a device (spec/crypto.md §Pairing): an enrolled device
// shows a QR carrying an ephemeral X25519 public key; the new device answers
// with its own ephemeral public key; both derive the same shared secret, show
// a 6-digit code for the human to compare, and the VK travels wrapped under a
// key derived from that secret.
const (
	// PairingSalt is the HKDF salt of all pairing derivations.
	PairingSalt = "yaop-pairing/1"
	// PairingQrVersion is the "v" field of the QR payload.
	PairingQrVersion = 1
)

// PairingQr is the exact JSON content of the QR code (compact form, key order
// as declared):
//
//	{"v":1,"server_url":"https://…","tls_fp":"<sha256 hex of DER SPKI>",
//	 "eph_pub":"<base64url-no-pad, 32 bytes>","pairing_id":"<16-byte hex>"}
type PairingQr struct {
	V         int    `json:"v"`
	ServerURL string `json:"server_url"`
	TlsFP     string `json:"tls_fp"`
	EphPub    string `json:"eph_pub"`
	PairingID string `json:"pairing_id"`
}

// EncodePairingQr renders the QR payload as canonical compact JSON (no
// spaces; encoding/json already emits struct fields in declaration order).
func EncodePairingQr(q PairingQr) (string, error) {
	q.V = PairingQrVersion
	raw, err := json.Marshal(q)
	if err != nil {
		return "", fmt.Errorf("simclient: pairing: qr: %w", err)
	}
	return string(raw), nil
}

// DecodePairingQr parses the QR payload (strict: required fields, 32-byte
// X25519 keys, 16-byte pairing id, version 1).
func DecodePairingQr(s string) (PairingQr, error) {
	var q PairingQr
	dec := json.NewDecoder(strings.NewReader(s))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&q); err != nil {
		return PairingQr{}, fmt.Errorf("simclient: pairing: qr: %w", err)
	}
	if q.V != PairingQrVersion {
		return PairingQr{}, fmt.Errorf("simclient: pairing: qr: unsupported version %d", q.V)
	}
	if q.ServerURL == "" || q.TlsFP == "" || q.PairingID == "" {
		return PairingQr{}, fmt.Errorf("simclient: pairing: qr: incomplete payload")
	}
	if _, err := DecodePub(q.EphPub); err != nil {
		return PairingQr{}, err
	}
	if _, err := hex.DecodeString(q.PairingID); err != nil || len(q.PairingID) != 32 {
		return PairingQr{}, fmt.Errorf("simclient: pairing: qr: pairing_id must be 16-byte hex")
	}
	if _, err := hex.DecodeString(q.TlsFP); err != nil || len(q.TlsFP) != 64 {
		return PairingQr{}, fmt.Errorf("simclient: pairing: qr: tls_fp must be sha-256 hex")
	}
	return q, nil
}

// EncodePub serializes a 32-byte X25519 public key as base64url without
// padding (the QR format).
func EncodePub(pub []byte) string { return base64.RawURLEncoding.EncodeToString(pub) }

// DecodePub parses a base64url-no-pad 32-byte public key.
func DecodePub(s string) ([]byte, error) {
	raw, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil {
		return nil, fmt.Errorf("simclient: pairing: bad public key: %w", err)
	}
	if len(raw) != 32 {
		return nil, fmt.Errorf("simclient: pairing: public key must be 32 bytes, has %d", len(raw))
	}
	return raw, nil
}

// X25519Shared computes the raw shared secret of the ephemeral exchange.
// X25519 clamps the scalars; the result is never used directly as a key —
// always through PairingKey/PairingCode.
func X25519Shared(priv, peerPub []byte) ([]byte, error) {
	shared, err := curve25519.X25519(priv, peerPub)
	if err != nil {
		return nil, fmt.Errorf("simclient: pairing: x25519: %w", err)
	}
	return shared, nil
}

// PubFromScalar derives the public key of an X25519 private scalar.
func PubFromScalar(priv []byte) ([]byte, error) {
	pub, err := curve25519.X25519(priv, curve25519.Basepoint)
	if err != nil {
		return nil, fmt.Errorf("simclient: pairing: x25519: %w", err)
	}
	return pub, nil
}

// pairingHKDF is the common KDF spine: HKDF-SHA256(shared, salt="yaop-
// pairing/1", info) with 32-byte output except where noted.
func pairingHKDF(shared []byte, info string, n int) []byte {
	r := hkdf.New(sha256.New, shared, []byte(PairingSalt), []byte(info))
	out := make([]byte, n)
	if _, err := io.ReadFull(r, out); err != nil {
		panic("simclient: pairing: hkdf: " + err.Error())
	}
	return out
}

// PairingCode derives the 6-digit confirmation code both devices show:
// the first 4 bytes of HKDF(info="short-code") as big-endian uint32 mod
// 10^6, zero-padded to 6 digits. The modulo of a 32-bit uniform value
// steals < 2^-10 of bias; irrelevant for a human-compare code.
func PairingCode(shared []byte) string {
	v := binary.BigEndian.Uint32(pairingHKDF(shared, "short-code", 4))
	return fmt.Sprintf("%06d", v%1000000)
}

// PairingVKKey derives the 32-byte key that wraps the VK for transfer.
func PairingVKKey(shared []byte) []byte {
	return pairingHKDF(shared, "vk-transfer", SubkeyBytes)
}

// SealPairingVK wraps the vault key of the given epoch for the new device:
// envelope v1 under PairingVKKey(shared), AAD "pairing/<pairing_id>",
// payload = VK. nonce may be nil (random, production) or fixed (vectors).
func SealPairingVK(shared []byte, pairingID string, vk []byte, epoch uint32, nonce []byte) ([]byte, error) {
	return SealEnvelope(PairingVKKey(shared), epoch, "pairing", pairingID, vk, false, nonce)
}

// OpenPairingVK unwraps the VK transferred during pairing.
func OpenPairingVK(shared []byte, pairingID string, env []byte) (vk []byte, epoch uint32, err error) {
	payload, epoch, version, err := OpenEnvelope(PairingVKKey(shared), env, "pairing", pairingID)
	if err != nil {
		return nil, 0, err
	}
	if version != EnvelopeRaw {
		return nil, 0, fmt.Errorf("simclient: pairing: vk envelope must be version 1, got 0x%02x", version)
	}
	if len(payload) != EntropyBytes {
		return nil, 0, fmt.Errorf("simclient: pairing: vk must be %d bytes, has %d", EntropyBytes, len(payload))
	}
	return payload, epoch, nil
}
