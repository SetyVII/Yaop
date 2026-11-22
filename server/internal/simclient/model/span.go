package model

// TextMark represents formatting marks applied to a text run.
type TextMark string

const (
	MarkBold          TextMark = "bold"
	MarkItalic        TextMark = "italic"
	MarkUnderline     TextMark = "underline"
	MarkStrikethrough TextMark = "strike"
	MarkCode          TextMark = "code"
	MarkHighlight     TextMark = "highlight"
)

// Canonical named color palette for inline text/background and block colors.
const (
	ColorGray   = "gray"
	ColorYellow = "yellow"
	ColorOrange = "orange"
	ColorRed    = "red"
	ColorPink   = "pink"
	ColorPurple = "purple"
	ColorBlue   = "blue"
	ColorIce    = "ice"
	ColorTeal   = "teal"
	ColorGreen  = "green"
)

// Run represents an inline span of structured rich text.
// wire-compatible with YAOP InlineSpan (spec/text.md).
type Run struct {
	Text          string  `json:"text"`
	Bold          bool    `json:"bold,omitempty"`
	Italic        bool    `json:"italic,omitempty"`
	Underline     bool    `json:"underline,omitempty"`
	Strike        bool    `json:"strike,omitempty"`
	Code          bool    `json:"code,omitempty"`
	Highlight     bool    `json:"highlight,omitempty"`
	Href          string  `json:"href,omitempty"`
	TextColor     string  `json:"text_color,omitempty"`
	BgColor       string  `json:"bg_color,omitempty"`
	ObjectID      string  `json:"object_id,omitempty"`
	BlockID       string  `json:"block_id,omitempty"` // block reference (spec/text.md)
	TagID         string  `json:"tag_id,omitempty"`
	TagName       string  `json:"tag_name,omitempty"`
	Date          string  `json:"date,omitempty"`
	HasTime       bool    `json:"has_time,omitempty"`
	Type          string  `json:"type,omitempty"` // "math" or "image"
	Latex         string  `json:"latex,omitempty"`
	Display       bool    `json:"display,omitempty"`
	ImageName     string  `json:"image_name,omitempty"`
	Width         float64 `json:"width,omitempty"`
	Height        float64 `json:"height,omitempty"`
	ReferenceIcon bool    `json:"reference_icon,omitempty"`
	PointSize     float64 `json:"point_size,omitempty"`
}

// PlainText concatenates the text of a slice of runs.
func RunsToPlainText(runs []Run) string {
	var total int
	for _, r := range runs {
		total += len(r.Text)
	}
	buf := make([]byte, 0, total)
	for _, r := range runs {
		buf = append(buf, r.Text...)
	}
	return string(buf)
}
