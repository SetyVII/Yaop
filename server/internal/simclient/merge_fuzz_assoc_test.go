package simclient

import (
	"encoding/json"
	"fmt"
	"math/rand"
	"testing"

	"yaop-server/internal/simclient/model"
)

// ---------------------------------------------------------------------------
// Deterministic fuzz of the merge algebra (spec/merge.md, yaop-sync/1):
//   idempotency    merge(x, x) == x
//   commutativity  merge(x, y) == merge(y, x)
//   associativity  merge(merge(x, y), z) == merge(x, merge(y, z))
// Devices simulate the client stamp() rules: a block is re-anchored whenever
// its predecessor changes; tombstones keep their own anchor.
// Compared CRDT state: title, title_runs, type_id, properties (incl. block
// order), hlc, field_hlc, block_hlc, block_anchor, deleted_blocks, links,
// property_links, created_at. (content/updated_at are local display
// passthrough, see spec/merge.md §Merge 6.)
// ---------------------------------------------------------------------------

func fuzzObjCanon(o model.Object) string {
	norm := func(m map[string]string) map[string]string {
		if m == nil {
			m = map[string]string{}
		}
		return m
	}
	normS := func(s []string) []string {
		if s == nil {
			s = []string{}
		}
		return s
	}
	return canon(struct {
		Title         string                        `json:"title"`
		TitleRuns     []any                         `json:"title_runs"`
		TypeID        string                        `json:"type_id"`
		Properties    map[string]any                `json:"properties"`
		HLC           string                        `json:"hlc"`
		FieldHLC      map[string]string             `json:"field_hlc"`
		BlockHLC      map[string]string             `json:"block_hlc"`
		BlockAnchor   map[string]*model.BlockAnchor `json:"block_anchor"`
		DeletedBlocks map[string]string             `json:"deleted_blocks"`
		Links         []string                      `json:"links"`
		PropertyLinks []string                      `json:"property_links"`
		CreatedAt     string                        `json:"created_at"`
		ID            string                        `json:"id"`
	}{o.Title, o.TitleRuns, o.TypeID, o.Properties, o.HLC, norm(o.FieldHLC), norm(o.BlockHLC),
		o.BlockAnchor, norm(o.DeletedBlocks), normS(o.Links), normS(o.PropertyLinks), o.CreatedAt, o.ID})
}

func maxH(a, b string) string {
	if model.CompareHLC(a, b) >= 0 {
		return a
	}
	return b
}

func blocksToAny(b []map[string]any) []any {
	out := make([]any, 0, len(b))
	for _, x := range b {
		out = append(out, x)
	}
	return out
}

func deepcopyObject(o model.Object) model.Object {
	raw := canon(o)
	var out model.Object
	if err := json.Unmarshal([]byte(raw), &out); err != nil {
		panic(err)
	}
	return out
}

// fuzzDevice acts like one client device stamping saves (spec/merge.md).
type fuzzDevice struct {
	r      *rand.Rand
	node   string
	t      int64
	obj    model.Object
	seq    []string
	block  map[string]map[string]any
	bhlc   map[string]string
	anchor map[string]*model.BlockAnchor
	del    map[string]string
	field  map[string]string
}

func (f *fuzzDevice) now() string {
	f.t += int64(1 + f.r.Intn(50))
	return model.FormatHLC(f.t, f.r.Intn(2), f.node)
}

func (f *fuzzDevice) setAnchorOf(id string) {
	pos := -1
	for i, v := range f.seq {
		if v == id {
			pos = i
			break
		}
	}
	if pos < 0 {
		return
	}
	pred := ""
	if pos > 0 {
		pred = f.seq[pos-1]
	}
	f.anchor[id] = &model.BlockAnchor{After: pred, HLC: f.now()}
}

func (f *fuzzDevice) op(id string) {
	pos := -1
	for i, v := range f.seq {
		if v == id {
			pos = i
			break
		}
	}
	if pos < 0 {
		return
	}
	f.block[id] = map[string]any{"id": id, "type": "paragraph", "text": "editado"}
	f.bhlc[id] = maxH(f.bhlc[id], f.now())
	delete(f.del, id)
}

func (f *fuzzDevice) insert(id string) {
	pos := 0
	if len(f.seq) > 0 {
		pos = f.r.Intn(len(f.seq) + 1)
	}
	f.block[id] = map[string]any{"id": id, "type": "paragraph", "text": "nuevo " + id}
	f.seq = append(f.seq, "")
	copy(f.seq[pos+1:], f.seq[pos:])
	f.seq[pos] = id
	f.anchor[id] = &model.BlockAnchor{After: "", HLC: ""}
	f.setAnchorOf(id)
	f.bhlc[id] = f.now()
	if pos+1 < len(f.seq) {
		f.setAnchorOf(f.seq[pos+1]) // the follower's predecessor changed
	}
	delete(f.del, id)
}

func (f *fuzzDevice) remove(id string) {
	pos := -1
	for i, v := range f.seq {
		if v == id {
			pos = i
			break
		}
	}
	if pos < 0 {
		return
	}
	f.seq = append(f.seq[:pos], f.seq[pos+1:]...)
	if pos < len(f.seq) {
		f.setAnchorOf(f.seq[pos])
	}
	f.del[id] = f.now()
	delete(f.bhlc, id)
	delete(f.block, id)
}

func (f *fuzzDevice) move(id string) {
	pos := -1
	for i, v := range f.seq {
		if v == id {
			pos = i
			break
		}
	}
	if pos < 0 || len(f.seq) < 2 {
		return
	}
	f.remove(id)
	np := f.r.Intn(len(f.seq) + 1)
	f.seq = append(f.seq, "")
	copy(f.seq[np+1:], f.seq[np:])
	f.seq[np] = id
	f.block[id] = map[string]any{"id": id, "type": "paragraph", "text": "movido"}
	f.anchor[id] = &model.BlockAnchor{After: "", HLC: ""}
	f.setAnchorOf(id)
	if np+1 < len(f.seq) {
		f.setAnchorOf(f.seq[np+1])
	}
	delete(f.del, id)
	f.bhlc[id] = maxH(f.bhlc[id], f.now())
}

func (f *fuzzDevice) toObject() model.Object {
	props := map[string]any{"color": f.obj.Properties["color"]}
	blocks := make([]any, 0, len(f.seq))
	for _, id := range f.seq {
		blocks = append(blocks, f.block[id])
	}
	props[model.PropBlocks] = blocks
	out := f.obj
	out.Properties = props
	out.BlockHLC = f.bhlc
	out.BlockAnchor = f.anchor
	out.DeletedBlocks = f.del
	out.FieldHLC = f.field
	max := ""
	for _, s := range f.field {
		max = maxH(max, s)
	}
	for _, s := range f.bhlc {
		max = maxH(max, s)
	}
	out.HLC = maxH(out.HLC, max)
	return out
}

func evolveFuzzState(base model.Object, node string, r *rand.Rand) model.Object {
	d := &fuzzDevice{
		r:      r,
		node:   node,
		t:      int64(1000000 + r.Intn(100000)),
		obj:    deepcopyObject(base),
		block:  map[string]map[string]any{},
		bhlc:   map[string]string{},
		anchor: map[string]*model.BlockAnchor{},
		del:    map[string]string{},
		field:  map[string]string{},
	}
	blocks := wireBlocks(d.obj.Properties)
	for i, b := range blocks {
		id := blockID(b)
		d.seq = append(d.seq, id)
		d.block[id] = b
		st := maxH("", stampOf(base.BlockHLC, id, base.HLC))
		d.bhlc[id] = st
		after := ""
		if i > 0 {
			after = blockID(blocks[i-1])
		}
		d.anchor[id] = &model.BlockAnchor{After: after, HLC: st}
	}
	for k, v := range base.FieldHLC {
		d.field[k] = v
	}
	steps := 1 + r.Intn(4)
	for i := 0; i < steps; i++ {
		switch r.Intn(5) {
		case 0:
			id := fmt.Sprintf("%s%d", node, r.Intn(100000))
			d.field["order"] = maxH(d.field["order"], d.now())
			d.insert(id)
		case 1:
			if len(d.seq) > 0 {
				d.op(d.seq[r.Intn(len(d.seq))])
			}
		case 2:
			if len(d.seq) > 1 {
				d.remove(d.seq[r.Intn(len(d.seq))])
			}
		case 3:
			d.obj.Properties["color"] = fmt.Sprintf("v %s %d", node, i)
			d.field["p:color"] = maxH(d.field["p:color"], d.now())
		case 4:
			if len(d.seq) > 1 {
				d.field["order"] = maxH(d.field["order"], d.now())
				d.move(d.seq[r.Intn(len(d.seq))])
			}
		}
	}
	return d.toObject()
}

func TestMergeAlgebra_Fuzz(t *testing.T) {
	if testing.Short() {
		t.Skip("short mode")
	}
	nodes := []string{"aaaaaaaa", "bbbbbbbb", "cccccccc"}
	for seed := int64(1); seed <= 400; seed++ {
		r := rand.New(rand.NewSource(seed))
		blockN := 2 + r.Intn(3)
		blocks := []map[string]any{}
		for i := 0; i < blockN; i++ {
			blocks = append(blocks, map[string]any{"id": fmt.Sprintf("b%d", i), "type": "paragraph", "text": fmt.Sprintf("base%d", i)})
		}
		wall := int64(500)
		stamp := func() string { wall += 10; return model.FormatHLC(wall, 0, "dddddddd") }
		base := model.Object{
			ID: "obj1", TypeID: "note", Title: "Base title",
			Properties: map[string]any{
				model.PropBlocks: blocksToAny(blocks),
				"color":          "rojo",
			},
			CreatedAt: "2026-11-01T00:00:00Z",
			HLC:       stamp(),
		}
		base.FieldHLC = map[string]string{"title": stamp(), "type_id": stamp(), "p:color": stamp(), "order": stamp()}
		base.BlockHLC = map[string]string{}
		for _, b := range blocks {
			id, _ := b["id"].(string)
			base.BlockHLC[id] = stamp()
		}

		a := evolveFuzzState(base, nodes[0], rand.New(rand.NewSource(seed*7+1)))
		b := evolveFuzzState(base, nodes[1], rand.New(rand.NewSource(seed*7+2)))
		c := evolveFuzzState(base, nodes[2], rand.New(rand.NewSource(seed*7+3)))

		for name, x := range map[string]model.Object{"a": a, "b": b, "c": c} {
			if got := MergeObjects(x, x); fuzzObjCanon(got) != fuzzObjCanon(x) {
				t.Fatalf("seed %d: idempotency merge(%s,%s) != %s\ngot  %s\n\nwant %s", seed, name, name, name,
					fuzzObjCanon(MergeObjects(x, x)), fuzzObjCanon(x))
			}
		}
		pairs := [][2]model.Object{{a, b}, {a, c}, {b, c}}
		for _, p := range pairs {
			if fuzzObjCanon(MergeObjects(p[0], p[1])) != fuzzObjCanon(MergeObjects(p[1], p[0])) {
				t.Fatalf("seed %d: not commutative", seed)
			}
		}
		perms := [][3]model.Object{
			{a, b, c}, {a, c, b}, {b, a, c}, {b, c, a}, {c, a, b}, {c, b, a},
		}
		for _, pm := range perms {
			x, y, z := pm[0], pm[1], pm[2]
			g1 := MergeObjects(MergeObjects(x, y), z)
			g2 := MergeObjects(x, MergeObjects(y, z))
			if fuzzObjCanon(g1) != fuzzObjCanon(g2) {
				t.Fatalf("seed %d: not associative\nleft %s\n\nright %s", seed, fuzzObjCanon(g1), fuzzObjCanon(g2))
			}
			// every grouping must converge to the same final state
			g3 := MergeObjects(z, MergeObjects(y, x))
			if fuzzObjCanon(g3) != fuzzObjCanon(g1) {
				t.Fatalf("seed %d: not convergent (swap of yet another grouping)\n1 %s\n\n2 %s", seed, fuzzObjCanon(g1), fuzzObjCanon(g3))
			}
		}
	}
}
