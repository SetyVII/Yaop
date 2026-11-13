package crypto

import (
	"bytes"
	"sync"
	"testing"
)

func TestVault_EncryptDecrypt(t *testing.T) {
	key, err := GenerateRandomKey()
	if err != nil {
		t.Fatalf("GenerateRandomKey failed: %v", err)
	}

	vault, err := NewVault(key)
	if err != nil {
		t.Fatalf("NewVault failed: %v", err)
	}

	plaintext := []byte("YAOP Knowledge Base Note with Markdown & LaTeX: $E=mc^2$")
	aad := "object/note_12345"

	ciphertext, err := vault.Encrypt(plaintext, aad)
	if err != nil {
		t.Fatalf("Encrypt failed: %v", err)
	}

	if len(ciphertext) <= len(plaintext)+NonceSize {
		t.Errorf("Ciphertext too short: got %d bytes", len(ciphertext))
	}

	decrypted, err := vault.Decrypt(ciphertext, aad)
	if err != nil {
		t.Fatalf("Decrypt failed: %v", err)
	}

	if !bytes.Equal(plaintext, decrypted) {
		t.Errorf("Decrypted text does not match original: got %q, want %q", string(decrypted), string(plaintext))
	}
}

func TestVault_AADIntegrity(t *testing.T) {
	key, _ := GenerateRandomKey()
	vault, _ := NewVault(key)

	plaintext := []byte("Sensitive content")
	ciphertext, _ := vault.Encrypt(plaintext, "asset/img_001")

	// Decrypting with wrong AAD must fail
	_, err := vault.Decrypt(ciphertext, "asset/img_002")
	if err == nil {
		t.Errorf("Expected decryption to fail with mismatched AAD, but succeeded")
	}

	// Tampered ciphertext must fail
	tampered := make([]byte, len(ciphertext))
	copy(tampered, ciphertext)
	tampered[len(tampered)-1] ^= 0xFF

	_, err = vault.Decrypt(tampered, "asset/img_001")
	if err == nil {
		t.Errorf("Expected decryption to fail with corrupted tag, but succeeded")
	}
}

func TestVault_ConcurrentUsage(t *testing.T) {
	key, _ := GenerateRandomKey()
	vault, _ := NewVault(key)

	var wg sync.WaitGroup
	workers := 30
	iterations := 100

	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func(workerID int) {
			defer wg.Done()
			data := []byte("Concurrent payload for worker")
			aad := "test/concurrency"

			for j := 0; j < iterations; j++ {
				enc, err := vault.Encrypt(data, aad)
				if err != nil {
					t.Errorf("worker %d encrypt failed: %v", workerID, err)
					return
				}
				dec, err := vault.Decrypt(enc, aad)
				if err != nil {
					t.Errorf("worker %d decrypt failed: %v", workerID, err)
					return
				}
				if !bytes.Equal(data, dec) {
					t.Errorf("worker %d data mismatch", workerID)
					return
				}
			}
		}(i)
	}

	wg.Wait()
}
