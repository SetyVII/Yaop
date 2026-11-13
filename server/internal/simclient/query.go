package simclient

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode"
)

// Reference evaluator of spec/query.md (yaop-query/1). Clients port it; the fixtures in
// spec/fixtures/query/ are the contract. It works on wire JSON objects (generic maps) and
// expects type ids already normalized by the caller.

// QueryError is a contract error code (unsupported_version, unknown_op, …).
type QueryError struct{ Code string }

func (e *QueryError) Error() string { return "query: " + e.Code }

// QueryGroup is one group of a grouped result; Key nil is the "null" group.
type QueryGroup struct {
	Key *string  `json:"key"`
	IDs []string `json:"ids"`
}

// QueryResult holds IDs (flat) or Groups (when the query has group).
type QueryResult struct {
	IDs    []string     `json:"ids,omitempty"`
	Groups []QueryGroup `json:"groups,omitempty"`
}

// EvaluateQuery runs query over objects with today = "YYYY-MM-DD" (the device's local day).
func EvaluateQuery(query map[string]any, objects []map[string]any, today string) (QueryResult, error) {
	if v, _ := query["version"].(float64); v != 1 {
		return QueryResult{}, &QueryError{"unsupported_version"}
	}
	match := strOr(query["match"], "all")
	filters, _ := query["filters"].([]any)
	if err := validateGroup(match, filters); err != nil {
		return QueryResult{}, err
	}
	sorts, _ := query["sort"].([]any)
	for _, s := range sorts {
		m, _ := s.(map[string]any)
		if d := strOr(m["dir"], "asc"); d != "asc" && d != "desc" {
			return QueryResult{}, &QueryError{"unknown_dir"}
		}
	}
	order := strOr(query["order"], "sorted")
	if order != "sorted" && order != "random" {
		return QueryResult{}, &QueryError{"unknown_order"}
	}

	types := map[string]bool{}
	if ts, ok := query["types"].([]any); ok {
		for _, t := range ts {
			if s, ok := t.(string); ok {
				types[s] = true
			}
		}
	}
	var hits []map[string]any
	for _, o := range objects {
		if s, _ := o["trashed_at"].(string); s != "" {
			continue
		}
		props, _ := o["properties"].(map[string]any)
		if b, _ := props["is_template"].(bool); b {
			continue
		}
		if len(types) > 0 && !types[strOr(o["type_id"], "")] {
			continue
		}
		if evalGroup(match, filters, o, today) {
			hits = append(hits, o)
		}
	}

	if order == "random" {
		seed := strOr(query["random_seed"], "")
		key := func(o map[string]any) string {
			h := sha256.Sum256([]byte(seed + ":" + strOr(o["id"], "")))
			return hex.EncodeToString(h[:])
		}
		sort.SliceStable(hits, func(i, j int) bool { return key(hits[i]) < key(hits[j]) })
	} else {
		if len(sorts) == 0 {
			sorts = []any{map[string]any{"prop": "@updated_at", "dir": "desc"}}
		}
		sort.SliceStable(hits, func(i, j int) bool {
			for _, s := range sorts {
				m, _ := s.(map[string]any)
				prop := strOr(m["prop"], "")
				c := compareForSort(fieldValue(hits[i], prop), fieldValue(hits[j], prop), strOr(m["dir"], "asc") == "desc")
				if c != 0 {
					return c < 0
				}
			}
			return comparePoints(strOr(hits[i]["id"], ""), strOr(hits[j]["id"], "")) < 0
		})
	}
	if lim, ok := query["limit"].(float64); ok && lim > 0 && int(lim) < len(hits) {
		hits = hits[:int(lim)]
	}

	g, hasGroup := query["group"].(map[string]any)
	if !hasGroup {
		ids := make([]string, 0, len(hits))
		for _, o := range hits {
			ids = append(ids, strOr(o["id"], ""))
		}
		return QueryResult{IDs: ids}, nil
	}
	return QueryResult{Groups: groupHits(hits, g)}, nil
}

func strOr(v any, def string) string {
	if s, ok := v.(string); ok && s != "" {
		return s
	}
	return def
}

var knownOps = map[string]bool{
	"eq": true, "neq": true, "contains": true, "not_contains": true, "starts_with": true,
	"gt": true, "gte": true, "lt": true, "lte": true, "empty": true, "not_empty": true,
	"is_true": true, "is_false": true, "has_any": true, "has_all": true, "has_none": true,
	"before": true, "after": true, "on": true, "between": true,
}

func validateGroup(match string, filters []any) error {
	if match != "all" && match != "any" {
		return &QueryError{"unknown_match"}
	}
	for _, f := range filters {
		m, _ := f.(map[string]any)
		if sub, ok := m["filters"].([]any); ok {
			if err := validateGroup(strOr(m["match"], "all"), sub); err != nil {
				return err
			}
			continue
		}
		if !knownOps[strOr(m["op"], "")] {
			return &QueryError{"unknown_op"}
		}
	}
	return nil
}

func evalGroup(match string, filters []any, o map[string]any, today string) bool {
	if len(filters) == 0 {
		return true
	}
	for _, f := range filters {
		m, _ := f.(map[string]any)
		var ok bool
		if sub, isGroup := m["filters"].([]any); isGroup {
			ok = evalGroup(strOr(m["match"], "all"), sub, o, today)
		} else {
			ok = evalCond(m, o, today)
		}
		if match == "any" && ok {
			return true
		}
		if match == "all" && !ok {
			return false
		}
	}
	return match == "all"
}

// fieldValue returns the value of prop on object o (nil when absent).
func fieldValue(o map[string]any, prop string) any {
	props, _ := o["properties"].(map[string]any)
	switch prop {
	case "@id":
		return o["id"]
	case "@type":
		return o["type_id"]
	case "@title":
		return o["title"]
	case "@created_at":
		return o["created_at"]
	case "@updated_at":
		return o["updated_at"]
	case "@pinned":
		b, _ := props["pinned"].(bool)
		return b
	case "@refs":
		set := map[string]bool{}
		for _, k := range []string{"links", "property_links"} {
			if arr, ok := o[k].([]any); ok {
				for _, v := range arr {
					if s, ok := v.(string); ok {
						set[s] = true
					}
				}
			}
		}
		out := make([]any, 0, len(set))
		keys := make([]string, 0, len(set))
		for s := range set {
			keys = append(keys, s)
		}
		sort.Slice(keys, func(i, j int) bool { return comparePoints(keys[i], keys[j]) < 0 })
		for _, s := range keys {
			out = append(out, s)
		}
		return out
	case "blocks":
		return nil
	}
	return props[prop]
}

func isEmpty(v any) bool {
	switch x := v.(type) {
	case nil:
		return true
	case string:
		return x == ""
	case []any:
		return len(x) == 0
	case map[string]any:
		return len(x) == 0
	}
	return false
}

// lower applies the simple per-code-point lowercase mapping (spec/query.md §Valores).
func lower(s string) string {
	var b strings.Builder
	for _, r := range s {
		b.WriteRune(unicode.ToLower(r))
	}
	return b.String()
}

func asList(v any) []any {
	if isEmpty(v) {
		return nil
	}
	if arr, ok := v.([]any); ok {
		return arr
	}
	return []any{v}
}

func jsonEqual(a, b any) bool {
	if fa, ok := a.(float64); ok {
		fb, ok := b.(float64)
		return ok && fa == fb
	}
	return canon(a) == canon(b)
}

// dateInterval returns [s, e] (YYYY-MM-DD) of a date value, ok=false when it is not a date.
func dateInterval(v any) (string, string, bool) {
	day := func(x any) (string, bool) {
		s, ok := x.(string)
		if !ok || len(s) < 10 {
			return "", false
		}
		if _, err := time.Parse("2006-01-02", s[:10]); err != nil {
			return "", false
		}
		return s[:10], true
	}
	if m, ok := v.(map[string]any); ok {
		s, ok := day(m["start"])
		if !ok {
			return "", "", false
		}
		e, ok := day(m["end"])
		if !ok {
			e = s
		}
		return s, e, true
	}
	s, ok := day(v)
	return s, s, ok
}

// resolveDate turns "today", "today+N", "today-N" or a literal date into YYYY-MM-DD.
func resolveDate(v any, today string) (string, bool) {
	s, ok := v.(string)
	if !ok {
		return "", false
	}
	if strings.HasPrefix(s, "today") {
		t, err := time.Parse("2006-01-02", today)
		if err != nil {
			return "", false
		}
		if rest := s[len("today"):]; rest != "" {
			n, err := strconv.Atoi(rest)
			if err != nil {
				return "", false
			}
			t = t.AddDate(0, 0, n)
		}
		return t.Format("2006-01-02"), true
	}
	d, _, ok := dateInterval(s)
	return d, ok
}

func evalCond(c map[string]any, o map[string]any, today string) bool {
	v := fieldValue(o, strOr(c["prop"], ""))
	want := c["value"]
	switch op := strOr(c["op"], ""); op {
	case "eq":
		if arr, ok := v.([]any); ok {
			for _, x := range arr {
				if jsonEqual(x, want) {
					return true
				}
			}
			return false
		}
		return v != nil && jsonEqual(v, want)
	case "neq":
		return !evalCond(map[string]any{"prop": c["prop"], "op": "eq", "value": want}, o, today)
	case "contains", "not_contains", "starts_with":
		s, ok := v.(string)
		w, _ := want.(string)
		hit := ok && (op == "starts_with" && strings.HasPrefix(lower(s), lower(w)) ||
			op != "starts_with" && strings.Contains(lower(s), lower(w)))
		if op == "not_contains" {
			return !hit
		}
		return hit
	case "gt", "gte", "lt", "lte":
		x, ok1 := v.(float64)
		y, ok2 := want.(float64)
		if !ok1 || !ok2 {
			return false
		}
		switch op {
		case "gt":
			return x > y
		case "gte":
			return x >= y
		case "lt":
			return x < y
		}
		return x <= y
	case "empty":
		return isEmpty(v)
	case "not_empty":
		return !isEmpty(v)
	case "is_true":
		b, _ := v.(bool)
		return b
	case "is_false":
		b, _ := v.(bool)
		return !b
	case "has_any", "has_all", "has_none":
		have := map[string]bool{}
		for _, x := range asList(v) {
			if s, ok := x.(string); ok {
				have[s] = true
			}
		}
		wants, _ := want.([]any)
		count := 0
		for _, w := range wants {
			if s, ok := w.(string); ok && have[s] {
				count++
			}
		}
		switch op {
		case "has_any":
			return count > 0
		case "has_all":
			return len(wants) > 0 && count == len(wants)
		}
		return count == 0
	case "before", "after", "on":
		s, e, ok := dateInterval(v)
		d, ok2 := resolveDate(want, today)
		if !ok || !ok2 {
			return false
		}
		switch op {
		case "before":
			return e < d
		case "after":
			return s > d
		}
		return s <= d && d <= e
	case "between":
		s, e, ok := dateInterval(v)
		pair, _ := want.([]any)
		if !ok || len(pair) != 2 {
			return false
		}
		a, ok1 := resolveDate(pair[0], today)
		b, ok2 := resolveDate(pair[1], today)
		return ok1 && ok2 && s <= b && e >= a
	}
	return false
}

// comparePoints compares strings by Unicode code point (Go strings are UTF-8: byte order is
// code point order).
func comparePoints(a, b string) int { return strings.Compare(a, b) }

// sortRank: 0 boolean, 1 number, 2 string; -1 empty.
func sortKey(v any) (int, any) {
	if isEmpty(v) {
		return -1, nil
	}
	if arr, ok := v.([]any); ok {
		return sortKey(arr[0])
	}
	if m, ok := v.(map[string]any); ok {
		if s, ok := m["start"].(string); ok {
			return 2, lower(s)
		}
		return 2, lower(canon(m))
	}
	switch x := v.(type) {
	case bool:
		return 0, x
	case float64:
		return 1, x
	case string:
		return 2, lower(x)
	}
	return 2, lower(canon(v))
}

// compareForSort orders a and b; empties always last, desc reverses the non-empty order.
func compareForSort(a, b any, desc bool) int {
	ra, ka := sortKey(a)
	rb, kb := sortKey(b)
	if ra < 0 || rb < 0 {
		switch {
		case ra < 0 && rb < 0:
			return 0
		case ra < 0:
			return 1
		}
		return -1
	}
	c := compareKeys(ra, ka, rb, kb)
	if desc {
		return -c
	}
	return c
}

func compareKeys(ra int, ka any, rb int, kb any) int {
	if ra != rb {
		if ra < rb {
			return -1
		}
		return 1
	}
	switch x := ka.(type) {
	case bool:
		y := kb.(bool)
		if x == y {
			return 0
		}
		if !x {
			return -1
		}
		return 1
	case float64:
		y := kb.(float64)
		switch {
		case x < y:
			return -1
		case x > y:
			return 1
		}
		return 0
	}
	return comparePoints(ka.(string), kb.(string))
}

// groupKeyOf returns the group key text of one scalar value.
func groupKeyOf(v any) string {
	switch x := v.(type) {
	case string:
		return x
	case bool:
		if x {
			return "true"
		}
		return "false"
	case map[string]any:
		if s, _, ok := dateInterval(x); ok {
			return s
		}
	}
	raw, _ := json.Marshal(v)
	return string(raw)
}

func groupHits(hits []map[string]any, g map[string]any) []QueryGroup {
	prop := strOr(g["prop"], "")
	type bucket struct {
		key    string
		rank   int
		val    any
		ids    []string
		seenID map[string]bool
	}
	buckets := map[string]*bucket{}
	var nullIDs []string
	for _, o := range hits {
		id := strOr(o["id"], "")
		v := fieldValue(o, prop)
		elems := asList(v)
		if len(elems) == 0 {
			nullIDs = append(nullIDs, id)
			continue
		}
		for _, el := range elems {
			if m, ok := v.(map[string]any); ok {
				el = m // a date interval groups as a whole (by its start)
			}
			k := groupKeyOf(el)
			rank, val := sortKey(el)
			b := buckets[k]
			if b == nil {
				b = &bucket{key: k, rank: rank, val: val, seenID: map[string]bool{}}
				buckets[k] = b
			} else if compareKeys(rank, val, b.rank, b.val) < 0 {
				b.rank, b.val = rank, val
			}
			if !b.seenID[id] {
				b.seenID[id] = true
				b.ids = append(b.ids, id)
			}
		}
	}
	var out []QueryGroup
	used := map[string]bool{}
	if explicit, ok := g["order"].([]any); ok {
		for _, x := range explicit {
			k, ok := x.(string)
			if !ok || used[k] || buckets[k] == nil {
				continue
			}
			used[k] = true
			key := k
			out = append(out, QueryGroup{Key: &key, IDs: buckets[k].ids})
		}
	}
	var rest []*bucket
	for k, b := range buckets {
		if !used[k] {
			rest = append(rest, b)
		}
	}
	sort.Slice(rest, func(i, j int) bool {
		if c := compareKeys(rest[i].rank, rest[i].val, rest[j].rank, rest[j].val); c != 0 {
			return c < 0
		}
		return comparePoints(rest[i].key, rest[j].key) < 0
	})
	for _, b := range rest {
		key := b.key
		out = append(out, QueryGroup{Key: &key, IDs: b.ids})
	}
	if len(nullIDs) > 0 {
		out = append(out, QueryGroup{Key: nil, IDs: nullIDs})
	}
	return out
}
