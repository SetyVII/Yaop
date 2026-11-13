package sync_test

// TestServerSeesNoPlaintext — the defining proof of the blind server
// (docs/product.md §15.1): after a full real scenario (objects, types, assets,
// pairing, pull merges), EVERY byte the server persists is scanned — the
// SQLite data file, its WAL and SHM, every file in the data directory —
// searching for each piece of content the scenario used: titles, block
// texts, type and property names, filename, MIME, the journal date id and
// every real object id. Zero appearances, byte-wise (UTF-8 and both UTF-16
// encodings; substring matches too).

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"testing"

	"yaop-server/internal/simclient"
	"yaop-server/internal/simclient/model"
)

func TestServerSeesNoPlaintext(t *testing.T) {
	w := newTestWorld(t)
	pc := w.newDevice("dev_pc")
	phone := w.newDevice("dev_phone")

	scenario := func(d *simclient.Device) {
		d.Save("obj", "daily-2026-02-14", func(o *model.Object) {
			o.Title = "Ultra Secret Bank Title 7f3a"
			o.TypeID = model.CanonicalTypeNote
			setBlocks(o,
				para("p1", "this line holds visible text the server must never see"),
				para("p2", "buy tickets for the Saturday concert with card 5555-7777"))
		})
		d.Save("type", "tipo-confidencial-77", func(o *model.Object) {
			o.Title = "Tipo Confidencial con Propiedad"
			o.TypeID = "type"
			o.Properties["customPropertyDefs"] = []any{
				map[string]any{"key": "account-number", "name": "Secret account number", "kind": "text"},
			}
		})
		d.Save("obj", "nota-con-id-real-visible-77", func(o *model.Object) {
			o.Title = "Note with account number 1234-XYZ"
			o.Properties["account-number"] = "1234-XYZ-secret-account-number"
			o.Properties["tags"] = []any{"TagSuperSecretOfE2E"}
			o.Properties["telegram"] = "@manuela-del-test"
		})
	}

	scenario(pc)
	plain := []byte("Confidential contract PDF 2026 — plain asset content with the secret e2e detail")
	if err := pc.UploadAsset("media-contrato-secreto", "asset-contrato-secreto", plain,
		"application/x-yaop-secreto", "contrato_confidencial_2026.pdf", 32); err != nil {
		t.Fatal(err)
	}
	pc.Sync()
	phone.Sync()

	// A merge round: the phone edits the daily offline; convergence traffic
	// crosses the server too.
	phone.Save("obj", "daily-2026-02-14", func(o *model.Object) {
		o.Title = "Ultra Secret Bank Title 7f3a (edited by the phone)"
	})
	phone.Sync()
	pc.Sync()
	phone.Sync()

	// A pairing transit also happened to leave relayed bytes behind (the
	// wrapped VK; content-wise but opaque to the server).
	runPairingThroughServer(t, w)

	// Sanity: the scenario really synced (the server holds the ops).
	if !deviceHas(pc, "daily-2026-02-14") || !deviceHas(phone, "daily-2026-02-14") {
		t.Fatal("scenario did not converge; the scan would be meaningless")
	}
	assertConverged(t, "daily-2026-02-14", pc, phone)

	// The needles: each one is content that MUST NOT exist in any byte the
	// server stored. (Substring search as well: partial leaks are leaks.)
	needles := []string{
		"Ultra Secret Bank Title 7f3a",
		"the server must never see",
		"compra entradas para el concierto",
		"5555-7777",
		"tipo-confidencial-77",
		"Tipo Confidencial con Propiedad",
		"Secret account number",
		"nota-con-id-real-visible-77",
		"1234-XYZ-secret-account-number",
		"TagSuperSecretOfE2E",
		"contrato_confidencial_2026.pdf",
		"application/x-yaop-secreto",
		"contrato confidencial",
		"daily-2026-02-14",
		"media-contrato-secreto",
		"asset-contrato-secreto",
		"@manuela-del-test",
	}

	var corpus []byte
	physical := 0
	err := filepath.Walk(w.dataDir, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.IsDir() {
			return nil
		}
		raw, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		physical += len(raw)
		t.Logf("escaneado %s (%d bytes)", filepath.Base(path), len(raw))
		corpus = append(corpus, raw...)
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if physical == 0 {
		t.Fatal("no server files to scan: the proof is empty")
	}

	// Positive control: the corpus IS the live server storage (SQLite magic +
	// our schema names appear), so a needle above would have been caught.
	for _, must := range []string{"oplog", "opaque_blobs", "pairings", "dev_pc"} {
		if !bytes.Contains(corpus, []byte(must)) {
			t.Fatalf("control: %q not in the scanned corpus — the scan is not looking at the real storage", must)
		}
	}
	if !bytes.Contains(corpus, []byte("SQLite format 3\x00")) {
		// the magic may sit only in the header page of the main db
		if !bytes.Contains(corpus, []byte("SQLite format 3")) {
			t.Fatal("control: the SQLite header is missing from the corpus")
		}
	}

	for _, needle := range needles {
		for _, what := range []string{"utf-8", "utf-16le", "utf-16be"} {
			if idx := bytes.Index(corpus, needleBytes(needle, what)); idx >= 0 {
				ctx0, ctx1 := idx-32, idx+64
				if ctx0 < 0 {
					ctx0 = 0
				}
				if ctx1 > len(corpus) {
					ctx1 = len(corpus)
				}
				t.Fatalf("PLAINTEXT FOUND (%s): %q appears in the server storage:\n%q",
					what, needle, safe(corpus[ctx0:ctx1]))
			}
		}
	}
}

// needleBytes renders a needle in the requested encoding.
func needleBytes(needle, encoding string) []byte {
	switch encoding {
	case "utf-8":
		return []byte(needle)
	case "utf-16le":
		out := make([]byte, 0, len(needle)*2)
		for _, r := range needle {
			out = append(out, byte(r), byte(r>>8))
		}
		return out
	case "utf-16be":
		out := make([]byte, 0, len(needle)*2)
		for _, r := range needle {
			out = append(out, byte(r>>8), byte(r))
		}
		return out
	}
	return []byte(needle)
}

// safe renders a window of raw bytes for the failure message.
func safe(b []byte) string {
	const hexdigits = "0123456789abcdef"
	out := make([]byte, 0, len(b)*2)
	for _, c := range b {
		if c >= 0x20 && c < 0x7f {
			out = append(out, c)
		} else {
			out = append(out, '\\', 'x', hexdigits[c>>4], hexdigits[c&0xf])
		}
	}
	return string(out)
}

// runPairingThroughServer leaves a relayed wrapped-VK envelope on the server
// (the pairing transport's own bytes: eph_pub, b_pub and the wrapped VK).
func runPairingThroughServer(t *testing.T, w *testWorld) {
	t.Helper()
	postJSON := func(path, token string, payload map[string]string) int {
		body, _ := json.Marshal(payload)
		req, _ := http.NewRequest(http.MethodPost, w.ts.URL+path, bytes.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		resp, err := w.ts.Client().Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		raw, _ := io.ReadAll(resp.Body)
		if resp.StatusCode >= 400 {
			t.Logf("POST %s -> %d %s", path, resp.StatusCode, string(raw))
		}
		return resp.StatusCode
	}

	ephPub := make([]byte, 32)
	for i := range ephPub {
		ephPub[i] = byte(i + 1)
	}
	bPub := make([]byte, 32)
	for i := range bPub {
		bPub[i] = byte(255 - i)
	}
	pairingID := "aa0102030405060708090a0b0c0d0e0f"
	if code := postJSON("/v1/pairing", "", map[string]string{
		"pairing_id": pairingID, "eph_pub": simclient.EncodePub(ephPub)}); code != http.StatusCreated {
		t.Fatalf("pairing announce: %d", code)
	}
	if code := postJSON("/v1/pairing/"+pairingID+"/join", "", map[string]string{
		"b_pub": simclient.EncodePub(bPub)}); code != http.StatusAccepted {
		t.Fatalf("pairing join: %d", code)
	}
	transfer := make([]byte, 80) // stand-in wrapped-VK envelope: opaque bytes
	for i := range transfer {
		transfer[i] = byte(i * 3)
	}
	req, _ := http.NewRequest(http.MethodPost, w.ts.URL+"/v1/pairing/"+pairingID+"/transfer", bytes.NewReader(transfer))
	resp, err := w.ts.Client().Do(req)
	if err != nil || resp.StatusCode != http.StatusCreated {
		t.Fatalf("pairing transfer: %v %v", err, resp)
	}
	resp.Body.Close()
}
