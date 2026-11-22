---
type: Handoff
title: Handoff
description: Open tasks for the next agent session.
tags: [knowledge, handoff]
generated: { by: opencode/knowledge-persistence, at: 2026-11-22T16:00:00Z }
status: stable
---

# Hand Off

Only open tasks. **#75 (provider refusals) is closed (2026-11-22)** — the
provider's error body now decides the `BUDGET` class, a budget wall costs exactly
one request per session, and the limits land in the existing diagnostics. The
#152 program is at 4/5 with **two open children: #186 (offline sessions) and #188
(the manual Retry STT row)**. #182–#184 were closed on 2026-11-20; #185 (recovery
UI) on 2026-11-21; **#187 was reopened and closed again on 2026-11-22**. What
remains are owner checks on the device, #186, #188 and #156.

## #152 — five sub-issues, dependency order (4/5 done)

Epic #152 ("never lose a dictation") is decomposed and formally linked.
**#182, #183, #184 and #185 are done.** Open children: **#186** (offline
sessions, the fifth sibling) and **#188** (the row-level Retry STT / Workbench
text for pending transcriptions — since #75 the rows can also say "Provider
budget exhausted", #188 owns the retry affordance).

1. [x] **#182 — durable fragment journal** (closed 2026-11-20):
   `filesDir/journal/<sessionId>/`, composed upload, 256-MB cap.
2. [x] **#184 — idempotent uploads** (closed 2026-11-20): `session_id` +
   `fragment_index` + `attempt` via one `@PartMap`; empty map = pre-#184 wire.
3. [x] **#183 — STT failure classification** (closed 2026-11-20):
   `SttFailureClassifier` (TRANSIENT/AUTH/PROTOCOL/TERMINAL), `Retry-After`
   with a 10 s/20 s budget, the per-session AUTH latch, `NEEDS_MANUAL` in the
   journal.
4. [x] **#185 — recovery UI** (closed 2026-11-21): the journal's `session.json`
   carries the session state (`completedAtMs`, `dismissedAtMs`/reason) —
   `recoverableSessions()` is the single source. The Workbench scans on entry,
   Toasts before the first request and auto-retries `TRANSIENT`/interrupted
   sessions (never AUTH/PROTOCOL/TERMINAL), once per process, yielding to a
   live dictation at every fragment boundary. The banner (GONE at zero) opens
   `RecoveryActivity`: Re-run (`JournalReplay` — only the missing fragments,
   byte-identical, recorded prompt, `EchoTrim`, `LoopGuard`), Restore (a
   completed session's stored text, no request), Share (one rebuilt WAV, pieces
   for OGG), Dismiss (record `user_discarded`, then delete; a capped tombstone
   stays).
5. [ ] **#186 — offline sessions** (record now, transcribe on reconnect).

1. [ ] **Owner: check the parked IME bar on the device.** Send a dictation → the
   bar stays visible and parked (▶ enabled, Send and Flush greyed out, Gear/X
   live, timer hidden) → no recording starts by itself. Tapping ▶ starts a fresh
   take. Focusing a different text field resumes the normal auto-start (#177).
2. [ ] **Owner: judge the icon-only actions on the device.** `ic_promote`
   (Material `publish`) and `ic_delete_outline` for "clear polished" carry no
   text label, and `ic_polish` (Material `auto_awesome`) sits next to the
   "Polish" label — all three must read correctly on a real screen. If they do
   not, that is a follow-up issue, not a code fix on the spot (#178, #181).
3. [ ] **#182 device check (owner, USB host): force-stop mid-dictation.**
   Start a dictation, `adb shell am force-stop com.georgernstgraf.polishedrecognition`
   while the bar is still recording, then open the Workbench: the banner must
   name the leftover, a Toast must announce the recovery, and the dictation must
   land in the ring. Two devices are attached to `think` (`adb devices`:
   `R7SRB06XF5K` Samsung SM-A032F, `f6de166c` OnePlus HD1903), and the app's
   logs are readable at
   `/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/logs` —
   pull the evidence before judging.
4. [ ] **#75 device check (owner, USB host): a real budget wall.** Point the app
   at the school gateway with the spend cap used up → dictate → the Toast must
   read "Provider budget exhausted — top up or switch provider" plus the
   provider's own `ExceededBudget: …` line, `stt-latency.json` must show exactly
   ONE `complete` attempt with `providerError.type = budget_exceeded` and the
   `headers` block, and re-entering the Workbench must NOT upload again (the row
   reads "Provider budget exhausted", no auto-retry of that session).
5. [ ] **#185 device check (owner): the recovery paths on a real device.**
   (a) Airplane mode → dictate → the failure parks; reopen the Workbench with
   network back → auto-recovery Toast, the text appears in the ring, the banner
   disappears. (b) A wrong STT token → the row shows "STT token rejected" and is
   NOT auto-retried; fix the token → Re-run works. (c) Share audio on a WAV
   session → ONE file whose seams do not repeat words; on an OGG session →
   the pieces. (d) Dismiss → confirmation, then the row is gone and the
   tombstone survives a restart (Settings' journalling is invisible to the
   user; check `logcat` for the `FragmentJournal prune` line at most).
6. [ ] **#156 — the screenshots are stale after the Workbench redesign**
   ([#156](https://github.com/georgernstgraf/polished-recognition/issues/156),
   open). Re-capture from a current release build; the issue carries the
   authoritative shot list (`docs/img/ime-recording.png` + `settings.png`), do
   not duplicate it here.

## Not scheduled (owner-gated, no issue yet)

- **Burn down the lint baseline (77 entries).** It is warning-only backlog
  (`UseKtx` 26, `UnusedResources` 15, `Autofill` 10, `IconLauncherShape` 5,
  `HardcodedText` 4, …); the one error is `MissingPermission` on
  `AudioRecorder.start()` (callers check `RECORD_AUDIO`, lint cannot see it).
  Reaching zero would let the baseline file go away entirely and make
  `warningsAsErrors = true` meaningful — which would finally turn "only new
  findings fail the build" into the truth for warnings too (#163/#187).
