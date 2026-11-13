package api

import (
	"compress/gzip"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"

	"yaop-server/internal/discovery"
	"yaop-server/internal/store"
	syncengine "yaop-server/internal/sync"
)

// ApiVersion identifies the wire surface of this binary: the blind
// yaop-sync/1 server. It no longer serves content — only opaque sync.
const ApiVersion = "0.1.0"

// Server is the blind sync server: oplog endpoints, opaque asset chunks,
// pairing relay and device management (spec/sync.md, spec/crypto.md).
type Server struct {
	store          *store.Store
	engine         *syncengine.Engine
	hub            *syncengine.Hub
	tokenHash      [32]byte
	allowedOrigins map[string]bool
	tlsFingerprint string
	mdnsService    *discovery.Service
	devices        *deviceRegistry
	limiter        *authLimiter
	rate           *rateLimiter
}

func NewServer(
	st *store.Store,
	serverID string,
	bearerToken string,
	allowedOrigins []string,
	tlsFingerprint string,
) *Server {
	hub := syncengine.NewHub()
	engine := syncengine.NewEngine(st, serverID, hub)

	origins := make(map[string]bool)
	for _, o := range allowedOrigins {
		if trimmed := strings.TrimSpace(o); trimmed != "" {
			origins[trimmed] = true
		}
	}

	s := &Server{
		store:          st,
		engine:         engine,
		hub:            hub,
		tokenHash:      sha256.Sum256([]byte(bearerToken)),
		allowedOrigins: origins,
		tlsFingerprint: tlsFingerprint,
		limiter:        &authLimiter{failures: map[string][]time.Time{}},
		rate:           newRateLimiter(DefaultRateLimits()),
	}
	s.loadDevices()
	return s
}

// SetRateLimits tightens/relaxes the per-device pacing of spec/sync.md.
func (s *Server) SetRateLimits(rl RateLimits) { s.rate.setLimits(rl) }

// SetEngineLimits adjusts the size/paging/engine limits (op counts, blob MiB).
func (s *Server) SetEngineLimits(l syncengine.Limits) { s.engine.SetLimits(l) }

func (s *Server) SetMdnsService(mdns *discovery.Service) {
	s.mdnsService = mdns
}

// MaxAssetChunkBytes is the largest ciphertext chunk an upload accepts: the
// spec's 4 MiB plaintext chunks plus the envelope overhead, with slack for
// producers that pad.
const MaxAssetChunkBytes = 4<<20 + 4<<10

// PairingTTL bounds how long a pairing transaction lives, complete or not —
// the QR holder and the joining device have minutes, not hours.
const PairingTTL = 10 * time.Minute

// MaxPairingTransferBytes bounds the relayed wrapped-VK envelope (a few dozen
// real bytes; generous slack for padding/versioning).
const MaxPairingTransferBytes = 4 << 10

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()

	// Health check (public, blind: counts only, no content)
	mux.HandleFunc("GET /health", s.handleHealth)
	mux.HandleFunc("POST /v1/devices/enroll", s.handleEnrollDevice)
	mux.HandleFunc("GET /v1/devices", s.handleListDevices)
	mux.HandleFunc("DELETE /v1/devices/{id}", s.handleRevokeDevice)

	// Blind sync (spec/sync.md)
	mux.HandleFunc("GET /v1/sync/status", s.handleSyncStatus)
	mux.HandleFunc("POST /v1/sync/push", s.handleSyncPush)
	mux.HandleFunc("POST /v1/sync/pull", s.handleSyncPull)
	mux.HandleFunc("GET /v1/sync/stream", s.handleSyncStream)
	mux.HandleFunc("GET /v1/sync/bootstrap", s.handleBootstrapGet)
	mux.HandleFunc("POST /v1/sync/bootstrap", s.handleBootstrapPromote)

	// Assets & thumbnails: resumable ciphertext chunks over the blind wire_id.
	for _, base := range []string{"/v1/assets"} {
		mux.HandleFunc("GET "+base+"/{wire_id}/upload", s.handleUploadStatus)
		mux.HandleFunc("PUT "+base+"/{wire_id}/upload", s.handleUploadPart)
		mux.HandleFunc("PATCH "+base+"/{wire_id}/upload", s.handleUploadPart) // HttpURLConnection has no PATCH
		mux.HandleFunc("GET "+base+"/{wire_id}/chunks", s.handleAssetChunksInfo)
		mux.HandleFunc("GET "+base+"/{wire_id}/chunk/{n}", s.handleAssetChunk)
		mux.HandleFunc("GET "+base+"/{wire_id}/content", s.handleAssetContent)
	}

	// Pairing transport (spec/crypto.md §Pairing): the joining device
	// holds no token yet — the 16-byte pairing_id of the QR is the capability,
	// the human compares the 6-digit code, and the server only relays bytes.
	mux.HandleFunc("POST /v1/pairing", s.handlePairingCreate)
	mux.HandleFunc("GET /v1/pairing/{id}", s.handlePairingGet)
	mux.HandleFunc("POST /v1/pairing/{id}/join", s.handlePairingJoin)
	mux.HandleFunc("GET /v1/pairing/{id}/answer", s.handlePairingAnswer)
	mux.HandleFunc("POST /v1/pairing/{id}/transfer", s.handlePairingTransfer)
	mux.HandleFunc("GET /v1/pairing/{id}/transfer", s.handlePairingTransferGet)

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Version headers
		w.Header().Set("X-YAOP-Api-Version", ApiVersion)
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")

		// CORS handling
		origin := r.Header.Get("Origin")
		if origin != "" {
			if len(s.allowedOrigins) > 0 && !s.allowedOrigins[origin] && !s.allowedOrigins["*"] {
				writeJSONMessage(w, http.StatusForbidden, "forbidden", "Origin not allowed")
				return
			}
			w.Header().Set("Access-Control-Allow-Origin", origin)
			w.Header().Set("Vary", "Origin")
			w.Header().Set("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS")
			w.Header().Set("Access-Control-Allow-Headers", "Authorization,Content-Type,Range,Upload-Offset,Upload-Length,Last-Event-ID,If-None-Match,X-YAOP-Api-Version")
			w.Header().Set("Access-Control-Expose-Headers", "Content-Range,Accept-Ranges,ETag,Upload-Offset,Retry-After,X-YAOP-Api-Version")
			w.Header().Set("Access-Control-Max-Age", "600")
		}

		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}

		// Public surface: health and the pairing relay (the joining device has
		// no credentials; the QR's unguessable pairing_id is its capability).
		if r.URL.Path == "/health" || strings.HasPrefix(r.URL.Path, "/v1/pairing") {
			mux.ServeHTTP(w, r)
			return
		}

		// Bearer Authentication
		authHeader := r.Header.Get("Authorization")
		if !strings.HasPrefix(authHeader, "Bearer ") {
			// URL query param fallback (WebSocket clients cannot set headers)
			if tokenParam := r.URL.Query().Get("token"); tokenParam != "" {
				authHeader = "Bearer " + tokenParam
			}
		}

		ip := clientIP(r)
		token := strings.TrimPrefix(authHeader, "Bearer ")
		role, deviceID, ok := "", "", false
		if strings.HasPrefix(authHeader, "Bearer ") {
			candidateHash := sha256.Sum256([]byte(token))
			if subtle.ConstantTimeCompare(candidateHash[:], s.tokenHash[:]) == 1 {
				role, ok = roleMaster, true
			} else {
				role, deviceID, ok = s.authenticate(token)
			}
		}
		if !ok {
			// Valid tokens always pass; a blocked IP (another client of the same
			// household or an attacker) only stops getting answers for invalid ones.
			if s.limiter.blocked(ip) {
				w.Header().Set("Retry-After", "600")
				writeJSONMessage(w, http.StatusTooManyRequests, "rate_limited", "Too many failed authentication attempts")
				return
			}
			s.limiter.fail(ip)
			w.Header().Set("WWW-Authenticate", "Bearer")
			writeJSONMessage(w, http.StatusUnauthorized, "unauthorized", "Authentication required")
			return
		}
		r = withAuth(r, role, deviceID)

		// Gzip compression for JSON reads (never for streams or asset bytes)
		if strings.Contains(r.Header.Get("Accept-Encoding"), "gzip") && r.Method == http.MethodGet &&
			!strings.Contains(r.URL.Path, "/content") && !strings.Contains(r.URL.Path, "/stream") &&
			!strings.Contains(r.URL.Path, "/chunk") {
			gz := gzip.NewWriter(w)
			defer gz.Close()
			w.Header().Set("Content-Encoding", "gzip")
			gzw := gzipResponseWriter{Writer: gz, ResponseWriter: w}
			mux.ServeHTTP(gzw, r)
			return
		}

		mux.ServeHTTP(w, r)
	})
}

// handleHealth is deliberately blind: no object or content counts, just the
// protocol the binary speaks and the log size.
func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	head, _ := s.engine.Status("")
	status := map[string]any{
		"status":           "ready",
		"protocol_version": syncengine.ProtocolVersion,
		"head_seq":         head.HeadSeq,
		"api_version":      ApiVersion,
	}
	jsonResponse(w, http.StatusOK, status)
}

type gzipResponseWriter struct {
	io.Writer
	http.ResponseWriter
}

func (w gzipResponseWriter) Write(b []byte) (int, error) {
	return w.Writer.Write(b)
}

func jsonResponse(w http.ResponseWriter, status int, val any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(val)
}

// writeSyncError answers with the exact error schema of spec/sync.md.
func writeSyncError(w http.ResponseWriter, serr *syncengine.SyncError) {
	if after, ok := serr.Extra["retry_after"]; ok {
		if secs, ok := after.(int); ok {
			w.Header().Set("Retry-After", itoa(secs))
		}
	}
	jsonResponse(w, serr.Status, serr.WriteTo())
}

// writeJSONMessage is the small helper for non-engine guards (auth, CORS).
func writeJSONMessage(w http.ResponseWriter, status int, code, msg string) {
	jsonResponse(w, status, map[string]any{"error": map[string]any{"code": code, "message": msg}})
}

func itoa(n int) string {
	if n < 0 {
		return "0"
	}
	if n == 0 {
		return "0"
	}
	var digits []byte
	for n > 0 {
		digits = append([]byte{byte('0' + n%10)}, digits...)
		n /= 10
	}
	return string(digits)
}

func decodeJSON(w http.ResponseWriter, r *http.Request, out any, maxBytes int64) *syncengine.SyncError {
	// Cheap guard first: buffered clients announce their size.
	if r.ContentLength > maxBytes {
		return &syncengine.SyncError{Status: http.StatusRequestEntityTooLarge, Code: "too_large",
			Message: "request exceeds the " + itoa(int(maxBytes>>20)) + " MiB limit"}
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxBytes)
	dec := json.NewDecoder(r.Body)
	if err := dec.Decode(out); err != nil {
		// The size guard must not masquerade as malformed JSON.
		var maxErr *http.MaxBytesError
		if errors.As(err, &maxErr) {
			return &syncengine.SyncError{Status: http.StatusRequestEntityTooLarge, Code: "too_large",
				Message: "request exceeds the " + itoa(int(maxBytes>>20)) + " MiB limit"}
		}
		return &syncengine.SyncError{Status: http.StatusBadRequest, Code: "bad_request",
			Message: "Invalid JSON payload"}
	}
	return nil
}
