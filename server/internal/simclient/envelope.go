package simclient

import (
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"strconv"

	"github.com/klauspost/compress/zstd"
)

// Envelope layout (spec/crypto.md §Envelope):
//
//	version(1) | key_epoch(u32 BE) | nonce(24) | ciphertext+tag
//
//	version 0x01: payload stored as-is (always valid).
//	version 0x02: payload was zstd-compressed (producers use level 3) BEFORE
//	             encrypting; only the decompressor ever sees the compressed
//	             bytes.
//
//	The AEAD AAD is exactly "<domain>/<wire_id>" (assets chunks:
//	"assets/<wire_id>/chunk/<n>", n in decimal ASCII). Flipping the version
//	byte cannot make a receiver misread data: a 0x02 envelope whose payload is
//	not a zstd frame always fails the magic-number check, and a 0x01 envelope
//	containing a frame fails the JSON/protocol parse — both fail closed.
type EnvelopeVersion byte

const (
	EnvelopeRaw       EnvelopeVersion = 0x01
	EnvelopeZstd      EnvelopeVersion = 0x02
	EnvelopeZstdLevel                 = 3 // compression level producers use for JSON payloads
)

// EnvelopeHeaderLen = version(1) + key_epoch(4) + nonce(24).
const EnvelopeHeaderLen = 1 + 4 + AeadNonceLen

// ErrEnvelope marks malformed envelopes (too short, unknown version).
var ErrEnvelope = errors.New("simclient: envelope: malformed")

// AAD builds the associated-data string for a domain object: "<domain>/<wire_id>".
func AAD(domain, wireID string) string {
	return domain + "/" + wireID
}

// ChunkAAD builds the AAD of an asset chunk: "assets/<wire_id>/chunk/<n>".
func ChunkAAD(wireID string, n int) string {
	return AAD(DomainAssets, wireID) + "/chunk/" + strconv.Itoa(n)
}

// SealEnvelope produces the full envelope bytes. With nonce == nil it uses a
// fresh random 24-byte nonce (production); tests pass a fixed nonce to get
// byte-exact vectors.
func SealEnvelope(key []byte, epoch uint32, domain, wireID string, payload []byte, compress bool, nonce []byte) ([]byte, error) {
	version := EnvelopeRaw
	if compress {
		enc, err := zstd.NewWriter(nil, zstd.WithEncoderLevel(EnvelopeZstdLevel))
		if err != nil {
			return nil, fmt.Errorf("simclient: envelope: zstd: %w", err)
		}
		payload = enc.EncodeAll(payload, nil)
		version = EnvelopeZstd
	}
	fixed := make([]byte, AeadNonceLen)
	if nonce != nil {
		if len(nonce) != AeadNonceLen {
			return nil, fmt.Errorf("%w: nonce must be %d bytes", ErrEnvelope, AeadNonceLen)
		}
		copy(fixed, nonce)
	} else {
		if _, err := rand.Read(fixed); err != nil {
			return nil, fmt.Errorf("simclient: envelope: nonce: %w", err)
		}
	}
	ct, err := SealWithNonce(key, fixed, payload, []byte(AAD(domain, wireID)))
	if err != nil {
		return nil, err
	}
	out := make([]byte, 0, EnvelopeHeaderLen+len(ct))
	out = append(out, byte(version))
	var ep [4]byte
	binary.BigEndian.PutUint32(ep[:], epoch)
	out = append(out, ep[:]...)
	out = append(out, fixed...)
	out = append(out, ct...)
	return out, nil
}

// OpenEnvelope authenticates, decrypts and (version 0x02) decompresses an
// envelope, returning the payload and its declared key epoch. The caller
// selects the epoch key; a wrong epoch fails authentication (key derivation
// differs per epoch, spec/crypto.md §Rotation).
func OpenEnvelope(key, env []byte, domain, wireID string) (payload []byte, epoch uint32, version EnvelopeVersion, err error) {
	if len(env) < EnvelopeHeaderLen+AeadTagLen {
		return nil, 0, 0, fmt.Errorf("%w: short (%d bytes)", ErrEnvelope, len(env))
	}
	version = EnvelopeVersion(env[0])
	if version != EnvelopeRaw && version != EnvelopeZstd {
		return nil, 0, 0, fmt.Errorf("%w: unknown version 0x%02x", ErrEnvelope, env[0])
	}
	epoch = binary.BigEndian.Uint32(env[1:5])
	nonce := env[5 : 5+AeadNonceLen]
	ct := env[5+AeadNonceLen:]
	plain, err := Open(key, nonce, ct, []byte(AAD(domain, wireID)))
	if err != nil {
		return nil, 0, 0, err
	}
	if version == EnvelopeZstd {
		dec, derr := zstd.NewReader(nil)
		if derr != nil {
			return nil, 0, 0, fmt.Errorf("simclient: envelope: zstd: %w", derr)
		}
		defer dec.Close()
		payload, derr = dec.DecodeAll(plain, nil)
		if derr != nil {
			return nil, 0, 0, fmt.Errorf("simclient: envelope: zstd: %w", derr)
		}
		return payload, epoch, version, nil
	}
	return plain, epoch, version, nil
}
