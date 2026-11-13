package simclient

import (
	"encoding/json"
	"testing"

	"yaop-server/internal/simclient/model"
)

// Runs the three-device fixtures of spec/fixtures/merge_assoc: the merge algebra
// (spec/merge.md §Merge algebra) must hold for every permutation of a,b,c and both
// groupings — idempotency, commutativity and associativity — and every final
// state must equal the fixture's "expect".
func TestConformance_MergeAssoc(t *testing.T) {
	checkExpect := func(label string, got model.Object, expect map[string]json.RawMessage) {
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
	for name, fx := range readFixtures(t, "merge_assoc") {
		a := decode[model.Object](t, fx["a"])
		b := decode[model.Object](t, fx["b"])
		c := decode[model.Object](t, fx["c"])
		expect := decode[map[string]json.RawMessage](t, fx["expect"])

		// idempotency: merge(x,x) == x for each device state (normalized CRDT canon)
		for dev, x := range map[string]model.Object{"a": a, "b": b, "c": c} {
			if fuzzObjCanon(MergeObjects(x, x)) != fuzzObjCanon(x) {
				t.Errorf("%s: merge(%s,%s) != %s\n  got  %s\n  want %s", name, dev, dev, dev,
					fuzzObjCanon(MergeObjects(x, x)), fuzzObjCanon(x))
			}
		}
		// all permutations, both groupings, plus the swapped fold: every
		// final state must be exactly the same convergent result
		perms := [][3]model.Object{
			{a, b, c}, {a, c, b}, {b, a, c}, {b, c, a}, {c, a, b}, {c, b, a},
		}
		for i, pm := range perms {
			x, y, z := pm[0], pm[1], pm[2]
			g1 := MergeObjects(MergeObjects(x, y), z)
			g2 := MergeObjects(x, MergeObjects(y, z))
			g3 := MergeObjects(z, MergeObjects(y, x))
			lbl := name + "/" + string(rune('0'+i))
			if fuzzObjCanon(g1) != fuzzObjCanon(g2) {
				t.Errorf("%s: not associative\nleft  %s\nright %s", lbl, fuzzObjCanon(g1), fuzzObjCanon(g2))
			}
			if fuzzObjCanon(g1) != fuzzObjCanon(g3) {
				t.Errorf("%s: not convergent\ng1 %s\ng3 %s", lbl, fuzzObjCanon(g1), fuzzObjCanon(g3))
			}
			checkExpect(lbl, g1, expect)
		}
	}
}
