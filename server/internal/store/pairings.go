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
	if _, err := s.writeDB.Exec("DELETE FROM pairing_enrolls WHERE updated_at < ?", cutoff); err != nil {
		return 0, err
	}
	return res.RowsAffected()
}

// SetPairingEnrollKey authorizes the pairing to mint one device token for the
// joining device (spec/crypto.md §Pairing step 7). Only the SHA-256 of the
// enroll key is kept. Setting it again (a retried transfer) re-arms it.
func (s *Store) SetPairingEnrollKey(pairingID, keyHash, now string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, err := s.writeDB.Exec(
		"INSERT INTO pairing_enrolls(pairing_id, key_hash, updated_at) VALUES(?, ?, ?) ON CONFLICT(pairing_id) DO UPDATE SET key_hash=excluded.key_hash, updated_at=excluded.updated_at WHERE redeemed = 0",
		pairingID, keyHash, now)
	return err
}

// GetPairingEnroll returns the enroll key hash and whether it was redeemed.
func (s *Store) GetPairingEnroll(pairingID string) (keyHash string, redeemed, ok bool, err error) {
	var r int
	err = s.readDB.QueryRow("SELECT key_hash, redeemed FROM pairing_enrolls WHERE pairing_id = ?", pairingID).Scan(&keyHash, &r)
	if errors.Is(err, sql.ErrNoRows) {
		return "", false, false, nil
	}
	if err != nil {
		return "", false, false, err
	}
	return keyHash, r != 0, true, nil
}

// RedeemPairingEnroll marks the enroll as used; false when it already was
// (two concurrent redeems: only one wins).
func (s *Store) RedeemPairingEnroll(pairingID, now string) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	res, err := s.writeDB.Exec("UPDATE pairing_enrolls SET redeemed = 1, updated_at = ? WHERE pairing_id = ? AND redeemed = 0", now, pairingID)
	if err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	return n == 1, nil
}
