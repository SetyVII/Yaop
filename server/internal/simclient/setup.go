package simclient

import (
	"encoding/hex"
	"encoding/json"
	"fmt"
	"strings"
)

// SetupQr is the first-device setup QR (spec/crypto.md §First device): the
// server operator shows it so a phone connects without typing the master
// token. Compact JSON, keys in declaration order.
type SetupQr struct {
	V         int    `json:"v"`
	ServerURL string `json:"server_url"`
	TlsFP     string `json:"tls_fp"`
	Token     string `json:"token"`
}

// NoTLSFingerprint is the tls_fp of a plain-http server (nothing to pin).
var NoTLSFingerprint = strings.Repeat("0", 64)

// EncodeSetupQr renders the setup payload as canonical compact JSON.
func EncodeSetupQr(q SetupQr) (string, error) {
	q.V = 1
	raw, err := json.Marshal(q)
	if err != nil {
		return "", fmt.Errorf("simclient: setup qr: %w", err)
	}
	return string(raw), nil
}

// DecodeSetupQr parses the setup payload strictly: version 1, exactly these
// keys, non-empty URL and token, 64 lowercase hex for tls_fp.
func DecodeSetupQr(s string) (SetupQr, error) {
	var q SetupQr
	dec := json.NewDecoder(strings.NewReader(s))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&q); err != nil {
		return SetupQr{}, fmt.Errorf("simclient: setup qr: %w", err)
	}
	if q.V != 1 {
		return SetupQr{}, fmt.Errorf("simclient: setup qr: unsupported version %d", q.V)
	}
	if q.ServerURL == "" || q.Token == "" {
		return SetupQr{}, fmt.Errorf("simclient: setup qr: incomplete payload")
	}
	if _, err := hex.DecodeString(q.TlsFP); err != nil || len(q.TlsFP) != 64 || strings.ToLower(q.TlsFP) != q.TlsFP {
		return SetupQr{}, fmt.Errorf("simclient: setup qr: tls_fp must be sha-256 lowercase hex")
	}
	return q, nil
}
