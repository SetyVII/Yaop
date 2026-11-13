package simclient

import "yaop-server/internal/simclient/model"

// StampObject implements the client-side stamp of spec/merge.md. The server only uses it
// for tests and for objects it creates itself; clients run their own port of it on save.
func StampObject(prev *model.Object, next model.Object, now string) model.Object {
	out := next
	field := copyMap(nil)
	blockH := copyMap(nil)
	deleted := copyMap(nil)
	if prev != nil {
		field = copyMap(prev.FieldHLC)
		blockH = copyMap(prev.BlockHLC)
		deleted = copyMap(prev.DeletedBlocks)
	}
	changed := false
	mark := func(m map[string]string, k string) {
		m[k] = now
		changed = true
	}

	var prevTitle, prevType, prevTitleRuns any
	var prevProps map[string]any
	var prevBlocks []map[string]any
	if prev != nil {
		prevTitle, prevType, prevTitleRuns = prev.Title, prev.TypeID, prev.TitleRuns
		prevProps = prev.Properties
		prevBlocks = wireBlocks(prev.Properties)
	}
	if prev == nil || canon([]any{prevTitle, prevTitleRuns}) != canon([]any{next.Title, next.TitleRuns}) {
		mark(field, "title")
	}
	if prev == nil || canon(prevType) != canon(next.TypeID) {
		mark(field, "type_id")
	}
	var prevTrashed string
	if prev != nil {
		prevTrashed = prev.TrashedAt
	}
	if prevTrashed != next.TrashedAt {
		mark(field, fieldTrashed)
	}

	// Block anchors (yaop-sync/1, spec/merge.md): every block whose
	// predecessor changed between prev and next (or that is new) is re-anchored
	// to the block it now follows; the anchoring event gets the save's stamp.
	// Tombstones keep their entry: a dead anchor still pins its followers.
	anchor := map[string]*model.BlockAnchor{}
	if prev != nil {
		for id, a := range prev.BlockAnchor {
			if a != nil {
				cp := *a
				anchor[id] = &cp
			}
		}
	}
	nextBlocks := wireBlocks(next.Properties)
	prevPred := map[string]string{}
	for i, b := range prevBlocks {
		if i == 0 {
			prevPred[blockID(b)] = ""
		} else {
			prevPred[blockID(b)] = blockID(prevBlocks[i-1])
		}
	}
	for i, b := range nextBlocks {
		id := blockID(b)
		pred := ""
		if i > 0 {
			pred = blockID(nextBlocks[i-1])
		}
		if _, changed := prevPred[id]; !changed || prevPred[id] != pred {
			anchor[id] = &model.BlockAnchor{After: pred, HLC: now}
		}
	}

	keys := map[string]bool{}
	for k := range prevProps {
		keys[k] = true
	}
	for k := range next.Properties {
		keys[k] = true
	}
	delete(keys, model.PropBlocks)
	for k := range keys {
		pv, inP := prevProps[k]
		nv, inN := next.Properties[k]
		if inP != inN || canon(pv) != canon(nv) {
			mark(field, propPrefix+k)
		}
	}

	prevByID := map[string]map[string]any{}
	for _, b := range prevBlocks {
		prevByID[blockID(b)] = b
	}
	nextIDs := map[string]bool{}
	for _, b := range nextBlocks {
		id := blockID(b)
		nextIDs[id] = true
		if old, ok := prevByID[id]; !ok || canon(old) != canon(b) {
			mark(blockH, id)
			delete(deleted, id)
		}
	}
	for _, b := range prevBlocks {
		id := blockID(b)
		if !nextIDs[id] {
			mark(deleted, id)
			delete(blockH, id)
		}
	}
	if canon(blockIDs(prevBlocks)) != canon(blockIDs(nextBlocks)) && (prev != nil || len(nextBlocks) > 0) {
		mark(field, "order")
	}

	out.FieldHLC = nilIfEmpty(field)
	out.BlockHLC = nilIfEmpty(blockH)
	out.DeletedBlocks = nilIfEmpty(deleted)
	if len(anchor) == 0 {
		anchor = nil
	}
	out.BlockAnchor = anchor
	if changed {
		out.HLC = now
	} else if prev != nil {
		out.HLC = prev.HLC
	}
	return out
}

func copyMap(m map[string]string) map[string]string {
	out := make(map[string]string, len(m))
	for k, v := range m {
		out[k] = v
	}
	return out
}

func nilIfEmpty(m map[string]string) map[string]string {
	if len(m) == 0 {
		return nil
	}
	return m
}
