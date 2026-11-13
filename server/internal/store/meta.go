package store

import (
	"database/sql"
	"errors"
)

// Meta is the server's tiny key/value table: only server-side bookkeeping
// (oplog compaction pointers) lives here — never content.

// ErrNoMeta reports a missing key.
var ErrNoMeta = errors.New("store: no such meta")

// Meta returns the string stored under key.
func (s *Store) Meta(key string) (string, bool, error) {
	var v string
	err := s.readDB.QueryRow("SELECT value FROM meta WHERE key = ?", key).Scan(&v)
	if errors.Is(err, sql.ErrNoRows) {
		return "", false, nil
	}
	if err != nil {
		return "", false, err
	}
	return v, true, nil
}

// SetMeta stores a value transactionally.
func (s *Store) SetMeta(key, value string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, err := s.writeDB.Exec(
		"INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", key, value)
	if err != nil {
		return err
	}
	return nil
}
