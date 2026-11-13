package sync

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// Hub fans ops out to connected devices over SSE (canonical; spec/sync.md
// §stream) or WebSocket (accepted). It only ever relays LoggedOps — the same
// frames pull returns — never content it cannot read.
//
// Limits themselves live in the handlers: each device may hold ONE open
// stream; a new stream for a device replaces the previous one, so a zombie
// connection never blocks a reconnecting client.
type Hub struct {
	mu       sync.RWMutex
	streams  map[string]map[*hubStream]struct{} // deviceID → live streams
	replayer *Engine
}

func NewHub() *Hub {
	return &Hub{streams: map[string]map[*hubStream]struct{}{}}
}

type hubStream struct {
	deviceID string
	sink     chan []streamFrame // broadcast → connection writer
	kill     chan struct{}      // closed when replaced
	onKill   func()             // unblocks a handler stuck on the socket
}

// streamFrame is every JSON frame of the stream: hello, op or error. The op
// frame carries the LoggedOp with the exact shape pull uses.
type streamFrame struct {
	V       int        `json:"v"`
	Event   string     `json:"event"`
	HeadSeq uint64     `json:"head_seq,omitempty"`
	Op      *LoggedOp  `json:"op,omitempty"`
	Error   *SyncError `json:"error,omitempty"` // stream-level reports (e.g. oplog_compacted during resume)
}

func (h *Hub) register(deviceID string, onKill func()) *hubStream {
	st := &hubStream{
		deviceID: deviceID,
		sink:     make(chan []streamFrame, 256),
		kill:     make(chan struct{}),
		onKill:   onKill,
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.streams[deviceID] == nil {
		h.streams[deviceID] = map[*hubStream]struct{}{}
	}
	for old := range h.streams[deviceID] { // one stream per device: replace
		delete(h.streams[deviceID], old)
		close(old.kill)
		if old.onKill != nil {
			old.onKill()
		}
	}
	h.streams[deviceID][st] = struct{}{}
	return st
}

func (h *Hub) unregister(st *hubStream) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if set := h.streams[st.deviceID]; set != nil {
		delete(set, st)
		if len(set) == 0 {
			delete(h.streams, st.deviceID)
		}
	}
	select {
	case <-st.kill: // already replaced: nothing to close
	default:
		close(st.kill)
	}
}

// BroadcastOps offers fresh ops to every live stream. Slow consumers drop
// frames rather than blocking a push — a dropped op is recovered by the
// client's pull, and reconnects replay from Last-Event-ID.
func (h *Hub) BroadcastOps(ops []LoggedOp) {
	if len(ops) == 0 {
		return
	}
	frames := make([]streamFrame, len(ops))
	for i, op := range ops {
		o := op
		frames[i] = streamFrame{V: 1, Event: "op", Op: &o}
	}
	h.mu.RLock()
	defer h.mu.RUnlock()
	for _, set := range h.streams {
		for st := range set {
			select {
			case st.sink <- frames:
			default: // buffer full: discard on this stream only
			}
		}
	}
}

// parseResume reads the resume point of a stream request: Last-Event-ID header
// (SSE reconnect) or ?since_seq= (WebSocket, curl).
func parseResume(r *http.Request, fallback uint64) uint64 {
	if v := r.Header.Get("Last-Event-ID"); v != "" {
		if n, err := strconv.ParseUint(v, 10, 64); err == nil {
			return n
		}
	}
	if v := r.URL.Query().Get("since_seq"); v != "" {
		if n, err := strconv.ParseUint(v, 10, 64); err == nil {
			return n
		}
	}
	return fallback
}

func (h *Hub) headSeq() uint64 {
	if h.replayer == nil {
		return 0
	}
	head, err := h.replayer.store.OplogHead()
	if err != nil {
		return 0
	}
	return head
}

func (h *Hub) hello() []byte {
	raw, _ := json.Marshal(streamFrame{V: 1, Event: "hello", HeadSeq: h.headSeq()})
	return raw
}

// replaySince writes the stored ops above since as op frames — the catch-up a
// reconnecting stream needs. When the floor swallowed them the frame is the
// exact oplog_compacted error (the client bootstraps; never a silent hole).
func (h *Hub) replaySince(write func(frame streamFrame), since uint64) {
	if h.replayer == nil || since == 0 {
		return
	}
	limit := h.replayer.limits.PullLimitMax
	ops, serr := h.replayer.OpsForReplay(since, limit)
	if serr != nil {
		write(streamFrame{V: 1, Event: "error", Error: serr})
		return
	}
	for _, op := range ops {
		o := op
		write(streamFrame{V: 1, Event: "op", Op: &o})
	}
}

// HandleSSE writes the canonical text/event-stream of /3:
//
//	data: {"v":1,"event":"hello","head_seq":9142}
//	id: 9143
//	data: {"v":1,"event":"op","op":{…same shape as pull…}}
//	: keep-alive                                   (every 20 s)
//
// The order is hello → replay (Last-Event-ID) → live frames from other
// devices, so nothing broadcast during the replay is lost (it queues in the
// sink and flushes right after it).
func (h *Hub) HandleSSE(w http.ResponseWriter, r *http.Request, deviceID string) {
	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "Streaming unsupported", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Accel-Buffering", "no")
	w.WriteHeader(http.StatusOK)

	st := h.register(deviceID, nil)
	defer h.unregister(st)

	// hello + replay: written directly, before the drain goroutine exists.
	if _, err := fmt.Fprintf(w, "data: %s\n\n", string(h.hello())); err != nil {
		return
	}
	flusher.Flush()
	h.replaySince(func(frame streamFrame) { writeSSEFrame(w, flusher, frame) }, parseResume(r, 0))

	// Drain: the only writer from here on (frames + keep-alives). The handler
	// only returns once the drain is done — net/http reclaims the Response
	// writer the moment the handler returns.
	drained := make(chan struct{})
	go func() {
		defer close(drained)
		for {
			select {
			case frames := <-st.sink:
				for _, frame := range frames {
					writeSSEFrame(w, flusher, frame)
				}
			case <-st.kill:
				return
			case <-r.Context().Done():
				return
			case <-time.After(20 * time.Second):
				if _, err := fmt.Fprint(w, ": keep-alive\n\n"); err != nil {
					return
				}
				flusher.Flush()
			}
		}
	}()
	select {
	case <-st.kill:
	case <-r.Context().Done():
	case <-drained:
	}
	<-drained
}

func writeSSEFrame(w http.ResponseWriter, flusher http.Flusher, frame streamFrame) {
	raw, err := json.Marshal(frame)
	if err != nil {
		return
	}
	if frame.Event == "op" && frame.Op != nil {
		fmt.Fprintf(w, "id: %d\n", frame.Op.Seq)
	}
	if _, err := fmt.Fprintf(w, "data: %s\n\n", string(raw)); err != nil {
		return
	}
	flusher.Flush()
}

var wsUpgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
	CheckOrigin: func(r *http.Request) bool {
		return true // Origin checked by the top-level CORS middleware
	},
}

// HandleWebSocket upgrades and speaks the same JSON frames ({v, event, op})
// that SSE sends, one per message; ?since_seq=N replays stored ops first.
func (h *Hub) HandleWebSocket(w http.ResponseWriter, r *http.Request, deviceID string) {
	ws, err := wsUpgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer ws.Close()

	st := h.register(deviceID, func() { ws.Close() })
	defer h.unregister(st)

	if err := ws.WriteJSON(streamFrame{V: 1, Event: "hello", HeadSeq: h.headSeq()}); err != nil {
		return
	}
	h.replaySince(func(frame streamFrame) { _ = ws.WriteJSON(frame) }, parseResume(r, 0))

	// Drain: the only socket writer from here on.
	go func() {
		for {
			select {
			case frames := <-st.sink:
				for _, frame := range frames {
					if err := ws.WriteJSON(frame); err != nil {
						ws.Close()
						return
					}
				}
			case <-st.kill:
				ws.Close()
				return
			}
		}
	}()

	// Read loop: nothing meaningful comes upstream in /3 (echo collab was a
	// /v1 content feature); drain and detect closure.
	for {
		if _, _, err := ws.ReadMessage(); err != nil {
			return
		}
	}
}
