// Package discovery announces the server on the LAN over mDNS (_yaop-sync._tcp)
// so clients can find it without typing an address.
package discovery

import (
	"fmt"

	"github.com/grandcat/zeroconf"
)

// SyncProtocolVersion is the sync wire protocol this server speaks (spec/sync.md).
// Changing the protocol means changing this constant once, here.
const SyncProtocolVersion = "yaop-sync/1"

// announceTXT builds the mDNS TXT record. Pure, so it is testable without a network.
func announceTXT(protocol, serverID, tlsFingerprint string) []string {
	txt := []string{"id=" + serverID, "protocol=" + protocol}
	if tlsFingerprint != "" {
		txt = append(txt, "tls=1", "fingerprint="+tlsFingerprint)
	}
	return txt
}

// Service is a running mDNS announcement.
type Service struct{ srv *zeroconf.Server }

// StartAnnounce registers the _yaop-sync._tcp service on the given port.
func StartAnnounce(serverID string, port int, tlsFingerprint string) (*Service, error) {
	srv, err := zeroconf.Register("YAOP", "_yaop-sync._tcp", "local.", port,
		announceTXT(SyncProtocolVersion, serverID, tlsFingerprint), nil)
	if err != nil {
		return nil, fmt.Errorf("mDNS register on port %d: %w", port, err)
	}
	return &Service{srv: srv}, nil
}

// Shutdown stops the announcement.
func (s *Service) Shutdown() {
	if s != nil && s.srv != nil {
		s.srv.Shutdown()
	}
}
