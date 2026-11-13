package store

import (
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"fmt"
	"sort"
)

// Opaque chunk storage for assets and thumbnails (spec/sync.md §Assets).
// Clients upload 4 MiB ciphertext chunks, one envelope per chunk so uploads
// resume per chunk; the server never learns MIME, filename or size in the
// clear. Identical ciphertexts are stored exactly once: the dedup key is the
// SHA-256 of the chunk's ciphertext bytes (spec §15.4).

// ChunkInfo describes one stored chunk of an asset (its ciphertext size).
type ChunkInfo struct {
	Seq  int    `json:"n"`
	Size int64  `json:"size"`
	Hash string `json:"sha256"`
}

// ChunkSession is the resumable state of an asset upload.
type ChunkSession struct {
	StoredChunks []int `json:"stored_chunks"` // chunk indices present, ascending
	NextChunk    int   `json:"next_chunk"`    // first missing index (where to resume)
	TotalChunks  int   `json:"total_chunks"`  // highest seen index + 1, -1 when empty
	Complete     bool  `json:"complete"`      // 0..TotalChunks-1 all present
	TotalSize    int64 `json:"total_size"`    // ciphertext bytes across chunks
}

// PutOpaqueChunk stores the chunk n of an asset under its blind wire_id,
// deduplicating identical ciphertexts. Returns the dedup hash and whether the
// bytes were new (false: identical ciphertext already in the store).
func (s *Store) PutOpaqueChunk(wireID string, n int, data []byte) (hash string, deduplicated bool, err error) {
	if wireID == "" || n < 0 || len(data) == 0 {
		return "", false, fmt.Errorf("chunks: wire_id, index and payload are required")
	}
	sum := sha256.Sum256(data)
	hash = hex.EncodeToString(sum[:])

	s.mu.Lock()
	defer s.mu.Unlock()

	tx, err := s.writeDB.Begin()
	if err != nil {
		return "", false, fmt.Errorf("chunks: begin: %w", err)
	}
	defer tx.Rollback()

	res, err := tx.Exec("INSERT OR IGNORE INTO opaque_blobs(hash, size, payload) VALUES(?, ?, ?)", hash, len(data), data)
	if err != nil {
		return "", false, fmt.Errorf("chunks: store blob: %w", err)
	}
	inserted, _ := res.RowsAffected()
	deduplicated = inserted == 0

	if _, err := tx.Exec(
		"INSERT INTO op_chunks(wire_id, chunk_seq, hash, size) VALUES(?, ?, ?, ?) ON CONFLICT(wire_id, chunk_seq) DO UPDATE SET hash=excluded.hash, size=excluded.size",
		wireID, n, hash, len(data)); err != nil {
		return "", false, fmt.Errorf("chunks: index %d: %w", n, err)
	}
	if err := tx.Commit(); err != nil {
		return "", false, fmt.Errorf("chunks: commit: %w", err)
	}
	return hash, deduplicated, nil
}

// ChunkSession reports which chunks of an asset exist, so an interrupted
// upload (or a device asking before download) knows where to resume.
func (s *Store) ChunkSession(wireID string) (ChunkSession, error) {
	var sess ChunkSession
	rows, err := s.readDB.Query("SELECT chunk_seq, size FROM op_chunks WHERE wire_id = ? ORDER BY chunk_seq ASC", wireID)
	if err != nil {
		return sess, err
	}
	defer rows.Close()
	seen := map[int]bool{}
	for rows.Next() {
		var n int
		var size int64
		if err := rows.Scan(&n, &size); err != nil {
			return sess, err
		}
		sess.StoredChunks = append(sess.StoredChunks, n)
		sess.TotalSize += size
		if n+1 > sess.TotalChunks {
			sess.TotalChunks = n + 1
		}
		seen[n] = true
	}
	if len(sess.StoredChunks) == 0 {
		sess.TotalChunks = -1
		return sess, nil
	}
	firstMissing := -1
	for i := 0; i < sess.TotalChunks; i++ {
		if !seen[i] {
			firstMissing = i
			break
		}
	}
	if firstMissing == -1 { // every chunk 0..TotalChunks-1 is present
		sess.Complete = true
		sess.NextChunk = sess.TotalChunks
	} else {
		sess.NextChunk = firstMissing
	}
	return sess, rows.Err()
}

// OpaqueChunk returns the stored ciphertext of chunk n (ETag = dedup hash).
func (s *Store) OpaqueChunk(wireID string, n int) (data []byte, hash string, size int64, err error) {
	if err = s.readDB.QueryRow(
		"SELECT b.payload, c.hash, c.size FROM op_chunks c JOIN opaque_blobs b ON b.hash = c.hash WHERE c.wire_id = ? AND c.chunk_seq = ?",
		wireID, n).Scan(&data, &hash, &size); err != nil {
		if err == sql.ErrNoRows {
			return nil, "", 0, sql.ErrNoRows
		}
		return nil, "", 0, err
	}
	return data, hash, size, nil
}

// ChunkInfos lists every chunk's ciphertext size — the metadata a client needs
// to map plaintext ranges onto chunks (the server cannot do that mapping: it
// never sees the plaintext).
func (s *Store) ChunkInfos(wireID string) ([]ChunkInfo, int64, error) {
	rows, err := s.readDB.Query("SELECT chunk_seq, size, hash FROM op_chunks WHERE wire_id = ? ORDER BY chunk_seq ASC", wireID)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var out []ChunkInfo
	var total int64
	for rows.Next() {
		var c ChunkInfo
		if err := rows.Scan(&c.Seq, &c.Size, &c.Hash); err != nil {
			return nil, 0, err
		}
		out = append(out, c)
		total += c.Size
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Seq < out[j].Seq })
	return out, total, rows.Err()
}

// ReadChunkRange writes the requested byte window of chunk n to w (HTTP 206
// inside a chunk).
func (s *Store) ReadChunkRange(wireID string, n int, start, end int64, w writeAt) error {
	data, _, size, err := s.OpaqueChunk(wireID, n)
	if err != nil {
		return err
	}
	if start < 0 {
		start = 0
	}
	if end >= size {
		end = size - 1
	}
	if start > end {
		return nil
	}
	_, err = w.Write(data[start : end+1])
	return err
}

type writeAt interface {
	Write(p []byte) (int, error)
}

// OpaqueBlobCount tells how many distinct ciphertexts the server keeps (dedup
// proof for tests and /health).
func (s *Store) OpaqueBlobCount() (int64, error) {
	var n int64
	err := s.readDB.QueryRow("SELECT COUNT(*) FROM opaque_blobs").Scan(&n)
	return n, err
}

// ChunkRefCount counts how many (wire_id, seq) slots point at a ciphertext.
func (s *Store) ChunkRefCount() (int64, error) {
	var n int64
	err := s.readDB.QueryRow("SELECT COUNT(*) FROM op_chunks").Scan(&n)
	return n, err
}

// AssetTotal declares the chunk count the uploading client split the asset
// into (the Upload-Length of the resumable session) and returns the previously
// declared total, if any, so a mid-upload change is refused instead of
// silently assembling a wedge of two different files.
func (s *Store) AssetTotal(wireID string, total int, now string) (prev int, hadPrev bool, err error) {
	if total <= 0 {
		return 0, false, fmt.Errorf("chunks: total must be positive")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	var prevTotal sql.NullInt64
	if err = s.writeDB.QueryRow("SELECT total_chunks FROM asset_uploads WHERE wire_id = ?", wireID).Scan(&prevTotal); err != nil && err != sql.ErrNoRows {
		return 0, false, err
	}
	hadPrev = prevTotal.Valid
	prev = int(prevTotal.Int64)
	if !hadPrev {
		_, err = s.writeDB.Exec("INSERT INTO asset_uploads(wire_id, total_chunks, updated_at) VALUES(?, ?, ?)", wireID, total, now)
		return prev, hadPrev, err
	}
	if prev != total {
		return prev, hadPrev, fmt.Errorf("chunks: declared total %d contradicts the earlier %d", total, prev)
	}
	_, err = s.writeDB.Exec("UPDATE asset_uploads SET updated_at = ? WHERE wire_id = ?", now, wireID)
	return prev, hadPrev, err
}

// DeclaredAssetTotal returns the chunk count declared for an upload.
func (s *Store) DeclaredAssetTotal(wireID string) (total int, ok bool, err error) {
	var t sql.NullInt64
	if err = s.readDB.QueryRow("SELECT total_chunks FROM asset_uploads WHERE wire_id = ?", wireID).Scan(&t); err != nil {
		if err == sql.ErrNoRows {
			return 0, false, nil
		}
		return 0, false, err
	}
	return int(t.Int64), t.Valid, nil
}

// DropUpload forgets a declared total when the client restarts a session.
func (s *Store) DropUpload(wireID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, err := s.writeDB.Exec("DELETE FROM asset_uploads WHERE wire_id = ?", wireID)
	return err
}
