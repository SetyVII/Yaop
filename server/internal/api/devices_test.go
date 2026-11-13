package api

import (
	"encoding/json"
	"net/http"
	"testing"
)

func TestDevices_EnrollUseRevoke(t *testing.T) {
	server, _, master, cleanup := setupTestServer(t)
	defer cleanup()
	h := server.Handler()

	rec := doRequest(t, h, "POST", "/v1/devices/enroll", master, []byte(`{"device_id":"android-1","name":"Tablet"}`), "application/json")
	if rec.Code != http.StatusOK {
		t.Fatalf("enroll: %d %s", rec.Code, rec.Body.String())
	}
	var enrolled struct{ Token string }
	_ = json.Unmarshal(rec.Body.Bytes(), &enrolled)
	if len(enrolled.Token) != 64 {
		t.Fatalf("token: %q", enrolled.Token)
	}
	if rec := doRequest(t, h, "GET", "/v1/sync/status", enrolled.Token, nil, ""); rec.Code != http.StatusOK {
		t.Fatalf("device token must sync: %d", rec.Code)
	}
	if rec := doRequest(t, h, "POST", "/v1/devices/enroll", enrolled.Token, []byte(`{"device_id":"evil"}`), "application/json"); rec.Code != http.StatusForbidden {
		t.Fatalf("a device must not enroll others: %d", rec.Code)
	}
	if rec := doRequest(t, h, "DELETE", "/v1/devices/android-1", enrolled.Token, nil, ""); rec.Code != http.StatusForbidden {
		t.Fatalf("a device must not revoke: %d", rec.Code)
	}
	if rec := doRequest(t, h, "DELETE", "/v1/devices/android-1", master, nil, ""); rec.Code != http.StatusOK {
		t.Fatalf("revoke: %d", rec.Code)
	}
	if rec := doRequest(t, h, "GET", "/v1/sync/status", enrolled.Token, nil, ""); rec.Code != http.StatusUnauthorized {
		t.Fatalf("revoked token must fail: %d", rec.Code)
	}
	list := doRequest(t, h, "GET", "/v1/devices", master, nil, "")
	if list.Code != http.StatusOK || !json.Valid(list.Body.Bytes()) || containsHash(list.Body.String()) {
		t.Fatalf("list: %d %s", list.Code, list.Body.String())
	}
}

func TestDevices_ReEnrollRotatesTokenAndSurvivesRestart(t *testing.T) {
	server, st, master, cleanup := setupTestServer(t)
	defer cleanup()
	h := server.Handler()
	var a, b struct{ Token string }
	_ = json.Unmarshal(doRequest(t, h, "POST", "/v1/devices/enroll", master, []byte(`{"device_id":"pc"}`), "application/json").Body.Bytes(), &a)
	_ = json.Unmarshal(doRequest(t, h, "POST", "/v1/devices/enroll", master, []byte(`{"device_id":"pc"}`), "application/json").Body.Bytes(), &b)
	if rec := doRequest(t, h, "GET", "/v1/sync/status", a.Token, nil, ""); rec.Code != http.StatusUnauthorized {
		t.Fatalf("old token must stop working after re-enroll: %d", rec.Code)
	}
	restarted := NewServer(st, "test-server-id", "supersecretbearertokenwiththirtytwocharsmin", nil, "")
	if rec := doRequest(t, restarted.Handler(), "GET", "/v1/sync/status", b.Token, nil, ""); rec.Code != http.StatusOK {
		t.Fatalf("device token must survive a restart: %d", rec.Code)
	}
}

func TestAuth_RateLimitsFailedAttempts(t *testing.T) {
	server, _, master, cleanup := setupTestServer(t)
	defer cleanup()
	h := server.Handler()
	for i := 0; i < authMaxFailures; i++ {
		if rec := doRequest(t, h, "GET", "/v1/sync/status", "wrong-token", nil, ""); rec.Code != http.StatusUnauthorized {
			t.Fatalf("attempt %d: %d", i, rec.Code)
		}
	}
	if rec := doRequest(t, h, "GET", "/v1/sync/status", "wrong-token", nil, ""); rec.Code != http.StatusTooManyRequests {
		t.Fatalf("after %d failures invalid tokens get 429: %d", authMaxFailures, rec.Code)
	}
	// Another device of the same household (same public IP) keeps working.
	if rec := doRequest(t, h, "GET", "/v1/sync/status", master, nil, ""); rec.Code != http.StatusOK {
		t.Fatalf("valid tokens must pass even from a blocked IP: %d", rec.Code)
	}
}

func containsHash(body string) bool {
	var v struct {
		Devices []map[string]any `json:"devices"`
	}
	_ = json.Unmarshal([]byte(body), &v)
	for _, d := range v.Devices {
		if h, ok := d["token_hash"].(string); ok && h != "" {
			return true
		}
	}
	return false
}
