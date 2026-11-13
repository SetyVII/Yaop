package api

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
)

// R5 over the blind /3 layer: a large upload cut mid-flight resumes from the
// chunk the server reports; identical ciphertexts dedup; the server never
// learns name or MIME (it never asks).
func TestResumableChunkUpload(t *testing.T) {
	server, st, master, cleanup := setupTestServer(t)
	defer cleanup()
	h := server.Handler()
	token := enroll(t, h, master, "dev_tablet")

	put := func(id string, chunk int, total int, part []byte) *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodPut, "/v1/assets/"+id+"/upload", bytes.NewReader(part))
		req.Header.Set("Authorization", "Bearer "+token)
		req.Header.Set("Upload-Offset", strconv.Itoa(chunk))
		req.Header.Set("Upload-Length", strconv.Itoa(total))
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		return rec
	}

	// Three ciphertext "chunks" of an asset split client-side.
	chunks := [][]byte{bytes.Repeat([]byte{0xA1}, 1024), bytes.Repeat([]byte{0xB2}, 512), bytes.Repeat([]byte{0xC3}, 64)}
	total := len(chunks)

	// Part 1 lands.
	rec := put(t_uploadID(), 0, total, chunks[0])
	if rec.Code != http.StatusNoContent {
		t.Fatalf("chunk 0: %d %s", rec.Code, rec.Body.String())
	}
	if rec.Header().Get("Upload-Offset") != "1" {
		t.Fatalf("after chunk 0 the resume point is 1: %q", rec.Header().Get("Upload-Offset"))
	}

	// The client asks where to resume after the connection dropped.
	var sess struct {
		StoredChunks []int `json:"stored_chunks"`
		NextChunk    int   `json:"next_chunk"`
		TotalChunks  int   `json:"total_chunks"`
		Complete     bool  `json:"complete"`
	}
	rec = doRequest(t, h, "GET", "/v1/assets/"+t_uploadID()+"/upload", token, nil, "")
	if err := json.Unmarshal(rec.Body.Bytes(), &sess); err != nil {
		t.Fatal(err)
	}
	if sess.NextChunk != 1 || sess.TotalChunks != total || sess.Complete || len(sess.StoredChunks) != 1 {
		t.Fatalf("session state: %+v", sess)
	}

	// A chunk at the wrong index is refused with the right resume point.
	rec = put(t_uploadID(), 0, total, chunks[0])
	if rec.Code != http.StatusNoContent || rec.Header().Get("Upload-Offset") != "1" {
		t.Fatalf("re-put of chunk 0 must be idempotent: %d %q", rec.Code, rec.Header().Get("Upload-Offset"))
	}

	// The middle chunk lands; the last one completes the session.
	if rec := put(t_uploadID(), 1, total, chunks[1]); rec.Code != http.StatusNoContent {
		t.Fatalf("chunk 1: %d", rec.Code)
	}
	rec = put(t_uploadID(), 2, total, chunks[2])
	if rec.Code != http.StatusCreated {
		t.Fatalf("last chunk must complete: %d %s", rec.Code, rec.Body.String())
	}
	var done map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &done); err != nil {
		t.Fatal(err)
	}
	if done["complete"] != true || done["total_chunks"].(float64) != float64(total) {
		t.Fatalf("completion body: %v", done)
	}
	wantTotal := int64(len(chunks[0]) + len(chunks[1]) + len(chunks[2]))
	if int64(done["total_size"].(float64)) != wantTotal {
		t.Fatalf("total_size: %v", done["total_size"])
	}

	// Chunk metadata: sizes + hashes for the client's range math.
	rec = doRequest(t, h, "GET", "/v1/assets/"+t_uploadID()+"/chunks", token, nil, "")
	var info struct {
		ChunkCount int   `json:"chunk_count"`
		TotalSize  int64 `json:"total_size"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &info)
	if info.ChunkCount != total || info.TotalSize != wantTotal {
		t.Fatalf("chunks info: %+v", info)
	}

	// Download chunk by chunk (the "clients ask the chunk first" path).
	var assembled []byte
	for i := 0; i < total; i++ {
		rec = doRequest(t, h, "GET", "/v1/assets/"+t_uploadID()+"/chunk/"+strconv.Itoa(i), token, nil, "")
		if rec.Code != http.StatusOK {
			t.Fatalf("chunk %d: %d", i, rec.Code)
		}
		if rec.Header().Get("Content-Type") != "application/octet-stream" {
			t.Fatalf("the server must not guess a MIME: %q", rec.Header().Get("Content-Type"))
		}
		etag := rec.Header().Get("ETag")
		if len(etag) < 10 {
			t.Fatalf("chunk ETag (sha of ciphertext): %q", etag)
		}
		assembled = append(assembled, rec.Body.Bytes()...)
	}
	if !bytes.Equal(assembled, bytes.Join(chunks, nil)) {
		t.Fatalf("round trip mismatch: %d bytes", len(assembled))
	}

	// Range within a chunk.
	rec = doRequestRaw(t, h, "GET", "/v1/assets/"+t_uploadID()+"/chunk/1", token, nil, "Range: bytes=10-19")
	if rec.Code != http.StatusPartialContent {
		t.Fatalf("range in chunk: %d", rec.Code)
	}
	if !bytes.Equal(rec.Body.Bytes(), chunks[1][10:20]) {
		t.Fatal("in-chunk range mismatch")
	}

	// Range across chunks (over the concatenated ciphertext).
	rec = doRequestRaw(t, h, "GET", "/v1/assets/"+t_uploadID()+"/content", token, nil, "Range: bytes=1020-1030")
	if rec.Code != http.StatusPartialContent {
		t.Fatalf("cross-chunk range: %d", rec.Code)
	}
	want := append(append([]byte{}, chunks[0][1020:]...), chunks[1][:7]...)
	if rec.Body.String() != string(want) {
		t.Fatalf("cross-chunk range bytes: %x", rec.Body.String())
	}
	if cr := rec.Header().Get("Content-Range"); cr != "bytes 1020-1030/1600" {
		t.Fatalf("content-range: %q", cr)
	}

	// Full content without ranges.
	rec = doRequest(t, h, "GET", "/v1/assets/"+t_uploadID()+"/content", token, nil, "")
	if rec.Code != http.StatusOK || !bytes.Equal(rec.Body.Bytes(), bytes.Join(chunks, nil)) {
		t.Fatalf("full content: %d", rec.Code)
	}

	// Dedup: the very same ciphertext chunks under another wire_id store zero
	// new blobs.
	rec = put(t_dedupID(), 0, 1, chunks[0])
	if rec.Code != http.StatusOK && rec.Code != http.StatusCreated {
		t.Fatalf("re-upload complete asset: %d", rec.Code)
	}
	blobsBefore, _ := st.OpaqueBlobCount()
	rec = put(t_dedupID2(), 0, 1, chunks[0])
	if rec.Code != http.StatusOK && rec.Code != http.StatusCreated {
		t.Fatalf("same chunk to third asset: %d", rec.Code)
	}
	if rec.Header().Get("X-Chunk-Deduplicated") != "1" {
		t.Fatal("identical ciphertext must dedup (D-BLOBS-1)")
	}
	blobsAfter, _ := st.OpaqueBlobCount()
	if blobsAfter != blobsBefore {
		t.Fatalf("identical ciphertext stored twice: %d → %d", blobsBefore, blobsAfter)
	}
	refs, _ := st.ChunkRefCount()
	if refs != 5 { // 3 chunks + two duplicate registrations
		t.Fatalf("chunk references: %d", refs)
	}

	// Contradicting Upload-Length mid-session is refused, not wedged.
	rec = put(t_uploadID(), 0, 99, chunks[0])
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("session confusion: %d %s", rec.Code, rec.Body.String())
	}
}

func t_uploadID() string { return "c0ffee0000000000000000000000aa01" }
func t_dedupID() string  { return "c0ffee0000000000000000000000bb02" }
func t_dedupID2() string { return "c0ffee0000000000000000000000cc03" }

func doRequestRaw(t *testing.T, h http.Handler, method, path, token string, body []byte, headers ...string) *httptest.ResponseRecorder {
	t.Helper()
	var reader io.Reader
	if body != nil {
		reader = bytes.NewReader(body)
	}
	req := httptest.NewRequest(method, path, reader)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	for _, hh := range headers {
		parts := bytes.SplitN([]byte(hh), []byte(": "), 2)
		if len(parts) == 2 {
			req.Header.Set(string(parts[0]), string(parts[1]))
		}
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

// The resumable machinery lives on the /v1/assets HTTP layer (spec/sync.md, assets).
func TestResumableUploadV1Alias(t *testing.T) {
	server, _, master, cleanup := setupTestServer(t)
	defer cleanup()
	h := server.Handler()
	token := enroll(t, h, master, "dev_tablet")

	part := bytes.Repeat([]byte{0x11}, 256)
	req := httptest.NewRequest(http.MethodPut, "/v1/assets/aa1100000000000000000000000000dd/upload", bytes.NewReader(part))
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Upload-Offset", "0")
	req.Header.Set("Upload-Length", "1")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusCreated {
		t.Fatalf("single chunk completes on /v1/assets too: %d %s", rec.Code, rec.Body.String())
	}
	rec = doRequest(t, h, "GET", "/v1/assets/aa1100000000000000000000000000dd/upload", token, nil, "")
	var sess map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &sess)
	if sess["complete"] != true {
		t.Fatalf("session after single chunk: %v", sess)
	}
}
