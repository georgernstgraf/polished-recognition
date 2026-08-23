# Hand Off

**Active: #44 IME voice bar — committed + pushed (`4bdedbc`), awaiting on-device verify. New planned issue #46 — keyboard selectability (mixed subtypes).**

## What's done (commit `4bdedbc`, issue #44)
- Row 3 of `ime_voice_input.xml`: 3 icon `ImageButton`s (Cancel `ic_close` / Pause-Resume `ic_pause`↔`ic_resume` / Mic-Send `ic_mic`↔`ic_send`), equal weight, 48dp, `?android:attr/selectableItemBackgroundBorderless` backgrounds (platform-safe — never `?attr/`).
- `PolishedVoiceInputIME` button semantics: Cancel→`cancel()`+`requestHideSelf`; Pause-Resume→RECORDING `pause()` / PAUSED `resume()`; Mic-Send role-swap IDLE→`startIfPermitted()` / RECORDING+PAUSED→`stopAndTranscribe()`.
- **Settings gear implicit-pause**: gear enabled during RECORDING; press → `controller.pause()` THEN open `SettingsActivity`. On return state=PAUSED → pause_resume shows `ic_resume` → user explicitly resumes. Gear disabled during PROCESSING.
- **Lifecycle fix (critical)**: `onFinishInputView` now only `controller.pause()`s if RECORDING (releases mic, keeps buffer); PAUSED/PROCESSING untouched. Previously cancelled any non-IDLE state → destroyed PAUSED recordings when the IME hid to open Settings.
- Spinner contrast: new `ime_spinner_item.xml` (black on transparent) + `ime_spinner_dropdown_item.xml` (black on white) with explicit hardcoded colors — platform spinner layouts are unreadable in the service context on Oplus.
- Quick-settings (spinner+raw) disabled+dimmed (`alpha=0.4`) during RECORDING/PROCESSING; enabled in IDLE/PAUSED.
- Content descriptions added (`ime_cancel_desc`, `ime_pause_desc`, `ime_resume_desc`, `ime_mic_desc`, `ime_send_desc`).
- Build + tests green. Pushed to `origin/master`.

## Open tasks
1. [ ] **On-device verify #44** (see STATE.md). Requires the phone.
2. [ ] **Create + implement #46 — keyboard selectability (mixed subtypes)**: when Polished is the only enabled IME, the user can't switch back to Gboard from the nav-bar picker because Polished is auxiliary-only (`isAuxiliary="true"` in `app/src/main/res/xml/voice_method.xml:9`). **Plan (agreed with user, NOT yet implemented):** add a second, non-auxiliary keyboard subtype to the same `<input-method>` so Polished also appears as a fully selectable primary keyboard, while keeping the existing auxiliary voice subtype for HeliBoard/Fossify integration. Edit only `voice_method.xml` — add a `<subtype>` with `android:isAuxiliary="false"` (or omit the attr — default is false) and `android:imeSubtypeMode="keyboard"` + a unique `android:subtypeId` (hex < `0x80000000`). PITFALLS #79 covers the `subtypeId` range and `overridesIme` constraints.
3. [ ] #45 — Settings theme-free (replace Material with plain Views + platform theme). Aesthetic; lower priority than #46.

## After #44 verified + #46 landed
- Tag `v1.2.0` → `release.yml` → bump `metadata/com.georgernstgraf.polishedrecognition.yml` (version + commit; keep `Binaries`/`AllowedAPKSigningKeys`, signing key unchanged `62f9d7b0…a76a85`) → comment on MR !40029 → force-push the `add-polished-recognition` branch (worktree `~/repos/schurlix/fdroiddata-mr-polished-recognition`, currently clean at `ae5b1e5d`).

## Known on-device gotchas (Oplus/OnePlus)
- `adb shell ime …` / `settings put secure …` / `pm grant …` → all blocked (SecurityException). Enable IME + grant mic + set default keyboard via Settings UI only. Verify via `dumpsys input_method | grep polished` + `dumpsys package … | grep RECORD_AUDIO`.
- HeliBoard's mic uses the system `voice_recognition_service`, NOT the auxiliary IME → HeliBoard's mic won't route to Polished (the bound service was removed). Use the nav-bar switcher to set Polished as the active keyboard, or Fossify Keyboard (has a voice-typing selector). Re-adding the bound service is a possible future follow-up if HeliBoard's mic is wanted.
- The IME crashes on `?attr/` theme attrs — only platform attrs / `@null` / explicit colors in IME layouts.

Last cleared: 2026-08-23. Knowledge files current (DECISIONS/ARCHITECTURE/PITFALLS/STATE updated with all session findings).
