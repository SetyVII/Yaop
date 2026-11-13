package main

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"yaop-server/internal/api"
	"yaop-server/internal/crypto"
	"yaop-server/internal/discovery"
	"yaop-server/internal/store"
)

// cliConfig carries the binary's flags (testable without running main).
type cliConfig struct {
	listenAddr     string
	dataDir        string
	allowedOrigins string
	enableMDNS     bool
	initSecrets    bool
	runFsck        bool
	runHealth      bool
}

// registerFlags declares the command line of the BLIND server: everything the
// /3 binary offers. The -migrate-doc2/3 one-shots of the content era are gone
// with the endpoints that read content.
func registerFlags(fs *flag.FlagSet) *cliConfig {
	c := &cliConfig{}
	fs.StringVar(&c.listenAddr, "listen", envString("YAOP_LISTEN", "0.0.0.0:8765"), "Address and port to bind")
	fs.StringVar(&c.dataDir, "data-dir", envString("YAOP_DATA_DIR", "./data"), "Directory for database and secret files")
	fs.StringVar(&c.allowedOrigins, "allowed-origins", envString("YAOP_ALLOWED_ORIGINS", ""), "Comma-separated list of allowed CORS origins")
	fs.BoolVar(&c.enableMDNS, "mdns", true, "Enable local mDNS zero-conf announcement")
	fs.BoolVar(&c.initSecrets, "init-secrets", false, "Generate new keys and exit")
	fs.BoolVar(&c.runFsck, "fsck", false, "Check cryptographic integrity of database and exit")
	fs.BoolVar(&c.runHealth, "healthcheck", false, "Perform local HTTP health check and exit")
	return c
}

func main() {
	fs := flag.NewFlagSet("yaop-server", flag.ExitOnError)
	cfg := registerFlags(fs)
	if err := fs.Parse(os.Args[1:]); err != nil {
		log.Fatalf("Fatal: %v", err)
	}

	// 1. Healthcheck mode
	if cfg.runHealth {
		os.Exit(checkHealth(cfg.listenAddr))
	}

	if err := os.MkdirAll(cfg.dataDir, 0700); err != nil {
		log.Fatalf("Fatal: failed to create data directory %s: %v", cfg.dataDir, err)
	}

	// 2. Secret management (AES-256 key + Bearer token)
	masterKeyPath := filepath.Join(cfg.dataDir, "master.key")
	bearerTokenPath := filepath.Join(cfg.dataDir, "bearer.token")

	if cfg.initSecrets {
		if err := generateSecrets(masterKeyPath, bearerTokenPath); err != nil {
			log.Fatalf("Fatal generating secrets: %v", err)
		}
		fmt.Printf("Secrets successfully generated in %s\n", cfg.dataDir)
		return
	}

	masterKey, bearerToken, err := loadOrInitSecrets(cfg.dataDir, masterKeyPath, bearerTokenPath)
	if err != nil {
		log.Fatalf("Fatal loading secrets: %v", err)
	}

	// 3. Vault & Encrypted Store
	vault, err := crypto.NewVault(masterKey)
	if err != nil {
		log.Fatalf("Fatal initializing AES-256 vault: %v", err)
	}

	dbPath := filepath.Join(cfg.dataDir, "yaop.db")
	st, err := store.Open(dbPath, vault)
	if err != nil {
		log.Fatalf("Fatal opening SQLite store: %v", err)
	}
	defer st.Close()

	// 4. Offline fsck mode
	if cfg.runFsck {
		log.Printf("Running cryptographic fsck on %s ...", dbPath)
		if err := st.Fsck(); err != nil {
			log.Fatalf("Fsck failed: %v", err)
		}
		log.Printf("Fsck completed: 100%% of records decrypted and authenticated with GCM tag.")
		return
	}

	// 5. Persistent Server Identity
	var identity struct {
		ID string `json:"id"`
	}
	if err := st.Get("system", "server-id", &identity); err != nil || identity.ID == "" {
		identity.ID = newRandomID()
		if err := st.Put("system", "server-id", identity); err != nil {
			log.Fatalf("Fatal writing server ID: %v", err)
		}
	}

	// Compute local fingerprint for pairing identification
	fpHash := sha256.Sum256([]byte(identity.ID + ":" + bearerToken))
	tlsFingerprint := hex.EncodeToString(fpHash[:])

	// 6. Origins
	var origins []string
	if cfg.allowedOrigins != "" {
		for _, o := range strings.Split(cfg.allowedOrigins, ",") {
			if trimmed := strings.TrimSpace(o); trimmed != "" {
				origins = append(origins, trimmed)
			}
		}
	}

	// 7. API Server & HTTP Router
	srv := api.NewServer(st, identity.ID, bearerToken, origins, tlsFingerprint)

	// Retention housekeeping: 30-day snapshot purge + expired pairings.
	srv.StartMaintenance(15 * time.Minute)

	// 8. Local mDNS Announcement
	var mdnsService *discovery.Service
	if cfg.enableMDNS {
		port := extractPort(cfg.listenAddr)
		var err error
		mdnsService, err = discovery.StartAnnounce(identity.ID, port, tlsFingerprint)
		if err != nil {
			log.Printf("Warning: failed to start mDNS service: %v", err)
		} else {
			srv.SetMdnsService(mdnsService)
			log.Printf("mDNS service active: broadcasting _yaop-sync._tcp on port %d", port)
		}
	}

	httpServer := &http.Server{
		Addr:              cfg.listenAddr,
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	// 9. Startup banner
	log.Printf("==========================================================")
	log.Printf("  YAOP Server v%s (Concurrent & Encrypted)", api.ApiVersion)
	log.Printf("  Data directory: %s", cfg.dataDir)
	log.Printf("  Server ID:      %s", identity.ID)
	log.Printf("  Fingerprint:    %s", tlsFingerprint)
	log.Printf("  Listening on:   http://%s", cfg.listenAddr)
	log.Printf("==========================================================")

	// Graceful shutdown handling
	stopChan := make(chan os.Signal, 1)
	signal.Notify(stopChan, os.Interrupt, syscall.SIGTERM)

	go func() {
		if err := httpServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("Server error: %v", err)
		}
	}()

	<-stopChan
	log.Println("Shutting down YAOP server gracefully...")

	if mdnsService != nil {
		mdnsService.Shutdown()
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := httpServer.Shutdown(ctx); err != nil {
		log.Printf("HTTP shutdown error: %v", err)
	}

	log.Println("YAOP server stopped cleanly.")
}

func extractPort(listen string) int {
	_, portStr, err := net.SplitHostPort(listen)
	if err == nil {
		if p, err := strconv.Atoi(portStr); err == nil && p > 0 {
			return p
		}
	}
	return 8765
}

func checkHealth(listen string) int {
	host, port, err := net.SplitHostPort(listen)
	if err != nil {
		port = "8765"
	}
	if host == "0.0.0.0" || host == "" {
		host = "127.0.0.1"
	}

	url := fmt.Sprintf("http://%s:%s/health", host, port)
	client := http.Client{Timeout: 3 * time.Second}
	resp, err := client.Get(url)
	if err != nil || resp.StatusCode != http.StatusOK {
		return 1
	}
	return 0
}

func generateSecrets(masterKeyPath, bearerTokenPath string) error {
	rawKey := make([]byte, 32)
	if _, err := rand.Read(rawKey); err != nil {
		return err
	}
	keyHex := hex.EncodeToString(rawKey)
	if err := os.WriteFile(masterKeyPath, []byte(keyHex), 0600); err != nil {
		return err
	}

	rawToken := make([]byte, 24)
	if _, err := rand.Read(rawToken); err != nil {
		return err
	}
	tokenHex := hex.EncodeToString(rawToken) // 48 chars
	if err := os.WriteFile(bearerTokenPath, []byte(tokenHex), 0600); err != nil {
		return err
	}

	return nil
}

func loadOrInitSecrets(dataDir, masterKeyPath, bearerTokenPath string) ([]byte, string, error) {
	// Master Key: Check ENV first, then file, then auto-generate
	var masterKey []byte
	if envKey := envString("YAOP_MASTER_KEY", ""); envKey != "" {
		b, err := hex.DecodeString(strings.TrimSpace(envKey))
		if err == nil && len(b) == 32 {
			masterKey = b
		} else if len(envKey) == 32 {
			masterKey = []byte(envKey)
		} else {
			return nil, "", fmt.Errorf("master key in env must be 32 bytes or 64 hex characters")
		}
	} else if keyBytes, err := os.ReadFile(masterKeyPath); err == nil {
		keyStr := strings.TrimSpace(string(keyBytes))
		if b, err := hex.DecodeString(keyStr); err == nil && len(b) == 32 {
			masterKey = b
		} else if len(keyStr) == 32 {
			masterKey = []byte(keyStr)
		} else {
			return nil, "", fmt.Errorf("invalid key in %s", masterKeyPath)
		}
	} else {
		// Auto-generate fresh master key
		raw := make([]byte, 32)
		if _, err := rand.Read(raw); err != nil {
			return nil, "", err
		}
		if err := os.WriteFile(masterKeyPath, []byte(hex.EncodeToString(raw)), 0600); err != nil {
			return nil, "", err
		}
		masterKey = raw
		log.Printf("Generated new AES-256 master key in %s", masterKeyPath)
	}

	// Bearer Token: Check ENV first, then file, then auto-generate
	var bearerToken string
	if envToken := envString("YAOP_BEARER_TOKEN", ""); envToken != "" {
		bearerToken = strings.TrimSpace(envToken)
	} else if tokenBytes, err := os.ReadFile(bearerTokenPath); err == nil {
		bearerToken = strings.TrimSpace(string(tokenBytes))
	} else {
		// Auto-generate fresh token
		raw := make([]byte, 24)
		if _, err := rand.Read(raw); err != nil {
			return nil, "", err
		}
		bearerToken = hex.EncodeToString(raw)
		if err := os.WriteFile(bearerTokenPath, []byte(bearerToken), 0600); err != nil {
			return nil, "", err
		}
		log.Printf("Generated new Bearer token in %s", bearerTokenPath)
	}

	if len(bearerToken) < 32 {
		return nil, "", fmt.Errorf("bearer token must be at least 32 characters")
	}

	return masterKey, bearerToken, nil
}

func newRandomID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

func envString(key, fallback string) string {
	if val := os.Getenv(key); val != "" {
		return val
	}
	return fallback
}
