package simclient

import (
	"encoding/json"
	"sort"
	"strings"
	"unicode"

	"yaop-server/internal/simclient/model"
)

// Implements spec/merge.md (yaop-sync/1). Pure, deterministic and symmetric:
// MergeObjects(a, b) == MergeObjects(b, a) for every field covered by the contract.

const propPrefix = "p:"

// fieldTrashed is the field_hlc key that stamps trashed_at (spec/merge.md).
const fieldTrashed = "trashed_at"

// canon returns the canonical JSON of v (sorted keys, numbers normalized through float64).
func canon(v any) string {
	raw, err := json.Marshal(v)
	if err != nil {
		return ""
	}
	var generic any
	if err := json.Unmarshal(raw, &generic); err != nil {
		return string(raw)
	}
	out, _ := json.Marshal(generic)
	return string(out)
}

// wireBlocks returns the top-level blocks of an object as generic maps, in order.
func wireBlocks(props map[string]any) []map[string]any {
	raw, ok := props[model.PropBlocks]
	if !ok || raw == nil {
		return nil
	}
	bytes, err := json.Marshal(raw)
	if err != nil {
		return nil
	}
	var list []map[string]any
	if err := json.Unmarshal(bytes, &list); err != nil {
		return nil
	}
	return flattenGroups(list)
}

// flattenGroups turns legacy nested groups (doc/1) into flat blocks with group_id (doc/3), so the
// merge always works block by block, whatever format each side sent.
func flattenGroups(list []map[string]any) []map[string]any {
	nested := false
	for _, b := range list {
		if b["type"] == "group" {
			nested = true
			break
		}
	}
	if !nested {
		return list
	}
	out := make([]map[string]any, 0, len(list))
	for _, b := range list {
		if b["type"] != "group" {
			out = append(out, b)
			continue
		}
		gid, _ := b["id"].(string)
		kids, _ := b["children"].([]any)
		if len(kids) == 0 {
			p := map[string]any{}
			for k, v := range b {
				if k != "children" {
					p[k] = v
				}
			}
			p["type"], p["group_id"] = "paragraph", gid
			out = append(out, p)
			continue
		}
		for _, k := range kids {
			if child, ok := k.(map[string]any); ok {
				c := map[string]any{}
				for kk, vv := range child {
					c[kk] = vv
				}
				c["group_id"] = gid
				out = append(out, c)
			}
		}
	}
	return out
}

func blockID(b map[string]any) string {
	id, _ := b["id"].(string)
	return id
}

func blockIDs(blocks []map[string]any) []string {
	ids := make([]string, 0, len(blocks))
	for _, b := range blocks {
		ids = append(ids, blockID(b))
	}
	return ids
}

func stampOf(m map[string]string, key, fallback string) string {
	if m != nil {
		if v, ok := m[key]; ok && v != "" {
			return v
		}
	}
	return fallback
}

// pick returns true when side a wins over side b for a value guarded by stamps ha/hb.
func pick(ha, hb string, va, vb any) bool {
	if c := model.CompareHLC(ha, hb); c != 0 {
		return c > 0
	}
	return canon(va) >= canon(vb)
}

// MergeObjects merges two wire versions of the same object.
func MergeObjects(a, b model.Object) model.Object {
	out := a

	// 1. title / type_id
	ht := [2]string{stampOf(a.FieldHLC, "title", a.HLC), stampOf(b.FieldHLC, "title", b.HLC)}
	if !pick(ht[0], ht[1], []any{a.Title, a.TitleRuns}, []any{b.Title, b.TitleRuns}) {
		out.Title, out.TitleRuns = b.Title, b.TitleRuns
	}
	hy := [2]string{stampOf(a.FieldHLC, "type_id", a.HLC), stampOf(b.FieldHLC, "type_id", b.HLC)}
	if !pick(hy[0], hy[1], a.TypeID, b.TypeID) {
		out.TypeID = b.TypeID
	}

	// 2. properties (everything except blocks)
	props := make(map[string]any)
	keys := map[string]bool{}
	for k := range a.Properties {
		keys[k] = true
	}
	for k := range b.Properties {
		keys[k] = true
	}
	for _, m := range []map[string]string{a.FieldHLC, b.FieldHLC} {
		for k := range m {
			if len(k) > len(propPrefix) && k[:len(propPrefix)] == propPrefix {
				keys[k[len(propPrefix):]] = true
			}
		}
	}
	delete(keys, model.PropBlocks)
	for k := range keys {
		va, inA := a.Properties[k]
		vb, inB := b.Properties[k]
		sa, aStamped := a.FieldHLC[propPrefix+k]
		sb, bStamped := b.FieldHLC[propPrefix+k]
		aKnows := inA || aStamped
		bKnows := inB || bStamped
		if !aStamped {
			sa = a.HLC
		}
		if !bStamped {
			sb = b.HLC
		}
		var winnerHas bool
		var winnerVal any
		switch {
		case aKnows && !bKnows:
			winnerHas, winnerVal = inA, va
		case bKnows && !aKnows:
			winnerHas, winnerVal = inB, vb
		default:
			if pick(sa, sb, presence(inA, va), presence(inB, vb)) {
				winnerHas, winnerVal = inA, va
			} else {
				winnerHas, winnerVal = inB, vb
			}
		}
		if winnerHas {
			props[k] = winnerVal
		}
	}

	// 2b. trashed_at (the object's tombstone): LWW by field_hlc.trashed_at, with the
	// same participation rule as a property, so a restore after a trash wins and an
	// edit on a device that never saw the trash does not resurrect the object.
	{
		sa, aStamped := a.FieldHLC[fieldTrashed]
		sb, bStamped := b.FieldHLC[fieldTrashed]
		inA, inB := a.TrashedAt != "", b.TrashedAt != ""
		aKnows, bKnows := inA || aStamped, inB || bStamped
		if !aStamped {
			sa = a.HLC
		}
		if !bStamped {
			sb = b.HLC
		}
		switch {
		case aKnows && !bKnows:
			out.TrashedAt = a.TrashedAt
		case bKnows && !aKnows:
			out.TrashedAt = b.TrashedAt
		case aKnows && bKnows:
			if pick(sa, sb, presence(inA, a.TrashedAt), presence(inB, b.TrashedAt)) {
				out.TrashedAt = a.TrashedAt
			} else {
				out.TrashedAt = b.TrashedAt
			}
		default:
			out.TrashedAt = ""
		}
	}

	// 3. blocks
	blocksA, blocksB := wireBlocks(a.Properties), wireBlocks(b.Properties)
	byA := map[string]map[string]any{}
	byB := map[string]map[string]any{}
	for _, blk := range blocksA {
		byA[blockID(blk)] = blk
	}
	for _, blk := range blocksB {
		byB[blockID(blk)] = blk
	}
	tomb := map[string]string{}
	for _, m := range []map[string]string{a.DeletedBlocks, b.DeletedBlocks} {
		for id, h := range m {
			tomb[id] = model.MaxHLC(tomb[id], h)
		}
	}
	kept := map[string]map[string]any{}
	keptHLC := map[string]string{}
	for _, id := range unionIDs(blockIDs(blocksA), blockIDs(blocksB)) {
		ba, inA := byA[id]
		bb, inB := byB[id]
		var cand map[string]any
		var ch string
		switch {
		case inA && !inB:
			cand, ch = ba, stampOf(a.BlockHLC, id, a.HLC)
		case inB && !inA:
			cand, ch = bb, stampOf(b.BlockHLC, id, b.HLC)
		default:
			ha, hb := stampOf(a.BlockHLC, id, a.HLC), stampOf(b.BlockHLC, id, b.HLC)
			if pick(ha, hb, ba, bb) {
				cand, ch = ba, ha
			} else {
				cand, ch = bb, hb
			}
		}
		if t, ok := tomb[id]; ok && model.CompareHLC(t, ch) >= 0 {
			continue
		}
		kept[id] = cand
		keptHLC[id] = ch
	}

	// 4. order
	oa, ob := stampOf(a.FieldHLC, "order", a.HLC), stampOf(b.FieldHLC, "order", b.HLC)
	primary, secondary := blockIDs(blocksA), blockIDs(blocksB)
	primaryIsA := pick(oa, ob, primary, secondary)
	if !primaryIsA {
		primary, secondary = secondary, primary
	}
	// Anchor-complete states (yaop-sync/1): every kept block carries a
	// block_anchor entry; the merged order is rendered from the closed union of
	// the anchor maps, which depends only on the set of states being merged,
	// never on the grouping — that makes merge associative. Anything older
	// (no anchors yet) takes the legacy whole-sequence rule below.
	anchors := mergeBlockAnchors(a.BlockAnchor, b.BlockAnchor)
	var order []string
	if anchorComplete(anchors, kept) {
		order = renderAnchorOrder(anchors, kept)
	} else {
		order = orderLegacy(primary, secondary, kept, keptHLC)
	}
	merged := make([]any, 0, len(order))
	for _, id := range order {
		merged = append(merged, kept[id])
	}
	if len(blocksA) > 0 || len(blocksB) > 0 || hasKey(a.Properties, model.PropBlocks) || hasKey(b.Properties, model.PropBlocks) {
		props[model.PropBlocks] = merged
	}
	out.Properties = props

	// 5. meta
	out.FieldHLC = maxMerge(a.FieldHLC, b.FieldHLC)
	out.BlockHLC = keptHLC
	out.DeletedBlocks = map[string]string{}
	for id, h := range tomb {
		if _, alive := kept[id]; !alive {
			out.DeletedBlocks[id] = h
		}
	}
	if len(out.BlockHLC) == 0 {
		out.BlockHLC = nil
	}
	if len(out.DeletedBlocks) == 0 {
		out.DeletedBlocks = nil
	}
	out.BlockAnchor = anchors
	if len(out.BlockAnchor) == 0 {
		out.BlockAnchor = nil
	}

	// 6. rest
	out.HLC = model.MaxHLC(a.HLC, b.HLC)
	out.CreatedAt = minNonEmpty(a.CreatedAt, b.CreatedAt)
	if primaryIsA {
		out.Content, out.UpdatedAt = a.Content, a.UpdatedAt
	} else {
		out.Content, out.UpdatedAt = b.Content, b.UpdatedAt
	}
	// schema_version merges by MAX (spec/object.md): associative, commutative
	// and idempotent like the rest of the algebra; legacy (0) loses to any
	// known schema. A side that knows only older schemas receives the max and
	// must refuse to open it (its codecs check before decoding).
	if b.SchemaVersion > out.SchemaVersion {
		out.SchemaVersion = b.SchemaVersion
	}
	// References are derived from the merged content, never unioned (a removed mention must
	// stop being a backlink). property_links keep only ids still present in merged properties.
	out.Links = DeriveLinks(out)
	out.PropertyLinks = filterPresent(unionSorted(a.PropertyLinks, b.PropertyLinks), out.Properties)
	return out
}

// DeriveLinks implements the `links` rule of spec/object.md over the wire object.
func DeriveLinks(o model.Object) []string {
	set := map[string]bool{}
	add := func(id string) {
		if id != "" && id != o.ID {
			set[id] = true
		}
	}
	var walkRuns func(runs any)
	walkRuns = func(runs any) {
		list, _ := runs.([]any)
		for _, r := range list {
			run, _ := r.(map[string]any)
			if run == nil {
				continue
			}
			if id, _ := run["object_id"].(string); id != "" {
				add(id)
				continue
			}
			tagID, _ := run["tag_id"].(string)
			tagName, _ := run["tag_name"].(string)
			if tagID != "" {
				add(tagID)
				continue
			}
			if tagName != "" {
				add(TagKey(tagName))
				continue
			}
			if d, _ := run["date"].(string); len(d) >= 10 {
				add("daily-" + d[:10])
			}
		}
	}
	var walkBlocks func(blocks []map[string]any)
	walkBlocks = func(blocks []map[string]any) {
		for _, b := range blocks {
			walkRuns(b["runs"])
			switch b["type"] {
			case "embed", "bookmark":
				if id, _ := b["object_id"].(string); id != "" {
					add(id)
				}
			case "image", "audio", "pdf":
				if id, _ := b["asset_id"].(string); id != "" {
					add("media_" + id)
				}
			}
			if kids, ok := b["children"]; ok {
				raw, _ := json.Marshal(kids)
				var list []map[string]any
				_ = json.Unmarshal(raw, &list)
				walkBlocks(list)
			}
		}
	}
	walkRuns(normalizeAny(o.TitleRuns))
	walkBlocks(wireBlocks(o.Properties))
	if len(set) == 0 {
		return nil
	}
	ids := make([]string, 0, len(set))
	for id := range set {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	return ids
}

func normalizeAny(v any) any {
	raw, err := json.Marshal(v)
	if err != nil {
		return nil
	}
	var out any
	_ = json.Unmarshal(raw, &out)
	return out
}

// filterPresent keeps the ids that still appear as a string value (or inside an array) of props.
// FilterPresent keeps property link ids whose values are still present in
// properties (spec/object.md); exported for reference-client users (tests).
func FilterPresent(ids []string, props map[string]any) []string {
	return filterPresent(ids, props)
}

func filterPresent(ids []string, props map[string]any) []string {
	if len(ids) == 0 {
		return nil
	}
	present := map[string]bool{}
	mark := func(s string) {
		present[s] = true
		present[TagKey(s)] = true // tag names reference their tag object
		if len(s) >= 10 && s[4] == '-' && s[7] == '-' {
			present["daily-"+s[:10]] = true // date properties reference their journal day
		}
	}
	for k, v := range props {
		if k == model.PropBlocks {
			continue
		}
		switch t := v.(type) {
		case string:
			mark(t)
		case []any:
			for _, x := range t {
				if s, ok := x.(string); ok {
					mark(s)
				}
			}
		case []string:
			for _, s := range t {
				mark(s)
			}
		}
	}
	var out []string
	for _, id := range ids {
		if present[id] || present[strings.TrimPrefix(id, "tag_")] {
			out = append(out, id)
		}
	}
	sort.Strings(out)
	return out
}

// presence makes "absent" compare lower than any present value in tie-breaks.
func presence(has bool, v any) any {
	if !has {
		return nil
	}
	return []any{v}
}

func hasKey(m map[string]any, k string) bool {
	_, ok := m[k]
	return ok
}

func unionIDs(a, b []string) []string {
	seen := map[string]bool{}
	var out []string
	for _, list := range [][]string{a, b} {
		for _, id := range list {
			if id != "" && !seen[id] {
				seen[id] = true
				out = append(out, id)
			}
		}
	}
	return out
}

func unionSorted(a, b []string) []string {
	ids := unionIDs(a, b)
	sort.Strings(ids)
	if len(ids) == 0 {
		return nil
	}
	return ids
}

// ---------------------------------------------------------------------------
// Block anchors (yaop-sync/1, spec/merge.md): associative ordering.
// ---------------------------------------------------------------------------

// mergeBlockAnchors unions two anchor maps. For each block id the anchoring
// event with the greatest HLC wins (tie -> canonically greater "after"). The
// inputs form a closed, per-key set, so successive merges reduce to the
// per-key max: the result never depends on merge grouping.
func mergeBlockAnchors(a, b map[string]*model.BlockAnchor) map[string]*model.BlockAnchor {
	if len(a) == 0 && len(b) == 0 {
		return nil
	}
	out := make(map[string]*model.BlockAnchor, len(a)+len(b))
	for id, x := range a {
		if x != nil {
			out[id] = x
		}
	}
	for id, y := range b {
		if y == nil {
			continue
		}
		if x := out[id]; x != nil {
			if anchorLess(x, y) {
				out[id] = y
			}
		} else {
			out[id] = y
		}
	}
	return out
}

// anchorLess reports whether x's anchoring event loses to y's: greater HLC
// wins, ties fall back to the canonically greater "after" value.
func anchorLess(x, y *model.BlockAnchor) bool {
	if c := model.CompareHLC(x.HLC, y.HLC); c != 0 {
		return c < 0
	}
	return canon(x.After) < canon(y.After)
}

// anchorComplete reports whether every kept block knows its anchor. One side
// that never stamped anchors (yaop-sync/1 data) sends the merge down the
// legacy order path instead.
func anchorComplete(anchors map[string]*model.BlockAnchor, kept map[string]map[string]any) bool {
	for id := range kept {
		if _, ok := anchors[id]; !ok {
			return false
		}
	}
	return true
}

// renderAnchorOrder renders the merged block order as an RGA forest:
//   - the parent of block B is the block it was last anchored after ("B became
//     its author's layout"); unknown parents root the block;
//   - tombstones keep their node (dead blocks hold their slot for children);
//   - siblings of one parent are ordered newest anchoring first (RGA: the
//     newer insert stays closer to the anchor), tie by canonically greater id;
//   - roots (anchor "") are ordered the same way;
//   - anchor cycles (concurrent moves pointing at each other) are broken by
//     re-rooting the smallest-stamped member of the cycle.
//
// The render is a pure function of the merged (closed) anchor map plus the
// alive set, so its output does not depend on how states were grouped:
// merge(merge(a,b),c) == merge(a,merge(b,c)) by construction.
func renderAnchorOrder(anchors map[string]*model.BlockAnchor, alive map[string]map[string]any) []string {
	if len(alive) == 0 {
		return nil
	}
	// Nodes: every anchored block (alive or tombstone) plus alive blocks.
	nodeSet := make(map[string]bool, len(anchors)+len(alive))
	for id := range anchors {
		nodeSet[id] = true
	}
	for id := range alive {
		nodeSet[id] = true
	}
	parent := make(map[string]string, len(nodeSet))
	children := make(map[string][]string, len(nodeSet))
	roots := make([]string, 0)
	for id := range nodeSet {
		a := anchors[id]
		p := ""
		if a != nil && a.After != "" && nodeSet[a.After] {
			p = a.After
		}
		parent[id] = p
		if p == "" {
			roots = append(roots, id)
		} else {
			children[p] = append(children[p], id)
		}
	}
	// Break cycles deterministically: re-root the cycle member with the
	// smallest anchor stamp (tie: canonically smallest id).
	breakCycles(parent, children, &roots, anchors)
	byAnc := func(ids []string) []string {
		sort.Slice(ids, func(i, j int) bool {
			hi, hj := "", ""
			if anchors[ids[i]] != nil {
				hi = anchors[ids[i]].HLC
			}
			if anchors[ids[j]] != nil {
				hj = anchors[ids[j]].HLC
			}
			if c := model.CompareHLC(hi, hj); c != 0 {
				return c > 0 // newest first
			}
			return canon(ids[i]) > canon(ids[j])
		})
		return ids
	}
	out := make([]string, 0, len(alive))
	var walk func(id string)
	walk = func(id string) {
		if _, ok := alive[id]; ok {
			out = append(out, id)
		}
		for _, child := range byAnc(children[id]) {
			walk(child)
		}
	}
	for _, id := range byAnc(roots) {
		walk(id)
	}
	return out
}

// breakCycles walks the parent pointers and re-roots, for every cycle, its
// smallest-stamped member (tie: canonically smallest id). Cycles in a
// parent-pointer graph are disjoint, so each cycle is resolved on its own.
// The scan restarts in sorted key order, keeping the whole step deterministic.
func breakCycles(parent map[string]string, children map[string][]string, roots *[]string, anchors map[string]*model.BlockAnchor) {
	for {
		keys := make([]string, 0, len(parent))
		for id := range parent {
			keys = append(keys, id)
		}
		sort.Strings(keys)
		broke := false
		for _, start := range keys {
			if parent[start] == "" {
				continue
			}
			path := []string{}
			idx := map[string]int{}
			cur := start
			cycle := []string(nil)
			for parent[cur] != "" {
				if at, seen := idx[cur]; seen {
					cycle = append([]string{}, path[at:]...)
					break
				}
				idx[cur] = len(path)
				path = append(path, cur)
				cur = parent[cur]
			}
			if len(cycle) == 0 {
				continue
			}
			worst := cycle[0]
			for _, id := range cycle[1:] {
				if anchorEventLess(id, worst, anchors) {
					worst = id
				}
			}
			children[parent[worst]] = removeID(children[parent[worst]], worst)
			parent[worst] = ""
			*roots = append(*roots, worst)
			broke = true
			break
		}
		if !broke {
			return
		}
	}
}

// anchorEventLess compares two nodes by their anchoring event (HLC, then
// canonically smaller id first). Smaller = re-rooted when a cycle forms.
func anchorEventLess(a, b string, anchors map[string]*model.BlockAnchor) bool {
	ha, hb := "", ""
	if anchors[a] != nil {
		ha = anchors[a].HLC
	}
	if anchors[b] != nil {
		hb = anchors[b].HLC
	}
	if c := model.CompareHLC(ha, hb); c != 0 {
		return c < 0
	}
	return canon(a) < canon(b)
}

func removeID(list []string, id string) []string {
	out := make([]string, 0, len(list))
	for _, v := range list {
		if v != id {
			out = append(out, v)
		}
	}
	return out
}

// orderLegacy is the yaop-sync/1 whole-sequence rule: the reordered side
// with the greater "order" stamp provides the base sequence; blocks only the
// other side knows are inserted after their closest predecessor there and, at
// the same anchor, newer inserts stay closer to it (RGA approximation).
// NOT associative across three states in general; kept only for /2 data.
func orderLegacy(primary, secondary []string, kept map[string]map[string]any, keptHLC map[string]string) []string {
	order := make([]string, 0, len(kept))
	inOrder := map[string]bool{}
	for _, id := range primary {
		if _, ok := kept[id]; ok && !inOrder[id] {
			order = append(order, id)
			inOrder[id] = true
		}
	}
	inSecondary := map[string]bool{}
	for _, id := range secondary {
		inSecondary[id] = true
	}
	for i, id := range secondary {
		if _, ok := kept[id]; !ok || inOrder[id] {
			continue
		}
		pos := 0
		for j := i - 1; j >= 0; j-- {
			if idx := indexOf(order, secondary[j]); idx >= 0 {
				pos = idx + 1
				break
			}
		}
		// Concurrent inserts at the same anchor (RGA): the newer one stays closer to the anchor.
		for pos < len(order) && !inSecondary[order[pos]] && model.CompareHLC(keptHLC[order[pos]], keptHLC[id]) > 0 {
			pos++
		}
		order = append(order, "")
		copy(order[pos+1:], order[pos:])
		order[pos] = id
		inOrder[id] = true
	}
	return order
}

func indexOf(list []string, id string) int {
	for i, v := range list {
		if v == id {
			return i
		}
	}
	return -1
}

func maxMerge(a, b map[string]string) map[string]string {
	if len(a) == 0 && len(b) == 0 {
		return nil
	}
	out := make(map[string]string, len(a)+len(b))
	for k, v := range a {
		out[k] = v
	}
	for k, v := range b {
		out[k] = model.MaxHLC(out[k], v)
	}
	return out
}

func minNonEmpty(a, b string) string {
	switch {
	case a == "":
		return b
	case b == "":
		return a
	case a < b:
		return a
	}
	return b
}

// TagKey is the canonical tag object id for a tag name (spec/object.md).
func TagKey(name string) string {
	n := strings.ToLower(strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(name), "#")))
	n = strings.ReplaceAll(n, " ", "_")
	var b strings.Builder
	for _, r := range n {
		if unicode.IsLetter(r) || unicode.IsDigit(r) || r == '_' {
			b.WriteRune(r)
		}
	}
	return "tag_" + b.String()
}
