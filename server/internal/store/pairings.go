package store

import (
	"database/sql"
	"errors"
)

// Pairing transport storage (spec/crypto.md §Pairing): the server only
// relays opaque bytes between the device showing the QR and the device
// joining. A pairing row is ephemeral: created when the QR holder announces
// its ephemeral public key, gone a few minutes later whether it succeeded or
// not. The server never learns the shared secret, the code or the VK.

// PairingRow is one in-flight pairing transaction.
type PairingRow struct {
	PairingID string
	EphPub    string // QR holder's ephemeral X25519 public key (base64url, opaque to the server)
	BPub      string // joining device's ephemeral public key ("" until it answers)
	Transfer  []byte // wrapped VK envelope, relayed verbatim (nil until set)
	CreatedAt string
	UpdatedAt string
}

// UpsertPairing registers (or refreshes) the pairing the QR advertises.
func (s *Store) UpsertPairing(pairingID, ephPub, now string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, err := s.writeDB.Exec(
		"INSERT INTO pairings(pairing_id, eph_pub, created_at, updated_at) VALUES(?, ?, ?, ?) ON CONFLICT(pairing_id) DO UPDATE SET eph_pub=excluded.eph_pub, updated_at=excluded.updated_at",
		pairingID, ephPub, now, now)
	return err
}

// GetPairing loads a pairing's transport state.
func (s *Store) GetPairing(pairingID string) (PairingRow, bool, error) {
	var p PairingRow
	var transfer sql.Null[[]byte]
	err := s.readDB.QueryRow(
		"SELECT pairing_id, eph_pub, b_pub, transfer, created_at, updated_at FROM pairings WHERE pairing_id = ?",
		pairingID).Scan(&p.PairingID, &p.EphPub, &p.BPub, &transfer, &p.CreatedAt, &p.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return PairingRow{}, false, nil
	}
	if err != nil {
		return PairingRow{}, false, err
	}
	if transfer.Valid {
		p.Transfer = transfer.V
	}
	return p, true, nil
}

// SetPairingJoin records the joining device's ephemeral public key.
func (s *Store) SetPairingJoin(pairingID, bPub, now string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	res, err := s.writeDB.Exec("UPDATE pairings SET b_pub = ?, updated_at = ? WHERE pairing_id = ?", bPub, now, pairingID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return sql.ErrNoRows
	}
	return nil
}

// SetPairingTransfer relays the wrapped-VK envelope from the QR holder.
func (s *Store) SetPairingTransfer(pairingID string, transfer []byte, now string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	res, err := s.writeDB.Exec("UPDATE pairings SET transfer = ?, updated_at = ? WHERE pairing_id = ?", transfer, now, pairingID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return sql.ErrNoRows
	}
	return nil
}

// PurgePairingsBefore drops pairings whose last update is older than the
// cutoff (TTL). cutoff must be RFC3339 UTC seconds (NowRFC3339).
func (s *Store) PurgePairingsBefore(cutoff string) (int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	res, err := s.writeDB.Exec("DELETE FROM pairings WHERE updated_at < ?", cutoff)
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}
