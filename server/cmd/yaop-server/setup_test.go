package main

import (
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"yaop-server/internal/simclient"
)

func TestSetupQrPayloadPlainHTTP(t *testing.T) {
	got, err := setupQrPayload("http://192.168.1.20:8765/", "master-token")
	if err != nil {
		t.Fatal(err)
	}
	q, err := simclient.DecodeSetupQr(got)
	if err != nil || q.ServerURL != "http://192.168.1.20:8765" || q.TlsFP != simclient.NoTLSFingerprint || q.Token != "master-token" {
		t.Fatalf("payload %s (%v)", got, err)
	}
	for _, bad := range []string{"", "yaop.example.net", "ftp://x"} {
		if _, err := setupQrPayload(bad, "t"); err == nil {
			t.Errorf("%q accepted as public url", bad)
		}
	}
}

func TestSpkiFingerprintReadsTheServedCertificate(t *testing.T) {
	srv := httptest.NewTLSServer(http.NotFoundHandler())
	defer srv.Close()
	roots := x509.NewCertPool()
	roots.AddCert(srv.Certificate())
	got, err := spkiFingerprint(strings.TrimPrefix(srv.URL, "https://"), &tls.Config{RootCAs: roots, ServerName: "example.com"})
	if err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(srv.Certificate().RawSubjectPublicKeyInfo)
	if got != hex.EncodeToString(sum[:]) {
		t.Fatalf("fingerprint %s", got)
	}
	// An unverifiable chain fails, as it will on the phone.
	if _, err := spkiFingerprint(strings.TrimPrefix(srv.URL, "https://"), &tls.Config{}); err == nil {
		t.Fatal("self-signed certificate accepted")
	}
}
