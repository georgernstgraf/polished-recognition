---
okf_version: "0.2"
---

# Knowledge Bundle Index

Entry point of the OKF v0.2 bundle `docs/ai/`. Short pointers; open the
individual concept files for details. There are no `hosts/` or `topics/`
directories here — this bundle describes one project, not a fleet.

## Active Files
* [HANDOFF.md](HANDOFF.md) - Open tasks for the next session — read first, act on it
* [STATE.md](STATE.md) - Live status line, focus, pending list and device notes (per-issue detail in `state/`)
* [CONVENTIONS.md](CONVENTIONS.md) - Coding patterns, naming, file layout
* [DECISIONS.md](DECISIONS.md) - Router for the decisions bundle: routing table and the ten newest decisions
* [ARCHITECTURE.md](ARCHITECTURE.md) - Living structural map of the app
* [PITFALLS.md](PITFALLS.md) - Router for the pitfalls bundle: routing rule and the theme table
* [DOMAIN.md](DOMAIN.md) - Canonical vocabulary (slice, search window, cut, fragment, seam …)

## Theme Bundles
* [decisions/](decisions/index.md) - What was decided and why, in eight theme concepts (226 entries)
* [pitfalls/](pitfalls/index.md) - What does not work and why, in ten theme concepts (291 entries)
* [state/](state/index.md) - Per-issue shipped work, in eight theme concepts (64 sections)

The three bundles share their slugs, so one theme is one look: `foundations`,
`build-release`, `ime-field`, `session-lifecycle`, `audio-pipeline`,
`stt-providers`, `workbench`, `settings`, `recognition-service`. `pitfalls/` adds
`testing` and `device-lab`.

## Reference
* [HISTORY.md](HISTORY.md) - Superseded entries archive (append-only, one file)
* [log.md](log.md) - Per-run persistence log
