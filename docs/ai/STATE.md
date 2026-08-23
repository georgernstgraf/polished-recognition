# Project State

Current status as of 2026-08-23.

## Current Focus
**IME voice bar redesign (#44)** — implemented, committed (`4bdedbc`), pushed. Build + tests green. Awaiting on-device verification. **#46 — keyboard selectability:** implemented, committed (`f4ebca4`), pushed. Added a non-auxiliary keyboard subtype to `voice_method.xml` alongside the existing auxiliary voice subtype (mixed subtypes) so Polished appears as a selectable primary keyboard while keeping the auxiliary voice path for HeliBoard/Fossify integration. Build + tests green. Awaiting on-device verification.

## Completed (this cycle)
- [x] #44 IME voice bar redesign (commit `4bdedbc`): 3 icon `ImageButton`s (Cancel/Pause-Resume/Mic-Send), pause/resume, gear-implicit-pause (RECORDING→pause before opening Settings), `onFinishInputView` lifecycle fix (only pause RECORDING, don't cancel PAUSED — opening Settings no longer destroys a paused recording), custom spinner item layouts with explicit hardcoded colors for contrast, quick-settings (spinner+raw) disabled+dimmed during RECORDING/PROCESSING, content descriptions for accessibility.
- [x] #46 IME keyboard selectability (commit `f4ebca4`): added a non-auxiliary keyboard subtype to `voice_method.xml` (`imeSubtypeMode="keyboard"`, `subtypeId="0x70c01a1f"`) alongside the existing auxiliary voice subtype. Mixed subtypes — Polished now appears as a selectable primary keyboard AND keeps the auxiliary voice path for HeliBoard/Fossify.
- [x] Knowledge persistence run; ARCHITECTURE.md rewritten (was stale — still described the removed `PolishedRecognitionService`).

## Pending
- [ ] On-device verify #44 AND #46 together: `adb install -r` → switch active keyboard to Polished → confirm Polished now appears as a selectable primary keyboard (Gboard no longer greyed out when Polished is the only enabled IME) → pause→resume, send (commitText), cancel, gear-implicit-pause→Settings→return→Resume, spinner readability, 2nd dictation (no deadlock) → Fossify voice-typing selector still invokes Polished's voice subtype.
- [ ] #45 — Settings theme-free (replace Material with plain Views + platform theme). Aesthetic; lower priority.
- [ ] After #44 verified + #46 landed: tag `v1.2.0` → `release.yml` → bump fdroiddata metadata + comment on MR !40029.

## Blockers
None. (Oplus ROM blocks adb `ime`/`settings secure`/`pm grant` — all UI-driven; not a blocker.)

## Next Session Suggestion
On-device verify #44 + #46 together (see Pending) — requires the phone. If both pass, tag `v1.2.0` → `release.yml` → bump fdroiddata metadata + comment on MR !40029. Then #45 (Settings theme-free).
