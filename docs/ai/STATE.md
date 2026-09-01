# Project State

Current status as of 2026-09-01 (#61 closed).

## Current Focus
**#61 (remove VoiceRecognitionActivity + drop Material/appcompat) CLOSED — build + tests + install verified.** Next: #62 (HeliBoard mic via additive bound RecognitionService, needs dedicated design session). MR !40029 watch + v1.2.1 release still open.

## Completed (this cycle)
- [x] #61 CLOSED: `VoiceRecognitionActivity` + `activity_voice_input.xml` + manifest block deleted; `NONE_TARGET_LANGUAGE`/`buildLanguageList` relocated to `config/LanguageOptions` (+ tests moved to `LanguageOptionsTest`); IME updated; **both** `com.google.android.material` and `androidx.appcompat` dropped (proguard rules + `DynamicColors` removed); `MicrophonePermissionActivity` → plain `Activity`; theme family reduced to `Theme.PolishedRecognition.Plain[.Transparent]`; unused resources deleted (`bg_quick_settings_pill`, `recording_text_secondary`, `recording_text_hint` — light + night).
- [x] #60 CLOSED (prior session): Ogg/Opus compression + CompressingAudio stage, on-device verified.

## Pending
- [ ] #62: HeliBoard mic via additive bound RecognitionService (not started; needs dedicated design session).
- [ ] MR !40029 watch: F-Droid maintainer (linsui) response to the 1.2.0 bump.
- [ ] Cut v1.2.1 (versionCode bump) — now bundles #60 + #61.
- [ ] On-device UX regression pass for #61: open IME in a text field, confirm quick-settings spinner/raw checkbox/gear still work (IME enabled + Settings verified via dumpsys; full IME bar interaction not yet re-driven).

## Blockers
None.

## Next Session Suggestion
On-device IME smoke pass (spinner + raw + gear + a real transcription via AnySoftKeyboard), then start the #62 design session or cut v1.2.1.
