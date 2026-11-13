package sync_test

// E2E suites of the blind /3 protocol: three reference devices (simclient)
// against a real HTTP server, covering the canonical scenario of
// docs/product.md §22.1 and the R1–R6 requirements rewritten over /3:
//
//	R1  a retried push after a lost response never duplicates the log
//	R2  an object and the one mentioning it, created offline in one batch
//	R3  a pull never overwrites objects with pending local edits: the CLIENT
//	    merges (spec/merge.md) and pushes the converged state back
//	R4  the SSE stream carries live ops and resumes from Last-Event-ID
//	R5  a big asset cuts and resumes, chunked and encrypted, and another
//	    device downloads and unwraps it whole
//	R6  a stale third device does not resurrect deleted blocks (tombstones)

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"yaop-server/internal/api"
	"yaop-server/internal/crypto"
	"yaop-server/internal/simclient"
	"yaop-server/internal/simclient/model"
	"yaop-server/internal/store"
)

type testWorld struct {
	t           *testing.T
	ts          *httptest.Server
	dataDir     string
	masterToken string
	vk          []byte
}

func newTestWorld(t *testing.T) *testWorld {
	t.Helper()
	dir := t.TempDir()
	key, err := crypto.GenerateRandomKey()
	if err != nil {
		t.Fatal(err)
	}
	vault, err := crypto.NewVault(key)
	if err != nil {
		t.Fatal(err)
	}
	st, err := store.Open(filepath.Join(dir, "yaop.db"), vault)
	if err != nil {
		t.Fatal(err)
	}

	master := "master-token-for-the-blind-server-e2e-tests"
	srv := api.NewServer(st, "vault-e2e", master, nil, "e2e-fingerprint")
	ts := httptest.NewServer(srv.Handler())
	t.Cleanup(func() {
		ts.Close()
		_ = st.Close()
	})

	vk := make([]byte, 32)
	if _, err := readRand(vk); err != nil {
		t.Fatal(err)
	}
	return &testWorld{t: t, ts: ts, dataDir: dir, masterToken: master, vk: vk}
}

func (w *testWorld) newDevice(name string) *simclient.Device {
	w.t.Helper()
	body, _ := json.Marshal(map[string]string{"device_id": name})
	req, _ := http.NewRequest(http.MethodPost, w.ts.URL+"/v1/devices/enroll", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+w.masterToken)
	req.Header.Set("Content-Type", "application/json")
	resp, err := w.ts.Client().Do(req)
	if err != nil {
		w.t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		w.t.Fatalf("enroll %s: %d", name, resp.StatusCode)
	}
	var out struct {
		Token string `json:"token"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		w.t.Fatal(err)
	}
	return simclient.NewDevice(name, w.ts.URL, out.Token, w.vk, 1)
}

func readRand(b []byte) (int, error) {
	f, err := os.Open("/dev/urandom")
	if err != nil {
		return 0, err
	}
	defer f.Close()
	return f.Read(b)
}

// ---------------------------------------------------------------------------
// Shared assertion helpers
// ---------------------------------------------------------------------------

func para(id, text string) map[string]any {
	return map[string]any{"id": id, "type": "paragraph", "text": text}
}

func setBlocks(o *model.Object, blocks ...map[string]any) {
	list := make([]any, len(blocks))
	for i, b := range blocks {
		list[i] = b
	}
	o.Properties[model.PropBlocks] = list
}

func blocksOf(t *testing.T, o model.Object) []map[string]any {
	t.Helper()
	raw, _ := json.Marshal(o.Properties[model.PropBlocks])
	var list []map[string]any
	if err := json.Unmarshal(raw, &list); err != nil {
		t.Fatal(err)
	}
	return list
}

func blocksOfValue(t *testing.T, o any) []map[string]any {
	t.Helper()
	switch v := o.(type) {
	case *model.Object:
		return blocksOf(t, *v)
	case model.Object:
		return blocksOf(t, v)
	}
	t.Fatalf("blocksOfValue: bad type %T", o)
	return nil
}

func textOf(t *testing.T, o model.Object) []string {
	t.Helper()
	var out []string
	for _, b := range blocksOf(t, o) {
		out = append(out, fmt.Sprintf("%s:%v", b["id"], b["text"]))
	}
	return out
}

func wantText(t *testing.T, got, want []string, what string) {
	t.Helper()
	if strings.Join(strings.Fields(fmt.Sprint(got)), " ") != strings.Join(strings.Fields(fmt.Sprint(want)), " ") {
		t.Fatalf("%s: blocks %v want %v", what, got, want)
	}
}

func deviceView(t *testing.T, d *simclient.Device, id string) model.Object {
	t.Helper()
	o, ok := d.Object(id)
	if !ok {
		t.Fatalf("device %s lacks object %s", fmt.Sprintf("%v", d), id)
	}
	return o
}

func deviceHas(d *simclient.Device, id string) bool {
	_, ok := d.Object(id)
	return ok
}

func assertConverged(t *testing.T, id string, devs ...*simclient.Device) model.Object {
	t.Helper()
	base := deviceView(t, devs[0], id)
	baseCanon := canonJSON(t, base)
	for _, other := range devs[1:] {
		if canonJSON(t, deviceView(t, other, id)) != baseCanon {
			t.Fatalf("devices diverged on %s\n  got   (%s) %s\n  other (%s) %s", id,
				devs[0].State()[id].Title, baseCanon,
				"", canonJSON(t, deviceView(t, other, id)))
		}
	}
	return base
}

func canonJSON(t *testing.T, v any) string {
	t.Helper()
	raw, _ := json.Marshal(v)
	var generic any
	_ = json.Unmarshal(raw, &generic)
	out, _ := json.Marshal(generic)
	return string(out)
}

// ---------------------------------------------------------------------------
// The canonical scenario (spec §22.1), three devices, both rounds
// ---------------------------------------------------------------------------

func TestE2E3_CanonicalScenario(t *testing.T) {
	w := newTestWorld(t)
	pc := w.newDevice("dev_pc")
	phone := w.newDevice("dev_phone")
	tablet := w.newDevice("dev_tablet")

	// PC creates the note; everybody syncs.
	pc.Save("obj", "n1", func(o *model.Object) {
		o.Title = "Plan"
		o.TypeID = model.CanonicalTypeNote
		setBlocks(o, para("p1", "plan"))
	})
	pc.Sync()
	phone.Sync()
	tablet.Sync()
	assertConverged(t, "n1", pc, phone, tablet)

	// The phone goes offline: edits p1, appends m1 and captures a weblink.
	phone.Save("obj", "n1", func(o *model.Object) {
		bs := blocksOf(t, *o)
		bs[0]["text"] = "plan (phone)"
		setBlocks(o, append(bs, para("m1", "phone idea - unique-in-e2e"))...)
	})
	phone.Save("obj", "w1", func(o *model.Object) {
		o.TypeID = model.CanonicalTypeWeb
		o.Title = "Article captured offline"
		o.Properties["url"] = "https://example.org/captura"
	})

	// Meanwhile the PC inserts c1 in the middle and renames (later in wall
	// time so stamps order strictly: HLC + ms sleep, deterministic merge).
	time.Sleep(4 * time.Millisecond)
	pc.Save("obj", "n1", func(o *model.Object) {
		bs := blocksOfValue(t, o)
		o.Title = "Plan semanal"
		setBlocks(o, bs[0], para("c1", "pc note"))
	})
	pc.Sync()

	// The phone comes back: sync, then the tablet (the third device that was
	// here all along sees the end state).
	phone.Sync()
	pc.Sync()
	tablet.Sync()
	phone.Sync()
	pc.Sync()

	merged := assertConverged(t, "n1", pc, phone, tablet)
	want := []string{"p1:plan (phone)", "c1:pc note", "m1:phone idea - unique-in-e2e"}
	wantText(t, textOf(t, merged), want, "round 1 blocks")
	if merged.Title != "Plan semanal" {
		t.Fatalf("title after merge: %q", merged.Title)
	}
	if !deviceHas(pc, "w1") {
		t.Fatal("pc never received the phone's offline capture")
	}
	assertConverged(t, "w1", pc, phone, tablet)

	// Round 2: the phone deletes c1 offline; the PC edits p1 afterwards.
	phone.Save("obj", "n1", func(o *model.Object) {
		bs := blocksOfValue(t, o)
		kept := []map[string]any{bs[0], bs[2]}
		setBlocks(o, kept...)
	})
	time.Sleep(4 * time.Millisecond)
	pc.Save("obj", "n1", func(o *model.Object) {
		bs := blocksOfValue(t, o)
		bs[0]["text"] = "plan final"
		setBlocks(o, bs...)
	})
	pc.Sync()
	phone.Sync()
	tablet.Sync()
	pc.Sync()
	phone.Sync()

	merged = assertConverged(t, "n1", pc, phone, tablet)
	wantText(t, textOf(t, merged), []string{"p1:plan final", "m1:phone idea - unique-in-e2e"}, "round 2: nothing resurrects")
}

// R1: a retried push after losing the response returns the original seq and
// the log never grows.
func TestE2E3_R1_RetriedPushIsIdempotent(t *testing.T) {
	w := newTestWorld(t)
	phone := w.newDevice("dev_phone")

	phone.Save("obj", "n1", func(o *model.Object) {
		o.Title = "Una"
		setBlocks(o, para("b1", "x"))
	})
	ops := phone.PendingOps()
	if len(ops) != 1 {
		t.Fatalf("pending ops: %d", len(ops))
	}
	phone.Sync()
	seq1, ok := phone.PushedSeq(ops[0].OpID)
	if !ok {
		t.Fatal("the device must track the seq of its own push")
	}

	// The Wi-Fi died AFTER the server applied it: the client replays the same
	// op_id (spec/sync.md push, idempotency).
	replay, cerr := phone.Client().Push(ops)
	if cerr != nil {
		t.Fatal(cerr)
	}
	if replay.Results[0].Seq != seq1 {
		t.Fatalf("retry got seq %d, want the original %d", replay.Results[0].Seq, seq1)
	}
	if phoneMustBeOnline(t, phone).HeadSeq != replay.HeadSeq {
		t.Fatalf("the log grew on a retry: %d", replay.HeadSeq)
	}

	// And the log really holds exactly one op for that op_id: pull proves it.
	pull, cerr := phone.Client().Pull(0, 0)
	if cerr != nil {
		t.Fatal(cerr)
	}
	seen := 0
	for _, op := range pull.Ops {
		if op.OpID == ops[0].OpID {
			seen++
		}
	}
	if seen != 1 {
		t.Fatalf("op %s appears %d times in the log", ops[0].OpID, seen)
	}
}

func phoneMustBeOnline(t *testing.T, d *simclient.Device) *simclient.StatusResponse {
	t.Helper()
	st, cerr := d.Client().Status()
	if cerr != nil {
		t.Fatal(cerr)
	}
	return st
}

// R2: the object A and the note that mentions A, both created offline in the
// same push; the other device receives the pair intact (the blind server
// never needed to know the order — ids converge regardless).
func TestE2E3_R2_MentionOfObjectCreatedInSameBatch(t *testing.T) {
	for _, mentionFirst := range []bool{false, true} {
		w := newTestWorld(t)
		pc := w.newDevice("dev_pc")
		phone := w.newDevice("dev_phone")

		createA := func() {
			pc.Save("type", "daily-2026-02-14", func(o *model.Object) { o.Title = "Valentine's Day" })
		}
		createB := func() {
			pc.Save("obj", "nota-menciona-diario", func(o *model.Object) {
				o.Title = "Note with a mention"
				setBlocks(o, map[string]any{"id": "b1", "type": "paragraph", "text": "ver el diario",
					"runs": []any{map[string]any{"text": "diario", "object_id": "daily-2026-02-14"}}})
			})
		}
		if mentionFirst {
			createB()
			createA()
		} else {
			createA()
			createB()
		}
		pc.Sync() // both ops in one push (the outbox flushes as a batch)
		phone.Sync()

		note := deviceView(t, phone, "nota-menciona-diario")
		blocks := blocksOf(t, note)
		runs, _ := blocks[0]["runs"].([]any)
		if len(runs) != 1 {
			t.Fatalf("mentionFirst=%v: runs %v", mentionFirst, blocks[0])
		}
		run, _ := runs[0].(map[string]any)
		if run["object_id"] != "daily-2026-02-14" {
			t.Fatalf("mentionFirst=%v: mention target %v", mentionFirst, run["object_id"])
		}
		if !deviceHas(phone, "daily-2026-02-14") {
			t.Fatalf("mentionFirst=%v: the mentioned object never arrived", mentionFirst)
		}
		assertConverged(t, "nota-menciona-diario", pc, phone)
	}
}

// R3: a pull never overwrites locals: the phone edits p1 offline while the PC
// edits m1; both keep their own change and converge with the union.
func TestE2E3_R3_ConcurrentEditsMergeWithoutOverwrite(t *testing.T) {
	w := newTestWorld(t)
	pc := w.newDevice("dev_pc")
	phone := w.newDevice("dev_phone")

	pc.Save("obj", "n1", func(o *model.Object) {
		o.Title = "Ediciones concurrentes"
		setBlocks(o, para("p1", "one"), para("m1", "two"))
	})
	pc.Sync()
	phone.Sync()

	phone.Save("obj", "n1", func(o *model.Object) {
		bs := blocksOfValue(t, o)
		bs[0]["text"] = "one (phone)"
		setBlocks(o, bs...)
	})
	time.Sleep(4 * time.Millisecond)
	pc.Save("obj", "n1", func(o *model.Object) {
		bs := blocksOfValue(t, o)
		bs[1]["text"] = "two (pc)"
		setBlocks(o, bs...)
	})
	pc.Sync()
	phone.Sync()
	pc.Sync()
	phone.Sync()

	merged := assertConverged(t, "n1", pc, phone)
	wantText(t, textOf(t, merged), []string{"p1:one (phone)", "m1:two (pc)"}, "concurrent edits union")
}

// R6: a third device that was offline a long time does not resurrect a block
// another device deleted, no matter how stale its state is.
func TestE2E3_R6_StaleTabletDoesNotResurrectDeletedBlock(t *testing.T) {
	w := newTestWorld(t)
	pc := w.newDevice("dev_pc")
	phone := w.newDevice("dev_phone")
	tablet := w.newDevice("dev_tablet")

	pc.Save("obj", "n1", func(o *model.Object) {
		o.Title = "Block traffic light"
		setBlocks(o, para("b1", "kept"), para("b2", "deleted"), para("b3", "edited"))
	})
	pc.Sync()
	phone.Sync()
	tablet.Sync() // the tablet now goes offline for a long time

	// While it is offline: the tablet makes a RANCID edit of b2 it will push
	// much later; the PC concurrently edits b3; the phone deletes b2 — none
	// of them see each other until they sync.
	tablet.Save("obj", "n1", func(o *model.Object) {
		bs := blocksOfValue(t, o)
		bs[1]["text"] = "stale tablet edit"
		setBlocks(o, bs...)
	})
	time.Sleep(4 * time.Millisecond)
	pc.Save("obj", "n1", func(o *model.Object) {
		bs := blocksOfValue(t, o)
		bs[2]["text"] = "edited (pc)"
		setBlocks(o, bs...)
	})
	time.Sleep(4 * time.Millisecond)
	phone.Save("obj", "n1", func(o *model.Object) {
		bs := blocksOfValue(t, o)
		setBlocks(o, bs[0], bs[2])
	})
	phone.Sync()
	pc.Sync()

	// The tablet returns with its stale edit: b2's tombstone is newer, so the
	// rancid edit of a dead block must not resurrect it — every device
	// converges without the block coming back.
	tablet.Sync()
	pc.Sync()
	phone.Sync()
	tablet.Sync()
	pc.Sync()
	phone.Sync()

	merged := assertConverged(t, "n1", pc, phone, tablet)
	wantText(t, textOf(t, merged), []string{"b1:kept", "b3:edited (pc)"}, "R6: deleted block stays dead")
}

// R5, cross-device: a chunked encrypted asset uploaded by the PC downloads
// and decrypts whole on the phone (and its metadata travels encrypted).
func TestE2E3_R5_AssetUploadDownloadAcrossDevices(t *testing.T) {
	w := newTestWorld(t)
	pc := w.newDevice("dev_pc")
	phone := w.newDevice("dev_phone")

	plain := make([]byte, 9<<20+12345) // serveral chunks of 4 MiB
	for i := range plain {
		plain[i] = byte(i*7 + 1)
	}
	if err := pc.UploadAsset("media_foto-2026-este-es-el-nombre", "asset_foto-2026", plain,
		"image/png", "foto secreta de familia.png", 4<<20); err != nil {
		t.Fatal(err)
	}
	pc.Sync() // the asset_meta op (mime+name encrypted inside)
	phone.Sync()

	meta := deviceView(t, phone, "media_foto-2026-este-es-el-nombre")
	chunks, _ := meta.Properties["chunks"].(float64)
	if chunks < 2 {
		t.Fatalf("chunks in meta: %v", meta.Properties["chunks"])
	}
	got, err := phone.DownloadAsset("asset_foto-2026", int(chunks))
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != len(plain) {
		t.Fatalf("downloaded %d bytes want %d", len(got), len(plain))
	}
	if !bytes.Equal(got, plain) {
		t.Fatal("the asset corrupted in transit/server storage")
	}
}

// The stream (R4 in spirit: the server side of reconnects): SSE frames are
// the pull shape, Last-Event-ID resumes from the seq, and a second stream of
// the same device replaces the first.
func TestE2E3_StreamSSEAndResume(t *testing.T) {
	w := newTestWorld(t)
	phone := w.newDevice("dev_phone")
	observer := w.newDevice("dev_observer")

	// One known op exists; the stream starts.
	po := makeSeedOp(t, phone)
	if _, cerr := phone.Client().Push([]simclient.Op{po}); cerr != nil {
		t.Fatal(cerr)
	}

	resp := mustSSE(t, w.ts.URL+"/v1/sync/stream?device_id=dev_observer&token="+observer.Client().Token, "")
	scanner := bufio.NewScanner(resp.Body)
	frame := readSSEFrame(t, scanner)
	if frame["event"] != "hello" {
		t.Fatalf("first frame must be hello: %v", frame)
	}
	if head, ok := frame["head_seq"].(float64); !ok || head < 1 {
		t.Fatalf("hello head_seq: %v", frame)
	}

	// A live op arrives while the stream is open.
	time.Sleep(60 * time.Millisecond)
	live := makeSeedOp(t, phone)
	if _, cerr := phone.Client().Push([]simclient.Op{live}); cerr != nil {
		t.Fatal(cerr)
	}
	var op map[string]any
	for {
		frame = readSSEFrame(t, scanner)
		if frame == nil {
			t.Fatal("stream closed before the live op")
		}
		if frame["event"] == "op" && frame["op"].(map[string]any)["op_id"] == live.OpID {
			op = frame["op"].(map[string]any)
			break
		}
	}
	if op["wire_id"] != live.WireID || op["kind"] != live.Kind || op["blob"] != live.Blob {
		t.Fatal("the stream op must be EXACTLY the pull shape")
	}
	resp.Body.Close()

	// Reconnect with Last-Event-ID resume: only the ops above it arrive.
	time.Sleep(60 * time.Millisecond)
	third := makeSeedOp(t, phone)
	if _, cerr := phone.Client().Push([]simclient.Op{third}); cerr != nil {
		t.Fatal(cerr)
	}
	resp = mustSSE(t, w.ts.URL+"/v1/sync/stream?device_id=dev_observer&token="+observer.Client().Token, "1")
	defer resp.Body.Close()
	scanner = bufio.NewScanner(resp.Body)
	frame = readSSEFrame(t, scanner) // hello again
	if frame["event"] != "hello" {
		t.Fatalf("resume hello: %v", frame)
	}
	var opIDs []string
	for {
		f := readSSEFrame(t, scanner)
		if f == nil {
			break
		}
		if f["event"] == "op" {
			id := f["op"].(map[string]any)["op_id"].(string)
			opIDs = append(opIDs, id)
			if id == third.OpID {
				break // caught up: the stream stays open (keep-alives)
			}
		}
	}
	if len(opIDs) == 0 || opIDs[len(opIDs)-1] != third.OpID {
		t.Fatalf("resume must deliver ops after seq 1: %v", opIDs)
	}
	for _, id := range opIDs {
		if id == po.OpID {
			t.Fatal("resume replayed an op at or below Last-Event-ID")
		}
	}
}

func makeSeedOp(t *testing.T, d *simclient.Device) simclient.Op {
	t.Helper()
	op, err := simclient.BuildOp(d.VKForTest(), 1, "obj", fmt.Sprintf("seed-%d", time.Now().UnixNano()), []byte(`{"id":"seed"}`))
	if err != nil {
		t.Fatal(err)
	}
	op.DeviceID = d.Name
	op.OpID = simclient.NewOpID()
	return op
}

func mustSSE(t *testing.T, url, lastEventID string) *http.Response {
	t.Helper()
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Accept", "text/event-stream")
	if lastEventID != "" {
		req.Header.Set("Last-Event-ID", lastEventID)
	}
	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != http.StatusOK || !strings.HasPrefix(resp.Header.Get("Content-Type"), "text/event-stream") {
		t.Fatalf("SSE open: %d %s", resp.StatusCode, resp.Header.Get("Content-Type"))
	}
	return resp
}

// readSSEFrame parses one `data:` frame (comments/keep-alives skipped). It
// returns nil at EOF.
func readSSEFrame(t *testing.T, scanner *bufio.Scanner) map[string]any {
	t.Helper()
	for scanner.Scan() {
		line := scanner.Text()
		if !strings.HasPrefix(line, "data: ") {
			continue
		}
		var frame map[string]any
		if err := json.Unmarshal([]byte(strings.TrimPrefix(line, "data: ")), &frame); err != nil {
			t.Fatalf("bad frame %q: %v", line, err)
		}
		return frame
	}
	if err := scanner.Err(); err != nil {
		t.Fatalf("sse read: %v", err)
	}
	return nil
}

// Bootstrap + compaction e2e (the compact fixture's model, live): after the
// node of trust uploads a snapshot covering head_seq, a brand-new device
// converges from the snapshot alone, and pulls below the floor answer the
// exact oplog_compacted error pointing at the bootstrap.
func TestE2E3_BootstrapNewDeviceAndCompaction(t *testing.T) {
	w := newTestWorld(t)
	pc := w.newDevice("dev_pc")
	phone := w.newDevice("dev_phone")

	pc.Save("obj", "n1", func(o *model.Object) {
		o.Title = "Mundo pre-bootstrap"
		setBlocks(o, para("b1", "contenido persistido"))
	})
	pc.Save("obj", "n2", func(o *model.Object) { o.Title = "Segunda" })
	pc.Sync()
	phone.Sync()

	status, cerr := pc.Client().Status()
	if cerr != nil {
		t.Fatal(cerr)
	}
	head := status.HeadSeq
	if status.BootstrapSeq != nil || status.CompactFloor != 0 {
		t.Fatalf("fresh vault must have no bootstrap: %+v", status)
	}

	// The node of trust uploads the bootstrap covering the whole log.
	if _, _, err := pc.PushBootstrap(head); err != nil {
		t.Fatal(err)
	}
	status, _ = pc.Client().Status()
	if status.CompactFloor != head || status.BootstrapSeq == nil || *status.BootstrapSeq <= head {
		t.Fatalf("bootstrap promoted incorrectly: %+v (head was %d)", status, head)
	}

	// A pull below the floor is the exact oplog_compacted error.
	_, cerr = phone.Client().Pull(0, 0)
	if cerr == nil || cerr.Code != "oplog_compacted" {
		t.Fatalf("pull from 0 under the floor: %+v", cerr)
	}
	if cerr.Extra["compact_floor"].(float64) != float64(head) {
		t.Fatalf("compact_floor in the error: %v", cerr.Extra)
	}
	boot, _ := cerr.Extra["bootstrap"].(map[string]any)
	if boot == nil || boot["covered_seq"].(float64) != float64(head) || boot["seq"].(float64) <= float64(head) {
		t.Fatalf("bootstrap pointer in the error: %v", cerr.Extra)
	}

	// Meanwhile something new happens after the bootstrap.
	phone.Save("obj", "n3", func(o *model.Object) { o.Title = "Post-bootstrap" })
	phone.Sync()
	pc.Sync()

	// The brand-new device starts from the snapshot, then pulls above it.
	tablet := w.newDevice("dev_tablet")
	if err := tablet.ConsumeBootstrap(); err != nil {
		t.Fatal(err)
	}
	if _, ok := tablet.Object("n1"); !ok {
		t.Fatal("the snapshot must carry the pre-floor objects")
	}
	tablet.Sync()

	for _, id := range []string{"n1", "n2", "n3"} {
		assertConverged(t, id, pc, phone, tablet)
	}
	// And keep syncing normally afterwards.
	pc.Save("obj", "n4", func(o *model.Object) { o.Title = "After bootstrap" })
	pc.Sync()
	tablet.Sync()
	phone.Sync()
	assertConverged(t, "n4", pc, phone, tablet)
}
