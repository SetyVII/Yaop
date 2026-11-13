package api

import (
	"encoding/json"
	"net/http"
	"testing"
)

// Pacing of spec/sync.md: limits are configurable, the rejection is 429
// rate_limited with retry_after, and a valid token never sees the IP block
// that failed attempts trigger.
func TestSyncRateLimits(t *testing.T) {
	server, _, master, cleanup := setupTestServer(t)
	defer cleanup()
	server.SetRateLimits(RateLimits{PushPerMin: 3, PullPerMin: 3, OpsPerDay: 10})
	h := server.Handler()
	token := enroll(t, h, master, "dev_tablet")
	other := enroll(t, h, master, "dev_pc")

	push := func() int {
		t.Helper()
		out := pushOps(t, h, token, []map[string]any{newOp("dev_tablet", "obj", "w", []byte("x"), 1)})
		if code, ok := out["_status"].(float64); ok {
			return int(code)
		}
		return http.StatusOK
	}
	for i := 1; i <= 3; i++ {
		if code := push(); code != http.StatusOK {
			t.Fatalf("push %d: %d", i, code)
		}
	}
	body, _ := json.Marshal(map[string]any{"ops": []map[string]any{newOp("dev_tablet", "obj", "w", []byte("x"), 1)}})
	rec := doRequest(t, h, "POST", "/v1/sync/push", token, body, "application/json")
	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("4th push: %d %s", rec.Code, rec.Body.String())
	}
	errObj := errorBody(t, rec)
	if errObj["code"] != "rate_limited" {
		t.Fatalf("rate code: %v", errObj)
	}
	if _, ok := errObj["retry_after"].(float64); !ok {
		t.Fatalf("retry_after missing: %v", errObj)
	}
	if rec.Header().Get("Retry-After") == "" {
		t.Fatal("Retry-After header missing")
	}

	// The daily op quota triggers with its own message.
	token2 := enroll(t, h, master, "dev_daily")
	server.SetRateLimits(RateLimits{PushPerMin: 1000, PullPerMin: 1000, OpsPerDay: 2})
	for i := 0; i < 2; i++ {
		if code := pushStatus(t, h, token2, "dev_daily"); code != http.StatusOK {
			t.Fatalf("daily seed push %d: %d", i, code)
		}
	}
	rec = doRequest(t, h, "POST", "/v1/sync/push", token2, pushBody(t, "dev_daily"), "application/json")
	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("daily quota: %d %s", rec.Code, rec.Body.String())
	}
	if errorBody(t, rec)["code"] != "rate_limited" {
		t.Fatalf("daily code: %v", errorBody(t, rec))
	}

	// Pull pacing — same shape.
	server.SetRateLimits(RateLimits{PushPerMin: 1000, PullPerMin: 2, OpsPerDay: 100000})
	for i := 0; i < 2; i++ {
		if code, _ := pullSince(t, h, token, 0, 0); code != http.StatusOK {
			t.Fatalf("pull %d: %d", i, code)
		}
	}
	body, _ = json.Marshal(map[string]any{"since_seq": 0})
	rec = doRequest(t, h, "POST", "/v1/sync/pull", token, body, "application/json")
	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("3rd pull: %d %s", rec.Code, rec.Body.String())
	}

	// Another device is not affected by dev_tablet's exhausted windows.
	code, _ := pullSince(t, h, other, 0, 0)
	if code != http.StatusOK {
		t.Fatalf("other device blocked: %d", code)
	}

	// Exhausted windows roll over naturally — the prune is time-based, so no
	// manual reset exists by design.
}

func pushBody(t *testing.T, deviceID string) []byte {
	t.Helper()
	body, _ := json.Marshal(map[string]any{"ops": []map[string]any{newOp(deviceID, "obj", "w", []byte("x"), 1)}})
	return body
}

func pushStatus(t *testing.T, h http.Handler, token, deviceID string) int {
	t.Helper()
	rec := doRequest(t, h, "POST", "/v1/sync/push", token, pushBody(t, deviceID), "application/json")
	return rec.Code
}
