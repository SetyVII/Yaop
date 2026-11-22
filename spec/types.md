# Standard types contract — `yaop-types/1`

The base types (product §4.1) and their properties are the same on every platform. They are
**not synced**: each client builds them from `fixtures/types/standard.json`, so a value written
on one device means the same thing on the others.

Tests: `fixtures/types/standard.json` is loaded by C++ (`pkmcore`), Kotlin and Go and checked
with the rules below (`tools/check.sh`).

## The file

```json
{ "version": 1, "palette": ["blue", …], "types": [
  { "id": "web", "labels": {"en": "Web link", "es": "Enlace web"}, "properties": [
    { "key": "category", "labels": {"en": "Category", "es": "Categoría"}, "kind": "select",
      "options": [ {"id": "article", "labels": {"en": "Article", "es": "Artículo"}, "color": "blue"}, … ] } ] } ] }
```

| Field | Rule |
|---|---|
| `id` | canonical `type_id` |
| `labels` | display name per language; `en` is always present and is the fallback |
| `properties[].key` | the key in `object.properties` (`spec/object.md`) |
| `properties[].kind` | `text`, `number`, `date`, `select`, `multi_select`, `checkbox`, `url`, `object`, `media` |
| `properties[].system` | `true`: filled by the app (file name, size, url…), not edited as a plain field |
| `properties[].options` | only for `select` / `multi_select`; may be empty when the options come from elsewhere (`collection.item_type_id`) |
| `options[].id` | the **wire value**: ASCII, lowercase, `_`; stable forever |
| `options[].color` | a name from `palette` (the 10 semantic names of `spec/text.md`) |

## Values on the wire

- `select` → the option id as a string: `"category": "article"`.
- `multi_select` → an array of option ids: `"topics": ["programming", "design"]`.
- An id the client does not know (a newer option, or one from a custom type) is **kept as it
  arrived** and shown as its raw id. Nothing rewrites it.
- Options are never renamed by id. A new option is a new id; a label may change.

## Filled by the client

- `image.colors`: when an image is imported and has no `colors` yet, the client may fill up to 3
  ids from its pixels (each pixel goes to the nearest reference color; shares of at least 12 %,
  largest first). Local, no AI. A value the user set is never overwritten. C++:
  `pkm::dominantColorIds`.

## Rules

1. Option ids are unique inside a property; property keys are unique inside a type.
2. Every option color is in `palette`.
3. `select` / `multi_select` are the only kinds with `options`.
4. The order in the file is the display order.
5. A synced type with the same `id` as a standard type (a user who added a property) **extends**
   it: the client shows the standard properties first, then the synced ones whose `key` is not a
   standard key. The standard definitions themselves always come from this file.
6. Custom types carry their property definitions in the type op (`{id, key, name, kind,
   options?: [{id, label, color}]}`), with the same value rules.

## Changing the contract

Adding a property or an option is compatible: add it to the file and bump nothing. Removing or
renaming an id is a wire break (product §16.4) and needs a migration in every client.
