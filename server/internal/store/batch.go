package store

import (
	"encoding/json"
	"fmt"
)

type sealedItem struct {
	kind    string
	id      string
	payload []byte
	err     error
}

// PutBatch seals the admin records with the vault and commits records and
// deletes in one atomic SQLite transaction. The server only writes small
// bookkeeping batches these days; anything user-generated goes through
// AppendOps (oplog) or PutOpaqueChunk (assets) instead.
func (s *Store) PutBatch(records []Record, deletes [][2]string) error {
	sealed := make([]sealedItem, len(records))
	for i, r := range records {
		raw, err := json.Marshal(r.Value)
		if err != nil {
			return fmt.Errorf("json marshal %s/%s: %w", r.Kind, r.ID, err)
		}
		enc, err := s.vault.Encrypt(raw, r.Kind+"/"+r.ID)
		if err != nil {
			return fmt.Errorf("encrypt %s/%s: %w", r.Kind, r.ID, err)
		}
		sealed[i] = sealedItem{kind: r.Kind, id: r.ID, payload: enc}
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	tx, err := s.writeDB.Begin()
	if err != nil {
		return fmt.Errorf("failed to begin write tx: %w", err)
	}
	defer tx.Rollback()

	for _, sr := range sealed {
		if _, err := tx.Exec(
			"INSERT INTO records(kind, id, payload) VALUES(?, ?, ?) ON CONFLICT(kind, id) DO UPDATE SET payload=excluded.payload",
			sr.kind, sr.id, sr.payload); err != nil {
			return fmt.Errorf("exec record %s/%s: %w", sr.kind, sr.id, err)
		}
	}
	for _, d := range deletes {
		if _, err := tx.Exec("DELETE FROM records WHERE kind = ? AND id = ?", d[0], d[1]); err != nil {
			return fmt.Errorf("delete record %s/%s: %w", d[0], d[1], err)
		}
	}
	return tx.Commit()
}
