package simclient

import (
	"bytes"
	crypto_rand "crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
)

// Blind-sync HTTP client — the reference transport a YAOP device speaks
// against a yaop-sync/1 server (spec/sync.md). It exists for the e2e
// suites (and as the Go reference the mobile/desktop ports validate against);
// the server binary never touches it.

// ClientError is the wire error schema parsed back into a value.
type ClientError struct {
	Status  int
	Code    string
	Message string
	Extra   map[string]any
	Raw     []byte
}

func (e *ClientError) Error() string {
	return fmt.Sprintf("%d %s: %s", e.Status, e.Code, e.Message)
}

// Client is one device's connection: base URL plus its per-device token.
type Client struct {
	BaseURL string
	Token   string
	HTTP    *http.Client
}

func NewClient(baseURL, token string) *Client {
	return &Client{BaseURL: baseURL, Token: token, HTTP: &http.Client{}}
}

func (c *Client) do(method, path string, body []byte, contentType string) (int, []byte, error) {
	var reader io.Reader
	if body != nil {
		reader = bytes.NewReader(body)
	}
	req, err := http.NewRequest(method, c.BaseURL+path, reader)
	if err != nil {
		return 0, nil, err
	}
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	req.Header.Set("Authorization", "Bearer "+c.Token)
	req.Header.Set("Accept-Encoding", "identity") // deterministic test bodies
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(resp.Body)
	return resp.StatusCode, raw, err
}

// decodeInto maps a JSON answer or builds the wire error.
func decodeInto(status int, raw []byte, v any) *ClientError {
	if status >= 400 {
		if serr := wireError(status, raw); serr != nil {
			return serr
		}
		return &ClientError{Status: status, Message: string(raw)}
	}
	if v == nil {
		return nil
	}
	if err := json.Unmarshal(raw, v); err != nil {
		return &ClientError{Status: status, Message: fmt.Sprintf("decode %s: %v", string(raw), err)}
	}
	return nil
}

// wireError parses {"error":{code,message,…}} when present.
func wireError(status int, raw []byte) *ClientError {
	var parsed struct {
		Error map[string]any `json:"error"`
	}
	if err := json.Unmarshal(raw, &parsed); err != nil || parsed.Error == nil {
		return nil
	}
	ce := &ClientError{Status: status, Raw: raw, Extra: map[string]any{}}
	if code, ok := parsed.Error["code"].(string); ok {
		ce.Code = code
	}
	if msg, ok := parsed.Error["message"].(string); ok {
		ce.Message = msg
	}
	for k, v := range parsed.Error {
		if k != "code" && k != "message" {
			ce.Extra[k] = v
		}
	}
	return ce
}

// PushResponse mirrors the push body of spec/sync.md.
type PushResponse struct {
	Results []PushResult `json:"results"`
	HeadSeq uint64       `json:"head_seq"`
}

// PullResponse mirrors the pull body of spec/sync.md.
type PullResponse struct {
	Ops     []LoggedOp `json:"ops"`
	HeadSeq uint64     `json:"head_seq"`
	HasMore bool       `json:"has_more"`
}

// StatusResponse mirrors GET /v1/sync/status.
type StatusResponse struct {
	ProtocolVersion string  `json:"protocol_version"`
	Vault           string  `json:"vault"`
	HeadSeq         uint64  `json:"head_seq"`
	CompactFloor    uint64  `json:"compact_floor"`
	BootstrapSeq    *uint64 `json:"bootstrap_seq"`
	DeviceID        string  `json:"device_id"`
}

// Push sends ops (already built with BuildOp — envelopes sealed, ids blinded).
func (c *Client) Push(ops []Op) (*PushResponse, *ClientError) {
	body, _ := json.Marshal(map[string]any{"ops": ops})
	status, raw, err := c.do(http.MethodPost, "/v1/sync/push", body, "application/json")
	if err != nil {
		return nil, &ClientError{Status: 0, Message: err.Error()}
	}
	var out PushResponse
	if serr := decodeInto(status, raw, &out); serr != nil {
		return nil, serr
	}
	return &out, nil
}

// Pull fetches every op above sinceSeq (loop has_more with the last seq).
func (c *Client) Pull(sinceSeq uint64, limit int) (*PullResponse, *ClientError) {
	req := map[string]any{"since_seq": sinceSeq}
	if limit > 0 {
		req["limit"] = limit
	}
	body, _ := json.Marshal(req)
	status, raw, err := c.do(http.MethodPost, "/v1/sync/pull", body, "application/json")
	if err != nil {
		return nil, &ClientError{Status: 0, Message: err.Error()}
	}
	var out PullResponse
	if serr := decodeInto(status, raw, &out); serr != nil {
		return nil, serr
	}
	return &out, nil
}

// Status reads the negotiation JSON.
func (c *Client) Status() (*StatusResponse, *ClientError) {
	status, raw, err := c.do(http.MethodGet, "/v1/sync/status", nil, "")
	if err != nil {
		return nil, &ClientError{Status: 0, Message: err.Error()}
	}
	var out StatusResponse
	if serr := decodeInto(status, raw, &out); serr != nil {
		return nil, serr
	}
	return &out, nil
}

// BootstrapResponse mirrors GET /v1/sync/bootstrap.
type BootstrapResponse struct {
	CoveredSeq uint64 `json:"covered_seq"`
	Seq        uint64 `json:"seq"`
	Blob       string `json:"blob"`
}

// GetBootstrap downloads the active bootstrap snapshot op.
func (c *Client) GetBootstrap() (*BootstrapResponse, *ClientError) {
	status, raw, err := c.do(http.MethodGet, "/v1/sync/bootstrap", nil, "")
	if err != nil {
		return nil, &ClientError{Status: 0, Message: err.Error()}
	}
	var out BootstrapResponse
	if serr := decodeInto(status, raw, &out); serr != nil {
		return nil, serr
	}
	return &out, nil
}

// PromoteBootstrap declares the covered_seq of a snapshot the device pushed.
func (c *Client) PromoteBootstrap(opID string, coveredSeq uint64) (*BootstrapResponse, *ClientError) {
	body, _ := json.Marshal(map[string]any{"op_id": opID, "covered_seq": coveredSeq})
	status, raw, err := c.do(http.MethodPost, "/v1/sync/bootstrap", body, "application/json")
	if err != nil {
		return nil, &ClientError{Status: 0, Message: err.Error()}
	}
	var out BootstrapResponse
	if cerr := decodeInto(status, raw, &out); cerr != nil {
		return nil, cerr
	}
	return &out, nil
}

// AssetChunkResult answers one uploaded chunk.
type AssetChunkResult struct {
	Status       int
	Body         map[string]any
	UploadOffset int // resume point after the PUT
	Deduplicated bool
	ETag         string
}

// PutAssetChunk uploads the ciphertext of chunk n of an asset (blind wire_id),
// declaring the session's total chunk count.
func (c *Client) PutAssetChunk(wireID string, n, total int, data []byte) (*AssetChunkResult, *ClientError) {
	path := "/v1/assets/" + wireID + "/upload"
	req, err := http.NewRequest(http.MethodPut, c.BaseURL+path, bytes.NewReader(data))
	if err != nil {
		return nil, &ClientError{Message: err.Error()}
	}
	req.Header.Set("Authorization", "Bearer "+c.Token)
	req.Header.Set("Content-Type", "application/octet-stream")
	req.Header.Set("Upload-Offset", strconv.Itoa(n))
	req.Header.Set("Upload-Length", strconv.Itoa(total))
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, &ClientError{Message: err.Error()}
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	res := &AssetChunkResult{
		Status:       resp.StatusCode,
		Deduplicated: resp.Header.Get("X-Chunk-Deduplicated") == "1",
		ETag:         resp.Header.Get("ETag"),
	}
	if off, err := strconv.Atoi(resp.Header.Get("Upload-Offset")); err == nil {
		res.UploadOffset = off
	}
	if len(raw) > 0 && json.Valid(raw) {
		_ = json.Unmarshal(raw, &res.Body)
	}
	if resp.StatusCode >= 400 {
		if serr := wireError(resp.StatusCode, raw); serr != nil {
			return res, serr
		}
		return res, &ClientError{Status: resp.StatusCode, Message: string(raw)}
	}
	return res, nil
}

// GetAssetChunk downloads the ciphertext of one chunk.
func (c *Client) GetAssetChunk(wireID string, n int) ([]byte, *ClientError) {
	status, raw, err := c.do(http.MethodGet, "/v1/assets/"+wireID+"/chunk/"+strconv.Itoa(n), nil, "")
	if err != nil {
		return nil, &ClientError{Message: err.Error()}
	}
	if status >= 400 {
		if serr := wireError(status, raw); serr != nil {
			return nil, serr
		}
		return nil, &ClientError{Status: status, Message: string(raw)}
	}
	return raw, nil
}

// KindDomain maps an op kind to its crypto domain (spec/crypto.md §Subkeys).
func KindDomain(kind string) string {
	switch kind {
	case "obj", "type", "asset_meta":
		return DomainObjects
	case "derived":
		return DomainDerived
	case "snapshot":
		return DomainSnapshots
	default: // node_request and anything still opaque at this level
		return DomainOps
	}
}

// NewOpID draws 32 hex chars, the op_id of a client push.
func NewOpID() string {
	raw := make([]byte, 16)
	if _, err := randRead(raw); err != nil {
		return ""
	}
	return hexEncode(raw)
}

// BuildOp seals a payload into an op: blinded id, envelope (zstd for JSON
// payloads), key epoch. The caller stamps op_id and device_id.
func BuildOp(vk []byte, epoch uint32, kind, realID string, payload []byte) (Op, error) {
	idsKey := Subkey(vk, DomainIds)
	wire := WireID(idsKey, realID)
	domain := KindDomain(kind)
	env, err := SealEnvelope(Subkey(vk, domain), epoch, domain, wire, payload, true, nil)
	if err != nil {
		return Op{}, err
	}
	return Op{
		Kind:     kind,
		WireID:   wire,
		KeyEpoch: epoch,
		Blob:     base64.StdEncoding.EncodeToString(env),
	}, nil
}

// WireIDOf blinds a real id under this vault key/epoch.
func WireIDOf(vk []byte, realID string) string {
	return WireID(Subkey(vk, DomainIds), realID)
}

// OpenOpEnvelope decrypts a pulled op with the vault key of its declared epoch.
func OpenOpEnvelope(vk []byte, op LoggedOp) ([]byte, error) {
	domain := KindDomain(op.Kind)
	env, err := base64.StdEncoding.DecodeString(op.Blob)
	if err != nil {
		return nil, err
	}
	payload, _, _, err := OpenEnvelope(Subkey(vk, domain), env, domain, op.WireID)
	if err != nil {
		return nil, err
	}
	return payload, nil
}

func randRead(b []byte) (int, error) { return crypto_rand.Read(b) }

func hexEncode(b []byte) string { return hex.EncodeToString(b) }
