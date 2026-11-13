package store

import (
	"bytes"
	"path/filepath"
	"sync"
	"testing"

	"yaop-server/internal/crypto"
)

func createTestStore(t *testing.T) (*Store, func()) {
	t.Helper()
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "test-store.sqlite")

	key, err := crypto.GenerateRandomKey()
	if err != nil {
		t.Fatalf("GenerateRandomKey: %v", err)
	}

	vault, err := crypto.NewVault(key)
	if err != nil {
		t.Fatalf("NewVault: %v", err)
	}

	s, err := Open(dbPath, vault)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}

	cleanup := func() {
		s.Close()
	}
	return s, cleanup
}

// Admin records keep their at-rest sealing and their round trip.
func TestStore_PutGetBatchAndFsck(t *testing.T) {
	s, cleanup := createTestStore(t)
	defer cleanup()

	secret := map[string]any{"device_id": "tablet-1", "name": "Living room tablet", "retries": 2}
	if err := s.Put("system", "registry", secret); err != nil {
		t.Fatalf("Put: %v", err)
	}
	var out map[string]any
	if err := s.Get("system", "registry", &out); err != nil {
		t.Fatalf("Get: %v", err)
	}
	if out["device_id"] != "tablet-1" || out["retries"] != float64(2) {
		t.Fatalf("round trip mismatch: %+v", out)
	}
	if !s.Has("system", "registry") {
		t.Fatal("Has must see the stored record")
	}
	if s.Has("system", "missing") {
		t.Fatal("Has must not invent records")
	}

	if err := s.PutBatch(
		[]Record{{Kind: "system", ID: "a", Value: map[string]int{"x": 1}}, {Kind: "system", ID: "b", Value: map[string]int{"x": 2}}},
		[][2]string{{"system", "registry"}}); err != nil {
		t.Fatalf("PutBatch: %v", err)
	}
	if s.Has("system", "registry") {
		t.Fatal("delete in batch must remove the record")
	}
	for _, id := range []string{"a", "b"} {
		if !s.Has("system", id) {
			t.Fatalf("record %s missing after batch", id)
		}
	}
	if err := s.Delete("system", "a"); err != nil {
		t.Fatalf("Delete: %v", err)
	}
	if err := s.Fsck(); err != nil {
		t.Fatalf("Fsck: %v", err)
	}
}

// The blind chunk store serves cross-chunk ranges over the concatenated
// ciphertext — the byte window HTTP Return-Range needs.
func TestStore_OpaqueChunkRangeAcrossChunks(t *testing.T) {
	s, cleanup := createTestStore(t)
	defer cleanup()

	big := make([]byte, int(2.5*1024*1024))
	for i := range big {
		big[i] = byte(i % 251)
	}
	const part = 512 * 1024
	for off, n := 0, 0; off < len(big); off, n = off+part, n+1 {
		end := off + part
		if end > len(big) {
			end = len(big)
		}
		if _, _, err := s.PutOpaqueChunk("aud1", n, big[off:end]); err != nil {
			t.Fatalf("chunk %d: %v", n, err)
		}
	}

	infos, total, err := s.ChunkInfos("aud1")
	if err != nil || len(infos) != 5 || total != int64(len(big)) {
		t.Fatalf("infos %d total %d", len(infos), total)
	}
	for i, c := range infos {
		if c.Seq != i {
			t.Fatalf("chunk order: %+v", infos)
		}
	}

	// Assemble from chunk reads and compare.
	var assembled []byte
	for i := 0; i < len(infos); i++ {
		data, _, size, err := s.OpaqueChunk("aud1", i)
		if err != nil || size != int64(len(data)) {
			t.Fatalf("read %d: %v", i, err)
		}
		assembled = append(assembled, data...)
	}
	if !bytes.Equal(assembled, big) {
		t.Fatalf("assembly mismatch: %d bytes", len(assembled))
	}
}

// The WAL/pool layout stays concurrent: 5 writers on the single write
// connection while 20 readers pull — no deadlock, no lost write.
func TestStore_ConcurrentReadsAndWrites(t *testing.T) {
	s, cleanup := createTestStore(t)
	defer cleanup()

	var wg sync.WaitGroup
	writers, readers, iterations := 5, 20, 25
	for w := 0; w < writers; w++ {
		wg.Add(1)
		go func(workerID int) {
			defer wg.Done()
			for i := 0; i < iterations; i++ {
				row := OpRow{OpID: "w" + itoa(workerID) + "-" + itoa(i), DeviceID: "dev", Kind: "obj",
					WireID: "wire", KeyEpoch: 1, Blob: []byte{byte(workerID), byte(i)}}
				if _, _, err := s.AppendOps([]OpRow{row}); err != nil {
					t.Errorf("writer %d: %v", workerID, err)
					return
				}
				_ = s.Put("system", "seen-"+itoa(workerID), map[string]int{"i": i})
			}
		}(w)
	}
	for r := 0; r < readers; r++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < iterations; i++ {
				if _, _, err := s.OpsSince(0, 10); err != nil {
					t.Errorf("reader: %v", err)
					return
				}
				var v map[string]any
				_ = s.Get("system", "definitely-not-here", &v) // misses are normal reads
			}
		}()
	}
	wg.Wait()

	ops, _, _ := s.OplogStats()
	if ops != int64(writers*iterations) {
		t.Fatalf("oplog holds %d ops, want %d", ops, writers*iterations)
	}
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var out []byte
	for n > 0 {
		out = append([]byte{byte('0' + n%10)}, out...)
		n /= 10
	}
	return string(out)
}
