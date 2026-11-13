package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

// Runs spec/fixtures/sync against the REAL HTTP handlers (the executable
// model of simclient/protocol.go is the reference; this proves the server
// produces exactly the same responses). Actions map 1:1 onto the protocol:
// push/pull/status/protocol_mismatch go through /v1, promote_bootstrap hits
// POST /v1/sync/bootstrap, and "compact" invokes the engine's maintenance
// call (with no HTTP surface by design — it also runs automatically on
// promotion).

const syncprotoFixtureDir = "../../../spec/fixtures/sync"

func canonJSON(t *testing.T, v any) string {
	t.Helper()
	raw, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	var generic any
	if err := json.Unmarshal(raw, &generic); err != nil {
		t.Fatal(err)
	}
	out, err := json.Marshal(generic)
	if err != nil {
		t.Fatal(err)
	}
	return string(out)
}

func canonEqual(t *testing.T, label string, got, want any) {
	t.Helper()
	if canonJSON(t, got) != canonJSON(t, want) {
		t.Errorf("%s:\n  got  %s\n  want %s", label, canonJSON(t, got), canonJSON(t, want))
	}
}

type syncprotoFixtureServer struct {
	t      *testing.T
	h      http.Handler
	srv    *Server
	master string
	tokens map[string]string // device_id → token
}

func newSyncProtoFixtureServer(t *testing.T) *syncprotoFixtureServer {
	t.Helper()
	srv, _, master, cleanup := setupTestServerNamed(t, "vault_test")
	t.Cleanup(cleanup)
	return &syncprotoFixtureServer{t: t, h: srv.Handler(), srv: srv, master: master, tokens: map[string]string{}}
}

func (fx *syncprotoFixtureServer) token(deviceID string) string {
	if fx.tokens[deviceID] == "" {
		body, _ := json.Marshal(map[string]string{"device_id": deviceID})
		rec := doRequest(fx.t, fx.h, "POST", "/v1/devices/enroll", fx.master, body, "application/json")
		if rec.Code != http.StatusOK {
			fx.t.Fatalf("fixture enroll %s: %d %s", deviceID, rec.Code, rec.Body.String())
		}
		var out struct {
			Token string `json:"token"`
		}
		_ = json.Unmarshal(rec.Body.Bytes(), &out)
		fx.tokens[deviceID] = out.Token
	}
	return fx.tokens[deviceID]
}

func (fx *syncprotoFixtureServer) post(path, deviceID string, req any) (*httptest.ResponseRecorder, map[string]any) {
	fx.t.Helper()
	body, _ := json.Marshal(req)
	token := ""
	if deviceID != "" {
		token = fx.token(deviceID)
	}
	rec := doRequest(fx.t, fx.h, "POST", path, token, body, "application/json")
	var out map[string]any
	if json.Valid(rec.Body.Bytes()) {
		_ = json.Unmarshal(rec.Body.Bytes(), &out)
	}
	return rec, out
}

func TestSyncProtoFixturesOverHTTP(t *testing.T) {
	files, err := filepath.Glob(filepath.Join(syncprotoFixtureDir, "*.json"))
	if err != nil || len(files) == 0 {
		t.Fatalf("no fixtures in %s: %v", syncprotoFixtureDir, err)
	}

	for _, f := range files {
		name := filepath.Base(f)
		t.Run(name, func(t *testing.T) {
			raw, err := os.ReadFile(f)
			if err != nil {
				t.Fatal(err)
			}
			var fxFile struct {
				Steps []json.RawMessage `json:"steps"`
			}
			if err := json.Unmarshal(raw, &fxFile); err != nil {
				t.Fatalf("%s: %v", name, err)
			}
			fx := newSyncProtoFixtureServer(t)

			for i, rawStep := range fxFile.Steps {
				var step struct {
					Action     string           `json:"action"`
					Comment    string           `json:"comment"`
					Note       string           `json:"note"`
					Ops        []map[string]any `json:"ops"`
					SinceSeq   uint64           `json:"since_seq"`
					OpID       string           `json:"op_id"`
					CoveredSeq uint64           `json:"covered_seq"`
					Expect     json.RawMessage  `json:"expect"`
					ExpectErr  json.RawMessage  `json:"expect_error"`
				}
				if err := json.Unmarshal(rawStep, &step); err != nil {
					t.Fatalf("%s step %d: %v", name, i, err)
				}
				label := name + " step " + string(rune('0'+i)) + " (" + step.Action + ")"

				switch step.Action {
				case "push":
					// Ops of one step belong to one device (the token's); the fixtures
					// push per device.
					byDevice := map[string][]map[string]any{}
					var order []string
					for _, op := range step.Ops {
						d, _ := op["device_id"].(string)
						if _, seen := byDevice[d]; !seen {
							order = append(order, d)
						}
						byDevice[d] = append(byDevice[d], op)
					}
					if len(order) > 1 {
						t.Fatalf("%s: a push carries ops of several devices", label)
					}
					rec, out := fx.post("/v1/sync/push", order[0], map[string]any{"ops": step.Ops})
					if len(step.ExpectErr) > 0 {
						checkFixtureError(t, label, rec, step.ExpectErr)
						continue
					}
					if rec.Code != http.StatusOK {
						t.Fatalf("%s: push HTTP %d: %s", label, rec.Code, rec.Body.String())
					}
					var want struct {
						Results []map[string]any `json:"results"`
						HeadSeq float64          `json:"head_seq"`
					}
					decodeStrict(t, step.Expect, &want)
					canonEqual(t, label+" results", out["results"], want.Results)
					if out["head_seq"].(float64) != want.HeadSeq {
						t.Errorf("%s: head_seq got %v want %v", label, out["head_seq"], want.HeadSeq)
					}

				case "pull":
					rec, out := fx.post("/v1/sync/pull", "dev_tablet", map[string]any{"since_seq": step.SinceSeq})
					if len(step.ExpectErr) > 0 {
						checkFixtureError(t, label, rec, step.ExpectErr)
						continue
					}
					if rec.Code != http.StatusOK {
						t.Fatalf("%s: pull HTTP %d: %s", label, rec.Code, rec.Body.String())
					}
					var want struct {
						Seqs    []float64 `json:"seqs"`
						OpIDs   []string  `json:"op_ids"`
						HeadSeq float64   `json:"head_seq"`
						HasMore bool      `json:"has_more"`
					}
					decodeStrict(t, step.Expect, &want)
					gotSeqs, gotIDs := []float64{}, []string{}
					for _, rawOp := range out["ops"].([]any) {
						op := rawOp.(map[string]any)
						gotSeqs = append(gotSeqs, op["seq"].(float64))
						gotIDs = append(gotIDs, op["op_id"].(string))
					}
					canonEqual(t, label+" seqs", gotSeqs, want.Seqs)
					canonEqual(t, label+" op_ids", gotIDs, want.OpIDs)
					if out["head_seq"].(float64) != want.HeadSeq {
						t.Errorf("%s: head_seq got %v want %v", label, out["head_seq"], want.HeadSeq)
					}
					if out["has_more"].(bool) != want.HasMore {
						t.Errorf("%s: has_more got %v want %v", label, out["has_more"], want.HasMore)
					}

				case "status":
					rec := doRequest(t, fx.h, "GET", "/v1/sync/status", fx.token("dev_tablet"), nil, "")
					if rec.Code != http.StatusOK {
						t.Fatalf("%s: status HTTP %d", label, rec.Code)
					}
					var got map[string]any
					_ = json.Unmarshal(rec.Body.Bytes(), &got)
					var want map[string]any
					decodeStrict(t, step.Expect, &want)
					canonEqual(t, label+" status", got, want)

				case "promote_bootstrap":
					rec, _ := fx.post("/v1/sync/bootstrap", "dev_pc", map[string]any{
						"op_id": step.OpID, "covered_seq": step.CoveredSeq,
					})
					if rec.Code != http.StatusOK {
						t.Fatalf("%s: promote HTTP %d: %s", label, rec.Code, rec.Body.String())
					}

				case "compact":
					if err := fx.srv.engine.Compact(); err != nil {
						t.Fatalf("%s: compact: %v", label, err)
					}

				default:
					t.Fatalf("%s: unknown action %q", label, step.Action)
				}
			}
		})
	}
}

func decodeStrict(t *testing.T, raw json.RawMessage, v any) {
	t.Helper()
	if len(raw) == 0 {
		return
	}
	if err := json.Unmarshal(raw, v); err != nil {
		t.Fatalf("decode %s: %v", raw, err)
	}
}

func checkFixtureError(t *testing.T, label string, rec *httptest.ResponseRecorder, wantRaw json.RawMessage) {
	t.Helper()
	var want map[string]any
	if err := json.Unmarshal(wantRaw, &want); err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("%s: error body is not JSON: %q", label, rec.Body.String())
	}
	errObj, _ := got["error"].(map[string]any)
	if errObj == nil {
		t.Fatalf("%s: expected an error object, got %s", label, rec.Body.String())
	}
	if wantStatus, ok := want["http_status"]; ok {
		if float64(rec.Code) != wantStatus.(float64) {
			t.Errorf("%s: http_status got %d want %v", label, rec.Code, wantStatus)
		}
	}
	for k, wantV := range want {
		if k == "http_status" || k == "note" {
			continue
		}
		canonEqual(t, label+" error."+k, errObj[k], wantV)
	}
}
