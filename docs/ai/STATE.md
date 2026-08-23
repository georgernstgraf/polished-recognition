# Project State

Current status as of 2026-08-23.

## Current Focus
**#44 IME voice bar redesign** + **#46 keyboard selectability** — both implemented, committed, pushed, and **verified on-device**. Build + tests green. #46 closed. #44 remains open pending on-device UX verification of the voice bar (pause/resume/send/cancel/gear-implicit-pause).

## Completed (this cycle)
- [x] #44 IME voice bar redesign (commit `4bdedbc`): 3 icon `ImageButton`s (Cancel/Pause-Resume/Mic-Send), pause/resume, gear-implicit-pause (RECORDING→pause before opening Settings), `onFinishInputView` lifecycle fix (only pause RECORDING, don't cancel PAUSED — opening Settings no longer destroys a paused recording), custom spinner item layouts with explicit hardcoded colors for contrast, quick-settings (spinner+raw) disabled+dimmed during RECORDING/PROCESSING, content descriptions for accessibility.
- [x] #46 IME keyboard selectability (commit `f4ebca4`): added a non-auxiliary keyboard subtype to `voice_method.xml` (`imeSubtypeMode="keyboard"`, `subtypeId="0x70c01a1f"`) alongside the existing auxiliary voice subtype. Mixed subtypes — Polished now appears as a selectable primary keyboard AND keeps the auxiliary voice path for HeliBoard/Fossify. **Verified on-device:** nav-bar switcher now renders for `Gboard + Polished` (the original problem — switcher not rendering when Polished was auxiliary-only — is fixed).
- [x] AOSP-source investigation of "Manage Keyboards" greyed-out rule: traced to `InputMethodSettingValuesWrapper.isAlwaysCheckedIme()` + `InputMethodAndSubtypeUtil.isValidNonAuxAsciiCapableIme()`. Confirmed (via 7-row on-device evidence table) that observed behavior matches AOSP — no Oplus OEM override. `isSystem()` is a hard requirement; current/default IME is never consulted; `isAuxiliary` only excludes from the count. Documented in PITFALLS.
- [x] `isAsciiCapable` decision: deliberately NOT set on Polished's non-aux keyboard subtype — keeps AOSP's user-protection (prevents stranding the user with only a voice bar, since Polished has no QWERTY layout). Documented in PITFALLS.
- [x] Knowledge persistence run; ARCHITECTURE.md rewritten (was stale — still described the removed `PolishedRecognitionService`).

## Pending
- [ ] On-device verify #44 UX: `adb install -r` → switch active keyboard to Polished → pause→resume, send (commitText), cancel, gear-implicit-pause→Settings→return→Resume, spinner readability, 2nd dictation (no deadlock).
- [ ] #45 — Settings theme-free (replace Material with plain Views + platform theme). Aesthetic; lower priority.
- [ ] After #44 verified: tag `v1.2.0` → `release.yml` → bump fdroiddata metadata + comment on MR !40029.

## Blockers
None. (Oplus ROM blocks adb `ime`/`settings secure`/`pm grant` — all UI-driven; not a blocker.)

## Next Session Suggestion
On-device verify #44's voice-bar UX (requires the phone). If it passes, tag `v1.2.0` → `release.yml` → bump fdroiddata metadata + comment on MR !40029. Then #45 (Settings theme-free).
