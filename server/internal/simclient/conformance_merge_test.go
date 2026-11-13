package simclient

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"yaop-server/internal/simclient/model"
)

// Runs the shared contract fixtures in spec/fixtures (same files as Kotlin and C++).
const mergeFixturesDir = "../../../spec/fixtures"

func readFixtures(t *testing.T, sub string) map[string]map[string]json.RawMessage {
	t.Helper()
	files, err := filepath.Glob(filepath.Join(mergeFixturesDir, sub, "*.json"))
	if err != nil || len(files) == 0 {
		t.Fatalf("no fixtures in %s/%s: %v", mergeFixturesDir, sub, err)
	}
	out := map[string]map[string]json.RawMessage{}
	for _, f := range files {
		raw, err := os.ReadFile(f)
		if err != nil {
			t.Fatal(err)
		}
		var m map[string]json.RawMessage
		if err := json.Unmarshal(raw, &m); err != nil {
			t.Fatalf("%s: %v", f, err)
		}
		out[filepath.Base(f)] = m
	}
	return out
}

func decode[T any](t *testing.T, raw json.RawMessage) T {
	t.Helper()
	var v T
	if err := json.Unmarshal(raw, &v); err != nil {
		t.Fatalf("decode: %v (%s)", err, raw)
	}
	return v
}

func emptyIfNil(m map[string]string) map[string]string {
	if m == nil {
		return map[string]string{}
	}
	return m
}

func TestConformance_HLC(t *testing.T) {
	for name, fx := range readFixtures(t, "hlc") {
		cases := decode[[]struct {
			Name string `json:"name"`
			A    string `json:"a"`
			B    string `json:"b"`
			Cmp  int    `json:"cmp"`
		}](t, fx["cases"])
		for _, c := range cases {
			if got := model.CompareHLC(c.A, c.B); got != c.Cmp {
				t.Errorf("%s/%s: CompareHLC(%q,%q)=%d want %d", name, c.Name, c.A, c.B, got, c.Cmp)
			}
			if got := model.CompareHLC(c.B, c.A); got != -c.Cmp {
				t.Errorf("%s/%s: not antisymmetric", name, c.Name)
			}
		}
		if raw, ok := fx["nodes"]; ok {
			for _, n := range decode[[]struct {
				DeviceID string `json:"device_id"`
				Node     string `json:"node"`
			}](t, raw) {
				if got := model.HLCNodeFor(n.DeviceID); got != n.Node {
					t.Errorf("node(%q)=%s want %s", n.DeviceID, got, n.Node)
				}
			}
		}
	}
}

func TestConformance_Stamp(t *testing.T) {
	for name, fx := range readFixtures(t, "stamp") {
		var prev *model.Object
		if string(fx["prev"]) != "null" {
			p := decode[model.Object](t, fx["prev"])
			prev = &p
		}
		next := decode[model.Object](t, fx["next"])
		now := decode[string](t, fx["now"])
		want := decode[struct {
			FieldHLC      map[string]string `json:"field_hlc"`
			BlockHLC      map[string]string `json:"block_hlc"`
			DeletedBlocks map[string]string `json:"deleted_blocks"`
			BlockAnchor   map[string]*struct {
				After string `json:"after"`
				HLC   string `json:"hlc"`
			} `json:"block_anchor"`
		}](t, fx["expect_meta"])
		wantHLC := decode[string](t, fx["expect_hlc"])

		got := StampObject(prev, next, now)
		check(t, name, "field_hlc", emptyIfNil(got.FieldHLC), emptyIfNil(want.FieldHLC))
		check(t, name, "block_hlc", emptyIfNil(got.BlockHLC), emptyIfNil(want.BlockHLC))
		check(t, name, "deleted_blocks", emptyIfNil(got.DeletedBlocks), emptyIfNil(want.DeletedBlocks))
		if want.BlockAnchor != nil {
			checkAnchors(t, name, got.BlockAnchor, want.BlockAnchor)
		}
		if got.HLC != wantHLC {
			t.Errorf("%s: hlc=%q want %q", name, got.HLC, wantHLC)
		}
	}
}

func emptyIfNilAnchors(m map[string]*model.BlockAnchor) map[string]*model.BlockAnchor {
	if m == nil {
		m = map[string]*model.BlockAnchor{}
	}
	return m
}

func checkAnchors(t *testing.T, name string, got map[string]*model.BlockAnchor, want map[string]*struct {
	After string `json:"after"`
	HLC   string `json:"hlc"`
}) {
	t.Helper()
	norm := emptyIfNilAnchors(got)
	if len(norm) != len(want) {
		t.Errorf("%s: block_anchor\n  got  %s\n  want %s", name, canon(norm), canon(want))
		return
	}
	for id, wa := range want {
		ga := norm[id]
		if ga == nil || wa == nil {
			t.Errorf("%s: block_anchor[%s]\n  got  %s\n  want %s", name, id, canon(norm), canon(want))
			return
		}
		if ga.After != wa.After || ga.HLC != wa.HLC {
			t.Errorf("%s: block_anchor[%s]\n  got  {after:%s hlc:%s}\n  want {after:%s hlc:%s}",
				name, id, ga.After, ga.HLC, wa.After, wa.HLC)
		}
	}
}

func TestConformance_Merge(t *testing.T) {
	for name, fx := range readFixtures(t, "merge") {
		a := decode[model.Object](t, fx["a"])
		b := decode[model.Object](t, fx["b"])
		expect := decode[map[string]json.RawMessage](t, fx["expect"])
		for dir, got := range map[string]model.Object{"merge(a,b)": MergeObjects(a, b), "merge(b,a)": MergeObjects(b, a)} {
			label := name + " " + dir
			gotJSON := decode[map[string]json.RawMessage](t, mustJSON(t, got))
			for key, wantRaw := range expect {
				g := gotJSON[key]
				if g == nil {
					g = json.RawMessage("{}")
				}
				if canon(decode[any](t, g)) != canon(decode[any](t, wantRaw)) {
					t.Errorf("%s: %s\n  got  %s\n  want %s", label, key, canon(decode[any](t, g)), canon(decode[any](t, wantRaw)))
				}
			}
		}
	}
}

func mustJSON(t *testing.T, v any) json.RawMessage {
	t.Helper()
	raw, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	return raw
}

func check(t *testing.T, name, field string, got, want map[string]string) {
	t.Helper()
	if canon(got) != canon(want) {
		t.Errorf("%s: %s\n  got  %s\n  want %s", name, field, canon(got), canon(want))
	}
}

func TestConformance_Links(t *testing.T) {
	for name, fx := range readFixtures(t, "links") {
		if raw, ok := fx["cases"]; ok {
			for _, c := range decode[[]struct {
				Name string `json:"name"`
				Key  string `json:"key"`
			}](t, raw) {
				if got := TagKey(c.Name); got != c.Key {
					t.Errorf("TagKey(%q)=%q want %q", c.Name, got, c.Key)
				}
			}
			continue
		}
		o := decode[model.Object](t, fx["object"])
		wantLinks := decode[[]string](t, fx["expect_links"])
		wantProp := decode[[]string](t, fx["expect_property_links"])
		if got := DeriveLinks(o); canon(got) != canon(wantLinks) {
			t.Errorf("%s: links\n  got  %v\n  want %v", name, got, wantLinks)
		}
		if got := filterPresent(o.PropertyLinks, o.Properties); canon(got) != canon(wantProp) {
			t.Errorf("%s: property_links got %v want %v", name, got, wantProp)
		}
	}
}
