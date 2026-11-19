package main

import (
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"fmt"
	"net"
	"net/url"
	"strings"
	"time"

	"yaop-server/internal/simclient"
)

// setupQrPayload builds the first-device setup QR (spec/crypto.md §First device) for the
// server's public URL. For https it reads the certificate that URL really serves (the proxy's,
// not this process's) so the phone can pin it; plain http carries no fingerprint.
func setupQrPayload(publicURL, masterToken string) (string, error) {
	u, err := url.Parse(strings.TrimRight(strings.TrimSpace(publicURL), "/"))
	if err != nil || u.Host == "" || (u.Scheme != "https" && u.Scheme != "http") {
		return "", fmt.Errorf("-public-url must be the http(s) URL devices use, e.g. https://yaop.example.net")
	}
	fp := simclient.NoTLSFingerprint
	if u.Scheme == "https" {
		if fp, err = spkiFingerprint(u.Host, &tls.Config{}); err != nil {
			return "", err
		}
	}
	return simclient.EncodeSetupQr(simclient.SetupQr{ServerURL: u.String(), TlsFP: fp, Token: masterToken})
}

// spkiFingerprint is the lowercase-hex SHA-256 of the served certificate's SPKI DER. The chain
// is verified as the phone will verify it (cfg only swaps roots in tests).
func spkiFingerprint(host string, cfg *tls.Config) (string, error) {
	if _, _, err := net.SplitHostPort(host); err != nil {
		host = net.JoinHostPort(host, "443")
	}
	conn, err := tls.DialWithDialer(&net.Dialer{Timeout: 8 * time.Second}, "tcp", host, cfg)
	if err != nil {
		return "", fmt.Errorf("could not read the TLS certificate of %s: %w", host, err)
	}
	defer conn.Close()
	sum := sha256.Sum256(conn.ConnectionState().PeerCertificates[0].RawSubjectPublicKeyInfo)
	return hex.EncodeToString(sum[:]), nil
}
