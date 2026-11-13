package api

import (
	"net/http"

	syncengine "yaop-server/internal/sync"
)

// request limit of spec/sync.md: a push may carry up to 64 MiB.
const maxPushRequestBytes = 64 << 20

func (s *Server) handleSyncStatus(w http.ResponseWriter, r *http.Request) {
	deviceID, _ := r.Context().Value(ctxDevice).(string)
	status, err := s.engine.Status(deviceID)
	if err != nil {
		writeSyncError(w, &syncengine.SyncError{Status: http.StatusInternalServerError, Code: "internal",
			Message: "Could not read sync status"})
		return
	}
	jsonResponse(w, http.StatusOK, status)
}

func (s *Server) handleSyncPush(w http.ResponseWriter, r *http.Request) {
	deviceID, _ := r.Context().Value(ctxDevice).(string)

	var req syncengine.PushRequest
	if serr := decodeJSON(w, r, &req, maxPushRequestBytes); serr != nil {
		writeSyncError(w, serr)
		return
	}

	if r.Context().Value(ctxRole) != roleDevice {
		writeSyncError(w, &syncengine.SyncError{Status: http.StatusForbidden, Code: "forbidden_device",
			Message: "only device tokens can push; the ops carry device_id and the server verifies it against the token"})
		return
	}

	// Pacing of spec/sync.md: pushes per minute and ops per day per device.
	if retry, serr := s.rate.allowPush(deviceID, len(req.Ops)); serr != nil {
		w.Header().Set("Retry-After", itoa(int(retry.Seconds())))
		writeSyncError(w, serr)
		return
	}

	resp, serr := s.engine.Push(deviceID, req)
	if serr != nil {
		writeSyncError(w, serr)
		return
	}
	jsonResponse(w, http.StatusOK, resp)
}

func (s *Server) handleSyncPull(w http.ResponseWriter, r *http.Request) {
	deviceID, _ := r.Context().Value(ctxDevice).(string)

	var req syncengine.PullRequest
	if serr := decodeJSON(w, r, &req, 1<<20); serr != nil {
		writeSyncError(w, serr)
		return
	}

	if retry, serr := s.rate.allowPull(deviceID); serr != nil {
		w.Header().Set("Retry-After", itoa(int(retry.Seconds())))
		writeSyncError(w, serr)
		return
	}

	resp, serr := s.engine.Pull(req.SinceSeq, req.Limit)
	if serr != nil {
		writeSyncError(w, serr)
		return
	}
	jsonResponse(w, http.StatusOK, resp)
}

// handleSyncStream is the canonical SSE (or WebSocket upgrade) of /3.
func (s *Server) handleSyncStream(w http.ResponseWriter, r *http.Request) {
	deviceID := r.URL.Query().Get("device_id")
	if deviceID == "" {
		if fromCtx, _ := r.Context().Value(ctxDevice).(string); fromCtx != "" {
			deviceID = fromCtx // a token-authenticated device names itself
		}
	}
	if deviceID == "" {
		deviceID = "client-" + r.RemoteAddr
	}

	if r.Header.Get("Upgrade") == "websocket" {
		s.hub.HandleWebSocket(w, r, deviceID)
		return
	}
	s.hub.HandleSSE(w, r, deviceID)
}

// GET /v1/sync/bootstrap: the active snapshot a new device starts from.
func (s *Server) handleBootstrapGet(w http.ResponseWriter, r *http.Request) {
	boot, serr := s.engine.Bootstrap()
	if serr != nil {
		writeSyncError(w, serr)
		return
	}
	jsonResponse(w, http.StatusOK, boot)
}

// POST /v1/sync/bootstrap {op_id, covered_seq}: the uploader declares which
// seq prefix its snapshot covers, and the server may compact below it. The
// covered_seq lives in the clear on purpose — it is a seq number, metadata
// the server already owns; the snapshot payload stays opaque.
func (s *Server) handleBootstrapPromote(w http.ResponseWriter, r *http.Request) {
	var req struct {
		OpID       string `json:"op_id"`
		CoveredSeq uint64 `json:"covered_seq"`
	}
	if serr := decodeJSON(w, r, &req, 4096); serr != nil {
		writeSyncError(w, serr)
		return
	}
	resp, serr := s.engine.PromoteBootstrap(req.OpID, req.CoveredSeq)
	if serr != nil {
		writeSyncError(w, serr)
		return
	}
	// Answer with the full snapshot blob: the promoting client never needs to
	// re-fetch, and the next device hits the GET.
	full, gerr := s.engine.Bootstrap()
	if gerr == nil && full != nil {
		jsonResponse(w, http.StatusOK, full)
		return
	}
	jsonResponse(w, http.StatusOK, resp)
}

// handleV2Mismatch answers the /2 push/pull/stream routes with the explicit
// PurgeExpired runs the server's retention housekeeping: 30-day-old snapshot
// ops from the oplog and expired pairing blobs. Returns what it removed.
func (s *Server) PurgeExpired() (snapshots, pairings int64, err error) {
	snapshots, err = s.engine.PurgeExpired(nowUTC())
	if err != nil {
		return snapshots, 0, err
	}
	cutoff := nowUTC().Add(-PairingTTL).UTC().Format(timeFormat)
	pairings, err = s.store.PurgePairingsBefore(cutoff)
	if err != nil {
		return snapshots, pairings, err
	}
	return snapshots, pairings, nil
}
