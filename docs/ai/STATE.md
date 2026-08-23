# Project State

Current status as of 2026-08-23.

## Current Focus
**IME voice bar redesign (#44)** — implemented, committed (`4bdedbc`), pushed. Build + tests green. Awaiting on-device verification. **New issue opened (#46, pending) — keyboard selectability:** Polished is auxiliary-only (`isAuxiliary="true"` in `voice_method.xml`), so it can't be set as a primary keyboard; when it's the only enabled IME, the user can't switch back to Gboard from the nav-bar picker. Planned fix: add a second non-auxiliary keyboard subtype to the same IME (mixed subtypes), preserving the auxiliary voice subtype for HeliBoard/Fossify integration.

## Completed (this cycle)
- [x] #44 IME voice bar redesign (commit `4bdedbc`): 3 icon `ImageButton`s (Cancel/Pause-Resume/Mic-Send), pause/resume, gear-implicit-pause (RECORDING→pause before opening Settings), `onFinishInputView` lifecycle fix (only pause RECORDING, don't cancel PAUSED — opening Settings no longer destroys a paused recording), custom spinner item layouts with explicit hardcoded colors for contrast, quick-settings (spinner+raw) disabled+dimmed during RECORDING/PROCESSING, content descriptions for accessibility.
- [x] Knowledge persistence run; ARCHITECTURE.md rewritten (was stale — still described the removed `PolishedRecognitionService`).

## Pending
- [ ] On-device verify #44: `adb install -r` → switch active keyboard to Polished → pause→resume, send (commitText), cancel, gear-implicit-pause→Settings→return→Resume, spinner readability, 2nd dictation (no deadlock).
- [ ] **#46 (to create) — IME keyboard selectability**: add a non-auxiliary keyboard subtype to `voice_method.xml` so Polished appears as a fully selectable primary keyboard, while keeping the existing auxiliary voice subtype for HeliBoard/Fossify integration. Planned, not yet implemented.
- [ ] #45 — Settings theme-free (replace Material with plain Views + platform theme). Aesthetic; lower priority than #46.
- [ ] After #44 verified + #46 landed: tag `v1.2.0` → `release.yml` → bump fdroiddata metadata + comment on MR !40029.

## Blockers
None. (Oplus ROM blocks adb `ime`/`settings secure`/`pm grant` — all UI-driven; not a blocker.)

## Next Session Suggestion
Create issue #46 (keyboard selectability — mixed subtypes), implement the `voice_method.xml` change (add a non-auxiliary keyboard subtype alongside the existing auxiliary voice subtype), build, install, on-device verify that Polished now appears as a selectable primary keyboard AND that the auxiliary voice path still works. Then on-device verify #44 in the same session.
