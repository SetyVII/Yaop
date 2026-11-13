package store

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"time"

	_ "github.com/mattn/go-sqlite3"

	"yaop-server/internal/crypto"
)

// Store is the blind server's persistence: the oplog of opaque synced
// operations, opaque asset chunks deduplicated by ciphertext hash, ephemeral
// pairing blobs, and the server's own (vault-encrypted at rest) admin
// records (device registry, identity). No content is ever stored or indexed.
//
// Concurrency layout is unchanged from the /1 era: a single SQLite WAL
// writer connection (_txlock=immediate) plus a pool of read-only
// connections, so pulls and streams never block behind a push.
type Store struct {
	writeDB *sql.DB
	readDB  *sql.DB
	vault   *crypto.Vault
	mu      sync.Mutex // serializes write transactions
}

type Record struct {
	Kind  string
	ID    string
	Value any
}

// Open initializes the store at the given path with the AES-256 vault.
func Open(path string, vault *crypto.Vault) (*Store, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return nil, fmt.Errorf("failed to create data directory: %w", err)
	}

	writeDsn := path + "?_busy_timeout=10000&_journal_mode=WAL&_synchronous=NORMAL&_foreign_keys=on&_txlock=immediate"
	writeDB, err := sql.Open("sqlite3", writeDsn)
	if err != nil {
		return nil, fmt.Errorf("failed to open write db: %w", err)
	}
	writeDB.SetMaxOpenConns(1)

	readDsn := path + "?_busy_timeout=10000&_journal_mode=WAL&_query_only=1"
	readDB, err := sql.Open("sqlite3", readDsn)
	if err != nil {
		writeDB.Close()
		return nil, fmt.Errorf("failed to open read db: %w", err)
	}
	maxReaders := runtime.NumCPU() * 2
	if maxReaders < 4 {
		maxReaders = 4
	}
	readDB.SetMaxOpenConns(maxReaders)

	s := &Store{
		writeDB: writeDB,
		readDB:  readDB,
		vault:   vault,
	}

	if err := s.initSchema(); err != nil {
		s.Close()
		return nil, err
	}

	if err := s.authenticateKey(); err != nil {
		s.Close()
		return nil, err
	}

	_ = os.Chmod(path, 0600)
	return s, nil
}

func (s *Store) Close() error {
	var err1, err2 error
	if s.writeDB != nil {
		err1 = s.writeDB.Close()
	}
	if s.readDB != nil {
		err2 = s.readDB.Close()
	}
	if err1 != nil {
		return err1
	}
	return err2
}

func (s *Store) initSchema() error {
	schema := `
	CREATE TABLE IF NOT EXISTS records (
		kind TEXT NOT NULL,
		id TEXT NOT NULL,
		payload BLOB NOT NULL,
		PRIMARY KEY(kind, id)
	);
	CREATE TABLE IF NOT EXISTS meta (
		key TEXT PRIMARY KEY,
		value TEXT NOT NULL
	);
	CREATE TABLE IF NOT EXISTS oplog (
		seq INTEGER PRIMARY KEY AUTOINCREMENT,
		op_id TEXT NOT NULL UNIQUE,
		device_id TEXT NOT NULL,
		kind TEXT NOT NULL,
		wire_id TEXT NOT NULL,
		key_epoch INTEGER NOT NULL,
		blob BLOB NOT NULL,
		received_at TEXT NOT NULL
	);
	CREATE TABLE IF NOT EXISTS op_chunks (
		wire_id TEXT NOT NULL,
		chunk_seq INTEGER NOT NULL,
		hash TEXT NOT NULL,
		size INTEGER NOT NULL,
		PRIMARY KEY(wire_id, chunk_seq)
	);
	CREATE TABLE IF NOT EXISTS opaque_blobs (
		hash TEXT PRIMARY KEY,
		size INTEGER NOT NULL,
		payload BLOB NOT NULL
	);
	CREATE TABLE IF NOT EXISTS pairings (
		pairing_id TEXT PRIMARY KEY,
		eph_pub TEXT NOT NULL,
		b_pub TEXT NOT NULL DEFAULT '',
		transfer BLOB,
		created_at TEXT NOT NULL,
		updated_at TEXT NOT NULL
	);
	CREATE TABLE IF NOT EXISTS asset_uploads (
		wire_id TEXT PRIMARY KEY,
		total_chunks INTEGER NOT NULL,
		updated_at TEXT NOT NULL
	);
	`
	_, err := s.writeDB.Exec(schema)
	return err
}

// authenticateKey proves the master key on every open: the very first record
// is a vault-sealed magic value.
func (s *Store) authenticateKey() error {
	var payload []byte
	err := s.readDB.QueryRow("SELECT payload FROM records WHERE kind = 'system' AND id = 'key-check'").Scan(&payload)
	if errors.Is(err, sql.ErrNoRows) {
		return s.Put("system", "key-check", map[string]string{"magic": "yaop-v1"})
	} else if err == nil {
		_, err = s.vault.Decrypt(payload, "system/key-check")
	}
	if err != nil {
		return fmt.Errorf("cannot authenticate workspace master key: %w", err)
	}
	return nil
}

// ---------------------------------------------------------------------------
// Admin records: AES-256-GCM sealed at rest with the server's master key
// (spec §3.4: extra protection on top of E2EE — never the primary one).
// Only server-side bookkeeping lives here: devices, server identity.
// ---------------------------------------------------------------------------

func (s *Store) Put(kind, id string, value any) error {
	return s.PutBatch([]Record{{Kind: kind, ID: id, Value: value}}, nil)
}

func (s *Store) Delete(kind, id string) error {
	return s.PutBatch(nil, [][2]string{{kind, id}})
}

func (s *Store) Get(kind, id string, out any) error {
	var payload []byte
	err := s.readDB.QueryRow("SELECT payload FROM records WHERE kind = ? AND id = ?", kind, id).Scan(&payload)
	if err != nil {
		return err
	}
	aad := kind + "/" + id
	dec, err := s.vault.Decrypt(payload, aad)
	if err != nil {
		return err
	}
	return json.Unmarshal(dec, out)
}

func (s *Store) Has(kind, id string) bool {
	var count int
	_ = s.readDB.QueryRow("SELECT count(*) FROM records WHERE kind = ? AND id = ?", kind, id).Scan(&count)
	return count > 0
}

// Fsck verifies every admin record still decrypts cleanly under the master
// key. The oplog and the chunk store hold client ciphertext by construction —
// there is nothing the server could verify about them (that is the point of
// a blind server).
func (s *Store) Fsck() error {
	rows, err := s.readDB.Query("SELECT kind, id, payload FROM records")
	if err != nil {
		return err
	}
	defer rows.Close()

	for rows.Next() {
		var kind, id string
		var payload []byte
		if err := rows.Scan(&kind, &id, &payload); err != nil {
			return err
		}
		if _, err := s.vault.Decrypt(payload, kind+"/"+id); err != nil {
			return fmt.Errorf("fsck: corruption in record %s/%s: %w", kind, id, err)
		}
	}
	return rows.Err()
}

func (s *Store) Vault() *crypto.Vault {
	return s.vault
}

func now() string {
	return time.Now().UTC().Format(time.RFC3339Nano)
}
