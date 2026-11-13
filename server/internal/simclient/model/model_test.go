package model

import (
	"encoding/json"
	"testing"
)

func TestModel_BlockSerialization(t *testing.T) {
	block := Block{
		ID:         "b-1",
		Type:       BlockHeading,
		Level:      2,
		Indent:     2,
		BlockColor: ColorIce,
		Text:       "Heading with indent and color",
		Runs: []Run{
			{
				Text:      "Encabezado",
				Bold:      true,
				TextColor: ColorIce,
			},
			{
				Text: " with indent",
			},
		},
	}

	data, err := json.Marshal(block)
	if err != nil {
		t.Fatalf("Marshal failed: %v", err)
	}

	var decoded Block
	if err := json.Unmarshal(data, &decoded); err != nil {
		t.Fatalf("Unmarshal failed: %v", err)
	}

	if decoded.Type != BlockHeading || decoded.Level != 2 || decoded.Indent != 2 || decoded.BlockColor != ColorIce {
		t.Errorf("Block decoded incorrectly: %+v", decoded)
	}

	if len(decoded.Runs) != 2 || !decoded.Runs[0].Bold || decoded.Runs[0].TextColor != ColorIce {
		t.Errorf("Runs decoded incorrectly: %+v", decoded.Runs)
	}
}

func TestModel_GroupFlattening(t *testing.T) {
	flat := []Block{
		{ID: "b1", Type: BlockParagraph, GroupID: "grp-1", Text: "Item 1"},
		{ID: "b2", Type: BlockParagraph, GroupID: "grp-1", Text: "Item 2"},
		{ID: "b3", Type: BlockParagraph, Text: "Standalone"},
	}

	grouped := GroupBlocks(flat)
	if len(grouped) != 2 {
		t.Fatalf("Expected 2 top blocks, got %d", len(grouped))
	}
	if grouped[0].Type != BlockGroup || len(grouped[0].Children) != 2 {
		t.Errorf("First block should be group with 2 children: %+v", grouped[0])
	}
	if grouped[1].Type != BlockParagraph {
		t.Errorf("Second block should be standalone paragraph")
	}

	unflattened := FlattenBlocks(grouped)
	if len(unflattened) != 3 {
		t.Fatalf("Expected 3 flat blocks, got %d", len(unflattened))
	}
	if unflattened[0].GroupID != "grp-1" || unflattened[1].GroupID != "grp-1" {
		t.Errorf("Children did not preserve group ID: %+v", unflattened)
	}
}

func TestModel_CanvasSerialization(t *testing.T) {
	canvasBlock := Block{
		ID:   "cv-1",
		Type: BlockCanvas,
		CanvasData: &WhiteboardData{
			ID: "wb-1",
			Nodes: []WhiteboardNode{
				{
					ID: "node-1",
					X:  100, Y: 200, Width: 240, Height: 150,
					Content: NodeContentItem{
						Type:     "sticky",
						Text:     "Idea importante",
						ColorHex: "#FFB522",
					},
				},
			},
			Connectors: []WhiteboardConnector{
				{
					ID:         "conn-1",
					FromNodeID: "node-1",
					ToNodeID:   "node-2",
					Directed:   true,
				},
			},
		},
	}

	data, err := json.Marshal(canvasBlock)
	if err != nil {
		t.Fatalf("Marshal canvas failed: %v", err)
	}

	var decoded Block
	if err := json.Unmarshal(data, &decoded); err != nil {
		t.Fatalf("Unmarshal canvas failed: %v", err)
	}

	if decoded.CanvasData == nil || len(decoded.CanvasData.Nodes) != 1 || len(decoded.CanvasData.Connectors) != 1 {
		t.Fatalf("CanvasData decoded incorrectly: %+v", decoded.CanvasData)
	}
}
