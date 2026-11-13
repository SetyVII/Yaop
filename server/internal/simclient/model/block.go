package model

// Canonical block types.
const (
	BlockParagraph      = "paragraph"
	BlockHeading        = "heading"
	BlockBulletList     = "bullet_list"
	BlockNumberedList   = "numbered_list"
	BlockAlphabeticList = "alphabetic_list"
	BlockRomanList      = "roman_list"
	BlockTodo           = "todo"
	BlockToggle         = "toggle"
	BlockQuote          = "quote"
	BlockCallout        = "callout"
	BlockGroup          = "group"
	BlockCode           = "code"
	BlockMath           = "math"
	BlockDivider        = "divider"
	BlockTable          = "table"
	BlockEmbed          = "embed"
	BlockImage          = "image"
	BlockAudio          = "audio"
	BlockPdf            = "pdf"
	BlockBookmark       = "bookmark"
	BlockCanvas         = "canvas"
)

// Callout types.
const (
	CalloutInfo    = "INFO"
	CalloutIdea    = "IDEA"
	CalloutWarning = "WARNING"
	CalloutNote    = "NOTE"
)

// Block represents a single content block in the structured document model.
// Complete 1:1 parity with YAOP mobile, tablet, and desktop editors.
type Block struct {
	ID         string `json:"id"`
	Type       string `json:"type"`
	Indent     int    `json:"indent,omitempty"` // 1-based (1..8) in wire format, matches indentLevel + 1
	BlockColor string `json:"block_color,omitempty"`
	GroupID    string `json:"group_id,omitempty"`

	// Text & formatting runs
	Text string `json:"text,omitempty"`
	Runs []Run  `json:"runs,omitempty"`

	// Heading
	Level int `json:"level,omitempty"` // 1..4

	// Lists
	Number int `json:"number,omitempty"` // for numbered_list, alphabetic_list, roman_list

	// Interactive states
	Checked  bool `json:"checked,omitempty"`  // for todo
	Expanded bool `json:"expanded,omitempty"` // for toggle

	// Callout
	CalloutType string `json:"callout_type,omitempty"` // INFO, IDEA, WARNING, NOTE
	Emoji       string `json:"emoji,omitempty"`

	// Technical & mathematical
	Language string `json:"language,omitempty"`
	Latex    string `json:"latex,omitempty"`

	// Table
	Align []string   `json:"align,omitempty"`
	Rows  [][]string `json:"rows,omitempty"`

	// Embed & Relations
	ObjectID string `json:"object_id,omitempty"`
	ViewMode string `json:"view_mode,omitempty"` // "card", "compact", "inline"

	// Media assets
	AssetID         string  `json:"asset_id,omitempty"`
	WidthPercent    float64 `json:"width_percent,omitempty"`
	DurationSeconds int     `json:"duration_seconds,omitempty"`
	PageCount       int     `json:"page_count,omitempty"`
	LastReadPage    int     `json:"last_read_page,omitempty"`
	FileName        string  `json:"file_name,omitempty"`

	// Web bookmark
	URL          string `json:"url,omitempty"`
	Title        string `json:"title,omitempty"`
	Description  string `json:"description,omitempty"`
	Domain       string `json:"domain,omitempty"`
	ThumbnailURL string `json:"thumbnail_url,omitempty"`
	FaviconURL   string `json:"favicon_url,omitempty"`

	// Whiteboard infinite canvas
	CanvasData *WhiteboardData `json:"canvas_data,omitempty"`

	// Hierarchical group container (when type == "group")
	Children []Block `json:"children,omitempty"`
}

// FlattenBlocks unpacks any hierarchical "group" blocks into a flat block sequence
// with GroupID populated, matching YAOP internal flat representation.
func FlattenBlocks(blocks []Block) []Block {
	out := make([]Block, 0, len(blocks))
	for _, b := range blocks {
		if b.Type == BlockGroup && len(b.Children) > 0 {
			gid := b.ID
			if gid == "" {
				gid = b.GroupID
			}
			for _, child := range b.Children {
				if child.GroupID == "" {
					child.GroupID = gid
				}
				out = append(out, child)
			}
		} else {
			out = append(out, b)
		}
	}
	return out
}

// GroupBlocks groups consecutive blocks sharing the same non-empty GroupID
// into a single "group" block with Children, matching the canonical wire format.
func GroupBlocks(blocks []Block) []Block {
	out := make([]Block, 0, len(blocks))
	i := 0
	for i < len(blocks) {
		b := blocks[i]
		if b.GroupID != "" {
			gid := b.GroupID
			group := Block{
				ID:       gid,
				Type:     BlockGroup,
				Text:     "",
				Runs:     []Run{},
				Children: []Block{},
			}
			for i < len(blocks) && blocks[i].GroupID == gid {
				group.Children = append(group.Children, blocks[i])
				i++
			}
			out = append(out, group)
		} else {
			out = append(out, b)
			i++
		}
	}
	return out
}
