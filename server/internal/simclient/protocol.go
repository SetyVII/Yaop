package simclient

// Executable model of spec/sync.md (yaop-sync/1): the abstract blind
// server semantics — seq assignment, op_id idempotency, bootstrap compaction,
// errors and limits. It is NOT the server: blind server implements the real endpoints
// and must produce exactly the responses this model derives from the same
// fixture steps (spec/fixtures/sync).

import (
	"encoding/json"
	"fmt"
	"sort"
)

// ProtocolVersion is the value reported by the status endpoint.
const ProtocolVersion = "1"

// Op is the sync operation defined by spec/sync.md.
type Op struct {
	OpID     string `json:"op_id"`
	DeviceID string `json:"device_id"`
	Kind     string `json:"kind"`
	WireID   string `json:"wire_id"`
	KeyEpoch uint32 `json:"key_epoch"`
	Blob     string `json:"blob"`
}

// LoggedOp is an Op as returned by pull/stream, with its server-assigned seq.
type LoggedOp struct {
	Seq        uint64 `json:"seq"`
	OpID       string `json:"op_id"`
	DeviceID   string `json:"device_id"`
	Kind       string `json:"kind"`
	WireID     string `json:"wire_id"`
	KeyEpoch   uint32 `json:"key_epoch"`
	Blob       string `json:"blob"`
	ReceivedAt string `json:"received_at"`
}

// ValidKinds are the operation kinds of spec/sync.md.
var ValidKinds = map[string]bool{
	"obj": true, "type": true, "asset_meta": true,
	"derived": true, "snapshot": true, "node_request": true,
}

// Limits: defaults of spec/sync.md (configurable in a real server).
const (
	MaxOpsPerPush = 500
	PullLimitDef  = 200
	PullLimitMax  = 1000
)

// SyncError is the exact error schema of spec/sync.md.
type SyncError struct {
	Code    string         `json:"code"`
	Message string         `json:"message"`
	Extra   map[string]any `json:"-"`
}

func (e *SyncError) Error() string { return e.Code + ": " + e.Message }

func (e *SyncError) body() map[string]any {
	err := map[string]any{"code": e.Code, "message": e.Message}
	for k, v := range e.Extra {
		if _, taken := err[k]; !taken {
			err[k] = v
		}
	}
	return map[string]any{"error": err}
}

func errf(code, format string, args ...any) *SyncError {
	return &SyncError{Code: code, Message: fmt.Sprintf(format, args...)}
}

// OpLog is the abstract per-vault log.
type OpLog struct {
	HeadSeq      uint64
	ops          []LoggedOp        // ordered by seq
	byOpID       map[string]uint64 // op_id -> seq (idempotency)
	Bootstrap    *LoggedOp         // latest bootstrap snapshot op (kind=snapshot, wire_id=blind("bootstrap"))
	CompactFloor uint64            // covered_seq of the active bootstrap
	BootstrapSeq uint64            // seq of the bootstrap op itself
}

// NewOpLog starts an empty vault log.
func NewOpLog() *OpLog {
	return &OpLog{byOpID: map[string]uint64{}}
}

func (l *OpLog) snapshot() OpLogSnapshot {
	ops := make([]LoggedOp, len(l.ops))
	copy(ops, l.ops)
	return OpLogSnapshot{HeadSeq: l.HeadSeq, Ops: ops,
		CompactFloor: l.CompactFloor, BootstrapSeq: l.BootstrapSeq}
}

// OpLogSnapshot is a copy-on-read view used by fixtures and tests.
type OpLogSnapshot struct {
	HeadSeq      uint64     `json:"head_seq"`
	Ops          []LoggedOp `json:"ops"`
	CompactFloor uint64     `json:"compact_floor"`
	BootstrapSeq uint64     `json:"bootstrap_seq"`
}

// PushResult is one entry of the push response.
type PushResult struct {
	OpID string `json:"op_id"`
	Seq  uint64 `json:"seq"`
}

// Push applies the push semantics: new seq per unseen op_id, original seq for
// retries. Returns the ordered results or the exact sync error.
func (l *OpLog) Push(ops []Op) (results []PushResult, head uint64, err *SyncError) {
	if len(ops) > MaxOpsPerPush {
		return nil, 0, errf("bad_request", "push carries %d ops, max %d", len(ops), MaxOpsPerPush)
	}
	results = make([]PushResult, 0, len(ops))
	for _, op := range ops {
		if !ValidKinds[op.Kind] {
			return nil, 0, errf("bad_kind", "unknown kind %q", op.Kind)
		}
		if op.OpID == "" || len(op.OpID) != 32 {
			return nil, 0, errf("bad_request", "op_id must be 32 hex chars")
		}
		if seq, seen := l.byOpID[op.OpID]; seen {
			results = append(results, PushResult{OpID: op.OpID, Seq: seq}) // idempotent
			continue
		}
		l.HeadSeq++
		broadcast := LoggedOp{Seq: l.HeadSeq, OpID: op.OpID, DeviceID: op.DeviceID,
			Kind: op.Kind, WireID: op.WireID, KeyEpoch: op.KeyEpoch, Blob: op.Blob,
			ReceivedAt: receivedAtFor(l.HeadSeq)}
		l.ops = append(l.ops, broadcast)
		l.byOpID[op.OpID] = l.HeadSeq
		// bootstrap promotion (spec/sync.md §Compaction): the payload's
		// covered_seq is opaque to the server; fixtures drive a dedicated
		// PromoteBootstrap call. Here we only record the op.
		results = append(results, PushResult{OpID: op.OpID, Seq: l.HeadSeq})
	}
	return results, l.HeadSeq, nil
}

// PromoteBootstrap marks the given snapshot op (already pushed) as the active
// bootstrap with the covered_seq declared in its manifest, moving the
// compact floor. The covered_seq value comes from the client that uploaded
// it (the server never decrypts); it must not exceed the op's own seq.
func (l *OpLog) PromoteBootstrap(opID string, coveredSeq uint64) *SyncError {
	seq, ok := l.byOpID[opID]
	if !ok {
		return errf("not_found", "op %s not in the log", opID)
	}
	if coveredSeq > seq {
		return errf("bad_request", "covered_seq %d exceeds the snapshot seq %d", coveredSeq, seq)
	}
	if coveredSeq < l.CompactFloor {
		return errf("bad_request", "covered_seq %d would lower the floor %d", coveredSeq, l.CompactFloor)
	}
	l.CompactFloor = coveredSeq
	l.BootstrapSeq = seq
	return nil
}

// Compact removes ops with seq <= the floor (the bootstrap replaces them).
// Always below the covered seq — never above (spec/sync.md).
func (l *OpLog) Compact() {
	kept := l.ops[:0]
	for _, op := range l.ops {
		if op.Seq <= l.CompactFloor && l.BootstrapSeq != 0 && op.Seq < l.BootstrapSeq {
			continue
		}
		kept = append(kept, op)
	}
	l.ops = kept
}

const bootstrapWireID = "bootstrap"

// Pull applies the pull semantics: every op with seq > since, ascending,
// never deduplicated, with the compaction floor guard.
func (l *OpLog) Pull(sinceSeq uint64, limit int) (out []LoggedOp, hasMore bool, head uint64, err *SyncError) {
	if limit <= 0 {
		limit = PullLimitDef
	}
	if limit > PullLimitMax {
		limit = PullLimitMax
	}
	if sinceSeq < l.CompactFloor {
		errBox := map[string]any{
			"compact_floor": l.CompactFloor,
			"bootstrap": map[string]any{
				"covered_seq": l.CompactFloor,
				"seq":         l.BootstrapSeq,
			},
		}
		return nil, false, 0, &SyncError{Code: "oplog_compacted",
			Message: fmt.Sprintf("sync from seq %d not available; bootstrap first", sinceSeq),
			Extra:   errBox}
	}

	for _, op := range l.ops {
		if op.Seq > sinceSeq && op.Seq <= l.HeadSeq {
			out = append(out, op)
			if len(out) >= limit {
				break
			}
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Seq < out[j].Seq })
	if len(out) == limit {
		if last := out[len(out)-1].Seq; last < l.HeadSeq {
			hasMore = true
		}
	}
	return out, hasMore, l.HeadSeq, nil
}

// Status is the status JSON of spec/sync.md (both /v1 and /v1 paths).
func (l *OpLog) Status(vault, deviceID string) map[string]any {
	var bootSeq any
	if l.BootstrapSeq != 0 {
		bootSeq = l.BootstrapSeq
	}
	return map[string]any{
		"protocol_version": ProtocolVersion,
		"vault":            vault,
		"head_seq":         l.HeadSeq,
		"compact_floor":    l.CompactFloor,
		"bootstrap_seq":    bootSeq,
		"device_id":        deviceID,
	}
}

func receivedAtFor(seq uint64) string {
	// abstract clock for the model: matches the fixture's received_at field
	return fmt.Sprintf("2026-11-09T12:%02d:%02dZ", (seq-1)/60%60, (seq-1)%60)
}

func jsonBytes(v any) []byte {
	b, err := json.Marshal(v)
	if err != nil {
		panic(err)
	}
	return b
}

var _ = jsonBytes
