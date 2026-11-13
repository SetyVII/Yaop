package api

import (
	"fmt"
	"io"
	"net/http"
	"strconv"
	"time"

	"yaop-server/internal/store"
)

// Resumable blind asset uploads (spec/sync.md §Assets): the client
// splits the file into 4 MiB chunks and encrypts EACH chunk with its own
// envelope (AAD assets/<wire_id>/chunk/<n>); what the server receives is
// ciphertext. Sessions keep the shape of the pre-E2EE resumable layer —
// GET reports where to resume, PUT carries Upload-Offset/Upload-Length — but
// offset and length count CHUNKS, not bytes, because a chunk is the unit a
// client can retry after a network cut.
//
//	GET   /v1/assets/{wire_id}/upload → {"stored_chunks":[…],"next_chunk":n,
//	                                     "total_chunks":N,"complete":bool,"total_size":x}
//	PUT   /v1/assets/{wire_id}/upload  Upload-Offset:<chunk n> Upload-Length:<total N>
//	      → 204 + Upload-Offset:<next> (+ X-Chunk-Deduplicated when the ciphertext exists)
//	        201 {"complete":true,…} when the declared N chunks are all in
//	GET   /v1/assets/{wire_id}/chunks     → chunk sizes + dedup hashes
//	GET   /v1/assets/{wire_id}/chunk/{n}  → the ciphertext chunk (ETag=sha256, 206 on Range)
//	GET   /v1/assets/{wire_id}/content    → the concatenated chunks, 206 on Range
//
// MIME type and filename NEVER appear here: they only exist inside the
// `asset_meta` op the client pushes through /v1/sync/push, encrypted.

func assetUploadStatusJSON(sess store.ChunkSession, declared int) map[string]any {
	totalChunks := declared
	if totalChunks <= 0 {
		totalChunks = -1
	}
	return map[string]any{
		"stored_chunks": sess.StoredChunks,
		"next_chunk":    sess.NextChunk,
		"total_chunks":  totalChunks,
		"complete":      sess.Complete,
		"total_size":    sess.TotalSize,
	}
}

// handleUploadStatus reports the session state (the resume point).
func (s *Server) handleUploadStatus(w http.ResponseWriter, r *http.Request) {
	wireID := r.PathValue("wire_id")
	if wireID == "" || len(wireID) > 200 {
		writeJSONMessage(w, http.StatusBadRequest, "bad_request", "Missing blind asset id (wire_id)")
		return
	}
	sess, err := s.store.ChunkSession(wireID)
	if err != nil {
		writeJSONMessage(w, http.StatusInternalServerError, "internal", "Could not read the upload state")
		return
	}
	declared, _, err := s.store.DeclaredAssetTotal(wireID)
	if err != nil {
		writeJSONMessage(w, http.StatusInternalServerError, "internal", "Could not read the upload state")
		return
	}
	declared = foldDeclared(declared, sess.TotalChunks)
	sess.Complete = completeAgainst(sess, declared)
	jsonResponse(w, http.StatusOK, assetUploadStatusJSON(sess, declared))
}

// foldDeclared: the view reports the chunk count the client declared; -1 when
// nobody declared one yet.
func foldDeclared(declared, recorded int) int {
	if declared > 0 {
		return declared
	}
	if recorded > 0 {
		return recorded
	}
	return -1
}

// completeAgainst: complete means contiguous chunks 0..declared-1.
func completeAgainst(sess store.ChunkSession, declared int) bool {
	if declared <= 0 || !sess.Complete || len(sess.StoredChunks) != declared {
		return false
	}
	for i, n := range sess.StoredChunks {
		if n != i {
			return false
		}
	}
	return true
}

// handleUploadPart stores one ciphertext chunk at Upload-Offset.
func (s *Server) handleUploadPart(w http.ResponseWriter, r *http.Request) {
	wireID := r.PathValue("wire_id")
	if wireID == "" || len(wireID) > 200 {
		writeJSONMessage(w, http.StatusBadRequest, "bad_request", "Missing blind asset id (wire_id)")
		return
	}
	offset, err1 := strconv.Atoi(r.Header.Get("Upload-Offset"))
	total, err2 := strconv.Atoi(r.Header.Get("Upload-Length"))
	if err1 != nil || err2 != nil || offset < 0 || total <= 0 || total > 1<<20 {
		writeJSONMessage(w, http.StatusBadRequest, "bad_request",
			"Upload-Offset (chunk index) and Upload-Length (declared chunk count) are required")
		return
	}

	prev, _, err := s.store.AssetTotal(wireID, total, time.Now().UTC().Format(timeFormat))
	if err != nil {
		writeJSONMessage(w, http.StatusBadRequest, "bad_request",
			fmt.Sprintf("Upload-Length %d contradicts the session's declared %d", total, prev))
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, MaxAssetChunkBytes+1)
	data, err := io.ReadAll(r.Body)
	if err != nil || len(data) == 0 {
		writeJSONMessage(w, http.StatusRequestEntityTooLarge, "too_large",
			"the chunk is empty or exceeds the "+itoa(MaxAssetChunkBytes>>20)+" MiB limit")
		return
	}
	if len(data) > MaxAssetChunkBytes {
		writeJSONMessage(w, http.StatusRequestEntityTooLarge, "too_large",
			"the chunk exceeds "+itoa(MaxAssetChunkBytes)+" bytes (4 MiB plaintext + envelope)")
		return
	}

	hash, deduplicated, err := s.store.PutOpaqueChunk(wireID, offset, data)
	if err != nil {
		writeJSONMessage(w, http.StatusInternalServerError, "internal", "Could not store the chunk")
		return
	}

	sess, err := s.store.ChunkSession(wireID)
	if err != nil {
		writeJSONMessage(w, http.StatusInternalServerError, "internal", "Could not read the upload state")
		return
	}
	w.Header().Set("ETag", `"`+hash+`"`)
	if deduplicated {
		w.Header().Set("X-Chunk-Deduplicated", "1")
	}

	if sess.Complete && len(sess.StoredChunks) >= total {
		w.Header().Set("Upload-Offset", itoa(sess.TotalChunks))
		jsonResponse(w, http.StatusCreated, map[string]any{
			"complete":     true,
			"total_chunks": sess.TotalChunks,
			"total_size":   sess.TotalSize,
			"chunks":       sess.StoredChunks,
		})
		return
	}
	w.Header().Set("Upload-Offset", itoa(sess.NextChunk))
	w.WriteHeader(http.StatusNoContent)
}

// handleAssetChunksInfo lists every chunk with its ciphertext size and dedup
// hash — the metadata a client needs to map plaintext ranges onto chunks (the
// server cannot do that mapping for it: it never sees the plaintext).
func (s *Server) handleAssetChunksInfo(w http.ResponseWriter, r *http.Request) {
	wireID := r.PathValue("wire_id")
	infos, total, err := s.store.ChunkInfos(wireID)
	if err != nil {
		writeJSONMessage(w, http.StatusInternalServerError, "internal", "Could not read the chunks")
		return
	}
	if len(infos) == 0 {
		writeJSONMessage(w, http.StatusNotFound, "not_found", "no such asset")
		return
	}
	jsonResponse(w, http.StatusOK, map[string]any{
		"chunk_count": len(infos),
		"chunks":      infos,
		"total_size":  total,
	})
}

// handleAssetChunk serves one ciphertext chunk, with ranges inside the chunk
// for HTTP 206 (unusual but free to support).
func (s *Server) handleAssetChunk(w http.ResponseWriter, r *http.Request) {
	wireID := r.PathValue("wire_id")
	n, err := strconv.Atoi(r.PathValue("n"))
	if err != nil || n < 0 {
		writeJSONMessage(w, http.StatusBadRequest, "bad_request", "chunk index must be a non-negative integer")
		return
	}
	data, hash, size, err := s.store.OpaqueChunk(wireID, n)
	if err != nil {
		writeJSONMessage(w, http.StatusNotFound, "not_found", "no such chunk")
		return
	}
	etag := `"` + hash + `"`
	if match := r.Header.Get("If-None-Match"); match == etag {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream") // blind: the real MIME lives encrypted
	w.Header().Set("Accept-Ranges", "bytes")
	w.Header().Set("ETag", etag)

	if start, end, ok := parseByteRange(r.Header.Get("Range"), size); ok {
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, size))
		w.Header().Set("Content-Length", itoa(int(end-start+1)))
		w.WriteHeader(http.StatusPartialContent)
		_, _ = w.Write(data[start : end+1])
		return
	}
	w.Header().Set("Content-Length", itoa(int(size)))
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(data)
}

// handleAssetContent serves the concatenated ciphertext with cross-chunk
// ranges (bytes over the whole encrypted stream).
func (s *Server) handleAssetContent(w http.ResponseWriter, r *http.Request) {
	wireID := r.PathValue("wire_id")
	infos, total, err := s.store.ChunkInfos(wireID)
	if err != nil {
		writeJSONMessage(w, http.StatusInternalServerError, "internal", "Could not read the asset")
		return
	}
	if len(infos) == 0 {
		writeJSONMessage(w, http.StatusNotFound, "not_found", "no such asset")
		return
	}

	start, end, ranged := int64(0), total-1, false
	if s, e, ok := parseByteRange(r.Header.Get("Range"), total); ok {
		start, end, ranged = s, e, true
	} else if r.Header.Get("Range") != "" {
		// unsatisfiable range
		w.Header().Set("Content-Range", fmt.Sprintf("bytes */%d", total))
		writeJSONMessage(w, http.StatusRequestedRangeNotSatisfiable, "bad_request", "range not satisfiable")
		return
	}
	if ranged {
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, total))
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Accept-Ranges", "bytes")
	w.Header().Set("Content-Length", itoa(int(end-start+1)))
	if ranged {
		w.WriteHeader(http.StatusPartialContent)
	} else {
		w.WriteHeader(http.StatusOK)
	}

	// Map the requested window chunk a chunk, writing only what it overlaps.
	offset := int64(0)
	for _, info := range infos {
		cStart := offset
		cEnd := offset + info.Size - 1
		if cEnd >= start && cStart <= end {
			data, _, _, err := s.store.OpaqueChunk(wireID, info.Seq)
			if err != nil {
				return
			}
			from := start - cStart
			if from < 0 {
				from = 0
			}
			to := end - cStart
			if to >= info.Size {
				to = info.Size - 1
			}
			_, _ = w.Write(data[from : to+1])
		}
		offset += info.Size
	}
}

// parseByteRange parses a single-range "bytes=a-b" header against size.
func parseByteRange(h string, size int64) (start, end int64, ok bool) {
	if len(h) < 7 || h[:6] != "bytes=" {
		return 0, 0, false
	}
	spec := h[6:]
	dash := -1
	for i := 0; i < len(spec); i++ {
		if spec[i] == '-' {
			dash = i
			break
		}
	}
	if dash == -1 {
		return 0, 0, false
	}
	left, right := spec[:dash], spec[dash+1:]
	if left == "" { // suffix range: last N bytes
		n, err := strconv.ParseInt(right, 10, 64)
		if err != nil || n <= 0 {
			return 0, 0, false
		}
		if n > size {
			n = size
		}
		return size - n, size - 1, true
	}
	s, err := strconv.ParseInt(left, 10, 64)
	if err != nil || s < 0 || s >= size {
		return 0, 0, false
	}
	if right == "" {
		return s, size - 1, true
	}
	e, err := strconv.ParseInt(right, 10, 64)
	if err != nil || e < s {
		return 0, 0, false
	}
	if e >= size {
		e = size - 1
	}
	return s, e, true
}
