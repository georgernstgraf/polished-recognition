---
type: Handoff
title: Handoff
description: Open tasks for the next agent session.
tags: [knowledge, handoff]
generated: { by: opencode/knowledge-persistence, at: 2026-11-28T10:15:00Z }
status: stable
---

# Hand Off

**#219 (2026-11-28, IMPLEMENTED — OPEN, `owner-verdict`, auto-closes after 30 days of silence): cross-app leftovers become Workbench dictations automatically.**
Five commits on `main`: `40ddfdc` (the Advanced checkbox `cross_app_keep`, default ON, decides whether a cross-app leftover is saved at all; both save branches confirm with ONE toast naming the app — `SettingsStore.appDisplayName`: known-apps label → PackageManager label → package name), `5c766a4` (zombie pruning: a COMPLETED journal session with no stored text is DELETED, not hidden — log `prune: zombie session …` under the tag `WorkbenchActivity`; the leftover hint became reason-aware: `workbench_leftover_hint_completed` "Transcribed — restore the text, share the audio, or dismiss.", failure kinds keep the old hint), `7bcbcca` (the owned toast strip at ~60 % screen height — IME dock + Workbench overlay, issue #222), `a4d4890` (the racy release-to-journal test pinned offline-deterministic via the #186 gate seam), `9bb3cb9` (review fix: the IME toast host is registered exactly while the input view is visible — attach in `onCreateInputView`/`onStartInputView`, detach in `onFinishInputView` and `onDestroy`; the OFF discard toast now quotes the REAL checkbox name; the prune log got its own tag). Two-axis code review (Standards + Spec): no hard violations; all findings fixed or decided. 876 `:app` tests green, lint 0, release installed on BOTH devices 2026-11-28 (OnePlus `f6de166c`, A03 `R7SRB06XF5K`). **Owner checks:** (a) checkbox OFF → dictate in app A, switch apps → NO Workbench entry, but the discard toast (with the app name) appears on every fresh dictation; (b) ON → ONE Workbench entry + SAVED toast, no banner / no leftover page for this path; (c) toasts appear over the lower half of the screen (~60 %), not at the bottom edge. Deferred decisions: see the #219 issue comment of 2026-11-28. See `decisions/session-lifecycle.md`, `state/session-lifecycle.md`, `state/settings.md`, `state/ime-field.md`, `state/workbench.md`.

**#222 (2026-11-28, IMPLEMENTED — OPEN, `owner-verdict`): the owned toast strip.**
`7bcbcca` + `9bb3cb9`: `Toasts` routes every production toast; contexts with a host render the strip, others keep the platform toast byte-identically. IME: the strip is docked as the first child of `ime_root` (the window does the anchoring) and is alive exactly while the input view is visible. Workbench: `WorkbenchToastStripHost` overlays the content, bottom edge at 60 % of the content height. Colors `#E6212121`/`#F0F0F3` in both palettes. Owner check: see #219 items (b)/(c). See `state/ime-field.md` + `state/workbench.md`.

**#218 (2026-11-28, IMPLEMENTED — OPEN, `owner-verdict`): a paused take shows „Paused…" + the pause icon instead of „Listening…" + the mic icon.**
`a23f9d4`: both `StateChanged` handlers (IME + bound service) now map PAUSED; the FGS itself STAYS UP (#83 untouched — only the display changes); pause/resume is a real text change, so churn suppression yields fresh timestamps. **Owner check:** pause a take → „Paused…" with the pause icon, no mic symbol in the status bar; resume → „Listening…" with a fresh timestamp. See `decisions/session-lifecycle.md` + `state/session-lifecycle.md`.

**#217 (2026-11-27, IMPLEMENTED — OPEN, `owner-verdict`): cross-app entry releases an uncommitted paused take to the Workbench instead of stalling the auto-start.**
`1edb349`: pure `ImeLeftoverPolicy` + `VoiceSessionController.releaseToRecovery()` (tail flushed, backlog journaled PENDING, journal survives); the fresh recording starts in every branch; the switch button carries the keyboard icon again (`ic_close` dropped, #195 rule). **Owner check:** (a) dictate in app A → X/nav-close → open Markor and focus a field → the keyboard RECORDS immediately + the leftover toast shows → the Workbench later shows ONE recovered entry (banner); (b) the switch button reads as a keyboard, not an X; (c) the trash still discards with the clipboard salvage. See `decisions/session-lifecycle.md` + `state/session-lifecycle.md`.

**#214 addendum (2026-11-26, IMPLEMENTED — OPEN, `owner-verdict`): API tokens are remembered per provider URL.**
`59ee546`: a shared token vault (canonical URL → token, STT + LLM) inside the backup-excluded secrets file; a URL change ALWAYS refills the token field from the vault or empties it (owner rule: unvalidated fiddled input is worthless). **Owner check:** preset away to a new provider and back — the token returns without retyping; nothing token-shaped in the Google backup. See `state/settings.md` + `decisions/stt-providers.md`.

**#153 (2026-11-26, IMPLEMENTED — OPEN, `owner-verdict`): the reasoning efforts of the LLM model become a setting.**
`ChatRequest.reasoning_effort` (null = not sent), the conditional spinner only when the model advertises levels, presets "OpenCode"/"OpenCode Go" (`0b03719`), and the `x-opencode-session` header for Go-URLs only (`d39a853` — owner device finding). **Owner check:** opencode-go LLM setup → Fetch Models → a reasoning model shows the spinner with exactly its levels, STT section unchanged; preset path: "OpenCode Go" preset → Fetch Models (must pass the former HTTP 400). See `state/settings.md` + `state/stt-providers.md`.

**#207 (2026-11-25, IMPLEMENTED — OPEN, `owner-verdict`): Send must leave exactly the polished text in the field.**
Four waves + one review fix (WS1 terminal cascade, WS2 held delivery, WS3 underline re-compose, WS4 `ui/Toasts.kt`; details in `state/session-lifecycle.md`). **Open: the owner device matrix on the OnePlus:** (1) the exact incident sequence in Markor (raw→polished + language + pause → Send → the FIELD must end with the polished German text); (2) held delivery (unreadable field → Send → pending toast → resume tap → text in the field); (3) the underline re-compose during a paused take (no duplicate, underline back); (4) toast readability (~5.5 s).

**#210 (2026-11-25, IMPLEMENTED — OPEN, `owner-verdict`): rotation keeps the edited dictation; landscape never goes fullscreen.**
`246a22b`: instance-state save/restore in `WorkbenchActivity` + the landscape `onEvaluateFullscreenMode = false`. **Owner checks:** (a) dictate into a stored edit → rotate → the same page stays, the dictation lands in the edit, no new ring entry; (b) landscape → the voice bar shows, no extract field, no Done button, no white field.

**#205 (2026-11-25, IMPLEMENTED — OPEN, `owner-verdict`): the live preview in the target field.**
Implemented in one wave; the first owner finding (the constant-64 probe freeze) is fixed (`8fcc84b`). Details in `state/ime-field.md`. **Open: the device matrix** — a multi-fragment take grows visibly in Markor, Telegram and one webview field; rotation mid-stream; nav-bar close mid-stream; Flush mid-stream; X mid-stream; kill switch OFF; a take resumed after hide/show. Evidence: `insertion.json` (`reason=STREAM`, `streamOutcome`) + `ime-lifecycle.log` (`livePreview …` lines).

**#156 (OPEN): the README + fastlane screenshots are stale.**
The authoritative shot list lives in the issue (`docs/img/ime-recording.png`, `settings.png`, the two Workbench shots, the fastlane `phoneScreenshots/`). The IME surface changed again with the toast strip — capture from a current release build in one session.

**#118 churn-line check (owner; the issue is CLOSED but the check was never run):**
rotate while dictating and read ONE `churn=` line in `ime-lifecycle.log` — the repeated rebind cycle must read all zeros (`churn=render:0 fgs:0 notify:0 breath:0`).

**Automation retarget (agent task, deferred):** retarget the weekly automation `aut_2217eaf83001NvG7A5lCemiQSY` to the `issue-janitor` agent. Needs ONE `opencode service restart` first (the server's agent watcher does not follow the symlinked agents dir; restarting kills running sessions, so it was deferred), then verify `issue-janitor` loads (`opencode api get /api/agent`) and recreate/retarget the automation with `to: { subagent: "issue-janitor" }`.
