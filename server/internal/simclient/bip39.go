// Package simclient is the Go reference implementation of the cross-platform
// YAOP contracts: spec/crypto.md (vault keys, HKDF domains, XChaCha20-
// Poly1305, blinded ids, envelopes, pairing) and the client side of
// spec/sync.md. Mobile (Kotlin) and desktop (C++) port it file by file; it
// never ships inside the server binary — blind server keeps it out of cmd/ and uses
// it from tests only .
package simclient

import (
	"crypto/sha256"
	"crypto/sha512"
	"fmt"
	"strings"

	"golang.org/x/crypto/pbkdf2"
)

// ---------------------------------------------------------------------------
// BIP39 (spec/crypto.md §BIP39): 24 words / 256-bit entropy. The Vault Key IS
// the entropy; there is NO passphrase ("mnemonic" alone is the PBKDF2 salt).
// ---------------------------------------------------------------------------

// Bip39Error classifies invalid recovery phrases.
type Bip39Error struct {
	Reason string
}

func (e *Bip39Error) Error() string { return "bip39: " + e.Reason }

var errBip39Wordlist = &Bip39Error{Reason: "wordlist must have exactly 2048 sorted unique words"}

// Bip39 holds a loaded wordlist. Use NewBip39 with the official English list
// (spec/fixtures/crypto/bip39_english.txt).
type Bip39 struct {
	words []string
	index map[string]int
}

// NewBip39 builds the codec from the 2048-word standard list. Any deviation
// (count, unsorted, duplicates) is rejected so a broken wordlist never
// silently garbles keys.
func NewBip39(words []string) (*Bip39, error) {
	if len(words) != 2048 {
		return nil, errBip39Wordlist
	}
	b := &Bip39{words: append([]string(nil), words...), index: make(map[string]int, 2048)}
	for i, w := range b.words {
		if i > 0 && b.words[i-1] >= w {
			return nil, errBip39Wordlist
		}
		if _, dup := b.index[w]; dup {
			return nil, errBip39Wordlist
		}
		b.index[w] = i
	}
	return b, nil
}

// EntropyWords is the fixed word count of the YAOP recovery phrase.
const EntropyWords = 24

// EntropyBytes is the VK size: 256 bits.
const EntropyBytes = 32

// MnemonicToEntropy decodes a 24-word phrase into its 256-bit entropy,
// validating the 8-bit SHA-256 checksum. Words are matched exactly (the
// English list is ASCII); an unknown word is an error, not a best effort.
func (b *Bip39) MnemonicToEntropy(phrase string) ([]byte, error) {
	parts := strings.Fields(phrase)
	if len(parts) != EntropyWords {
		return nil, &Bip39Error{Reason: fmt.Sprintf("phrase must have %d words, has %d", EntropyWords, len(parts))}
	}
	// 24 words x 11 bits = 264 bits = 32 entropy bytes + 1 checksum byte.
	bitLen := EntropyWords * 11
	bits := make([]bool, 0, bitLen)
	for i, w := range parts {
		idx, ok := b.index[w]
		if !ok {
			return nil, &Bip39Error{Reason: fmt.Sprintf("word %d (%q) not in the list", i+1, w)}
		}
		for k := 10; k >= 0; k-- {
			bits = append(bits, idx&(1<<uint(k)) != 0)
		}
	}
	entropy := make([]byte, EntropyBytes)
	for i := 0; i < EntropyBytes*8; i++ {
		if bits[i] {
			entropy[i/8] |= 1 << uint(7-i%8)
		}
	}
	var checksum byte
	for i := 0; i < 8; i++ {
		if bits[EntropyBytes*8+i] {
			checksum |= 1 << uint(7-i)
		}
	}
	sum := sha256.Sum256(entropy)
	if sum[0] != checksum {
		return nil, &Bip39Error{Reason: fmt.Sprintf("checksum mismatch: got %02x want %02x", checksum, sum[0])}
	}
	return entropy, nil
}

// EntropyToMnemonic encodes 256-bit entropy into its 24-word phrase.
func (b *Bip39) EntropyToMnemonic(entropy []byte) (string, error) {
	if len(entropy) != EntropyBytes {
		return "", &Bip39Error{Reason: fmt.Sprintf("entropy must be %d bytes, has %d", EntropyBytes, len(entropy))}
	}
	sum := sha256.Sum256(entropy)
	payload := append(append([]byte(nil), entropy...), sum[0])
	var sb strings.Builder
	for i := 0; i < EntropyWords; i++ {
		idx := 0
		for k := 0; k < 11; k++ {
			bitPos := i*11 + k
			if payload[bitPos/8]&(1<<uint(7-bitPos%8)) != 0 {
				idx |= 1 << uint(10-k)
			}
		}
		if i > 0 {
			sb.WriteByte(' ')
		}
		sb.WriteString(b.words[idx])
	}
	return sb.String(), nil
}

// Seed derives the standard BIP39 seed (PBKDF2-HMAC-SHA512, 2048 iterations,
// salt "mnemonic", no passphrase). The vault does NOT use it as key material
// (the VK is the entropy itself); it is provided for compatibility checks and
// future interoperable backups.
func Seed(phrase string) []byte {
	return pbkdf2.Key([]byte(phrase), []byte("mnemonic"), 2048, 64, sha512.New)
}
