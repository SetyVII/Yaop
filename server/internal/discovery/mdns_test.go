package discovery

import "testing"

// The _yaop-sync._tcp TXT record must announce the sync protocol contract
// this server implements (spec/merge.md), carried by SyncProtocolVersion — not
// a stale hardcoded string. If the protocol changes, the constant changes once
// here and the test fails until it is updated (spec §16.4 explicit error).
func TestAnnounceTXTCarriesSyncProtocolVersion(t *testing.T) {
	txt := announceTXT(SyncProtocolVersion, "srv-test", "")
	want := map[string]bool{
		"id=srv-test":                     false,
		"protocol=" + SyncProtocolVersion: false,
	}
	for _, entry := range txt {
		if seen, ok := want[entry]; ok {
			if seen {
				t.Errorf("TXT entry %q appears more than once", entry)
			}
			want[entry] = true
		}
	}
	for entry, seen := range want {
		if !seen {
			t.Errorf("TXT of the _yaop-sync._tcp announcement is missing %q; got %v", entry, txt)
		}
	}
}

// Pin the constant to the wire contract in force (spec/sync.md: yaop-sync/1).
func TestSyncProtocolVersionMatchesContract(t *testing.T) {
	if SyncProtocolVersion != "yaop-sync/1" {
		t.Fatalf("SyncProtocolVersion = %q, want the current wire contract \"yaop-sync/1\" (spec/sync.md)", SyncProtocolVersion)
	}
}

func TestAnnounceTXTTLSFields(t *testing.T) {
	withTLS := announceTXT(SyncProtocolVersion, "srv", "AA:BB")
	if !contains(withTLS, "tls=1") || !contains(withTLS, "fingerprint=AA:BB") {
		t.Errorf("TXT with fingerprint missing tls fields: %v", withTLS)
	}
	plain := announceTXT(SyncProtocolVersion, "srv", "")
	if contains(plain, "tls=1") || contains(plain, "fingerprint=AA:BB") {
		t.Errorf("TXT without fingerprint should not carry tls fields: %v", plain)
	}
}

func contains(txt []string, entry string) bool {
	for _, e := range txt {
		if e == entry {
			return true
		}
	}
	return false
}
