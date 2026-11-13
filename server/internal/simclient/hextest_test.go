package simclient

import (
	"crypto/sha256"
	"encoding/hex"
	"strings"
)

func hx(s string) []byte {
	b, err := hex.DecodeString(s)
	if err != nil {
		panic(err)
	}
	return b
}

func he(b []byte) string { return hex.EncodeToString(b) }

func sha256Hex(b []byte) string {
	s := sha256.Sum256(b)
	return hex.EncodeToString(s[:])
}

func fields(s string) []string { return strings.Fields(s) }
