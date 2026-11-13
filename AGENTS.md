# Notes for coding agents

- Read [ARCHITECTURE.md](ARCHITECTURE.md) first; [ROADMAP.md](ROADMAP.md) lists open work.
- Authority when documents disagree: `docs/product.md` (decisions) → `spec/*.md` + `spec/fixtures/`
  (wire contracts, law for all platforms) → code. Items marked REJECTED are not built.
- Cross-platform change = spec + fixture first, then Go reference (`server/internal/simclient`),
  Kotlin and C++. All three must pass the same fixture.
- Keep it simple: small files, pure functions for decisions, no new abstraction without a
  second user. Do not refactor what you are not changing.
- Mobile UI rules (no literal strings, es/en resources, editor behavior): [mobile/GUIDELINES.md](mobile/GUIDELINES.md).
- Run `tools/check.sh` (at least the affected suites) before each commit.
- Commits: one line, `<area>: what` (e.g. `pkmcore:merge: keep anchors on delete`).
