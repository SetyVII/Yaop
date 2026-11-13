package simclient

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"sort"
	"strconv"

	"yaop-server/internal/simclient/model"
)

// Device is the reference CLIENT of the /3 protocol used by the e2e suites:
// it holds a vault key, stamps every save (spec/merge.md), seals ops with the
// envelopes of spec/crypto.md, pushes/pulls over simclient.Client, and merges
// every incoming op deterministically — the same behavior a real client
// implements. The server BinaryCodebase never references this file.

type Device struct {
	Name    string // device_id (must match the enrolled token)
	vk      []byte
	epoch   uint32
	client  *Client
	clock   *model.HybridLogicalClock
	objects map[string]model.Object // local state by REAL object id
	kinds   map[string]string       // real id → op kind it travels as (obj/type/asset_meta)
	outbox  []Op                    // sealed, pending ops
	pushed  map[string]uint64       // op_id → seq this device already sent
	lastSeq uint64
}

// NewDevice binds a device identity to a server (url+token already enrolled).
func NewDevice(name, url, token string, vk []byte, epoch uint32) *Device {
	node := deviceNode(name)
	return &Device{
		Name:    name,
		vk:      vk,
		epoch:   epoch,
		client:  NewClient(url, token),
		clock:   model.NewClock(node),
		objects: map[string]model.Object{},
		kinds:   map[string]string{},
		pushed:  map[string]uint64{},
	}
}

// deviceNode is the HLC node of a device: first 8 hex of SHA-256(name).
func deviceNode(name string) string {
	sum := sha256.Sum256([]byte(name))
	return hex.EncodeToString(sum[:])[:8]
}

// Client exposes the raw transport (tests drive endpoints directly).
func (d *Device) Client() *Client { return d.client }

// PendingOps returns the sealed ops waiting for the next push (R1 tests replay
// them verbatim after a "lost" response).
func (d *Device) PendingOps() []Op {
	return append([]Op(nil), d.outbox...)
}

// PushedSeq returns the seq the server assigned a pushed op_id.
func (d *Device) PushedSeq(opID string) (uint64, bool) {
	seq, ok := d.pushed[opID]
	return seq, ok
}

// VKForTest exposes the vault key to the e2e suites (crafting raw ops).
func (d *Device) VKForTest() []byte { return d.vk }

// LastSeq exposes the client's pull cursor.
func (d *Device) LastSeq() uint64 { return d.lastSeq }

// State is the local object store (by real id).
func (d *Device) State() map[string]model.Object {
	out := map[string]model.Object{}
	for k, v := range d.objects {
		out[k] = v
	}
	return out
}

func (d *Device) Object(id string) (model.Object, bool) {
	o, ok := d.objects[id]
	return o, ok
}

// cloneObj deep-copies through the wire JSON (the only canonical form).
func cloneObj(o model.Object) model.Object {
	raw, _ := json.Marshal(o)
	var out model.Object
	if err := json.Unmarshal(raw, &out); err != nil {
		return o
	}
	return out
}

// Save edits an object and stamps it (spec/merge.md §Stamp). kind is the op
// kind it travels as: "obj", "type" or "asset_meta".
func (d *Device) Save(kind, id string, edit func(o *model.Object)) {
	var prev *model.Object
	var next model.Object
	if stored, ok := d.objects[id]; ok {
		p := cloneObj(stored) // prev and next must be INDEPENDENT copies:
		n := cloneObj(stored) // sharing the Properties map would hide every
		prev = &p             // change from StampObject and nothing gets stamped.
		next = n
	} else {
		next = model.Object{ID: id, Properties: map[string]any{model.PropBlocks: []any{}}}
	}
	edit(&next)
	now := d.clock.Now()
	stamped := StampObject(prev, next, now)
	d.objects[id] = stamped
	d.kinds[id] = kind
	d.enqueue(kind, id, stamped)
}

// Delete drafts nothing in /3: object deletion is content (TrashedAt) inside
// the encrypted state — the server has no delete op.
func (d *Device) Delete(id string) {
	d.Save("obj", id, func(o *model.Object) { o.TrashedAt = model.NowUTC() })
}

func (d *Device) enqueue(kind, realID string, o model.Object) {
	payload, err := json.Marshal(o)
	if err != nil {
		panic(err)
	}
	op, err := BuildOp(d.vk, d.epoch, kind, realID, payload)
	if err != nil {
		panic(err)
	}
	op.DeviceID = d.Name
	op.OpID = NewOpID()
	d.outbox = append(d.outbox, op)
}

// pushOutbox flushes pending ops in one push, remembering op_id → seq (R1: a
// client that loses the response replays the same op_id, never a new one).
func (d *Device) pushOutbox() bool {
	if len(d.outbox) == 0 {
		return false
	}
	resp, cerr := d.client.Push(d.outbox)
	if cerr != nil {
		panic(fmt.Sprintf("%s push: %v", d.Name, cerr))
	}
	for i, r := range resp.Results {
		d.pushed[d.outbox[i].OpID] = r.Seq
	}
	d.outbox = nil
	return true
}

// appliedOp merges one incoming op into the local state (spec/sync.md: pull
// ops are ALWAYS merged — full state, deterministic, idempotent). It returns
// whether the remote op was new to this device's view.
func (d *Device) applyOp(op LoggedOp) (wasNew bool) {
	if op.DeviceID == d.Name {
		if _, mine := d.pushed[op.OpID]; !mine {
			return false // this device produced a different op with same id? ignore
		}
	}
	payload, err := OpenOpEnvelope(d.vk, op)
	if err != nil {
		// Not ours to open: another key epoch (revoked device paths) — skip.
		return false
	}
	switch op.Kind {
	case "obj", "type", "asset_meta":
		var remote model.Object
		if err := json.Unmarshal(payload, &remote); err != nil {
			return false
		}
		if op.Kind == "obj" && remote.ID == "" {
			return false // malformed envelope content: ignore
		}
		if op.Kind == "type" && remote.ID == "" {
			return false
		}
		d.kinds[remote.ID] = op.Kind
		local, hasLocal := d.objects[remote.ID]
		if !hasLocal {
			d.clock.Receive(remote.HLC)
			d.objects[remote.ID] = remote
			return true
		}
		merged := MergeObjects(local, remote)
		d.clock.Receive(merged.HLC)
		canonM, canonR, canonL := canonOf(merged), canonOf(remote), canonOf(local)
		if canonM == canonR && canonM == canonL {
			return false // already converged with this very state
		}
		d.objects[remote.ID] = merged
		if canonM != canonR {
			// The merge result differs from what the remote pushed: /3 clients
			// push the merged state back so everyone converges.
			d.enqueue(op.Kind, remote.ID, merged)
		}
		return canonM != canonL
	default:
		// snapshot/derived/node_request ops exist in the log; this reference
		// client consumes snapshots only through the bootstrap flow.
		return false
	}
}

func canonOf(o model.Object) string {
	raw, _ := json.Marshal(o)
	return string(raw)
}

// pullAll fetches every new page and merges; returns ops that changed the
// local view.
func (d *Device) pullAll() int {
	applied := 0
	for round := 0; round < 100; round++ {
		resp, cerr := d.client.Pull(d.lastSeq, 0)
		if cerr != nil {
			panic(fmt.Sprintf("%s pull: %v", d.Name, cerr))
		}
		for _, op := range resp.Ops {
			if d.applyOp(op) {
				applied++
			}
			d.lastSeq = op.Seq
		}
		if !resp.HasMore {
			d.lastSeq = resp.HeadSeq
			return applied
		}
	}
	return applied
}

// Sync runs the client cycle until stable: push pending ops, pull and merge,
// and push diverge-fixed states. Deterministic and idempotent.
func (d *Device) Sync() {
	for round := 0; round < 10; round++ {
		pushed := d.pushOutbox()
		applied := d.pullAll()
		if !pushed && applied == 0 {
			return
		}
	}
}

// ---------------------------------------------------------------------------
// Assets: ciphertext chunks over the resumable session (spec/sync.md §Assets)
// ---------------------------------------------------------------------------

// SealChunk seals one chunk with the exact chunk AAD, uncompressed (binaries).
func SealChunk(vk []byte, epoch uint32, wireID string, n int, plain []byte) ([]byte, error) {
	// AAD = "assets/<wire_id>/chunk/<n>": SealEnvelope's AAD(domain, wireID)
	// with wireID = "<wire>/chunk/<n>" produces exactly that string.
	return SealEnvelope(Subkey(vk, DomainAssets), epoch, DomainAssets,
		wireID+"/chunk/"+strconv.Itoa(n), plain, false, nil)
}

// OpenChunk unwraps chunk n of an asset.
func OpenChunk(vk []byte, wireID string, n int, env []byte) ([]byte, error) {
	return OpenEnvelopeWith(vk, DomainAssets, wireID, n, env)
}

func OpenEnvelopeWith(vk []byte, domain, wireID string, n int, env []byte) ([]byte, error) {
	payload, _, _, err := OpenEnvelope(Subkey(vk, domain), env, domain,
		wireID+"/chunk/"+strconv.Itoa(n))
	return payload, err
}

// UploadAsset chunks the plaintext, seals every chunk, uploads the resumable
// session, and pushes the encrypted asset_meta (MIME and filename ONLY here).
func (d *Device) UploadAsset(mediaObjectID, assetRealID string, plain []byte, mime, filename string, chunkSize int) error {
	if chunkSize <= 0 {
		chunkSize = 4 << 20
	}
	wire := WireIDOf(d.vk, assetRealID)
	nChunks := (len(plain) + chunkSize - 1) / chunkSize
	if nChunks == 0 {
		nChunks = 1
	}
	for n := 0; n < nChunks; n++ {
		end := (n + 1) * chunkSize
		if end > len(plain) {
			end = len(plain)
		}
		env, err := SealChunk(d.vk, d.epoch, wire, n, plain[n*chunkSize:end])
		if err != nil {
			return err
		}
		if _, cerr := d.client.PutAssetChunk(wire, n, nChunks, env); cerr != nil {
			return cerr
		}
	}

	// The media object carries the asset metadata, encrypted.
	meta := model.Object{
		ID:     mediaObjectID,
		TypeID: "media",
		HLC:    d.clock.Now(),
		Properties: map[string]any{
			"asset_wire_id":    wire,
			"chunks":           nChunks,
			"chunk_size":       chunkSize,
			"total_plain_size": len(plain),
			"mime":             mime,
			"filename":         filename,
		},
	}
	d.Save("asset_meta", mediaObjectID, func(o *model.Object) {
		o.TypeID = "media"
		o.Properties = meta.Properties
		o.ID = mediaObjectID
	})
	return nil
}

// DownloadAsset fetches every chunk and unwraps it (Range per chunk).
func (d *Device) DownloadAsset(assetRealID string, chunks int) ([]byte, error) {
	wire := WireIDOf(d.vk, assetRealID)
	var out []byte
	for n := 0; n < chunks; n++ {
		env, cerr := d.client.GetAssetChunk(wire, n)
		if cerr != nil {
			return nil, cerr
		}
		plain, err := OpenChunk(d.vk, wire, n, env)
		if err != nil {
			return nil, err
		}
		out = append(out, plain...)
	}
	return out, nil
}

// ---------------------------------------------------------------------------
// Bootstrap snapshot (spec/sync.md §Snapshot de bootstrap)
// ---------------------------------------------------------------------------

type BootstrapAsset struct {
	WireID         string `json:"wire_id"`
	Chunks         int    `json:"chunks"`
	TotalPlainSize int64  `json:"total_plain_size"`
}

type BootstrapPayload struct {
	V          uint32           `json:"v"`
	KeyEpoch   uint32           `json:"key_epoch"`
	CoveredSeq uint64           `json:"covered_seq"`
	Objects    []model.Object   `json:"objects"`
	Assets     []BootstrapAsset `json:"assets"`
}

// BuildBootstrap produces the snapshot payload of the device's current state.
func (d *Device) BuildBootstrap(coveredSeq uint64) ([]byte, error) {
	ids := make([]string, 0, len(d.objects))
	for id := range d.objects {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	objects := make([]model.Object, 0, len(ids))
	for _, id := range ids {
		objects = append(objects, d.objects[id])
	}
	payload, err := json.Marshal(BootstrapPayload{
		V: 1, KeyEpoch: d.epoch, CoveredSeq: coveredSeq,
		Objects: objects, Assets: []BootstrapAsset{},
	})
	if err != nil {
		return nil, err
	}
	return payload, nil
}

// PushBootstrap uploads a bootstrap snapshot op and promotes it — the floor
// moves to coveredSeq and the log below it may go.
func (d *Device) PushBootstrap(coveredSeq uint64) (opID string, seq uint64, err error) {
	payload, err := d.BuildBootstrap(coveredSeq)
	if err != nil {
		return "", 0, err
	}
	op, err := BuildOp(d.vk, d.epoch, "snapshot", "bootstrap", payload)
	if err != nil {
		return "", 0, err
	}
	op.DeviceID = d.Name
	op.OpID = NewOpID()
	resp, cerr := d.client.Push([]Op{op})
	if cerr != nil {
		return "", 0, cerr
	}
	seq = resp.Results[0].Seq
	if _, cerr := d.client.PromoteBootstrap(op.OpID, coveredSeq); cerr != nil {
		return op.OpID, seq, cerr
	}
	return op.OpID, seq, nil
}

// ConsumeBootstrap is the new-device path: snapshot first (states applied
// whole, no merge), then pull since covered_seq.
func (d *Device) ConsumeBootstrap() error {
	c := d.client
	boot, cerr := c.GetBootstrap()
	if cerr != nil {
		return cerr
	}
	env, err := hexB64(boot.Blob)
	if err != nil {
		return err
	}
	wire := WireIDOf(d.vk, "bootstrap")
	payload, _, _, err := OpenEnvelope(Subkey(d.vk, DomainSnapshots), env, DomainSnapshots, wire)
	if err != nil {
		return err
	}
	var bp BootstrapPayload
	if err := json.Unmarshal(payload, &bp); err != nil {
		return err
	}
	for _, o := range bp.Objects {
		d.objects[o.ID] = o
		d.kinds[o.ID] = "obj"
		d.clock.Receive(o.HLC)
	}
	d.lastSeq = bp.CoveredSeq
	return nil
}

func hexB64(b64 string) ([]byte, error) {
	return b64Decode(b64)
}

func b64Decode(s string) ([]byte, error) {
	return base64.StdEncoding.DecodeString(s)
}
