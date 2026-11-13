package model

// WhiteboardData represents the entire 2D canvas of visual notes and connectors.
type WhiteboardData struct {
	ID         string                `json:"id"`
	Nodes      []WhiteboardNode      `json:"nodes"`
	Connectors []WhiteboardConnector `json:"connectors"`
	Sections   []WhiteboardSection   `json:"sections"`
	Strokes    []WhiteboardStroke    `json:"strokes"`
	Viewport   CanvasViewport        `json:"viewport"`
}

type CanvasViewport struct {
	PanX float64 `json:"panX"`
	PanY float64 `json:"panY"`
	Zoom float64 `json:"zoom"`
}

type WhiteboardNode struct {
	ID      string          `json:"id"`
	X       float64         `json:"x"`
	Y       float64         `json:"y"`
	Width   float64         `json:"width"`
	Height  float64         `json:"height"`
	ZIndex  int             `json:"zIndex"`
	Content NodeContentItem `json:"content"`
}

type NodeContentItem struct {
	Type     string `json:"type"` // "object_card", "sticky", "text"
	ObjectID string `json:"objectId,omitempty"`
	ViewMode string `json:"viewMode,omitempty"` // "COMPACT", "DETAILED"
	Text     string `json:"text,omitempty"`
	ColorHex string `json:"colorHex,omitempty"`
	FontSize int    `json:"fontSize,omitempty"`
}

type WhiteboardConnector struct {
	ID         string `json:"id"`
	FromNodeID string `json:"fromNodeId"`
	ToNodeID   string `json:"toNodeId"`
	Label      string `json:"label,omitempty"`
	Style      string `json:"style,omitempty"` // "SOLID", "DASHED"
	ColorHex   string `json:"colorHex,omitempty"`
	Directed   bool   `json:"directed"`
}

type WhiteboardSection struct {
	ID       string  `json:"id"`
	Title    string  `json:"title"`
	X        float64 `json:"x"`
	Y        float64 `json:"y"`
	Width    float64 `json:"width"`
	Height   float64 `json:"height"`
	ColorHex string  `json:"colorHex,omitempty"`
}

type WhiteboardStroke struct {
	ID            string        `json:"id"`
	Points        []StrokePoint `json:"points"`
	ColorHex      string        `json:"colorHex"`
	StrokeWidth   float64       `json:"strokeWidth"`
	IsHighlighter bool          `json:"isHighlighter,omitempty"`
}

type StrokePoint struct {
	X        float64 `json:"x"`
	Y        float64 `json:"y"`
	Pressure float64 `json:"pressure"`
}
