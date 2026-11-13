package store

import (
	"database/sql"
	"fmt"
	"time"
)

// Oplog storage of the blind sync protocol (spec/sync.md): an append-only
// sequence of opaque operations the server never decodes or merges. The only
// structure it gives them is the seq it assigns, the op_id idempotency index
// and the received_at bookkeeping for retention.

// OpRow is one operation of the oplog. Blob is the client's envelope verbatim
// (base64 on the wire, raw bytes at rest); the server attaches no meaning to
// it beyond its size.
type OpRow struct {
	Seq        uint64
	OpID       string
	DeviceID   string
	Kind       string
	WireID     string
	KeyEpoch   uint32
	Blob       []byte
	ReceivedAt string
}

// OpAppendResult reports what a push did with one op: the seq it ends up
// under and whether the server freshly stored it (idempotent repeats return
// the original seq with Created=false).
type OpAppendResult struct {
	Seq     uint64
	Created bool
}

// OplogHead returns the current maximum seq (0 for an empty log).
func (s *Store) OplogHead() (uint64, error) {
	var head sql.NullInt64
	if err := s.readDB.QueryRow("SELECT MAX(seq) FROM oplog").Scan(&head); err != nil {
		return 0, err
	}
	return uint64(head.Int64), nil
}

// AppendOps appends every op whose op_id has not been seen, and answers the
// seq of each one (new or existing) in arrival order — the whole batch in a
// single transaction so seq assignments and the reported head are consistent.
// An op_id repeated inside the same batch keeps the seq of its first copy.
func (s *Store) AppendOps(rows []OpRow) ([]OpAppendResult, uint64, error) {
	if len(rows) == 0 {
		head, err := s.OplogHead()
		return nil, head, err
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	tx, err := s.writeDB.Begin()
	if err != nil {
		return nil, 0, fmt.Errorf("oplog: begin: %w", err)
	}
	defer tx.Rollback()

	results := make([]OpAppendResult, 0, len(rows))
	seen := make(map[string]uint64, len(rows)) // op_id -> seq within this batch
	head := uint64(0)

	for _, row := range rows {
		if row.ReceivedAt == "" {
			row.ReceivedAt = NowRFC3339()
		}
		if seq, ok := seen[row.OpID]; ok {
			results = append(results, OpAppendResult{Seq: seq, Created: false})
			continue
		}
		var existing sql.NullInt64
		if err := tx.QueryRow("SELECT seq FROM oplog WHERE op_id = ?", row.OpID).Scan(&existing); err != nil && err != sql.ErrNoRows {
			return nil, 0, fmt.Errorf("oplog: lookup %s: %w", row.OpID, err)
		}
		if existing.Valid {
			seq := uint64(existing.Int64)
			seen[row.OpID] = seq
			results = append(results, OpAppendResult{Seq: seq, Created: false})
			continue
		}
		res, err := tx.Exec(
			"INSERT INTO oplog(op_id, device_id, kind, wire_id, key_epoch, blob, received_at) VALUES(?, ?, ?, ?, ?, ?, ?)",
			row.OpID, row.DeviceID, row.Kind, row.WireID, row.KeyEpoch, row.Blob, row.ReceivedAt)
		if err != nil {
			return nil, 0, fmt.Errorf("oplog: insert %s: %w", row.OpID, err)
		}
		last, err := res.LastInsertId()
		if err != nil {
			return nil, 0, fmt.Errorf("oplog: insert %s: no rowid: %w", row.OpID, err)
		}
		seq := uint64(last)
		if seq > head {
			head = seq
		}
		seen[row.OpID] = seq
		results = append(results, OpAppendResult{Seq: seq, Created: true})
	}

	var storedHead sql.NullInt64
	if err := tx.QueryRow("SELECT MAX(seq) FROM oplog").Scan(&storedHead); err != nil {
		return nil, 0, fmt.Errorf("oplog: head: %w", err)
	}
	if storedHead.Valid && uint64(storedHead.Int64) > head {
		head = uint64(storedHead.Int64)
	}

	if err := tx.Commit(); err != nil {
		return nil, 0, fmt.Errorf("oplog: commit: %w", err)
	}
	return results, head, nil
}

// OpsSince returns every op with seq > sinceSeq in ascending order, up to
// limit rows, never deduplicated — pull semantics of spec/sync.md. The second
// return value reports whether more ops remain above the returned page.
func (s *Store) OpsSince(sinceSeq uint64, limit int) (rows []OpRow, hasMore bool, err error) {
	if limit <= 0 {
		limit = 1
	}
	q, err := s.readDB.Query(
		"SELECT seq, op_id, device_id, kind, wire_id, key_epoch, blob, received_at FROM oplog WHERE seq > ? ORDER BY seq ASC LIMIT ?",
		sinceSeq, limit+1)
	if err != nil {
		return nil, false, err
	}
	defer q.Close()
	for q.Next() {
		var r OpRow
		if err := q.Scan(&r.Seq, &r.OpID, &r.DeviceID, &r.Kind, &r.WireID, &r.KeyEpoch, &r.Blob, &r.ReceivedAt); err != nil {
			return nil, false, err
		}
		if len(rows) == limit {
			hasMore = true
			break
		}
		rows = append(rows, r)
	}
	return rows, hasMore, q.Err()
}

// OpByOpID returns the stored op behind an idempotency repeat.
func (s *Store) OpByOpID(opID string) (OpRow, bool, error) {
	var r OpRow
	err := s.readDB.QueryRow(
		"SELECT seq, op_id, device_id, kind, wire_id, key_epoch, blob, received_at FROM oplog WHERE op_id = ?", opID).
		Scan(&r.Seq, &r.OpID, &r.DeviceID, &r.Kind, &r.WireID, &r.KeyEpoch, &r.Blob, &r.ReceivedAt)
	if err == sql.ErrNoRows || err == nil {
		return r, err == nil, nil
	}
	return OpRow{}, false, err
}

// OpBySeq returns the op stored at a given seq (the bootstrap snapshot, for
// GET /v1/sync/bootstrap).
func (s *Store) OpBySeq(seq uint64) (OpRow, bool, error) {
	var r OpRow
	err := s.readDB.QueryRow(
		"SELECT seq, op_id, device_id, kind, wire_id, key_epoch, blob, received_at FROM oplog WHERE seq = ?", seq).
		Scan(&r.Seq, &r.OpID, &r.DeviceID, &r.Kind, &r.WireID, &r.KeyEpoch, &r.Blob, &r.ReceivedAt)
	if err == sql.ErrNoRows || err == nil {
		return r, err == nil, nil
	}
	return OpRow{}, false, err
}

// ---------------------------------------------------------------------------
// Bootstrap compaction state (spec/sync.md §Compaction): the floor only ever
// moves when a client uploads a bootstrap snapshot that covers it, and the log
// may only lose ops strictly below it (never the bootstrap op itself).
// ---------------------------------------------------------------------------

const (
	metaOplogFloor        = "oplog.compact_floor"
	metaOplogBootstrapSeq = "oplog.bootstrap_seq"
)

// OplogCompaction returns (compactFloor, bootstrapSeq); both 0 when no
// bootstrap has been promoted yet.
func (s *Store) OplogCompaction() (floor, bootstrapSeq uint64, err error) {
	floor, err = s.metaUint(metaOplogFloor)
	if err != nil {
		return 0, 0, err
	}
	bootstrapSeq, err = s.metaUint(metaOplogBootstrapSeq)
	if err != nil {
		return 0, 0, err
	}
	return floor, bootstrapSeq, nil
}

// SetOplogBootstrap promotes a bootstrap: from now on the floor is the
// snapshot's covered_seq and the bootstrap op itself lives at bootstrapSeq.
// Every op with seq <= coveredSeq and seq < bootstrapSeq may be dropped.
func (s *Store) SetOplogBootstrap(coveredSeq, bootstrapSeq uint64) error {
	if err := s.SetMeta(metaOplogFloor, fmt.Sprintf("%d", coveredSeq)); err != nil {
		return err
	}
	return s.SetMeta(metaOplogBootstrapSeq, fmt.Sprintf("%d", bootstrapSeq))
}
func (s *Store) metaUint(key string) (uint64, error) {
	v, ok, err := s.Meta(key)
	if err != nil || !ok {
		return 0, err
	}
	var n uint64
	if _, err := fmt.Sscan(v, &n); err != nil {
		return 0, fmt.Errorf("oplog: meta %s: %w", key, err)
	}
	return n, nil
}

// CompactOplog removes ops the active bootstrap already covers: seq <= floor
// and strictly below the bootstrap op's own seq. Matching OpLog.Compact of
// the executable model, it is a no-op while no bootstrap exists.
func (s *Store) CompactOplog() (int64, error) {
	floor, bootstrapSeq, err := s.OplogCompaction()
	if err != nil {
		return 0, err
	}
	if bootstrapSeq == 0 {
		return 0, nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	res, err := s.writeDB.Exec("DELETE FROM oplog WHERE seq <= ? AND seq < ?", floor, bootstrapSeq)
	if err != nil {
		return 0, fmt.Errorf("oplog: compact: %w", err)
	}
	return res.RowsAffected()
}

// PurgeSnapshotOpsBefore drops 30-day-old kind=snapshot history ops by
// received_at (spec §Compaction: orthogonal retention; the active bootstrap
// op is always kept). cutoff must be RFC3339 UTC seconds.
func (s *Store) PurgeSnapshotOpsBefore(cutoff string) (int64, error) {
	_, bootstrapSeq, err := s.OplogCompaction()
	if err != nil {
		return 0, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	res, err := s.writeDB.Exec("DELETE FROM oplog WHERE kind = 'snapshot' AND received_at < ? AND seq <> ?", cutoff, bootstrapSeq)
	if err != nil {
		return 0, fmt.Errorf("oplog: purge snapshots: %w", err)
	}
	return res.RowsAffected()
}

// OplogStats reports counts for /health and the README story.
func (s *Store) OplogStats() (ops, snapshots int64, err error) {
	if err = s.readDB.QueryRow("SELECT COUNT(*) FROM oplog").Scan(&ops); err != nil {
		return 0, 0, err
	}
	err = s.readDB.QueryRow("SELECT COUNT(*) FROM oplog WHERE kind = 'snapshot'").Scan(&snapshots)
	return ops, snapshots, err
}

// NowRFC3339 is the fixed-width timestamp of every received_at column: with
// no fractional seconds, lexicographic order equals chronological order, so
// retention cutoffs can compare strings.
func NowRFC3339() string {
	return time.Now().UTC().Format(time.RFC3339)
}
