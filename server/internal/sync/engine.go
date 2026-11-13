// Package sync implements the SERVER half of the blind sync protocol
// yaop-sync/1 (spec/sync.md): it assigns seq, keeps an append-only log
// of encrypted operations it cannot read, answers pulls without dedup, moves
// the compaction floor when a bootstrap covers the log, and fans ops out over
// HTTP streams. It never merges, validates, or inspects content — the client
// reference (merge/stamp/crypto) lives in internal/simclient, outside this
// binary.
package sync

import (
	"encoding/base64"
	"fmt"
	"strings"
	gosync "sync"
	"time"

	"yaop-server/internal/store"
)

// ProtocolVersion is the "1" of protocol_version in /v1/sync/status
// (and its /v1/sync/status alias — the handshake legacy clients know).
const ProtocolVersion = "1"

// ValidKinds are the operation kinds of spec/sync.md — the schema the server
// checks, the only piece of an op it understands.
var ValidKinds = map[string]bool{
	"obj":          true,
	"type":         true,
	"asset_meta":   true,
	"derived":      true,
	"snapshot":     true,
	"node_request": true,
}

// Limits are the size and paging limits of spec/sync.md (defaults; a real
// deployment can tighten them at construction).
type Limits struct {
	MaxOpsPerPush        int   // 500
	MaxBlobBytes         int64 // 32 MiB per op
	MaxSnapshotBlobBytes int64 // 256 MiB when kind=snapshot
	PullLimitDefault     int   // 200
	PullLimitMax         int   // 1000
	SnapshotRetention    time.Duration
}

// DefaultLimits are the values fixed by spec/sync.md §Default limits.
func DefaultLimits() Limits {
	return Limits{
		MaxOpsPerPush:        500,
		MaxBlobBytes:         32 << 20,
		MaxSnapshotBlobBytes: 256 << 20,
		PullLimitDefault:     200,
		PullLimitMax:         1000,
		SnapshotRetention:    30 * 24 * time.Hour,
	}
}

// Op is the operation as pushed on the wire (spec/sync.md §Operation).
// Blob is the encrypted envelope, base64; the server moves it around without
// ever opening it.
type Op struct {
	OpID     string `json:"op_id"`
	DeviceID string `json:"device_id"`
	Kind     string `json:"kind"`
	WireID   string `json:"wire_id"`
	KeyEpoch uint32 `json:"key_epoch"`
	Blob     string `json:"blob"`
}

// LoggedOp is an Op as the server hands it back (pull, stream, bootstrap):
// same fields the clients pushed plus the server-assigned seq and arrival
// time — exactly the shape of spec/sync.md.
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

type PushRequest struct {
	Ops []Op `json:"ops"`
}

type PushResult struct {
	OpID string `json:"op_id"`
	Seq  uint64 `json:"seq"`
}

type PushResponse struct {
	Results []PushResult `json:"results"`
	HeadSeq uint64       `json:"head_seq"`
}

type PullRequest struct {
	SinceSeq uint64 `json:"since_seq"`
	Limit    int    `json:"limit"`
}

type PullResponse struct {
	Ops     []LoggedOp `json:"ops"`
	HeadSeq uint64     `json:"head_seq"`
	HasMore bool       `json:"has_more"`
}

// StatusResponse is the JSON of GET /v1/sync/status (and its /v1 alias):
// everything a compatible client needs to negotiate the protocol.
type StatusResponse struct {
	ProtocolVersion string  `json:"protocol_version"`
	Vault           string  `json:"vault"`
	HeadSeq         uint64  `json:"head_seq"`
	CompactFloor    uint64  `json:"compact_floor"`
	BootstrapSeq    *uint64 `json:"bootstrap_seq"`
	DeviceID        string  `json:"device_id"`
}

// BootstrapResponse is GET /v1/sync/bootstrap: the snapshot a fresh device
// starts from instead of reproducing the whole log.
type BootstrapResponse struct {
	CoveredSeq uint64 `json:"covered_seq"`
	Seq        uint64 `json:"seq"`
	Blob       string `json:"blob"`
}

// SyncError is the exact error schema of spec/sync.md: an HTTP status plus
// the JSON object under "error" (code, message, and optional extra fields
// like retry_after, compact_floor or the client/server protocol versions).
type SyncError struct {
	Status  int
	Code    string
	Message string
	Extra   map[string]any
}

func (e *SyncError) Error() string {
	if e == nil {
		return "<nil>"
	}
	return fmt.Sprintf("%d %s: %s", e.Status, e.Code, e.Message)
}

// body is the "error" object of the wire response.
func (e *SyncError) body() map[string]any {
	obj := map[string]any{"code": e.Code, "message": e.Message}
	for k, v := range e.Extra {
		if _, taken := obj[k]; !taken {
			obj[k] = v
		}
	}
	return obj
}

// WriteTo is the full response body (also used verbatim by the API layer).
func (e *SyncError) WriteTo() map[string]any {
	return map[string]any{"error": e.body()}
}

func errAt(status int, code, format string, args ...any) *SyncError {
	return &SyncError{Status: status, Code: code, Message: fmt.Sprintf(format, args...)}
}

// Engine coordinates the oplog: seq assignment, idempotency, pulls, bootstrap
// promotion and compaction. Pushes serialize on pushMu so two concurrent
// pushes never interleave a lost update; pulls read concurrently from the
// WAL pool.
type Engine struct {
	store  *store.Store
	vault  string // vault id reported by status (the server's own identity)
	hub    *Hub
	limits Limits
	pushMu gosync.Mutex
}

func NewEngine(st *store.Store, vaultID string, hub *Hub) *Engine {
	e := &Engine{
		store:  st,
		vault:  vaultID,
		hub:    hub,
		limits: DefaultLimits(),
	}
	if hub != nil {
		hub.replayer = e
	}
	return e
}

// SetLimits tightens (or relaxes) the spec defaults.
func (e *Engine) SetLimits(l Limits) { e.limits = l }

// Limits returns the configuration in force.
func (e *Engine) Limits() Limits { return e.limits }

// Push applies the push semantics to a request pinned to the token's device:
// a new seq for each unseen op_id, the original seq for retries, results in
// arrival order, one transaction, fan-out after commit.
func (e *Engine) Push(fromDevice string, req PushRequest) (*PushResponse, *SyncError) {
	if len(req.Ops) > e.limits.MaxOpsPerPush {
		return nil, errAt(400, "bad_request", "push carries %d ops, max %d", len(req.Ops), e.limits.MaxOpsPerPush)
	}
	if fromDevice == "" {
		return nil, errAt(403, "forbidden_device", "only device tokens can push; the ops carry device_id and the server verifies it against the token")
	}

	rows := make([]store.OpRow, 0, len(req.Ops))
	for _, op := range req.Ops {
		if !ValidKinds[op.Kind] {
			return nil, errAt(400, "bad_kind", "unknown kind %q", op.Kind)
		}
		if len(op.OpID) != 32 {
			return nil, errAt(400, "bad_request", "op_id must be 32 hex chars")
		}
		if op.DeviceID != fromDevice {
			return nil, errAt(403, "forbidden_device", "op %s carries device_id %q but the token belongs to %q", op.OpID, op.DeviceID, fromDevice)
		}
		blob, err := base64.StdEncoding.DecodeString(op.Blob)
		if err != nil {
			return nil, errAt(400, "bad_request", "op %s: blob must be standard base64 of the envelope", op.OpID)
		}
		limit := e.limits.MaxBlobBytes
		if op.Kind == "snapshot" {
			limit = e.limits.MaxSnapshotBlobBytes
		}
		if int64(len(blob)) > limit {
			return nil, errAt(413, "too_large", "op %s: blob of %d bytes exceeds the %d MiB limit for kind %q", op.OpID, len(blob), limit>>20, op.Kind)
		}
		rows = append(rows, store.OpRow{
			OpID:     op.OpID,
			DeviceID: op.DeviceID,
			Kind:     op.Kind,
			WireID:   op.WireID,
			KeyEpoch: op.KeyEpoch,
			Blob:     blob,
		})
	}

	// Serialize: seq assignment and the reported head must not interleave.
	e.pushMu.Lock()
	defer e.pushMu.Unlock()

	results, head, err := e.store.AppendOps(rows)
	if err != nil {
		return nil, errAt(500, "internal", "could not append the oplog")
	}

	resp := &PushResponse{HeadSeq: head, Results: make([]PushResult, 0, len(results))}
	var fresh []LoggedOp
	for i, r := range results {
		resp.Results = append(resp.Results, PushResult{OpID: rows[i].OpID, Seq: r.Seq})
		if r.Created {
			if row, ok, rerr := e.store.OpBySeq(r.Seq); rerr == nil && ok {
				fresh = append(fresh, LoggedOp{
					Seq: row.Seq, OpID: row.OpID, DeviceID: row.DeviceID,
					Kind: row.Kind, WireID: row.WireID, KeyEpoch: row.KeyEpoch,
					Blob: base64.StdEncoding.EncodeToString(row.Blob), ReceivedAt: row.ReceivedAt,
				})
			}
		}
	}
	if e.hub != nil && len(fresh) > 0 {
		e.hub.BroadcastOps(fresh)
	}
	return resp, nil
}

// Pull returns EVERY op above sinceSeq in ascending order, never deduplicated
// (clients merge every one; the merge is idempotent). Below the compaction
// floor the answer is the exact oplog_compacted error pointing at the
// bootstrap — never a silent hole (spec/sync.md §Compaction).
func (e *Engine) Pull(sinceSeq uint64, limit int) (*PullResponse, *SyncError) {
	if limit <= 0 {
		limit = e.limits.PullLimitDefault
	}
	if limit > e.limits.PullLimitMax {
		limit = e.limits.PullLimitMax
	}

	floor, bootstrapSeq, err := e.store.OplogCompaction()
	if err != nil {
		return nil, errAt(500, "internal", "could not read the compaction floor")
	}
	if sinceSeq < floor {
		serr := errAt(410, "oplog_compacted", "sync from seq %d not available; bootstrap first", sinceSeq)
		serr.Extra = map[string]any{
			"compact_floor": floor,
			"bootstrap": map[string]any{
				"covered_seq": floor,
				"seq":         bootstrapSeq,
			},
		}
		return nil, serr
	}

	rows, hasMore, err := e.store.OpsSince(sinceSeq, limit)
	if err != nil {
		return nil, errAt(500, "internal", "could not read the oplog")
	}
	head, err := e.store.OplogHead()
	if err != nil {
		return nil, errAt(500, "internal", "could not read the head")
	}
	ops := make([]LoggedOp, 0, len(rows))
	for _, r := range rows {
		ops = append(ops, LoggedOp{
			Seq: r.Seq, OpID: r.OpID, DeviceID: r.DeviceID, Kind: r.Kind,
			WireID: r.WireID, KeyEpoch: r.KeyEpoch,
			Blob:       base64.StdEncoding.EncodeToString(r.Blob),
			ReceivedAt: r.ReceivedAt,
		})
	}
	resp := &PullResponse{Ops: ops, HeadSeq: head, HasMore: hasMore}
	if len(ops) == limit && ops[len(ops)-1].Seq == head {
		resp.HasMore = false // the page ends exactly at the head
	}
	if len(ops) == 0 {
		resp.Ops = []LoggedOp{}
	}
	return resp, nil
}

// OpsForReplay feeds the streams' Last-Event-ID/since_seq catch-up with the
// same order pull uses.
func (e *Engine) OpsForReplay(sinceSeq uint64, max int) ([]LoggedOp, *SyncError) {
	resp, serr := e.Pull(sinceSeq, max)
	if serr != nil {
		return nil, serr
	}
	return resp.Ops, nil
}

// Status is the negotiation JSON for GET /v1/sync/status (and /v1 alias).
func (e *Engine) Status(deviceID string) (*StatusResponse, error) {
	head, err := e.store.OplogHead()
	if err != nil {
		return nil, err
	}
	floor, bootstrapSeq, err := e.store.OplogCompaction()
	if err != nil {
		return nil, err
	}
	var boot *uint64
	if bootstrapSeq != 0 {
		b := bootstrapSeq
		boot = &b
	}
	return &StatusResponse{
		ProtocolVersion: ProtocolVersion,
		Vault:           e.vault,
		HeadSeq:         head,
		CompactFloor:    floor,
		BootstrapSeq:    boot,
		DeviceID:        deviceID,
	}, nil
}

// Bootstrap returns the active bootstrap snapshot (404-shaped SyncError when
// no client ever uploaded one).
func (e *Engine) Bootstrap() (*BootstrapResponse, *SyncError) {
	_, bootstrapSeq, err := e.store.OplogCompaction()
	if err != nil {
		return nil, errAt(500, "internal", "could not read the bootstrap state")
	}
	if bootstrapSeq == 0 {
		return nil, errAt(404, "not_found", "no bootstrap snapshot has been uploaded yet")
	}
	row, ok, err := e.store.OpBySeq(bootstrapSeq)
	if err != nil || !ok {
		return nil, errAt(410, "oplog_compacted", "the bootstrap snapshot is no longer available; wait for a node to upload a fresh one")
	}
	return &BootstrapResponse{
		CoveredSeq: e.compactionFloor(),
		Seq:        row.Seq,
		Blob:       base64.StdEncoding.EncodeToString(row.Blob),
	}, nil
}

func (e *Engine) compactionFloor() uint64 {
	floor, _, err := e.store.OplogCompaction()
	if err != nil {
		return 0
	}
	return floor
}

// PromoteBootstrap marks a pushed snapshot op as the active bootstrap with
// the covered_seq its uploader declares (the server cannot derive it from the
// encrypted payload — it is a seq number, already server-visible metadata).
// Rules mirror the executable model exactly, and promoting auto-compacts the
// log below the new floor: the bootstrap replaces what it covers.
func (e *Engine) PromoteBootstrap(opID string, coveredSeq uint64) (*BootstrapResponse, *SyncError) {
	opID = strings.TrimSpace(opID)
	row, ok, err := e.store.OpByOpID(opID)
	if err != nil || !ok {
		return nil, errAt(404, "not_found", "op %s not in the log", opID)
	}
	floor, _, err := e.store.OplogCompaction()
	if err != nil {
		return nil, errAt(500, "internal", "could not read the compaction floor")
	}
	if coveredSeq > row.Seq {
		return nil, errAt(400, "bad_request", "covered_seq %d exceeds the snapshot seq %d", coveredSeq, row.Seq)
	}
	if coveredSeq < floor {
		return nil, errAt(400, "bad_request", "covered_seq %d would lower the floor %d", coveredSeq, floor)
	}
	if err := e.store.SetOplogBootstrap(coveredSeq, row.Seq); err != nil {
		return nil, errAt(500, "internal", "could not persist the bootstrap")
	}
	if _, err := e.store.CompactOplog(); err != nil {
		return nil, errAt(500, "internal", "compaction failed")
	}
	return &BootstrapResponse{CoveredSeq: coveredSeq, Seq: row.Seq, Blob: ""}, nil
}

// Compact manually compacts below the active bootstrap's floor (idempotent,
// no-op without a bootstrap). Exposed as engine machinery — promotion already
// compacted; this catches runs that skipped it.
func (e *Engine) Compact() error {
	_, err := e.store.CompactOplog()
	return err
}

// PurgeExpired runs the orthogonal retention of spec/sync.md §Compaction:
// snapshot-kind ops older than SnapshotRetention (by received_at). The
// active bootstrap op is never purged.
func (e *Engine) PurgeExpired(now time.Time) (int64, error) {
	cutoff := now.Add(-e.limits.SnapshotRetention).UTC().Format(storeTimeFormat)
	return e.store.PurgeSnapshotOpsBefore(cutoff)
}

const storeTimeFormat = time.RFC3339 // fixed-width received_at: lexicographic == chronological
