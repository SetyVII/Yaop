package simclient

// Reference implementation of the .yaop backup container (spec/crypto.md §Backup).
// The Kotlin and C++ clients port this byte for byte; spec/fixtures/backup holds the vectors.

import (
	"encoding/binary"
	"errors"
	"fmt"
)

const (
	BackupMagic        = "YBK1"
	BackupDomain       = "snapshots"
	BackupKindObj      = 1
	BackupKindType     = 2
	BackupKindAsset    = 3
	BackupKindEnd      = 0xFF
	BackupAssetChunkSz = 4 << 20
)

// BackupRecord is one logical entry. For assets, ID is the asset id, N the 0-based chunk
// index and Total the chunk count; for obj/type, Data is the wire JSON.
type BackupRecord struct {
	Kind  byte
	ID    string
	N     uint32
	Total uint32
	Data  []byte
}

func backupWireID(seq int) string { return fmt.Sprintf("backup/%d", seq) }

func encodeBackupPayload(r BackupRecord) []byte {
	out := make([]byte, 0, 11+len(r.ID)+len(r.Data))
	out = append(out, r.Kind)
	out = binary.BigEndian.AppendUint16(out, uint16(len(r.ID)))
	out = append(out, r.ID...)
	out = binary.BigEndian.AppendUint32(out, r.N)
	out = binary.BigEndian.AppendUint32(out, r.Total)
	return append(out, r.Data...)
}

func decodeBackupPayload(p []byte) (BackupRecord, error) {
	if len(p) < 11 {
		return BackupRecord{}, errors.New("backup: short record")
	}
	idLen := int(binary.BigEndian.Uint16(p[1:3]))
	if len(p) < 3+idLen+8 {
		return BackupRecord{}, errors.New("backup: short record id")
	}
	r := BackupRecord{Kind: p[0], ID: string(p[3 : 3+idLen])}
	r.N = binary.BigEndian.Uint32(p[3+idLen:])
	r.Total = binary.BigEndian.Uint32(p[7+idLen:])
	r.Data = append([]byte(nil), p[11+idLen:]...)
	return r, nil
}

// WriteBackup seals recs (plus the terminating END record) with the snapshots subkey.
// nonce(i) may return nil for a random nonce. JSON kinds are zstd-compressed iff compress.
func WriteBackup(vk []byte, epoch uint32, recs []BackupRecord, compress bool, nonce func(i int) []byte) ([]byte, error) {
	key := Subkey(vk, BackupDomain)
	out := []byte(BackupMagic)
	all := append(append([]BackupRecord(nil), recs...), BackupRecord{Kind: BackupKindEnd, N: uint32(len(recs))})
	for i, r := range all {
		var n []byte
		if nonce != nil {
			n = nonce(i)
		}
		comp := compress && (r.Kind == BackupKindObj || r.Kind == BackupKindType)
		env, err := SealEnvelope(key, epoch, BackupDomain, backupWireID(i), encodeBackupPayload(r), comp, n)
		if err != nil {
			return nil, err
		}
		out = binary.BigEndian.AppendUint32(out, uint32(len(env)))
		out = append(out, env...)
	}
	return out, nil
}

// ReadBackup opens every record and verifies order (AAD carries the index) and completeness
// (END record count). It returns the records without END and the key_epoch used.
func ReadBackup(vk []byte, file []byte) ([]BackupRecord, uint32, error) {
	if len(file) < 4 || string(file[:4]) != BackupMagic {
		return nil, 0, errors.New("backup: bad magic")
	}
	key := Subkey(vk, BackupDomain)
	pos := 4
	var recs []BackupRecord
	var epoch uint32
	for i := 0; ; i++ {
		if pos+4 > len(file) {
			return nil, 0, errors.New("backup: truncated (no END record)")
		}
		l := int(binary.BigEndian.Uint32(file[pos:]))
		pos += 4
		if pos+l > len(file) {
			return nil, 0, errors.New("backup: truncated record")
		}
		payload, ep, _, err := OpenEnvelope(key, file[pos:pos+l], BackupDomain, backupWireID(i))
		if err != nil {
			return nil, 0, fmt.Errorf("backup: record %d: %w", i, err)
		}
		pos += l
		epoch = ep
		r, err := decodeBackupPayload(payload)
		if err != nil {
			return nil, 0, err
		}
		if r.Kind == BackupKindEnd {
			if int(r.N) != len(recs) {
				return nil, 0, errors.New("backup: END count mismatch")
			}
			if pos != len(file) {
				return nil, 0, errors.New("backup: trailing bytes after END")
			}
			return recs, epoch, nil
		}
		recs = append(recs, r)
	}
}
