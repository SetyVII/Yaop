package main

import (
	"encoding/hex"
	"flag"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// The binary's flags: the /3 set exists, the content-era -migrate-doc2/3 are
// gone with the endpoints that read content .
func TestFlagsOfTheBlindBinary(t *testing.T) {
	fs := flag.NewFlagSet("test", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	cfg := registerFlags(fs)
	if err := fs.Parse([]string{"-init-secrets", "-data-dir", "/tmp/f0-2"}); err != nil {
		t.Fatalf("parse: %v", err)
	}
	if !cfg.initSecrets {
		t.Fatal("-init-secrets must still work")
	}
	if cfg.dataDir != "/tmp/f0-2" {
		t.Fatalf("data dir: %q", cfg.dataDir)
	}
	if cfg.runFsck || cfg.runHealth || !cfg.enableMDNS {
		t.Fatalf("defaults changed: %+v", cfg)
	}

	// The removed migration flags must fail to parse (no silent acceptance).
	fs2 := flag.NewFlagSet("test2", flag.ContinueOnError)
	fs2.SetOutput(io.Discard)
	_ = registerFlags(fs2)
	if err := fs2.Parse([]string{"-migrate-doc2", "apply"}); err == nil {
		t.Fatal("-migrate-doc2 must be gone from the blind binary")
	}
	if err := fs2.Parse([]string{"-migrate-doc3", "dry"}); err == nil {
		t.Fatal("-migrate-doc3 must be gone from the blind binary")
	}

	// The default bind stays.
	fs3 := flag.NewFlagSet("test3", flag.ContinueOnError)
	fs3.SetOutput(io.Discard)
	cfg3 := registerFlags(fs3)
	if err := fs3.Parse(nil); err != nil {
		t.Fatal(err)
	}
	if cfg3.listenAddr != "0.0.0.0:8765" {
		t.Fatalf("default listen: %q", cfg3.listenAddr)
	}
}

// -init-secrets writes the two secret files with sizes and modes promised by
// the README, and loadOrInitSecrets reads the very same values back.
func TestInitSecretsRoundTrip(t *testing.T) {
	dir := t.TempDir()
	keyPath := filepath.Join(dir, "master.key")
	tokenPath := filepath.Join(dir, "bearer.token")

	if err := generateSecrets(keyPath, tokenPath); err != nil {
		t.Fatal(err)
	}
	keyRaw, err := os.ReadFile(keyPath)
	if err != nil {
		t.Fatal(err)
	}
	tokenRaw, err := os.ReadFile(tokenPath)
	if err != nil {
		t.Fatal(err)
	}
	keyHex := strings.TrimSpace(string(keyRaw))
	if len(keyHex) != 64 { // 32 bytes of key in hex
		t.Fatalf("master.key has %d chars, want 64", len(keyHex))
	}
	if _, err := hex.DecodeString(keyHex); err != nil {
		t.Fatalf("master.key is not hex: %v", err)
	}
	token := strings.TrimSpace(string(tokenRaw))
	if len(token) != 48 { // 24 bytes of token in hex
		t.Fatalf("bearer.token has %d chars, want 48", len(token))
	}

	// loadOrInit reads them back unchanged.
	key, bearer, err := loadOrInitSecrets(dir, keyPath, tokenPath)
	if err != nil {
		t.Fatal(err)
	}
	if hex.EncodeToString(key) != keyHex {
		t.Fatal("the master key round trip broke")
	}
	if bearer != token {
		t.Fatal("the bearer token round trip broke")
	}

	// Without files it generates both and insists on a sane token length.
	dir2 := t.TempDir()
	if _, short, err := func() ([]byte, string, error) {
		return loadOrInitSecrets(dir2, filepath.Join(dir2, "master.key"), filepath.Join(dir2, "bearer.token"))
	}(); err != nil || len(short) < 32 {
		t.Fatalf("auto-init: %v token=%q", err, short)
	}
}

// The healthcheck answers the process exit code of the /health endpoint.
func TestCheckHealthExitCode(t *testing.T) {
	ok := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer ok.Close()
	if code := checkHealth(strings.TrimPrefix(ok.URL, "http://")); code != 0 {
		t.Fatalf("healthy server: %d", code)
	}

	bad := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer bad.Close()
	if code := checkHealth(strings.TrimPrefix(bad.URL, "http://")); code != 1 {
		t.Fatalf("broken server: %d", code)
	}

	if code := checkHealth("127.0.0.1:1"); code != 1 {
		t.Fatalf("unreachable server: %d", code)
	}
}

// extractPort handles the shapes the -listen flag accepts.
func TestExtractPort(t *testing.T) {
	if got := extractPort("0.0.0.0:8765"); got != 8765 {
		t.Fatalf("v4: %d", got)
	}
	if got := extractPort("[::1]:9001"); got != 9001 {
		t.Fatalf("v6: %d", got)
	}
}
