package store

import (
	"database/sql"
	"fmt"
	"testing"
)

func opRow(opID string) OpRow {
	return OpRow{OpID: opID, DeviceID: "dev_x", Kind: "obj", WireID: "wire_" + opID,
		KeyEpoch: 1, Blob: []byte("envelope:" + opID), ReceivedAt: NowRFC3339()}
}

// Push semantics (spec/sync.md): seq assignment, op_id idempotency — inside a
// batch and across batches — and exact paging on pull.
func TestOplog_PushSeqIdempotencyPull(t *testing.T) {
	s, cleanup := createTestStore(t)
	defer cleanup()

	results, head, err := s.AppendOps([]OpRow{opRow("op1"), opRow("op2"), opRow("op3")})
	if err != nil {
		t.Fatal(err)
	}
	if head != 3 || len(results) != 3 {
		t.Fatalf("head=%d results=%d", head, len(results))
	}
	for i, r := range results {
		if !r.Created || r.Seq != uint64(i+1) {
			t.Fatalf("op %d: %+v", i, r)
		}
	}

	// Retrying one op_id after losing Wi-Fi: the original seq, nothing stored.
	results, head, err = s.AppendOps([]OpRow{opRow("op2"), opRow("op4")})
	if err != nil {
		t.Fatal(err)
	}
	if head != 4 || !results[0].Created != true && results[0].Created {
		t.Fatalf("retry: %+v head=%d", results, head)
	}
	if results[0].Created || results[0].Seq != 2 {
		t.Fatalf("retry must return the original seq: %+v", results[0])
	}
	if !results[1].Created || results[1].Seq != 4 {
		t.Fatalf("new op after retry: %+v", results[1])
	}

	// A duplicated op_id *inside one push* keeps the first seq.
	results, _, err = s.AppendOps([]OpRow{opRow("op5"), opRow("op5")})
	if err != nil {
		t.Fatal(err)
	}
	if results[0].Seq != 5 || results[1].Seq != 5 || results[1].Created {
		t.Fatalf("in-batch dup: %+v", results)
	}

	// Pull returns EVERY op after since, ascending, no dedup by wire id.
	since := uint64(0)
	var seqs []uint64
	for {
		rows, hasMore, err := s.OpsSince(since, 2)
		if err != nil {
			t.Fatal(err)
		}
		for _, r := range rows {
			seqs = append(seqs, r.Seq)
		}
		if len(rows) == 0 || !hasMore {
			if hasMore {
				t.Fatal("empty page with has_more")
			}
			break
		}
		since = rows[len(rows)-1].Seq
	}
	want := []uint64{1, 2, 3, 4, 5}
	if fmt.Sprint(seqs) != fmt.Sprint(want) {
		t.Fatalf("pull seqs %v want %v", seqs, want)
	}

	// OpsBySeq / OpByOpID
	op, ok, err := s.OpByOpID("op3")
	if err != nil || !ok || op.Seq != 3 || op.Kind != "obj" || op.DeviceID != "dev_x" || string(op.Blob) != "envelope:op3" {
		t.Fatalf("by op_id: %+v %v %v", op, ok, err)
	}
	op, ok, err = s.OpByOpID("missing")
	if err != nil || ok {
		t.Fatalf("unknown op_id: %+v %v %v", op, ok, err)
	}
	op, ok, err = s.OpBySeq(4)
	if err != nil || !ok || op.OpID != "op4" || op.KeyEpoch != 1 || op.ReceivedAt == "" || op.WireID != "wire_op4" {
		t.Fatalf("by seq: %+v %v %v", op, ok, err)
	}
}

// A fresh store reports a zero floor and no bootstrap.
func TestOplog_EmptyDefaultValue(t *testing.T) {
	s, cleanup := createTestStore(t)
	defer cleanup()
	floor, boot, err := s.OplogCompaction()
	if err != nil || floor != 0 || boot != 0 {
		t.Fatalf("floor=%d boot=%d err=%v", floor, boot, err)
	}
	if err := s.Fsck(); err != nil { // the sealed system records stay healthy
		t.Fatal(err)
	}
	if n, err := s.CompactOplog(); err != nil || n != 0 {
		t.Fatalf("compact without bootstrap: %d %v", n, err)
	}
}

// Compact removes only ops covered by the active bootstrap (seq <= floor and
// seq < bootstrapSeq); the bootstrap op itself survives.
func TestOplog_BootstrapCompaction(t *testing.T) {
	s, cleanup := createTestStore(t)
	defer cleanup()

	_, head, err := s.AppendOps([]OpRow{opRow("a"), opRow("b"), opRow("c")})
	if err != nil {
		t.Fatal(err)
	}
	if head != 3 {
		t.Fatalf("head %d", head)
	}
	_, head, err = s.AppendOps([]OpRow{{OpID: "boot", DeviceID: "dev_x", Kind: "snapshot", WireID: "wire_x",
		KeyEpoch: 1, Blob: []byte("snapshot"), ReceivedAt: NowRFC3339()}})
	if err != nil {
		t.Fatal(err)
	}
	bootSeq := head // 4

	if err := s.SetOplogBootstrap(3, bootSeq); err != nil {
		t.Fatal(err)
	}
	floor, gotBoot, err := s.OplogCompaction()
	if err != nil || floor != 3 || gotBoot != bootSeq {
		t.Fatalf("floor=%d boot=%d err=%v", floor, gotBoot, err)
	}

	deleted, err := s.CompactOplog()
	if err != nil {
		t.Fatal(err)
	}
	if deleted != 3 {
		t.Fatalf("compacted %d ops, want 3", deleted)
	}
	rows, hasMore, err := s.OpsSince(0, 100)
	if err != nil || hasMore {
		t.Fatal(err)
	}
	if len(rows) != 1 || rows[0].OpID != "boot" || rows[0].Seq != bootSeq {
		t.Fatalf("after compact: %+v", rows)
	}
	if h, _ := s.OplogHead(); h != bootSeq {
		t.Fatalf("head after compact %d", h)
	}
	// Second compact run is a no-op.
	if n, _ := s.CompactOplog(); n != 0 {
		t.Fatalf("second compact deleted %d", n)
	}
}

// 30-day retention of snapshot-kind ops (received_at), keeping the active
// bootstrap regardless of age.
func TestOplog_PurgeOldSnapshots(t *testing.T) {
	s, cleanup := createTestStore(t)
	defer cleanup()

	_, head, err := s.AppendOps([]OpRow{
		{OpID: "old_snap", DeviceID: "d", Kind: "snapshot", WireID: "w1", KeyEpoch: 1, Blob: []byte("x"),
			ReceivedAt: "2026-01-01T00:00:00Z"},
		{OpID: "boot_snap", DeviceID: "d", Kind: "snapshot", WireID: "wb", KeyEpoch: 1, Blob: []byte("x"),
			ReceivedAt: "2026-01-02T00:00:00Z"},
		{OpID: "fresh_snap", DeviceID: "d", Kind: "snapshot", WireID: "w2", KeyEpoch: 1, Blob: []byte("x"),
			ReceivedAt: NowRFC3339()},
		{OpID: "old_obj", DeviceID: "d", Kind: "obj", WireID: "w3", KeyEpoch: 1, Blob: []byte("x"),
			ReceivedAt: "2026-01-01T00:00:00Z"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if head != 4 {
		t.Fatalf("head %d", head)
	}
	if err := s.SetOplogBootstrap(0, 2); err != nil {
		t.Fatal(err)
	} // boot_snap (seq 2) is the active bootstrap

	n, err := s.PurgeSnapshotOpsBefore("2026-06-01T00:00:00Z")
	if err != nil {
		t.Fatal(err)
	}
	if n != 1 { // old_snap only: bootstrap kept, fresh one too new, obj is not a snapshot
		t.Fatalf("purged %d, want 1", n)
	}
	rows, _, err := s.OpsSince(0, 100)
	if err != nil {
		t.Fatal(err)
	}
	got := map[string]bool{}
	for _, r := range rows {
		got[r.OpID] = true
	}
	for _, id := range []string{"boot_snap", "fresh_snap", "old_obj"} {
		if !got[id] {
			t.Fatalf("op %s must survive", id)
		}
	}
	if got["old_snap"] {
		t.Fatal("expired snapshot must be purged")
	}
}

// Identical ciphertext chunks dedup to a single stored blob; different bytes do
// not collide; the session state drives resumable uploads.
func TestChunks_DedupAndSession(t *testing.T) {
	s, cleanup := createTestStore(t)
	defer cleanup()

	c1 := []byte("chunk-ciphertext-identical")
	h1, dup, err := s.PutOpaqueChunk("wire_a", 0, c1)
	if err != nil || dup {
		t.Fatalf("first put: %v %v", err, dup)
	}
	h2, dup, err := s.PutOpaqueChunk("wire_b", 0, c1) // same bytes, other asset
	if err != nil || !dup || h1 != h2 {
		t.Fatalf("dedup put: %v dup=%v %s vs %s", err, dup, h1, h2)
	}
	h3, dup, err := s.PutOpaqueChunk("wire_a", 1, []byte("other bytes"))
	if err != nil || dup || h3 == h1 {
		t.Fatalf("different bytes must not dedup: %v", err)
	}

	blobs, _ := s.OpaqueBlobCount()
	if blobs != 2 {
		t.Fatalf("store keeps %d blobs, want 2", blobs)
	}
	refs, _ := s.ChunkRefCount()
	if refs != 3 {
		t.Fatalf("%d chunk references, want 3", refs)
	}

	sess, err := s.ChunkSession("wire_a")
	if err != nil {
		t.Fatal(err)
	}
	if !sess.Complete || sess.TotalChunks != 2 || sess.NextChunk != sess.TotalChunks {
		t.Fatalf("wire_a session: %+v", sess)
	}
	sess, err = s.ChunkSession("wire_b")
	if err != nil {
		t.Fatal(err)
	}
	if !sess.Complete || sess.TotalChunks != 1 || sess.NextChunk != 1 {
		t.Fatalf("wire_b single-chunk session: %+v", sess)
	}
	sess, err = s.ChunkSession("wire_missing")
	if err != nil || sess.TotalChunks != -1 || len(sess.StoredChunks) != 0 {
		t.Fatalf("empty session: %+v %v", sess, err)
	}

	// A gap in the sequence reports the first missing chunk as the resume point.
	if _, _, err := s.PutOpaqueChunk("wire_c", 1, []byte("only one")); err != nil {
		t.Fatal(err)
	}
	sess, err = s.ChunkSession("wire_c")
	if err != nil {
		t.Fatal(err)
	}
	if sess.Complete || sess.NextChunk != 0 || sess.TotalChunks != 2 {
		t.Fatalf("gap session: %+v", sess)
	}

	// Retrieval + range.
	data, hash, size, err := s.OpaqueChunk("wire_a", 0)
	if err != nil || string(data) != string(c1) || hash != h1 || size != int64(len(c1)) {
		t.Fatalf("get chunk: %v", err)
	}
	_, _, _, err = s.OpaqueChunk("wire_a", 9)
	if err != sql.ErrNoRows {
		t.Fatalf("missing chunk err=%v", err)
	}
	infos, total, err := s.ChunkInfos("wire_a")
	if err != nil || len(infos) != 2 || total != int64(len(c1)+len("other bytes")) {
		t.Fatalf("infos %+v total %d err %v", infos, total, err)
	}
}

// Pairing blobs are pure transport: they exist, they update, and they vanish
// past the TTL cutoff.
func TestPairings_TTLTransport(t *testing.T) {
	s, cleanup := createTestStore(t)
	defer cleanup()

	now := NowRFC3339()
	if err := s.UpsertPairing("ab32", "ephA", now); err != nil {
		t.Fatal(err)
	}
	p, ok, err := s.GetPairing("ab32")
	if err != nil || !ok || p.EphPub != "ephA" || p.BPub != "" || p.Transfer != nil {
		t.Fatalf("get: %+v %v %v", p, ok, err)
	}
	if err := s.SetPairingJoin("ab32", "bPub", now); err != nil {
		t.Fatal(err)
	}
	if err := s.SetPairingTransfer("ab32", []byte{1, 2, 3}, now); err != nil {
		t.Fatal(err)
	}
	p, _, _ = s.GetPairing("ab32")
	if p.BPub != "bPub" || len(p.Transfer) != 3 {
		t.Fatalf("join+transfer: %+v", p)
	}

	if err := s.SetPairingJoin("ghost", "x", now); err != sql.ErrNoRows {
		t.Fatalf("unknown pairing must not be created: %v", err)
	}

	if n, err := s.PurgePairingsBefore("2000-01-01T00:00:00Z"); err != nil || n != 0 {
		t.Fatalf("nothing older than 2000 to purge: %d %v", n, err)
	}
	if n, err := s.PurgePairingsBefore("2100-01-01T00:00:00Z"); err != nil || n != 1 {
		t.Fatalf("purge expired: %d %v", n, err)
	}
	if _, ok, _ := s.GetPairing("ab32"); ok {
		t.Fatal("pairing survived its TTL")
	}
}
