package simclient

// Runs spec/fixtures/sync against the executable protocol model of
// spec/sync.md (seq, op_id idempotency, bootstrap compaction, errors).
// blind server's real server must produce these exact responses.

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

const syncprotoFixturesDir = "../../../spec/fixtures/sync"

func canonAny(t *testing.T, v any) string {
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

func tCan(t *testing.T, a, b any, what string) {
	t.Helper()
	if canonAny(t, a) != canonAny(t, b) {
		t.Errorf("%s\n  got  %s\n  want %s", what, canonAny(t, a), canonAny(t, b))
	}
}

func TestSyncProtoFixtures(t *testing.T) {
	files, err := filepath.Glob(filepath.Join(syncprotoFixturesDir, "*.json"))
	if err != nil || len(files) == 0 {
		t.Fatalf("no fixtures in %s: %v", syncprotoFixturesDir, err)
	}
	for _, f := range files {
		raw, err := os.ReadFile(f)
		if err != nil {
			t.Fatal(err)
		}
		name := filepath.Base(f)
		var fx struct {
			Name  string `json:"name"`
			Steps []struct {
				Action     string          `json:"action"`
				Comment    string          `json:"comment"`
				Note       string          `json:"note"`
				Ops        []Op            `json:"ops"`
				SinceSeq   uint64          `json:"since_seq"`
				OpID       string          `json:"op_id"`
				CoveredSeq uint64          `json:"covered_seq"`
				Expect     json.RawMessage `json:"expect"`
				ExpectErr  json.RawMessage `json:"expect_error"`
			} `json:"steps"`
		}
		if err := json.Unmarshal(raw, &fx); err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		log := NewOpLog()
		for i, step := range fx.Steps {
			label := name + " step " + string(rune('0'+i)) + " (" + step.Action + ")"
			switch step.Action {
			case "push":
				results, head, serr := log.Push(step.Ops)
				if len(step.ExpectErr) > 0 && serr == nil {
					t.Fatalf("%s: expected error, got ok", label)
				}
				if serr != nil {
					checkErr(t, label, serr, step.ExpectErr)
					continue
				}
				var want struct {
					Results []PushResult `json:"results"`
					HeadSeq uint64       `json:"head_seq"`
				}
				decodeRaw(t, step.Expect, &want)
				tCan(t, results, want.Results, label+" results")
				if head != want.HeadSeq {
					t.Errorf("%s: head_seq got %d want %d", label, head, want.HeadSeq)
				}
			case "pull":
				ops, hasMore, head, serr := log.Pull(step.SinceSeq, PullLimitDef)
				if serr != nil {
					checkErr(t, label, serr, step.ExpectErr)
					continue
				}
				if len(step.ExpectErr) > 0 {
					t.Fatalf("%s: expected error, got ok", label)
				}
				seqs := []uint64{}
				opIDs := []string{}
				for _, op := range ops {
					seqs = append(seqs, op.Seq)
					opIDs = append(opIDs, op.OpID)
				}
				var want struct {
					Seqs    []uint64 `json:"seqs"`
					OpIDs   []string `json:"op_ids"`
					Head    uint64   `json:"head_seq"`
					HasMore bool     `json:"has_more"`
				}
				decodeRaw(t, step.Expect, &want)
				tCan(t, seqs, want.Seqs, label+" seqs")
				tCan(t, opIDs, want.OpIDs, label+" op_ids")
				if head != want.Head {
					t.Errorf("%s: head_seq got %d want %d", label, head, want.Head)
				}
				if hasMore != want.HasMore {
					t.Errorf("%s: has_more got %v want %v", label, hasMore, want.HasMore)
				}
			case "status":
				got := log.Status("vault_test", "dev_tablet")
				var want map[string]any
				decodeRaw(t, step.Expect, &want)
				tCan(t, got, want, label+" status")
			case "promote_bootstrap":
				if serr := log.PromoteBootstrap(step.OpID, step.CoveredSeq); serr != nil {
					t.Fatalf("%s: promote: %v", label, serr)
				}
			case "compact":
				log.Compact()
			default:
				t.Fatalf("%s: unknown action %q", label, step.Action)
			}
		}
	}
}

func decodeRaw(t *testing.T, raw json.RawMessage, v any) {
	t.Helper()
	if len(raw) == 0 {
		return
	}
	if err := json.Unmarshal(raw, v); err != nil {
		t.Fatalf("decode %s: %v", raw, err)
	}
}

func checkErr(t *testing.T, label string, serr *SyncError, wantRaw json.RawMessage) {
	t.Helper()
	if len(wantRaw) == 0 {
		t.Fatalf("%s: unexpected error %s", label, serr.Message)
	}
	var want map[string]any
	if err := json.Unmarshal(wantRaw, &want); err != nil {
		t.Fatal(err)
	}
	body := serr.body()["error"].(map[string]any)
	if cors, ok := want["http_status"]; !ok {
		_ = cors
	}
	for k, v := range want {
		if k == "http_status" || k == "note" {
			continue
		}
		got := body[k]
		tCan(t, got, v, label+" error."+k)
	}
}
