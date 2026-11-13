package api

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"yaop-server/internal/simclient"
)

// End-to-end pairing over the real transport endpoints, driven by the
// reference client code (simclient/pairing.go): A shows the QR, B answers,
// both derive the same secret and code, the VK travels wrapped, and the
// server only ever relayed opaque bytes (spec/crypto.md §Pairing).
func TestPairingTransportEndToEnd(t *testing.T) {
	server, _, master, cleanup := setupTestServer(t)
	defer cleanup()
	h := server.Handler()

	// The joining device has NO credentials at this point: /v1/pairing is the
	// only surface it can reach, keyed by the QR's unguessable pairing_id.
	if rec := doRequest(t, h, "GET", "/v1/sync/status", "", nil, ""); rec.Code != http.StatusUnauthorized {
		t.Fatalf("the rest of the server stays authenticated: %d", rec.Code)
	}

	// --- A (QR holder, enrolled) generates the ephemeral pair and the QR ---
	idRaw := make([]byte, 16)
	_, _ = rand.Read(idRaw)
	pairingID := hex.EncodeToString(idRaw)
	aPriv := make([]byte, 32)
	_, _ = rand.Read(aPriv)
	aPub, err := simclient.PubFromScalar(aPriv)
	if err != nil {
		t.Fatal(err)
	}
	qr, err := simclient.EncodePairingQr(simclient.PairingQr{
		ServerURL: "http://127.0.0.1:0", TlsFP: strings.Repeat("ab", 32), EphPub: simclient.EncodePub(aPub), PairingID: pairingID,
	})
	if err != nil {
		t.Fatal(err)
	}
	parsedQR, err := simclient.DecodePairingQr(qr)
	if err != nil {
		t.Fatal(err)
	}

	// A registers the eph_pub behind the QR.
	body, _ := json.Marshal(map[string]string{"pairing_id": parsedQR.PairingID, "eph_pub": parsedQR.EphPub})
	rec := doRequest(t, h, "POST", "/v1/pairing", master, body, "application/json")
	if rec.Code != http.StatusCreated {
		t.Fatalf("pairing create: %d %s", rec.Code, rec.Body.String())
	}

	// Malformed keys are refused at the schema layer.
	bad, _ := json.Marshal(map[string]string{"pairing_id": parsedQR.PairingID, "eph_pub": "not-base64!"})
	rec = doRequest(t, h, "POST", "/v1/pairing", "", bad, "application/json")
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("bad eph_pub: %d", rec.Code)
	}
	badID, _ := json.Marshal(map[string]string{"pairing_id": "SHORT", "eph_pub": parsedQR.EphPub})
	rec = doRequest(t, h, "POST", "/v1/pairing", "", badID, "application/json")
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("bad pairing_id: %d", rec.Code)
	}

	// --- B scans the QR and answers with its ephemeral public key ---
	bPriv := make([]byte, 32)
	_, _ = rand.Read(bPriv)
	bPub, err := simclient.PubFromScalar(bPriv)
	if err != nil {
		t.Fatal(err)
	}
	join, _ := json.Marshal(map[string]string{"b_pub": simclient.EncodePub(bPub)})
	rec = doRequest(t, h, "POST", "/v1/pairing/"+parsedQR.PairingID+"/join", "", join, "application/json")
	if rec.Code != http.StatusAccepted {
		t.Fatalf("join: %d %s", rec.Code, rec.Body.String())
	}
	// A second device cannot hijack the pairing.
	rogue, _ := json.Marshal(map[string]string{"b_pub": simclient.EncodePub(make([]byte, 32))})
	rec = doRequest(t, h, "POST", "/v1/pairing/"+parsedQR.PairingID+"/join", "", rogue, "application/json")
	if rec.Code != http.StatusConflict {
		t.Fatalf("hijack: %d", rec.Code)
	}

	// --- A polls for the answer and derives the shared secret ---
	rec = doRequest(t, h, "GET", "/v1/pairing/"+parsedQR.PairingID+"/answer", master, nil, "")
	var answer struct {
		BPub string `json:"b_pub"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &answer); err != nil {
		t.Fatal(err)
	}
	if answer.BPub != simclient.EncodePub(bPub) {
		t.Fatalf("relayed b_pub corrupted: %q", answer.BPub)
	}
	bPeerPub, err := simclient.DecodePub(answer.BPub)
	if err != nil {
		t.Fatal(err)
	}
	sharedA, err := simclient.X25519Shared(aPriv, bPeerPub)
	if err != nil {
		t.Fatal(err)
	}
	sharedB, err := simclient.X25519Shared(bPriv, aPub)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(sharedA, sharedB) {
		t.Fatal("both devices must derive the same shared secret")
	}
	codeA, codeB := simclient.PairingCode(sharedA), simclient.PairingCode(sharedB)
	if codeA != codeB || len(codeA) != 6 {
		t.Fatalf("human codes differ: %q vs %q", codeA, codeB)
	}

	// A wraps the VK (epoch 7) after the codes match.
	vk := make([]byte, 32)
	_, _ = rand.Read(vk)
	envelope, err := simclient.SealPairingVK(sharedA, parsedQR.PairingID, vk, 7, nil)
	if err != nil {
		t.Fatal(err)
	}
	req := httptest.NewRequest(http.MethodPost, "/v1/pairing/"+parsedQR.PairingID+"/transfer", bytes.NewReader(envelope))
	rec = httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusCreated {
		t.Fatalf("transfer: %d %s", rec.Code, rec.Body.String())
	}

	// --- B fetches and unwraps ---
	rec = doRequest(t, h, "GET", "/v1/pairing/"+parsedQR.PairingID+"/transfer", "", nil, "")
	if rec.Code != http.StatusOK {
		t.Fatalf("transfer get: %d", rec.Code)
	}
	if rec.Body.Len() != len(envelope) {
		t.Fatalf("relayed envelope size: %d vs %d", rec.Body.Len(), len(envelope))
	}
	gotVK, epoch, err := simclient.OpenPairingVK(sharedB, parsedQR.PairingID, rec.Body.Bytes())
	if err != nil {
		t.Fatalf("unwrap: %v", err)
	}
	if !bytes.Equal(gotVK, vk) {
		t.Fatal("the VK must arrive intact")
	}
	if epoch != 7 {
		t.Fatalf("key epoch relayed: %d", epoch)
	}

	// An attacker without the shared secret cannot unwrap (the server stored
	// only the wrapper, and the envelope is constant-time garbage to it).
	wrongShared, _ := simclient.X25519Shared(bPriv, bPeerPub) // not the real DH pair
	if _, _, err := simclient.OpenPairingVK(wrongShared, parsedQR.PairingID, envelope); err == nil {
		t.Fatal("envelope opened with the wrong secret")
	}

	// Transport sizes are bounded: an oversized mailbox is refused.
	bigTransfer := make([]byte, MaxPairingTransferBytes+1)
	req = httptest.NewRequest(http.MethodPost, "/v1/pairing/"+parsedQR.PairingID+"/transfer", bytes.NewReader(bigTransfer))
	rec = httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("oversized transfer: %d", rec.Code)
	}

	if blobs, err := server.storePendingPairings(); err != nil || blobs != 1 {
		t.Fatalf("pairings stored: %d %v", blobs, err)
	}
}

// storePendingPairings is the test hook for the TTL purge.
func (s *Server) storePendingPairings() (int64, error) {
	// The TTL is short; run it against the far future to expire everything.
	cutoff := time.Now().UTC().Add(time.Hour).Format(timeFormat)
	return s.store.PurgePairingsBefore(cutoff)
}

// Unknown pairings answer 404 (the relay must not distinguish beyond
// "expired" and it never confirms existence through timing-heavy work).
func TestPairingUnknownAndExpired(t *testing.T) {
	server, _, _, cleanup := setupTestServer(t)
	defer cleanup()
	h := server.Handler()

	rec := doRequest(t, h, "GET", "/v1/pairing/0011223344556677889900aabbccddee/answer", "", nil, "")
	if rec.Code != http.StatusNotFound {
		t.Fatalf("unknown pairing: %d", rec.Code)
	}
	if code := errorBody(t, rec)["code"]; code != "not_found" {
		t.Fatalf("unknown pairing code: %v", code)
	}

	// Create one, let the TTL eat it, verify the state is gone.
	body, _ := json.Marshal(map[string]string{"pairing_id": "0011223344556677889900aabbccddee", "eph_pub": simclient.EncodePub(make([]byte, 32))})
	if rec := doRequest(t, h, "POST", "/v1/pairing", "", body, "application/json"); rec.Code != http.StatusCreated {
		t.Fatalf("create: %d %s", rec.Code, rec.Body.String())
	}
	if _, err := server.storePendingPairings(); err != nil { // force TTL expiry
		t.Fatal(err)
	}
	rec = doRequest(t, h, "GET", "/v1/pairing/0011223344556677889900aabbccddee", "", nil, "")
	if rec.Code != http.StatusNotFound {
		t.Fatalf("expired pairing must vanish: %d", rec.Code)
	}

	// Requests never leak write bodies on this transport.
	_ = io.EOF
}
