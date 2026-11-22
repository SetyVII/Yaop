# Schema changes contract — `yaop-schema/1`

How an object moves between types without losing data (product §16). Tests:
`fixtures/schema/change_type.json`, run by Go, C++ and Kotlin.

## Changing an object's type

`changeType(object, to, toKeys)` where `toKeys` are the property keys of the target type
(`spec/types.md` for a standard type, the type op for a custom one):

1. **Fixed types never change**: if the object's `type_id` or `to` is `image`, `audio`, `pdf`,
   `video`, `file`, `journal` or `tag`, the result is the error `fixed_type`. Media objects are
   one per asset, journal one per day, tags one per name (`spec/object.md`).
2. `to` equal to the current type: no change (the result is the object as it was, no loose keys).
3. Otherwise `type_id = to`; the change is stamped like any edit (`field_hlc.type_id`,
   `spec/merge.md`). **Blocks, title and every property stay exactly as they were**: nothing is
   renamed, converted or removed.
4. **Loose properties**: the keys of `properties` that are not in `toKeys` and are not special
   keys. They keep their values; the UI shows them in a separate "loose" section where the user
   may remove them by hand. Going back to the old type shows them as normal fields again.
5. Special keys never count as loose: `blocks`, `icon`, `cover`, `aliases`, `summary`, `pinned`,
   `is_template`, `tags`.
6. `loose` is sorted by key (code point order), so every client lists them the same way.

Because nothing is deleted, two devices that change the type concurrently converge by the
normal `type_id` merge and no value is lost.

## Fixture shape

```json
{ "cases": [ { "name": "…", "object": { "type_id": "book", "properties": { … } },
               "to": "project", "to_keys": ["status", "tags"],
               "expect": { "type_id": "project", "loose": ["author"] } },
             { "name": "…", "object": { "type_id": "image", "properties": {} }, "to": "note",
               "to_keys": ["tags"], "expect": { "error": "fixed_type" } } ] }
```

`expect.properties`, when present, must equal the resulting `properties` exactly.
