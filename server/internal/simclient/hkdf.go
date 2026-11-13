package simclient

import (
	"crypto/sha256"
	"io"

	"golang.org/x/crypto/hkdf"
)

// HKDF salt and subkey size fixed by spec/crypto.md §Subkeys.
const (
	// HkdfSalt is the protocol-wide HKDF salt ("yaop-vault/1").
	HkdfSalt = "yaop-vault/1"
	// SubkeyBytes is the length of every domain subkey (32 = 256 bits).
	SubkeyBytes = 32
)

// Domain names (spec/crypto.md §Subkeys): every kind of payload is sealed
// under its own subkey so a leak in one domain never crosses into another.
const (
	DomainObjects   = "objects"
	DomainOps       = "ops"
	DomainSnapshots = "snapshots"
	DomainAssets    = "assets"
	DomainThumbs    = "thumbs"
	DomainDerived   = "derived"
	DomainIds       = "ids"
)

// Subkey derives the 32-byte domain key: HKDF-SHA256(ikm = VK of the current
// key epoch, salt = "yaop-vault/1", info = "<domain>").
func Subkey(vk []byte, domain string) []byte {
	r := hkdf.New(sha256.New, vk, []byte(HkdfSalt), []byte(domain))
	out := make([]byte, SubkeyBytes)
	if _, err := io.ReadFull(r, out); err != nil {
		panic("simclient: hkdf: " + err.Error()) // only possible for len(out) > hash capacity
	}
	return out
}
