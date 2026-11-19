package api

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"
)

// Per-device tokens (spec §15.3). The bootstrap bearer token is the *master*: it only
// enrolls and revokes devices. Each device syncs with its own token; the server keeps hashes only.

type deviceRecord struct {
	DeviceID  string `json:"device_id"`
	Name      string `json:"name"`
	TokenHash string `json:"token_hash"`
	CreatedAt string `json:"created_at"`
	LastSeen  string `json:"last_seen,omitempty"`
	Revoked   bool   `json:"revoked,omitempty"`
}

type deviceRegistry struct {
	mu      sync.Mutex
	devices map[string]*deviceRecord // by device id
	byHash  map[string]string        // token hash → device id (active only)
	seenAt  map[string]time.Time     // last persisted last_seen, to throttle writes
}

type ctxKey int

const (
	ctxRole ctxKey = iota
	ctxDevice
)

const (
	roleMaster = "master"
	roleDevice = "device"
)

func hashToken(t string) string {
	h := sha256.Sum256([]byte(t))
	return hex.EncodeToString(h[:])
}

func (s *Server) loadDevices() {
	reg := &deviceRegistry{devices: map[string]*deviceRecord{}, byHash: map[string]string{}, seenAt: map[string]time.Time{}}
	var stored map[string]*deviceRecord
	if err := s.store.Get("system", "devices", &stored); err == nil {
		for id, d := range stored {
			reg.devices[id] = d
			if !d.Revoked {
				reg.byHash[d.TokenHash] = id
			}
		}
	}
	s.devices = reg
}

func (s *Server) saveDevicesLocked() error {
	return s.store.Put("system", "devices", s.devices.devices)
}

// authenticate returns the role ("master"/"device") and device id for a bearer token.
func (s *Server) authenticate(token string) (role, deviceID string, ok bool) {
	h := hashToken(token)
	if h == hex.EncodeToString(s.tokenHash[:]) {
		return roleMaster, "", true
	}
	s.devices.mu.Lock()
	defer s.devices.mu.Unlock()
	id, found := s.devices.byHash[h]
	if !found {
		return "", "", false
	}
	if now := time.Now(); now.Sub(s.devices.seenAt[id]) > 10*time.Minute {
		s.devices.seenAt[id] = now
		s.devices.devices[id].LastSeen = nowUTC().Format(timeFormat)
		_ = s.saveDevicesLocked()
	}
	return roleDevice, id, true
}

func withAuth(r *http.Request, role, device string) *http.Request {
	ctx := context.WithValue(r.Context(), ctxRole, role)
	ctx = context.WithValue(ctx, ctxDevice, device)
	return r.WithContext(ctx)
}

func requireMaster(w http.ResponseWriter, r *http.Request) bool {
	if r.Context().Value(ctxRole) != roleMaster {
		writeJSONMessage(w, http.StatusForbidden, "error", "master token required")
		return false
	}
	return true
}

// POST /v1/devices/enroll {device_id, name} (master) → {device_id, token}. Re-enrolling a device
// rotates its token (the previous one stops working immediately).
func (s *Server) handleEnrollDevice(w http.ResponseWriter, r *http.Request) {
	if !requireMaster(w, r) {
		return
	}
	var req struct {
		DeviceID string `json:"device_id"`
		Name     string `json:"name"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&req); err != nil {
		writeJSONMessage(w, http.StatusBadRequest, "error", "invalid json")
		return
	}
	req.DeviceID = strings.TrimSpace(req.DeviceID)
	if req.DeviceID == "" || len(req.DeviceID) > 200 {
		writeJSONMessage(w, http.StatusBadRequest, "error", "device_id is required")
		return
	}
	s.devices.mu.Lock()
	defer s.devices.mu.Unlock()
	token, err := s.mintDeviceTokenLocked(req.DeviceID, req.Name)
	if err != nil {
		writeJSONMessage(w, http.StatusInternalServerError, "error", err.Error())
		return
	}
	writeJSONResponse(w, http.StatusOK, map[string]string{"device_id": req.DeviceID, "token": token})
}

// mintDeviceTokenLocked issues a fresh token for deviceID (replacing any previous one) and
// persists the registry. The caller holds s.devices.mu.
func (s *Server) mintDeviceTokenLocked(deviceID, name string) (string, error) {
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return "", errors.New("rng failure")
	}
	token := hex.EncodeToString(raw)
	if old, ok := s.devices.devices[deviceID]; ok {
		delete(s.devices.byHash, old.TokenHash)
	}
	rec := &deviceRecord{DeviceID: deviceID, Name: strings.TrimSpace(name), TokenHash: hashToken(token), CreatedAt: nowUTC().Format(timeFormat)}
	s.devices.devices[deviceID] = rec
	s.devices.byHash[rec.TokenHash] = deviceID
	if err := s.saveDevicesLocked(); err != nil {
		return "", errors.New("could not persist device")
	}
	return token, nil
}

// GET /v1/devices → devices without hashes (any authenticated caller).
func (s *Server) handleListDevices(w http.ResponseWriter, r *http.Request) {
	s.devices.mu.Lock()
	out := make([]deviceRecord, 0, len(s.devices.devices))
	for _, d := range s.devices.devices {
		c := *d
		c.TokenHash = ""
		out = append(out, c)
	}
	s.devices.mu.Unlock()
	sort.Slice(out, func(i, j int) bool { return out[i].DeviceID < out[j].DeviceID })
	writeJSONResponse(w, http.StatusOK, map[string]any{"devices": out, "you": r.Context().Value(ctxDevice)})
}

// DELETE /v1/devices/{id} (master) → revoke.
func (s *Server) handleRevokeDevice(w http.ResponseWriter, r *http.Request) {
	if !requireMaster(w, r) {
		return
	}
	id := r.PathValue("id")
	s.devices.mu.Lock()
	defer s.devices.mu.Unlock()
	d, ok := s.devices.devices[id]
	if !ok {
		writeJSONMessage(w, http.StatusNotFound, "error", "unknown device")
		return
	}
	d.Revoked = true
	delete(s.devices.byHash, d.TokenHash)
	if err := s.saveDevicesLocked(); err != nil {
		writeJSONMessage(w, http.StatusInternalServerError, "error", "could not persist device")
		return
	}
	writeJSONResponse(w, http.StatusOK, map[string]string{"revoked": id})
}

func writeJSONResponse(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

// ---------------------------------------------------------------------------
// Failed-auth rate limit: 10 failures per client IP in 10 minutes → 429 until the window ends.
// ---------------------------------------------------------------------------

type authLimiter struct {
	mu       sync.Mutex
	failures map[string][]time.Time
}

const (
	authWindow      = 10 * time.Minute
	authMaxFailures = 10
)

func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	// Behind Caddy on loopback: trust the first X-Forwarded-For hop only from loopback.
	if ip := net.ParseIP(host); ip != nil && ip.IsLoopback() {
		if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
			return strings.TrimSpace(strings.Split(xff, ",")[0])
		}
	}
	return host
}

func (l *authLimiter) blocked(ip string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.prune(ip)) >= authMaxFailures
}

func (l *authLimiter) fail(ip string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.failures[ip] = append(l.prune(ip), time.Now())
}

func (l *authLimiter) prune(ip string) []time.Time {
	cut := time.Now().Add(-authWindow)
	list := l.failures[ip]
	i := 0
	for i < len(list) && list[i].Before(cut) {
		i++
	}
	list = list[i:]
	if len(list) == 0 {
		delete(l.failures, ip)
	} else {
		l.failures[ip] = list
	}
	return list
}

// nowUTC and timeFormat are the api clock helpers.
const timeFormat = time.RFC3339

func nowUTC() time.Time {
	return time.Now().UTC()
}
