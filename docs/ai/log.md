# Persistence Log

## 2026-11-13
* **Creation**: [index.md](index.md) — bundle entry point with `okf_version: "0.2"` (#159).
* **Creation**: [log.md](log.md) — this per-run persistence log (#159).
* **Migration**: Added the OKF v0.2 frontmatter envelope (`type`, `title`, `description`, `tags`, `generated`, `status`, and `verified` where the content was re-checked against the codebase) to [HANDOFF.md](HANDOFF.md), [STATE.md](STATE.md), [DECISIONS.md](DECISIONS.md), [ARCHITECTURE.md](ARCHITECTURE.md), [CONVENTIONS.md](CONVENTIONS.md), [PITFALLS.md](PITFALLS.md), [DOMAIN.md](DOMAIN.md) and [HISTORY.md](HISTORY.md). Bodies unchanged (#159).
* **Update**: README entry point now points at `docs/ai/index.md`; `AGENTS.md` bootstrap starts at the index (#159).
* **Update**: Added one entry to [PITFALLS.md](PITFALLS.md) — theme attributes must never be taken as an `Int` (`TypedValue.data` is a resource id for selector/OEM night themes), a read-only display needs the enabled variant, and a contrast test is only meaningful under a selector-based theme (#160).
* **Update**: Regenerated [STATE.md](STATE.md) (status line + a `#158` section) and added open task 12 to [HANDOFF.md](HANDOFF.md) — the UI-contrast epic #158 with its four remaining sub-issues (#161–#164); #159 and #160 closed (#158, #159, #160).
* **Update**: Added two entries to [PITFALLS.md](PITFALLS.md) — the IME window is edge-to-edge from API 35 (up to API 34 the platform insets the IME content itself, so the same listener is inert there; rebuild padding from the XML base, `max` not sum), and the emulator recipe that produced the evidence (3-button nav via `cmd overlay enable-exclusive`, the API-31 SystemUI ANR, the `ime enable` retry). [DECISIONS.md](DECISIONS.md) records the "no SDK gate" decision; [STATE.md](STATE.md) has the `#157` section, [HANDOFF.md](HANDOFF.md) the run entry (#157).
