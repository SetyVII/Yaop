package api

import (
	"bytes"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"yaop-server/internal/crypto"
	"yaop-server/internal/store"
)

func setupTestServer(t *testing.T) (*Server, *store.Store, string, func()) {
	t.Helper()
	return setupTestServerNamed(t, "test-server-id")
}

func setupTestServerNamed(t *testing.T, vaultID string) (*Server, *store.Store, string, func()) {
	t.Helper()
	tmpDir, err := os.MkdirTemp("", "yaop-api-test-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}

	rawKey := make([]byte, 32)
	for i := range rawKey {
		rawKey[i] = byte(i + 1)
	}
	vault, err := crypto.NewVault(rawKey)
	if err != nil {
		t.Fatalf("failed to create vault: %v", err)
	}

	dbPath := filepath.Join(tmpDir, "store.db")
	st, err := store.Open(dbPath, vault)
	if err != nil {
		t.Fatalf("failed to open store: %v", err)
	}

	bearerToken := "supersecretbearertokenwiththirtytwocharsmin"
	server := NewServer(st, vaultID, bearerToken, nil, "FINGERPRINT123")

	cleanup := func() {
		st.Close()
		os.RemoveAll(tmpDir)
	}

	return server, st, bearerToken, cleanup
}

func doRequest(t *testing.T, handler http.Handler, method, path, token string, body []byte, contentType string) *httptest.ResponseRecorder {
	t.Helper()
	var bodyReader io.Reader
	if body != nil {
		bodyReader = bytes.NewReader(body)
	}
	req := httptest.NewRequest(method, path, bodyReader)
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	return rec
}

// enroll registers a device with the master token and returns its token.
func enroll(t *testing.T, h http.Handler, master, deviceID string) string {
	t.Helper()
	body, _ := json.Marshal(map[string]string{"device_id": deviceID})
	rec := doRequest(t, h, "POST", "/v1/devices/enroll", master, body, "application/json")
	if rec.Code != http.StatusOK {
		t.Fatalf("enroll %s: %d %s", deviceID, rec.Code, rec.Body.String())
	}
	var out struct {
		Token string `json:"token"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("enroll %s: %v", deviceID, err)
	}
	return out.Token
}

// newOp builds a spec-shaped op with an envelope-shaped blob (never decoded by
// the server, but indistinguishable from a real one in transit).
func newOp(deviceID, kind, wireID string, payload []byte, epoch uint32) map[string]any {
	if epoch == 0 {
		epoch = 1
	}
	return map[string]any{
		"op_id":     newHex32(),
		"device_id": deviceID,
		"kind":      kind,
		"wire_id":   wireID,
		"key_epoch": epoch,
		"blob":      base64.StdEncoding.EncodeToString(payload),
	}
}

func newHex32() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func pushOps(t *testing.T, h http.Handler, token string, ops []map[string]any) map[string]any {
	t.Helper()
	body, _ := json.Marshal(map[string]any{"ops": ops})
	rec := doRequest(t, h, "POST", "/v1/sync/push", token, body, "application/json")
	var out map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("push: %d %s", rec.Code, rec.Body.String())
	}
	if rec.Code != http.StatusOK {
		return map[string]any{"_status": float64(rec.Code), "_body": out}
	}
	return out
}

func pullSince(t *testing.T, h http.Handler, token string, since uint64, limit int) (int, map[string]any) {
	t.Helper()
	req := map[string]any{"since_seq": since}
	if limit > 0 {
		req["limit"] = limit
	}
	body, _ := json.Marshal(req)
	rec := doRequest(t, h, "POST", "/v1/sync/pull", token, body, "application/json")
	var out map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &out)
	return rec.Code, out
}

func errorBody(t *testing.T, rec *httptest.ResponseRecorder) map[string]any {
	t.Helper()
	var out map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("decode error body %q: %v", rec.Body.String(), err)
	}
	return out["error"].(map[string]any)
}

func TestHealthCheck(t *testing.T) {
	server, _, _, cleanup := setupTestServer(t)
	defer cleanup()
	h := server.Handler()

	rec := doRequest(t, h, "GET", "/health", "", nil, "")
	if rec.Code != http.StatusOK {
		t.Fatalf("health: %d", rec.Code)
	}
	var health map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &health); err != nil {
		t.Fatal(err)
	}
	if health["protocol_version"] != "1" {
		t.Fatalf("protocol_version: %v", health["protocol_version"])
	}
	if _, ok := health["objects"]; ok {
		t.Fatal("the blind server must not count objects it cannot see")
	}
}

func TestAuthProtection(t *testing.T) {
	server, _, master, cleanup := setupTestServer(t)
	defer cleanup()
	h := server.Handler()
	device := enroll(t, h, master, "dev-a")

	// No token: 401 with the sync error schema.
	rec := doRequest(t, h, "GET", "/v1/sync/status", "", nil, "")
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("no token: %d", rec.Code)
	}
	errObj := errorBody(t, rec)
	if errObj["code"] != "unauthorized" {
		t.Fatalf("401 body: %v", errObj)
	}

	// /v1 endpoints need authentication like everything else.
	rec = doRequest(t, h, "POST", "/v1/sync/pull", "", []byte(`{"since_seq":0}`), "application/json")
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("pull without token: %d", rec.Code)
	}
	_ = device

	// A revoked device token stops working.
	rec = doRequest(t, h, "DELETE", "/v1/devices/dev-a", master, nil, "")
	if rec.Code != http.StatusOK {
		t.Fatalf("revoke: %d", rec.Code)
	}
	rec = doRequest(t, h, "GET", "/v1/sync/status", device, nil, "")
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("revoked token: %d", rec.Code)
	}
}

func TestSyncStatusReportsBlindProtocolState(t *testing.T) {
	server, _, master, cleanup := setupTestServerNamed(t, "vault_test")
	defer cleanup()
	h := server.Handler()
	token := enroll(t, h, master, "dev_tablet")

	for _, path := range []string{"/v1/sync/status", "/v1/sync/status"} {
		rec := doRequest(t, h, "GET", path, token, nil, "")
		if rec.Code != http.StatusOK {
			t.Fatalf("%s: %d %s", path, rec.Code, rec.Body.String())
		}
		var status map[string]any
		if err := json.Unmarshal(rec.Body.Bytes(), &status); err != nil {
			t.Fatal(err)
		}
		if status["protocol_version"] != "1" || status["vault"] != "vault_test" {
			t.Fatalf("%s: protocol=%v vault=%v", path, status["protocol_version"], status["vault"])
		}
		if status["device_id"] != "dev_tablet" {
			t.Fatalf("%s: device_id=%v", path, status["device_id"])
		}
		if v, ok := status["head_seq"].(float64); !ok || v != 0 {
			t.Fatalf("%s: head_seq=%v", path, status["head_seq"])
		}
		if status["compact_floor"].(float64) != 0 {
			t.Fatalf("%s: compact_floor=%v", path, status["compact_floor"])
		}
		if status["bootstrap_seq"] != nil {
			t.Fatalf("%s: bootstrap_seq must be null before any bootstrap: %v", path, status["bootstrap_seq"])
		}
	}
}

func TestSyncPushPullLifecycle(t *testing.T) {
	server, _, master, cleanup := setupTestServer(t)
	defer cleanup()
	h := server.Handler()
	tokenA := enroll(t, h, master, "dev_tablet")
	tokenB := enroll(t, h, master, "dev_pc")

	ops := []map[string]any{
		newOp("dev_tablet", "obj", "6f85020f85506181e573ce22c245d578", []byte("envelope-1"), 1),
		newOp("dev_tablet", "type", "a872162d777ca2d56908fffe5773a781", []byte("envelope-2"), 1),
		newOp("dev_tablet", "asset_meta", "417794695851bd508d25f135e539b56a", []byte("envelope-3"), 1),
	}
	out := pushOps(t, h, tokenA, ops)
	if out["head_seq"].(float64) != 3 {
		t.Fatalf("head after 3 ops: %v", out["head_seq"])
	}
	results := out["results"].([]any)
	if len(results) != 3 {
		t.Fatalf("results: %v", results)
	}
	for i, r := range results {
		m := r.(map[string]any)
		if m["seq"].(float64) != float64(i+1) {
			t.Fatalf("op %d seq: %v", i, m)
		}
	}

	// Another device pulls from zero and sees everything, ascending, no dedup.
	code, pull := pullSince(t, h, tokenB, 0, 0)
	if code != http.StatusOK {
		t.Fatalf("pull: %d %v", code, pull)
	}
	list := pull["ops"].([]any)
	if len(list) != 3 {
		t.Fatalf("pulled %d ops", len(list))
	}
	for i, o := range list {
		m := o.(map[string]any)
		if m["seq"].(float64) != float64(i+1) {
			t.Fatalf("pull order: %v", m)
		}
		// The op of the stream and pull is EXACTLY the shape spec/sync.md fixes.
		for _, field := range []string{"seq", "op_id", "device_id", "kind", "wire_id", "key_epoch", "blob", "received_at"} {
			if _, ok := m[field]; !ok {
				t.Fatalf("op missing %s: %v", field, m)
			}
		}
	}
	if pull["has_more"].(bool) {
		t.Fatal("has_more with the whole log delivered")
	}

	// Same-saved object arrives 3 times: pull never dedups by wire_id.
	more := []map[string]any{newOp("dev_tablet", "obj", "6f85020f85506181e573ce22c245d578", []byte("envelope-1b"), 1)}
	out = pushOps(t, h, tokenA, more)
	results = out["results"].([]any)
	if results[0].(map[string]any)["seq"].(float64) != 4 {
		t.Fatalf("seq after head 3: %v", results)
	}
	_, pull2 := pullSince(t, h, tokenB, 0, 0)
	if len(pull2["ops"].([]any)) != 4 {
		t.Fatalf("the log must keep every push: %v", pull2)
	}

	// Incremental pull from 3 sees only the new op.
	_, pull3 := pullSince(t, h, tokenB, 3, 0)
	list = pull3["ops"].([]any)
	if len(list) != 1 || list[0].(map[string]any)["seq"].(float64) != 4 {
		t.Fatalf("pull since 3: %v", list)
	}
}

func TestSyncPushDeviceAndSchemaGuards(t *testing.T) {
	server, st, master, cleanup := setupTestServer(t)
	defer cleanup()
	h := server.Handler()
	tokenA := enroll(t, h, master, "dev_tablet")
	_ = enroll(t, h, master, "dev_pc") // exists: impersonating it must fail

	// op.device_id must match the token's device.
	wrong := newOp("dev_pc", "obj", "w", []byte("x"), 1)
	body, _ := json.Marshal(map[string]any{"ops": []map[string]any{wrong}})
	rec := doRequest(t, h, "POST", "/v1/sync/push", tokenA, body, "application/json")
	if rec.Code != http.StatusForbidden {
		t.Fatalf("device impersonation: %d %s", rec.Code, rec.Body.String())
	}
	errObj := errorBody(t, rec)
	if errObj["code"] != "forbidden_device" {
		t.Fatalf("impersonation code: %v", errObj)
	}

	// The master token cannot push ops (it is not a device).
	op := newOp("dev_pc", "obj", "w", []byte("x"), 1)
	body, _ = json.Marshal(map[string]any{"ops": []map[string]any{op}})
	rec = doRequest(t, h, "POST", "/v1/sync/push", master, body, "application/json")
	if rec.Code != http.StatusForbidden || errorBody(t, rec)["code"] != "forbidden_device" {
		t.Fatalf("master push: %d %s", rec.Code, rec.Body.String())
	}

	// Unknown kind → 400 bad_kind (exact code/schema of the spec).
	badKind := newOp("dev_tablet", "search_index", "w", []byte("x"), 1)
	body, _ = json.Marshal(map[string]any{"ops": []map[string]any{badKind}})
	rec = doRequest(t, h, "POST", "/v1/sync/push", tokenA, body, "application/json")
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("bad kind status: %d %s", rec.Code, rec.Body.String())
	}
	errObj = errorBody(t, rec)
	if errObj["code"] != "bad_kind" {
		t.Fatalf("bad kind code: %v", errObj)
	}

	// op_id must be 32 chars.
	badID := newOp("dev_tablet", "obj", "w", []byte("x"), 1)
	badID["op_id"] = "short"
	body, _ = json.Marshal(map[string]any{"ops": []map[string]any{badID}})
	rec = doRequest(t, h, "POST", "/v1/sync/push", tokenA, body, "application/json")
	if rec.Code != http.StatusBadRequest || errorBody(t, rec)["code"] != "bad_request" {
		t.Fatalf("bad op_id: %d %s", rec.Code, rec.Body.String())
	}

	// All six valid kinds go through.
	ops := make([]map[string]any, 0, 6)
	for _, kind := range []string{"obj", "type", "asset_meta", "derived", "snapshot", "node_request"} {
		ops = append(ops, newOp("dev_tablet", kind, "w"+kind, []byte("k"+kind), 1))
	}
	out := pushOps(t, h, tokenA, ops)
	if out["head_seq"].(float64) != 6 {
		t.Fatalf("six kinds: %v", out)
	}

	// A push above the limit says so with the exact spec count.
	tooMany := make([]map[string]any, 0, 501)
	for i := 0; i < 501; i++ {
		tooMany = append(tooMany, tooManyOp("dev_tablet", i))
	}
	body, _ = json.Marshal(map[string]any{"ops": tooMany})
	rec = doRequest(t, h, "POST", "/v1/sync/push", tokenA, body, "application/json")
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("too many ops: %d", rec.Code)
	}
	errObj = errorBody(t, rec)
	if errObj["code"] != "bad_request" || errObj["message"] != "push carries 501 ops, max 500" {
		t.Fatalf("too many ops body: %v", errObj)
	}

	// 64 MiB request guard → 413.
	huge := make([]byte, int(maxPushRequestBytes)+1024)
	rec = doRequest(t, h, "POST", "/v1/sync/push", tokenA, huge, "application/json")
	if rec.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("oversized push: %d", rec.Code)
	}
	_ = st
}

func tooManyOp(deviceID string, i int) map[string]any {
	return map[string]any{
		"op_id":     fmt.Sprintf("%032d", i+1),
		"device_id": deviceID,
		"kind":      "obj",
		"wire_id":   "w",
		"key_epoch": 1,
		"blob":      "eA==",
	}
}

func TestSyncBlobSizeLimits(t *testing.T) {
	server, _, master, cleanup := setupTestServer(t)
	defer cleanup()
	h := server.Handler()
	token := enroll(t, h, master, "dev_tablet")

	big := base64.StdEncoding.EncodeToString(make([]byte, 33<<20))
	op := map[string]any{"op_id": newHex32(), "device_id": "dev_tablet", "kind": "obj",
		"wire_id": "w", "key_epoch": 1, "blob": big}
	body, _ := json.Marshal(map[string]any{"ops": []map[string]any{op}})
	rec := doRequest(t, h, "POST", "/v1/sync/push", token, body, "application/json")
	if rec.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("32 MiB guard: %d %s", rec.Code, rec.Body.String())
	}
	errObj := errorBody(t, rec)
	if errObj["code"] != "too_large" {
		t.Fatalf("too large code: %v", errObj)
	}

	// The same size travels fine as a snapshot (256 MiB allowance).
	op["kind"] = "snapshot"
	body, _ = json.Marshal(map[string]any{"ops": []map[string]any{op}})
	rec = doRequest(t, h, "POST", "/v1/sync/push", token, body, "application/json")
	if rec.Code != http.StatusOK {
		t.Fatalf("snapshot over 32 MiB must be accepted: %d %s", rec.Code, rec.Body.String())
	}

	// Non-base64 blob → 400.
	op2 := map[string]any{"op_id": newHex32(), "device_id": "dev_tablet", "kind": "obj",
		"wire_id": "w", "key_epoch": 1, "blob": "¿清楚?"}
	body, _ = json.Marshal(map[string]any{"ops": []map[string]any{op2}})
	rec = doRequest(t, h, "POST", "/v1/sync/push", token, body, "application/json")
	if rec.Code != http.StatusBadRequest || errorBody(t, rec)["code"] != "bad_request" {
		t.Fatalf("bad blob: %d %s", rec.Code, rec.Body.String())
	}
}
