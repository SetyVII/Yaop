package api

import (
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"io"
	"net/http"
	"strings"
	"time"

	"yaop-server/internal/store"
)

// Pairing transport (spec/crypto.md §Pairing). The server relays three
// opaque values between the device that shows the QR and the one joining:
//
//	POST /v1/pairing               {pairing_id, eph_pub}      — the QR holder announces
//	GET  /v1/pairing/{id}                                     — transport state (polling)
//	POST /v1/pairing/{id}/join     {b_pub}                    — the new device answers
//	GET  /v1/pairing/{id}/answer   {b_pub|null}               — the QR holder polls
//	POST /v1/pairing/{id}/transfer   <raw envelope bytes>     — wrapped VK
//	GET  /v1/pairing/{id}/transfer   <raw envelope bytes>     — the new device unwraps
//	POST /v1/pairing/{id}/enroll     {device_id, name, enroll_key} — its own device token
//
// The server never sees the shared secret, the 6-digit code or the VK: only
// pairing_id, two 32-byte public keys and a few dozen opaque envelope bytes.
// Rows expire PairingTTL after their last touch whether they succeeded or not.

type pairingRequest struct {
	PairingID string `json:"pairing_id"`
	EphPub    string `json:"eph_pub"`
	BPub      string `json:"b_pub"`
}

func (s *Server) purgeStalePairings() {
	cutoff := nowUTC().Add(-PairingTTL).UTC().Format(timeFormat)
	_, _ = s.store.PurgePairingsBefore(cutoff)
}

func validPairingID(id string) bool {
	if len(id) != 32 { // 16 bytes in hex, exactly as the QR carries it
		return false
	}
	if _, err := hex.DecodeString(id); err != nil {
		return false
	}
	return strings.ToLower(id) == id
}

// validPubKey: 32 raw bytes, base64url without padding — the QR/join format of
// spec/crypto.md. Opaque to the server beyond shape.
func validPubKey(pub string) bool {
	raw, err := base64.RawURLEncoding.DecodeString(pub)
	return err == nil && len(raw) == 32
}

func (s *Server) handlePairingCreate(w http.ResponseWriter, r *http.Request) {
	var req pairingRequest
	if serr := decodeJSON(w, r, &req, 4096); serr != nil {
		writeSyncError(w, serr)
		return
	}
	if !validPairingID(req.PairingID) {
		writeJSONMessage(w, http.StatusBadRequest, "bad_request", "pairing_id must be 16 bytes in lowercase hex (32 chars)")
		return
	}
	if !validPubKey(req.EphPub) {
		writeJSONMessage(w, http.StatusBadRequest, "bad_request", "eph_pub must be a 32-byte X25519 public key in base64url without padding")
		return
	}
	s.purgeStalePairings()
	now := nowUTC()
	if err := s.store.UpsertPairing(req.PairingID, req.EphPub, now.Format(timeFormat)); err != nil {
		writeJSONMessage(w, http.StatusInternalServerError, "internal", "could not register the pairing")
		return
	}
	jsonResponse(w, http.StatusCreated, map[string]any{
		"pairing_id": req.PairingID,
		"eph_pub":    req.EphPub,
		"expires_at": now.Add(PairingTTL).UTC().Format(timeFormat),
	})
}

func (s *Server) lookupPairing(w http.ResponseWriter, id string) (store.PairingRow, bool) {
	if !validPairingID(id) {
		writeJSONMessage(w, http.StatusBadRequest, "bad_request", "pairing_id must be 16 bytes in lowercase hex (32 chars)")
		return store.PairingRow{}, false
	}
	s.purgeStalePairings()
	p, ok, err := s.store.GetPairing(id)
	if err != nil {
		writeJSONMessage(w, http.StatusInternalServerError, "internal", "could not read the pairing")
		return store.PairingRow{}, false
	}
	if !ok {
		writeJSONMessage(w, http.StatusNotFound, "not_found", "no such pairing (it may have expired)")
		return store.PairingRow{}, false
	}
	return p, true
}

func (s *Server) handlePairingGet(w http.ResponseWriter, r *http.Request) {
	p, ok := s.lookupPairing(w, r.PathValue("id"))
	if !ok {
		return
	}
	var bPub any
	if p.BPub != "" {
		bPub = p.BPub
	}
	jsonResponse(w, http.StatusOK, map[string]any{
		"pairing_id":   p.PairingID,
		"eph_pub":      p.EphPub,
		"b_pub":        bPub,
		"has_transfer": p.Transfer != nil,
	})
}

func (s *Server) handlePairingJoin(w http.ResponseWriter, r *http.Request) {
	p, ok := s.lookupPairing(w, r.PathValue("id"))
	if !ok {
		return
	}
	var req pairingRequest
	if serr := decodeJSON(w, r, &req, 4096); serr != nil {
		writeSyncError(w, serr)
		return
	}
	if !validPubKey(req.BPub) {
		writeJSONMessage(w, http.StatusBadRequest, "bad_request", "b_pub must be a 32-byte X25519 public key in base64url without padding")
		return
	}
	if p.BPub != "" && p.BPub != req.BPub {
		writeJSONMessage(w, http.StatusConflict, "forbidden_device", "another device already answered this pairing")
		return
	}
	if err := s.store.SetPairingJoin(p.PairingID, req.BPub, nowUTC().Format(timeFormat)); err != nil {
		writeJSONMessage(w, http.StatusInternalServerError, "internal", "could not record the join")
		return
	}
	jsonResponse(w, http.StatusAccepted, map[string]any{
		"pairing_id": p.PairingID,
		"eph_pub":    p.EphPub,
	})
}

// handlePairingAnswer is where the QR holder polls for the new device's key.
func (s *Server) handlePairingAnswer(w http.ResponseWriter, r *http.Request) {
	p, ok := s.lookupPairing(w, r.PathValue("id"))
	if !ok {
		return
	}
	var bPub any
	if p.BPub != "" {
		bPub = p.BPub
	}
	jsonResponse(w, http.StatusOK, map[string]any{"pairing_id": p.PairingID, "b_pub": bPub})
}

// handlePairingTransfer relays the wrapped-VK envelope: raw envelope bytes in
// the body (the exact bytes the holder sealed — the server cannot parse them).
func (s *Server) handlePairingTransfer(w http.ResponseWriter, r *http.Request) {
	p, ok := s.lookupPairing(w, r.PathValue("id"))
	if !ok {
		return
	}
	if p.BPub == "" {
		writeJSONMessage(w, http.StatusConflict, "forbidden_device", "the joining device has not answered yet")
		return
	}
	// Step 7: an authenticated holder may let the joining device mint one token.
	enrollKey := r.Header.Get("X-YAOP-Enroll-Key")
	if enrollKey != "" {
		if !validEnrollKey(enrollKey) {
			writeJSONMessage(w, http.StatusBadRequest, "bad_request", "X-YAOP-Enroll-Key must be 32 bytes in lowercase hex (64 chars)")
			return
		}
		auth := r.Header.Get("Authorization")
		if _, _, ok := s.authenticate(strings.TrimPrefix(auth, "Bearer ")); !strings.HasPrefix(auth, "Bearer ") || !ok {
			w.Header().Set("WWW-Authenticate", "Bearer")
			writeJSONMessage(w, http.StatusUnauthorized, "unauthorized", "an enroll key needs the holder's own token")
			return
		}
	}
	r.Body = http.MaxBytesReader(w, r.Body, MaxPairingTransferBytes+1)
	blob, err := io.ReadAll(r.Body)
	if err != nil || len(blob) == 0 {
		writeJSONMessage(w, http.StatusRequestEntityTooLarge, "too_large",
			"the transfer envelope exceeds "+itoa(MaxPairingTransferBytes)+" bytes or is empty")
		return
	}
	if len(blob) > MaxPairingTransferBytes {
		writeJSONMessage(w, http.StatusRequestEntityTooLarge, "too_large",
			"the transfer envelope exceeds "+itoa(MaxPairingTransferBytes)+" bytes")
		return
	}
	if err := s.store.SetPairingTransfer(p.PairingID, blob, nowUTC().Format(timeFormat)); err != nil {
		writeJSONMessage(w, http.StatusInternalServerError, "internal", "could not relay the transfer")
		return
	}
	if enrollKey != "" {
		if err := s.store.SetPairingEnrollKey(p.PairingID, hashToken(enrollKey), nowUTC().Format(timeFormat)); err != nil {
			writeJSONMessage(w, http.StatusInternalServerError, "internal", "could not authorize the enroll")
			return
		}
	}
	jsonResponse(w, http.StatusCreated, map[string]any{
		"pairing_id": p.PairingID, "transfer_size": len(blob), "enroll": enrollKey != "",
	})
}

func validEnrollKey(k string) bool {
	if len(k) != 64 || strings.ToLower(k) != k {
		return false
	}
	_, err := hex.DecodeString(k)
	return err == nil
}

// handlePairingEnroll gives the joining device its own token (spec/crypto.md
// §Pairing step 7): once, only to whoever holds enroll_key (derived from the
// X25519 secret, so a QR onlooker cannot claim it), never for an existing id.
func (s *Server) handlePairingEnroll(w http.ResponseWriter, r *http.Request) {
	p, ok := s.lookupPairing(w, r.PathValue("id"))
	if !ok {
		return
	}
	var req struct {
		DeviceID  string `json:"device_id"`
		Name      string `json:"name"`
		EnrollKey string `json:"enroll_key"`
	}
	if serr := decodeJSON(w, r, &req, 4096); serr != nil {
		writeSyncError(w, serr)
		return
	}
	req.DeviceID = strings.TrimSpace(req.DeviceID)
	if req.DeviceID == "" || len(req.DeviceID) > 200 {
		writeJSONMessage(w, http.StatusBadRequest, "bad_request", "device_id is required")
		return
	}
	keyHash, redeemed, found, err := s.store.GetPairingEnroll(p.PairingID)
	if err != nil {
		writeJSONMessage(w, http.StatusInternalServerError, "internal", "could not read the enroll")
		return
	}
	if !found {
		writeJSONMessage(w, http.StatusNotFound, "not_found", "the QR holder did not authorize a token for this pairing")
		return
	}
	if subtle.ConstantTimeCompare([]byte(hashToken(req.EnrollKey)), []byte(keyHash)) != 1 {
		writeJSONMessage(w, http.StatusForbidden, "forbidden_device", "wrong enroll key")
		return
	}
	if redeemed {
		writeJSONMessage(w, http.StatusConflict, "conflict", "this pairing already gave its token")
		return
	}
	s.devices.mu.Lock()
	defer s.devices.mu.Unlock()
	if _, exists := s.devices.devices[req.DeviceID]; exists {
		writeJSONMessage(w, http.StatusConflict, "conflict", "a device with this id already exists")
		return
	}
	if won, err := s.store.RedeemPairingEnroll(p.PairingID, nowUTC().Format(timeFormat)); err != nil || !won {
		writeJSONMessage(w, http.StatusConflict, "conflict", "this pairing already gave its token")
		return
	}
	token, err := s.mintDeviceTokenLocked(req.DeviceID, req.Name)
	if err != nil {
		writeJSONMessage(w, http.StatusInternalServerError, "internal", err.Error())
		return
	}
	writeJSONResponse(w, http.StatusOK, map[string]string{"device_id": req.DeviceID, "token": token})
}

func (s *Server) handlePairingTransferGet(w http.ResponseWriter, r *http.Request) {
	p, ok := s.lookupPairing(w, r.PathValue("id"))
	if !ok {
		return
	}
	if p.Transfer == nil {
		writeJSONMessage(w, http.StatusNotFound, "not_found", "the QR holder has not sent the transfer yet")
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", itoa(len(p.Transfer)))
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(p.Transfer)
}

// StartMaintenance runs the background housekeeping (30-day snapshot purge +
// expired pairings) until ctx ends. Periodic, tiny, and never on the request
// path.
func (s *Server) StartMaintenance(tick time.Duration) {
	go func() {
		t := time.NewTicker(tick)
		defer t.Stop()
		for range t.C {
			_, _, _ = s.PurgeExpired()
		}
	}()
}
