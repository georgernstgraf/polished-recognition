# Project State

Current status as of 2026-08-22 (on-device testing on an Oplus/OnePlus phone).

## Current Focus
**Auxiliary voice IME (#43)** — implemented (commit `97939c9`) + further crash/deadlock/Copy-button fixes (uncommitted in this session, will be committed). Installed on the phone (v1.2.0, versionCode 10200, release-key-signed). **The IME works** — transcription `commitText`s into the focused field. On-device testing revealed the compact-bar UX needs polish + a crash (now fixed). **Two follow-up issues opened for fresh agents** (see Pending); this session does NOT implement them.

## Completed (this cycle)
- [x] `VoiceSessionController` de-dup; `PolishedVoiceInputIME` + `voice_method.xml` + `MicrophonePermissionActivity` trampoline; removed bound `PolishedRecognitionService`; `SettingsActivity` → "Enable Voice Keyboard (IME)"; `INSTALLATION.md` rewritten; bump to 1.2.0. (commit `97939c9`, issue #43)
- [x] **Crash fix**: IME `ImageButton` `?attr/selectableItemBackgroundBorderless` → `@null` (service context can't resolve Material theme attrs → crash loop). (uncommitted)
- [x] **Deadlock fix**: `VoiceSessionController` resets to IDLE after `Completed` (2nd-dictation deadlock). (uncommitted)
- [x] **CrashDialog Copy-to-Clipboard** button (left) + Close App (right) via `dialog_crash.xml`. (uncommitted)
- [x] v1.2.0 compact bar (gear + language Spinner + Raw + Cancel + Mic) — baseline for issue #44. (uncommitted)
- [x] GitLab token hygiene: exposed PAT stripped from remote URL + local `credential.helper`; `credential.helper=store` set; old tokens revoked; new `api`-scoped token cached. fdroiddata worktree unstuck (`git rebase --abort` → `ae5b1e5d`).

## Pending (new issues, for fresh agents with clean context)
- [ ] **#44 — IME voice bar redesign**: icon buttons (Cancel `ic_close` / Pause-Resume / Mic-Send), pause/resume, spinner contrast (custom item layouts), **implicit-pause on Settings-gear**, lifecycle fix (`onFinishInputView` must not cancel PAUSED). Do NOT delegate to the full-screen activity.
- [ ] **#45 — Settings theme-free**: replace all Material (`TextInputLayout`, `MaterialCheckBox`, `Widget.Material3.*`, `?attr/textAppearance*`, androidx `AlertDialog`) with plain Views + a platform theme. No functional need (Settings works with Material) — aesthetic. Large/risky; see issue body.
- [ ] On-device verify the #44 redesign (pause/resume/send/cancel + gear-implicit-pause→return→Resume + spinner readability + 2nd dictation).
- [ ] Tag `v1.2.0` → `release.yml` → bump fdroiddata metadata + comment on MR !40029 (after #44 lands; the bar must be polished first).

## Blockers
None. (The Oplus ROM blocks adb `ime`/`settings secure`/`pm grant` — all UI-driven; not a blocker.)

## Next Session Suggestion
A fresh agent picks up **#44** (IME bar) — it has the full context + the crash/deadlock pitfalls in PITFALLS. Then **#45** (Settings theme-free). After both + on-device verify → tag `v1.2.0` + F-Droid MR !40029 bump.
