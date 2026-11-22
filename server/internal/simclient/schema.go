package simclient

import "sort"

// Reference implementation of spec/schema.md "Changing an object's type".

var fixedTypes = map[string]bool{"image": true, "audio": true, "pdf": true, "video": true,
	"file": true, "journal": true, "tag": true}

var specialPropertyKeys = map[string]bool{"blocks": true, "icon": true, "cover": true, "aliases": true,
	"summary": true, "pinned": true, "is_template": true, "tags": true}

// TypeChange is the result of ChangeType: the new type_id, the loose keys (sorted) or an error.
type TypeChange struct {
	TypeID string
	Loose  []string
	Error  string
}

// ChangeType never touches properties or blocks; it only reports which keys become loose.
func ChangeType(typeID string, properties map[string]any, to string, toKeys []string) TypeChange {
	if fixedTypes[typeID] || fixedTypes[to] {
		return TypeChange{TypeID: typeID, Error: "fixed_type"}
	}
	r := TypeChange{TypeID: to, Loose: []string{}}
	if to == typeID {
		return r
	}
	keys := map[string]bool{}
	for _, k := range toKeys {
		keys[k] = true
	}
	for k := range properties {
		if !specialPropertyKeys[k] && !keys[k] {
			r.Loose = append(r.Loose, k)
		}
	}
	sort.Strings(r.Loose) // byte order == code point order for UTF-8
	return r
}
