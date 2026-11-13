package simclient

// Runs spec/fixtures/crypto against the reference implementation (the same
// files the Kotlin (mobile) and C++ (desktop) runners will read when
// ports spec/crypto.md).

import (
	"os"
	"path/filepath"
	"testing"

	"encoding/json"

	"golang.org/x/crypto/chacha20"
)

const fixturesDir = "../../../spec/fixtures/crypto"

func fixtureTop[T any](t *testing.T, name string) T {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join(fixturesDir, name))
	if err != nil {
		t.Fatalf("fixture %s: %v", name, err)
	}
	var v T
	if err := json.Unmarshal(raw, &v); err != nil {
		t.Fatalf("fixture %s: %v", name, err)
	}
	return v
}

func fixtureAs(t *testing.T, name string) map[string]json.RawMessage {
	return fixtureTop[map[string]json.RawMessage](t, name)
}

func fixtureRaw(t *testing.T, name string) []byte {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join(fixturesDir, name))
	if err != nil {
		t.Fatalf("fixture %s: %v", name, err)
	}
	return raw
}

// ---------------------------------------------------------------------------
// HKDF domains
// ---------------------------------------------------------------------------

func TestCryptoFixtures_HkdfDomains(t *testing.T) {
	f := fixtureTop[struct {
		Vk      string            `json:"vk"`
		Salt    string            `json:"salt"`
		Len     int               `json:"len"`
		Subkeys map[string]string `json:"subkeys"`
	}](t, "hkdf_domains.json")
	if f.Salt != HkdfSalt {
		t.Fatalf("salt = %q want %q", f.Salt, HkdfSalt)
	}
	if f.Len != SubkeyBytes {
		t.Fatalf("len = %d want %d", f.Len, SubkeyBytes)
	}
	if len(f.Subkeys) != 7 {
		t.Fatalf("want 7 domains, got %d", len(f.Subkeys))
	}
	for domain, wantHex := range f.Subkeys {
		if got := he(Subkey(hx(f.Vk), domain)); got != wantHex {
			t.Errorf("subkey(%s)\n got  %s\n want %s", domain, got, wantHex)
		}
	}
}

// ---------------------------------------------------------------------------
// Blinded ids
// ---------------------------------------------------------------------------

func TestCryptoFixtures_WireID(t *testing.T) {
	f := fixtureTop[struct {
		Vk        string `json:"vk"`
		IdsSubkey string `json:"ids_subkey"`
		Cases     []struct {
			ID     string `json:"id"`
			WireID string `json:"wire_id"`
		} `json:"cases"`
		Derived []struct {
			ObjectID string `json:"object_id"`
			Kind     string `json:"kind"`
			Input    string `json:"input"`
			WireID   string `json:"wire_id"`
		} `json:"derived_cases"`
	}](t, "wire_id.json")
	if got := he(Subkey(hx(f.Vk), DomainIds)); got != f.IdsSubkey {
		t.Fatalf("ids subkey\n got  %s\n want %s", got, f.IdsSubkey)
	}
	seen := map[string]string{}
	for _, c := range f.Cases {
		got := WireID(hx(f.IdsSubkey), c.ID)
		if got != c.WireID {
			t.Errorf("WireID(%q)\n got  %s\n want %s", c.ID, got, c.WireID)
		}
		if prev, ok := seen[c.ID]; ok && prev != c.WireID {
			t.Errorf("WireID(%q) not deterministic across the vault", c.ID)
		}
		seen[c.ID] = c.WireID
	}
	for _, c := range f.Derived {
		if got := DerivedBlindInput(c.ObjectID, c.Kind); got != c.Input {
			t.Errorf("derived input for %s/%s = %q want %q", c.ObjectID, c.Kind, got, c.Input)
		}
		if got := DerivedWireID(hx(f.IdsSubkey), c.ObjectID, c.Kind); got != c.WireID {
			t.Errorf("DerivedWireID(%s,%s)\n got  %s\n want %s", c.ObjectID, c.Kind, got, c.WireID)
		}
	}
}

// ---------------------------------------------------------------------------
// BIP39
// ---------------------------------------------------------------------------

func TestCryptoFixtures_Bip39(t *testing.T) {
	f := fixtureTop[struct {
		Wordlist struct {
			Words  int    `json:"words"`
			Sha256 string `json:"sha256"`
		} `json:"wordlist"`
		Valid []struct {
			Name     string `json:"name"`
			Entropy  string `json:"entropy_hex"`
			Mnemonic string `json:"mnemonic"`
			Seed     string `json:"seed_no_passphrase_hex"`
		} `json:"valid"`
		Invalid []struct {
			Name     string `json:"name"`
			Mnemonic string `json:"mnemonic"`
		} `json:"invalid"`
	}](t, "bip39.json")

	raw := fixtureRaw(t, "bip39_english.txt")
	if sum := sha256Hex(raw); sum != f.Wordlist.Sha256 {
		t.Fatalf("wordlist sha256 = %s want %s", sum, f.Wordlist.Sha256)
	}
	lines := fields(string(raw))
	if len(lines) != f.Wordlist.Words {
		t.Fatalf("wordlist has %d words want %d", len(lines), f.Wordlist.Words)
	}
	b, err := NewBip39(lines)
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range f.Valid {
		m, err := b.EntropyToMnemonic(hx(c.Entropy))
		if err != nil {
			t.Fatalf("%s: %v", c.Name, err)
		}
		if m != c.Mnemonic {
			t.Errorf("%s: mnemonic\n got  %s\n want %s", c.Name, m, c.Mnemonic)
		}
		ent, err := b.MnemonicToEntropy(c.Mnemonic)
		if err != nil {
			t.Fatalf("%s: %v", c.Name, err)
		}
		if he(ent) != c.Entropy {
			t.Errorf("%s: entropy roundtrip\n got  %s\n want %s", c.Name, he(ent), c.Entropy)
		}
		if got := he(Seed(c.Mnemonic)); got != c.Seed {
			t.Errorf("%s: seed\n got  %s\n want %s", c.Name, got, c.Seed)
		}
	}
	for _, c := range f.Invalid {
		if _, err := b.MnemonicToEntropy(c.Mnemonic); err == nil {
			t.Errorf("%s: invalid phrase accepted", c.Name)
		}
	}
}

// ---------------------------------------------------------------------------
// Envelope (exact bytes, version 0x01)
// ---------------------------------------------------------------------------

func TestCryptoFixtures_Envelope(t *testing.T) {
	f := fixtureTop[struct {
		Vk    string `json:"vk"`
		Cases []struct {
			Name       string `json:"name"`
			Domain     string `json:"domain"`
			Key        string `json:"key"`
			WireID     string `json:"wire_id"`
			KeyEpoch   uint32 `json:"key_epoch"`
			ChunkIndex *int   `json:"chunk_index"`
			Nonce      string `json:"nonce"`
			Payload    string `json:"payload"`
			PayloadHex string `json:"payload_hex"`
			AAD        string `json:"aad"`
			Envelope   string `json:"envelope_hex"`
		} `json:"cases"`
	}](t, "envelope.json")
	if len(f.Cases) == 0 {
		t.Fatal("no cases")
	}
	for _, c := range f.Cases {
		key := Subkey(hx(f.Vk), c.Domain)
		if he(key) != c.Key {
			t.Errorf("%s: fixture key does not match HKDF(%s) of the fixture VK (fixture bug)", c.Name, c.Domain)
		}
		var payload []byte
		if c.PayloadHex != "" {
			payload = hx(c.PayloadHex)
		} else {
			payload = []byte(c.Payload)
		}
		env, err := SealEnvelope(key, c.KeyEpoch, c.Domain, c.WireID, payload, false, hx(c.Nonce))
		if err != nil {
			t.Fatalf("%s: %v", c.Name, err)
		}
		if he(env) != c.Envelope {
			t.Errorf("%s: envelope bytes\n got  %s\n want %s", c.Name, he(env), c.Envelope)
		}
		wantAAD := AAD(c.Domain, c.WireID)
		if c.ChunkIndex != nil {
			wantAAD = ChunkAAD(c.WireID, *c.ChunkIndex)
		}
		if c.AAD != wantAAD {
			t.Errorf("%s: aad fixture = %q want %q", c.Name, c.AAD, wantAAD)
		}
		got, epoch, version, err := OpenEnvelope(key, hx(c.Envelope), c.Domain, c.WireID)
		if err != nil || epoch != c.KeyEpoch || version != EnvelopeRaw {
			t.Fatalf("%s: open: %v", c.Name, err)
		}
		if he(got) != he(payload) {
			t.Errorf("%s: payload mismatch on open", c.Name)
		}
		tampered := hx(c.Envelope)
		tampered[len(tampered)-1] ^= 0x01
		if _, _, _, err := OpenEnvelope(key, tampered, c.Domain, c.WireID); err == nil {
			t.Errorf("%s: tampered envelope accepted", c.Name)
		}
		if _, _, _, err := OpenEnvelope(key, hx(c.Envelope), c.Domain, "ffffffffffffffff"); err == nil {
			t.Errorf("%s: wrong AAD accepted", c.Name)
		}
		otherVK := append([]byte(nil), hx(f.Vk)...)
		otherVK[0] ^= 1
		if _, _, _, err := OpenEnvelope(Subkey(otherVK, c.Domain), hx(c.Envelope), c.Domain, c.WireID); err == nil {
			t.Errorf("%s: wrong epoch key accepted", c.Name)
		}
	}
}

// ---------------------------------------------------------------------------
// Envelope version 0x02: zstd roundtrip (never exact bytes)
// ---------------------------------------------------------------------------

func TestCryptoFixtures_EnvelopeZstdRoundtrip(t *testing.T) {
	f := fixtureTop[struct {
		Vk    string `json:"vk_hex"`
		Cases []struct {
			Name      string `json:"name"`
			Domain    string `json:"domain"`
			KeySubkey string `json:"key_subkey"`
			KeyEpoch  uint32 `json:"key_epoch"`
			WireID    string `json:"wire_id"`
			Nonce     string `json:"nonce_hex"`
			Payload   string `json:"payload"`
		} `json:"cases"`
	}](t, "envelope_zstd_roundtrip.json")
	if len(f.Cases) == 0 {
		t.Fatal("no cases")
	}
	vk := hx(f.Vk)
	for _, c := range f.Cases {
		key := Subkey(vk, c.KeySubkey)
		env, err := SealEnvelope(key, c.KeyEpoch, c.Domain, c.WireID, []byte(c.Payload), true, hx(c.Nonce))
		if err != nil {
			t.Fatalf("%s: seal: %v", c.Name, err)
		}
		if env[0] != byte(EnvelopeZstd) {
			t.Errorf("%s: compress=true must mark version 0x02", c.Name)
		}
		got, epoch, version, err := OpenEnvelope(key, env, c.Domain, c.WireID)
		if err != nil || epoch != c.KeyEpoch || version != EnvelopeZstd {
			t.Fatalf("%s: open: %v", c.Name, err)
		}
		if string(got) != c.Payload {
			t.Errorf("%s: zstd roundtrip mismatch", c.Name)
		}
		// an uncompressed envelope of the same payload must open too:
		// version 0x01 is always valid
		env1, err := SealEnvelope(key, c.KeyEpoch, c.Domain, c.WireID, []byte(c.Payload), false, hx(c.Nonce))
		if err != nil {
			t.Fatal(err)
		}
		if got1, _, _, err := OpenEnvelope(key, env1, c.Domain, c.WireID); err != nil || string(got1) != c.Payload {
			t.Errorf("%s: v1 envelope invalid", c.Name)
		}
	}
}

// ---------------------------------------------------------------------------
// Pairing transcript
// ---------------------------------------------------------------------------

func TestCryptoFixtures_PairingTranscript(t *testing.T) {
	f := fixtureTop[struct {
		Steps []struct {
			N         int    `json:"n"`
			PrivA     string `json:"priv_a"`
			PubA      string `json:"pub_a"`
			QrJSON    string `json:"qr_json"`
			PrivB     string `json:"priv_b"`
			PubB      string `json:"pub_b"`
			Shared    string `json:"shared_hex"`
			Code      string `json:"short_code"`
			Vk        string `json:"vk"`
			VkKey     string `json:"vk_transfer_key"`
			PairingID string `json:"pairing_id"`
			Nonce     string `json:"nonce"`
			Envelope  string `json:"envelope_v1_hex"`
		} `json:"steps"`
	}](t, "pairing_transcript.json")
	steps := map[int]struct {
		N         int    `json:"n"`
		PrivA     string `json:"priv_a"`
		PubA      string `json:"pub_a"`
		QrJSON    string `json:"qr_json"`
		PrivB     string `json:"priv_b"`
		PubB      string `json:"pub_b"`
		Shared    string `json:"shared_hex"`
		Code      string `json:"short_code"`
		Vk        string `json:"vk"`
		VkKey     string `json:"vk_transfer_key"`
		PairingID string `json:"pairing_id"`
		Nonce     string `json:"nonce"`
		Envelope  string `json:"envelope_v1_hex"`
	}{}
	for _, s := range f.Steps {
		steps[s.N] = s
	}
	s1, s2, s3, s4, s5, s6 := steps[1], steps[2], steps[3], steps[4], steps[5], steps[6]

	pubA, err := PubFromScalar(hx(s1.PrivA))
	if err != nil {
		t.Fatal(err)
	}
	if he(pubA) != s1.PubA {
		t.Errorf("pub_a\n got  %s\n want %s", he(pubA), s1.PubA)
	}
	gotQr, err := DecodePairingQr(s2.QrJSON)
	if err != nil {
		t.Fatalf("qr decode: %v", err)
	}
	if gotQr.EphPub != EncodePub(pubA) || gotQr.PairingID != s6.PairingID || gotQr.V != PairingQrVersion {
		t.Errorf("qr payload mismatch: %+v", gotQr)
	}
	pubB, err := PubFromScalar(hx(s3.PrivB))
	if err != nil {
		t.Fatal(err)
	}
	if he(pubB) != s3.PubB {
		t.Errorf("pub_b\n got  %s\n want %s", he(pubB), s3.PubB)
	}
	sA, err := X25519Shared(hx(s1.PrivA), pubB)
	if err != nil {
		t.Fatal(err)
	}
	sB, err := X25519Shared(hx(s3.PrivB), pubA)
	if err != nil {
		t.Fatal(err)
	}
	if he(sA) != s4.Shared || he(sB) != s4.Shared {
		t.Errorf("shared\n A %s\n B %s\n want %s", he(sA), he(sB), s4.Shared)
	}
	if got := PairingCode(sA); got != s5.Code {
		t.Errorf("short code\n got  %s\n want %s", got, s5.Code)
	}
	if got := he(PairingVKKey(sA)); got != s6.VkKey {
		t.Errorf("vk transfer key\n got  %s\n want %s", got, s6.VkKey)
	}
	vk, epoch, err := OpenPairingVK(sA, s6.PairingID, hx(s6.Envelope))
	if err != nil {
		t.Fatalf("open vk: %v", err)
	}
	if he(vk) != s6.Vk || epoch != 1 {
		t.Errorf("vk unwrap\n got  %s/%d\n want %s/1", he(vk), epoch, s6.Vk)
	}
	env, err := SealEnvelope(PairingVKKey(sA), 1, "pairing", s6.PairingID, hx(s6.Vk), false, hx(s6.Nonce))
	if err != nil {
		t.Fatal(err)
	}
	if he(env) != s6.Envelope {
		t.Errorf("vk envelope bytes\n got  %s\n want %s", he(env), s6.Envelope)
	}
}

// ---------------------------------------------------------------------------
// AEAD: official vectors of draft-irtf-cfrg-xchacha-03
// ---------------------------------------------------------------------------

func TestCryptoFixtures_AeadOfficialVector(t *testing.T) {
	f := fixtureTop[struct {
		Cases []struct {
			Name           string `json:"name"`
			KeyHex         string `json:"key_hex"`
			NonceHex       string `json:"nonce_hex"`
			AADHex         string `json:"aad_hex"`
			PlaintextASCII string `json:"plaintext_ascii"`
			CTTagHex       string `json:"ciphertext_plus_tag_hex"`
		} `json:"cases"`
	}](t, "aead_xchacha20poly1305.json")
	if len(f.Cases) == 0 {
		t.Fatal("no aead cases")
	}
	for _, c := range f.Cases {
		ct, err := SealWithNonce(hx(c.KeyHex), hx(c.NonceHex), []byte(c.PlaintextASCII), hx(c.AADHex))
		if err != nil {
			t.Fatalf("%s: %v", c.Name, err)
		}
		if he(ct) != c.CTTagHex {
			t.Errorf("%s: ct+tag\n got  %s\n want %s", c.Name, he(ct), c.CTTagHex)
		}
		pt, err := Open(hx(c.KeyHex), hx(c.NonceHex), hx(c.CTTagHex), hx(c.AADHex))
		if err != nil || string(pt) != c.PlaintextASCII {
			t.Errorf("%s: open: %v", c.Name, err)
		}
		bad := hx(c.CTTagHex)
		bad[3] ^= 0xff
		if _, err := Open(hx(c.KeyHex), hx(c.NonceHex), bad, hx(c.AADHex)); err == nil {
			t.Errorf("%s: tampered ct accepted", c.Name)
		}
		if _, err := Open(hx(c.KeyHex), hx(c.NonceHex), hx(c.CTTagHex), []byte("other-aad")); err == nil {
			t.Errorf("%s: tampered aad accepted", c.Name)
		}
	}
}

func TestCryptoFixtures_HChaCha20Subkey(t *testing.T) {
	// Official HChaCha20 vector (draft-irtf-cfrg-xchacha-03 §2.2.1); the X in
	// XChaCha20: subkey = HChaCha20(key, nonce[0:16]).
	subkey, err := chacha20.HChaCha20(
		hx("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"),
		hx("000000090000004a0000000031415927"))
	if err != nil {
		t.Fatal(err)
	}
	want := "82413b4227b27bfed30e42508a877d73a0f9e4d58a74a853c12ec41326d3ecdc"
	if he(subkey) != want {
		t.Fatalf("HChaCha20 subkey\n got  %s\n want %s", he(subkey), want)
	}
}

func TestSealRandomNonceRoundtrip(t *testing.T) {
	key := Subkey(hx("11223344556677889900aabbccddeeff00112233445566778899aabbccddeeff"), DomainObjects)
	s, err := Seal(key, []byte("production payload"), []byte("objects/xyz"))
	if err != nil {
		t.Fatal(err)
	}
	allZero := true
	for _, b := range s.Nonce {
		if b != 0 {
			allZero = false
			break
		}
	}
	if allZero {
		t.Fatal("nonce must be random")
	}
	pt, err := Open(key, s.Nonce, s.Ciphertext, []byte("objects/xyz"))
	if err != nil || string(pt) != "production payload" {
		t.Fatal("roundtrip failed")
	}
}
