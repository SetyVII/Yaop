package simclient

import (
	"regexp"
	"testing"
)

type stdOption struct {
	ID     string            `json:"id"`
	Labels map[string]string `json:"labels"`
	Color  string            `json:"color"`
}

type stdProperty struct {
	Key     string            `json:"key"`
	Labels  map[string]string `json:"labels"`
	Kind    string            `json:"kind"`
	Options *[]stdOption      `json:"options"`
}

type stdType struct {
	ID         string            `json:"id"`
	Labels     map[string]string `json:"labels"`
	Properties []stdProperty     `json:"properties"`
}

// spec/types.md rules 1–3 on spec/fixtures/types/standard.json (same checks as C++ and Kotlin).
func TestConformance_StandardTypes(t *testing.T) {
	fx := readFixtures(t, "types")["standard.json"]
	if fx == nil {
		t.Fatal("spec/fixtures/types/standard.json missing")
	}
	palette := map[string]bool{}
	for _, c := range decode[[]string](t, fx["palette"]) {
		palette[c] = true
	}
	kinds := map[string]bool{"text": true, "number": true, "date": true, "select": true, "multi_select": true,
		"checkbox": true, "url": true, "object": true, "media": true}
	idRe := regexp.MustCompile(`^[a-z0-9_]+$`)
	typeIDs := map[string]bool{}
	for _, ty := range decode[[]stdType](t, fx["types"]) {
		if ty.ID == "" || typeIDs[ty.ID] {
			t.Errorf("type id missing or repeated: %q", ty.ID)
		}
		typeIDs[ty.ID] = true
		if ty.Labels["en"] == "" {
			t.Errorf("%s: no English label", ty.ID)
		}
		keys := map[string]bool{}
		for _, p := range ty.Properties {
			where := ty.ID + "." + p.Key
			if p.Key == "" || keys[p.Key] {
				t.Errorf("property key missing or repeated: %s", where)
			}
			keys[p.Key] = true
			if !kinds[p.Kind] {
				t.Errorf("%s: unknown kind %q", where, p.Kind)
			}
			isSelect := p.Kind == "select" || p.Kind == "multi_select"
			if p.Options != nil && !isSelect {
				t.Errorf("%s: options on a non-select kind", where)
			}
			if p.Options == nil {
				continue
			}
			ids := map[string]bool{}
			for _, o := range *p.Options {
				if !idRe.MatchString(o.ID) || ids[o.ID] {
					t.Errorf("%s: bad or repeated option id %q", where, o.ID)
				}
				ids[o.ID] = true
				if !palette[o.Color] {
					t.Errorf("%s.%s: color %q not in palette", where, o.ID, o.Color)
				}
				if o.Labels["en"] == "" {
					t.Errorf("%s.%s: no English label", where, o.ID)
				}
			}
		}
	}
	for _, want := range []string{"note", "journal", "task", "project", "person", "book", "web", "image", "tag", "habit"} {
		if !typeIDs[want] {
			t.Errorf("standard type %q missing", want)
		}
	}
}
