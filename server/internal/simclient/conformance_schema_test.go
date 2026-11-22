package simclient

import (
	"reflect"
	"testing"
)

// spec/fixtures/schema/change_type.json: same loose keys and errors as C++ and Kotlin.
func TestConformance_ChangeType(t *testing.T) {
	type tcase struct {
		Name   string `json:"name"`
		Object struct {
			TypeID     string         `json:"type_id"`
			Properties map[string]any `json:"properties"`
		} `json:"object"`
		To     string   `json:"to"`
		ToKeys []string `json:"to_keys"`
		Expect struct {
			TypeID string   `json:"type_id"`
			Loose  []string `json:"loose"`
			Error  string   `json:"error"`
		} `json:"expect"`
	}
	ran := 0
	for file, fx := range readFixtures(t, "schema") {
		for _, c := range decode[[]tcase](t, fx["cases"]) {
			ran++
			r := ChangeType(c.Object.TypeID, c.Object.Properties, c.To, c.ToKeys)
			if c.Expect.Error != "" {
				if r.Error != c.Expect.Error {
					t.Errorf("%s/%s: error %q, want %q", file, c.Name, r.Error, c.Expect.Error)
				}
				continue
			}
			if r.Error != "" || r.TypeID != c.Expect.TypeID || !reflect.DeepEqual(r.Loose, c.Expect.Loose) {
				t.Errorf("%s/%s: got %+v, want type %q loose %v", file, c.Name, r, c.Expect.TypeID, c.Expect.Loose)
			}
		}
	}
	if ran < 6 {
		t.Fatalf("only %d change_type cases ran", ran)
	}
}
