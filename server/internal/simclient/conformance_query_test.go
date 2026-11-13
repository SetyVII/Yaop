package simclient

import (
	"encoding/json"
	"errors"
	"testing"
)

// spec/fixtures/query: same vault, same query, same today → same ids, order and groups.
func TestConformance_Query(t *testing.T) {
	for name, fx := range readFixtures(t, "query") {
		objects := decode[[]map[string]any](t, fx["objects"])
		today := decode[string](t, fx["today"])
		cases := decode[[]struct {
			Name   string          `json:"name"`
			Query  map[string]any  `json:"query"`
			Expect json.RawMessage `json:"expect"`
		}](t, fx["cases"])
		for _, c := range cases {
			label := name + "/" + c.Name
			want := decode[map[string]any](t, c.Expect)
			got, err := EvaluateQuery(c.Query, objects, today)
			if code, ok := want["error"].(string); ok {
				var qe *QueryError
				if !errors.As(err, &qe) || qe.Code != code {
					t.Errorf("%s: want error %s, got %v", label, code, err)
				}
				continue
			}
			if err != nil {
				t.Errorf("%s: unexpected error %v", label, err)
				continue
			}
			var gotAny any
			if _, grouped := want["groups"]; grouped {
				gotAny = map[string]any{"groups": got.Groups}
			} else {
				ids := got.IDs
				if ids == nil {
					ids = []string{}
				}
				gotAny = map[string]any{"ids": ids}
			}
			if canon(gotAny) != canon(want) {
				t.Errorf("%s\n  got  %s\n  want %s", label, canon(gotAny), canon(want))
			}
		}
	}
}
