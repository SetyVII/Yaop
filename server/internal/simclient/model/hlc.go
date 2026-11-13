package model

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"strconv"
	"strings"
)

// FormatHLC renders the canonical stamp. node is 8 lowercase hex chars ("" only for legacy stamps).
func FormatHLC(wall int64, counter int, node string) string {
	if node == "" {
		return fmt.Sprintf("%013d-%04x", wall, counter)
	}
	return fmt.Sprintf("%013d-%04x-%s", wall, counter, node)
}

// ParseHLC accepts "<wall>", "<wall>-<counter>" (legacy) and "<wall>-<counter>-<node>".
func ParseHLC(h string) (wall int64, counter int, node string) {
	parts := strings.SplitN(strings.TrimSpace(h), "-", 3)
	if len(parts) >= 1 {
		wall, _ = strconv.ParseInt(parts[0], 10, 64)
	}
	if len(parts) >= 2 {
		c, _ := strconv.ParseInt(parts[1], 16, 32)
		counter = int(c)
	}
	if len(parts) == 3 {
		node = parts[2]
	}
	return
}

// CompareHLC is a total order: wall, then counter, then node (legacy "" sorts first). "" < any stamp.
func CompareHLC(a, b string) int {
	if a == b {
		return 0
	}
	if a == "" {
		return -1
	}
	if b == "" {
		return 1
	}
	aw, ac, an := ParseHLC(a)
	bw, bc, bn := ParseHLC(b)
	switch {
	case aw != bw:
		if aw < bw {
			return -1
		}
		return 1
	case ac != bc:
		if ac < bc {
			return -1
		}
		return 1
	case an != bn:
		if an < bn {
			return -1
		}
		return 1
	}
	return 0
}

// MaxHLC returns the greater of two stamps.
func MaxHLC(a, b string) string {
	if CompareHLC(a, b) >= 0 {
		return a
	}
	return b
}

// NewClock returns an independent clock for a node (tests and simulated devices).
func NewClock(node string) *HybridLogicalClock {
	return &HybridLogicalClock{node: node}
}

// HLCNodeFor derives the 8-hex node of a device (spec/merge.md).
func HLCNodeFor(deviceID string) string {
	sum := sha256.Sum256([]byte(deviceID))
	return hex.EncodeToString(sum[:])[:8]
}
