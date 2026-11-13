package simclient

import (
	"crypto/rand"
	"errors"
	"fmt"

	"golang.org/x/crypto/chacha20poly1305"
)

// AeadNonceLen is the XChaCha20-Poly1305 nonce size: 24 random bytes
// (collision-safe without a counter, spec/crypto.md §AEAD).
const AeadNonceLen = chacha20poly1305.NonceSizeX

// AeadTagLen is the Poly1305 tag size appended to every ciphertext.
const AeadTagLen = chacha20poly1305.Overhead

// ErrOpen heans the ciphertext failed authentication: wrong key, wrong AAD,
// tampered bytes or a peer from another key epoch.
var ErrOpen = errors.New("simclient: aead: authentication failed")

// Sealed is a key+ciphertext pair: everything AEAD-level code returns.
type Sealed struct {
	Nonce      []byte // 24 random bytes
	Ciphertext []byte // plaintext || tag
}

// Seal encrypts plaintext with XChaCha20-Poly1305 under a 32-byte key,
// binding the AAD. Produces a fresh random 24-byte nonce.
func Seal(key, plaintext, aad []byte) (Sealed, error) {
	a, err := chacha20poly1305.NewX(key)
	if err != nil {
		return Sealed{}, fmt.Errorf("simclient: aead: %w", err)
	}
	nonce := make([]byte, AeadNonceLen)
	if _, err := rand.Read(nonce); err != nil {
		return Sealed{}, fmt.Errorf("simclient: aead: nonce: %w", err)
	}
	return Sealed{Nonce: nonce, Ciphertext: a.Seal(nil, nonce, plaintext, aad)}, nil
}

// SealWithNonce is Seal with a caller-chosen nonce (deterministic vectors in
// spec/fixtures/crypto; production code always uses Seal's random nonce).
func SealWithNonce(key, nonce, plaintext, aad []byte) ([]byte, error) {
	a, err := chacha20poly1305.NewX(key)
	if err != nil {
		return nil, fmt.Errorf("simclient: aead: %w", err)
	}
	return a.Seal(nil, nonce, plaintext, aad), nil
}

// Open authenticates and decrypts ciphertext+tag produced by Seal.
func Open(key, nonce, ciphertext, aad []byte) ([]byte, error) {
	a, err := chacha20poly1305.NewX(key)
	if err != nil {
		return nil, fmt.Errorf("simclient: aead: %w", err)
	}
	plain, err := a.Open(nil, nonce, ciphertext, aad)
	if err != nil {
		return nil, ErrOpen
	}
	return plain, nil
}
