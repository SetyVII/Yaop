# Contributing

## Commit Format

Use a single-line commit message scoped to the area and component being modified, followed by a brief description:

```text
<scope>: <brief description>
<scope>:<subscope>: <brief description>
```

Do **not** use type prefixes such as `feat:`, `fix:`, `update:`, `chore:`, `docs:`, or `refactor:`. Start directly with the scope you are touching (`desktop`, `mobile`, `pkmcore`, `server`, `spec`, `docs`, `tools`, etc.), narrow down with a sub-scope when relevant, and briefly state what changed.

### Examples

- `pkmcore:merge: keep anchors on delete`
- `desktop:core: update workspace object cache`
- `mobile:ui: adjust floating dock spacing`
- `server:sync: handle compacted oplog cursor`
- `spec:crypto: add pairing envelope test vector`
- `docs:server: document endpoint rate limits`
