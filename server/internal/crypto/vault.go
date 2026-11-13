package crypto

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"errors"
	"fmt"
	"io"
)

const (
	KeySize   = 32 // AES-256
	NonceSize = 12 // GCM Standard 96-bit nonce
	TagSize   = 16 // GCM 128-bit authentication tag
)

var (
	ErrInvalidKeySize     = errors.New("crypto: encryption key must be exactly 32 bytes for AES-256")
	ErrCiphertextTooShort = errors.New("crypto: ciphertext payload too short to contain nonce and tag")
	ErrDecryptionFailed   = errors.New("crypto: authentication tag mismatch or corrupted ciphertext")
)

// Vault handles authenticated encryption and decryption using AES-256-GCM,
// completely wire-compatible with AndroidKeyStoreVault and EncryptedMediaStore.
type Vault struct {
	aead cipher.AEAD
}

// NewVault initializes an AES-256-GCM vault with a 32-byte secret key.
func NewVault(key []byte) (*Vault, error) {
	if len(key) != KeySize {
		return nil, ErrInvalidKeySize
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, fmt.Errorf("crypto: failed to create AES block cipher: %w", err)
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, fmt.Errorf("crypto: failed to initialize GCM mode: %w", err)
	}

	return &Vault{
		aead: aead,
	}, nil
}

// Encrypt encrypts plaintext using AES-256-GCM with associated authenticated data (AAD).
// Output format: Nonce (12 bytes) || Ciphertext || Auth Tag (16 bytes).
func (v *Vault) Encrypt(plaintext []byte, aad string) ([]byte, error) {
	nonce := make([]byte, NonceSize, NonceSize+len(plaintext)+TagSize)
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return nil, fmt.Errorf("crypto: failed to generate random nonce: %w", err)
	}

	// aead.Seal appends ciphertext + auth tag to nonce
	ciphertext := v.aead.Seal(nonce, nonce, plaintext, []byte(aad))
	return ciphertext, nil
}

// Decrypt decrypts an AES-256-GCM payload with the expected AAD.
// Returns an error if the AAD does not match or data has been tampered with.
func (v *Vault) Decrypt(data []byte, aad string) ([]byte, error) {
	if len(data) < NonceSize+TagSize {
		return nil, ErrCiphertextTooShort
	}

	nonce := data[:NonceSize]
	ciphertextAndTag := data[NonceSize:]

	plaintext, err := v.aead.Open(nil, nonce, ciphertextAndTag, []byte(aad))
	if err != nil {
		return nil, ErrDecryptionFailed
	}
	return plaintext, nil
}

// GenerateRandomKey generates a cryptographically secure 256-bit key.
func GenerateRandomKey() ([]byte, error) {
	key := make([]byte, KeySize)
	if _, err := io.ReadFull(rand.Reader, key); err != nil {
		return nil, fmt.Errorf("crypto: failed to generate random key: %w", err)
	}
	return key, nil
}
