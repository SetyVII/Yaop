package simclient

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
)

// The server is blind: object ids carry meaning (daily-2026-11-09,
// tag_mi_tema, media_<asset>) and spec §15.1 forbids it from seeing them.
// On the wire each real id travels as:
//
//	wire_id = hex(HMAC-SHA256(key = subkey "ids", message = real id UTF-8))[0:32]
//
// which is the first 16 bytes of the HMAC as lowercase hex (spec/crypto.md
// §Ids ciegos) — deterministic, so two offline devices that create the same
// daily note use the same wire_id.

// WireID blinds a real object id.
func WireID(idsKey []byte, realID string) string {
	mac := hmac.New(sha256.New, idsKey)
	mac.Write([]byte(realID))
	return hex.EncodeToString(mac.Sum(nil))[:32]
}

// DerivedBlindInput is the HMAC message for a derived artifact of an object:
// blinding the composite key itself (derived/<objectId>/<kind>) keeps both
// the object id and the artifact kind invisible on the wire.
func DerivedBlindInput(objectID, kind string) string {
	return "derived/" + objectID + "/" + kind
}

// DerivedWireID blinds the pair (objectId, kind) of a derived/ artifact.
func DerivedWireID(idsKey []byte, objectID, kind string) string {
	return WireID(idsKey, DerivedBlindInput(objectID, kind))
}
