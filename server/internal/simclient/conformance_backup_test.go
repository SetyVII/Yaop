package simclient

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

const backupFixtureDir = "../../../spec/fixtures/backup"

var backupVK = mustHex("11223344556677889900aabbccddeeff00112233445566778899aabbccddeeff")

func mustHex(s string) []byte {
	b, err := hex.DecodeString(s)
	if err != nil {
		panic(err)
	}
	return b
}

func fixedNonce(i int) []byte { return bytes.Repeat([]byte{byte(0x20 + i)}, AeadNonceLen) }

func smallBackupRecords() []BackupRecord {
	return []BackupRecord{
		{Kind: BackupKindType, ID: "note", Data: []byte(`{"id":"note","name":"Note"}`)},
		{Kind: BackupKindObj, ID: "daily-2026-11-09", Data: []byte(`{"id":"daily-2026-11-09","type_id":"daily","title":"Diario"}`)},
		{Kind: BackupKindObj, ID: "media_a1", Data: []byte(`{"id":"media_a1","type_id":"media","title":"foto"}`)},
		{Kind: BackupKindAsset, ID: "a1", N: 0, Total: 2, Data: []byte("hello ")},
		{Kind: BackupKindAsset, ID: "a1", N: 1, Total: 2, Data: []byte("backup")},
	}
}

type backupFixtureMeta struct {
	VkHex   string `json:"vk_hex"`
	Epoch   uint32 `json:"key_epoch"`
	Records []struct {
		Kind  int    `json:"kind"`
		ID    string `json:"id"`
		N     uint32 `json:"n"`
		Total uint32 `json:"total"`
		Data  string `json:"data_hex"`
	} `json:"records"`
}

// UPDATE_FIXTURES=1 regenerates spec/fixtures/backup from the reference implementation.
func TestBackupFixture(t *testing.T) {
	recs := smallBackupRecords()
	file, err := WriteBackup(backupVK, 2, recs, false, fixedNonce)
	if err != nil {
		t.Fatal(err)
	}
	binPath := filepath.Join(backupFixtureDir, "small.yaop")
	metaPath := filepath.Join(backupFixtureDir, "small.json")
	if os.Getenv("UPDATE_FIXTURES") == "1" {
		meta := backupFixtureMeta{VkHex: hex.EncodeToString(backupVK), Epoch: 2}
		for _, r := range recs {
			meta.Records = append(meta.Records, struct {
				Kind  int    `json:"kind"`
				ID    string `json:"id"`
				N     uint32 `json:"n"`
				Total uint32 `json:"total"`
				Data  string `json:"data_hex"`
			}{int(r.Kind), r.ID, r.N, r.Total, hex.EncodeToString(r.Data)})
		}
		js, _ := json.MarshalIndent(meta, "", "  ")
		_ = os.MkdirAll(backupFixtureDir, 0o755)
		if err := os.WriteFile(binPath, file, 0o644); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(metaPath, append(js, '\n'), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	onDisk, err := os.ReadFile(binPath)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(onDisk, file) {
		t.Fatal("small.yaop differs from the reference writer (run UPDATE_FIXTURES=1 only if the format changed on purpose)")
	}
	got, epoch, err := ReadBackup(backupVK, onDisk)
	if err != nil || epoch != 2 || len(got) != len(recs) {
		t.Fatalf("read: %v epoch=%d n=%d", err, epoch, len(got))
	}
	for i := range recs {
		if got[i].Kind != recs[i].Kind || got[i].ID != recs[i].ID || got[i].N != recs[i].N ||
			got[i].Total != recs[i].Total || !bytes.Equal(got[i].Data, recs[i].Data) {
			t.Fatalf("record %d differs: %+v", i, got[i])
		}
	}
}

func TestBackupNegatives(t *testing.T) {
	file, _ := WriteBackup(backupVK, 1, smallBackupRecords(), true, nil)
	if _, _, err := ReadBackup(backupVK, file); err != nil {
		t.Fatalf("compressed roundtrip: %v", err)
	}
	wrong := bytes.Repeat([]byte{7}, 32)
	if _, _, err := ReadBackup(wrong, file); err == nil {
		t.Fatal("wrong VK must fail")
	}
	if _, _, err := ReadBackup(backupVK, file[:len(file)-5]); err == nil {
		t.Fatal("truncated file must fail")
	}
	bad := append([]byte(nil), file...)
	bad[len(bad)/2] ^= 0xff
	if _, _, err := ReadBackup(backupVK, bad); err == nil {
		t.Fatal("tampered file must fail")
	}
	if _, _, err := ReadBackup(backupVK, append([]byte("XXXX"), file[4:]...)); err == nil {
		t.Fatal("bad magic must fail")
	}
	// Dropping a middle record must be caught (AAD carries the record index).
	first := int(file[4])<<24 | int(file[5])<<16 | int(file[6])<<8 | int(file[7])
	cut := append(append([]byte(nil), file[:4]...), file[8+first:]...)
	if _, _, err := ReadBackup(backupVK, cut); err == nil {
		t.Fatal("dropped record must fail")
	}
}
