---
type: Handoff
title: Handoff
description: Open tasks for the next agent session.
tags: [knowledge, handoff]
generated: { by: opencode/knowledge-persistence, at: 2026-11-22T19:00:00Z }
status: stable
---

# Hand Off

Only open tasks. **#186 (offline sessions) is closed (2026-11-22) — and with it
epic #152: all six children have shipped, „never lose a dictation" is
complete.** A dictation recorded without a usable network makes no request,
lands in the journal as `OFFLINE`, shows as an incomplete page with that reason
and transcribes when the network is back (validated reconnect while the
Workbench is visible, the next entry, or Retry STT). #188 (incomplete
dictations in the Workbench) closed 2026-11-22 as well; #182–#184 were closed
on 2026-11-20; #185 (recovery UI) on 2026-11-21; **#187 was reopened and closed
again on 2026-11-22**; #75 on 2026-11-22. What remains are owner checks on the
device and #156.

## #152 — six sub-issues, all closed (the epic is COMPLETE)

Epic #152 ("never lose a dictation") is decomposed and formally linked.
**#182, #183, #184, #185, #186 and #188 are done** — the last one (#186) closed
2026-11-22.

1. [x] **#182 — durable fragment journal** (closed 2026-11-20):
   `filesDir/journal/<sessionId>/`, composed upload, 256-MB cap, no age rule.
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
   sessions (never AUTH/PROTOCOL/TERMINAL/BUDGET), once per process, yielding to
   a live dictation at every fragment boundary. Re-run (`JournalReplay` — only
   the missing fragments, byte-identical, recorded prompt, `EchoTrim`,
   `LoopGuard`), Restore (a completed session's stored text, no request), Share
   (one rebuilt WAV, pieces for OGG), Dismiss (record `user_discarded`, then
   delete; a capped tombstone stays).
5. [x] **#188 — incomplete dictations in the Workbench** (closed 2026-11-22):
   `ui/WorkbenchPage` + `WorkbenchPages.merge` (one ring, ordered by the last
   change), the tiered when-line (`ui/WorkbenchStamp`), the leftover page
   (read-only Raw, `DictationTarget` = NEW), the four actions on the page
   (`recovery/RecoveryRestore` + `RecoveryShare`), the count+jump banner, and
   the #185 screen deleted with its 4 assertions moved to
   `WorkbenchActivityTest`.
6. [x] **#186 — offline sessions** (closed 2026-11-22): the `NetworkGate`
   (VALIDATED, `reconnectFires` edge rule), `SttFailure.OFFLINE` +
   `isOnline` in the runner and the pipeline, the OFFLINE latch in
   `FragmentTranscriber`, `network/AndroidNetworkStatus` +
   `network/ReconnectMonitor` (started in the Workbench's `onResume`, stopped
   in `onPause`), `RecoveryReason.Kind.OFFLINE` (auto-retryable) and
   `ime_stt_offline`. **No journal age rule** — the 256-MB cap keeps
   everything, so nothing expires.

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
5. [ ] **#185/#188 device check (owner): the recovery paths on a real device.**
   (a) see the #186 check below — offline is its own item now. (b) A wrong STT
   token → the incomplete page's when-line says
   "STT token rejected" and it is NOT auto-retried; fix the token → Retry STT
   works. (c) Share audio on a WAV session → ONE file whose seams do not repeat
   words; on an OGG session → the pieces. (d) Dismiss → confirmation, then the
   page is gone and the tombstone survives a restart (check `logcat` for the
   `FragmentJournal prune` line at most).
6. [ ] **#188 device check (owner): the merged ring on a real screen.**
   (a) With two incomplete dictations the banner reads "2 incomplete dictations
   · <time>" and a tap lands on the NEWEST one — not on a stored dictation.
   (b) ◀/▶ walk from a leftover page to a stored one and the Raw field becomes
   editable again; dictating while a leftover page is shown creates a NEW
   dictation at the ring's end and leaves the leftover in place. (c) The
   when-line is legible on a phone (14sp, secondary colour) and the four
   buttons fit in one row in portrait — if they wrap, that is a follow-up, not
   a code fix on the spot.
7. [ ] **#186 device check (owner, USB host): a dictation recorded offline.**
   `adb shell cmd connectivity airplane-mode enable` → dictate → open the
   Workbench: the page must show "Offline — saved, will transcribe when online"
   and `stt-latency.json` must gain **no** record for that session (nothing went
   out; only `stt-upload.json` stays untouched too). Then
   `airplane-mode disable`: with the Workbench visible the page must transcribe
   right away (Reconnect Toast), otherwise the next entry or Retry STT must do
   it. Evidence: `/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/logs`
   + `files/audio`. Use the Samsung `R7SRB06XF5K`, never the OnePlus. **Not done
   in the #186 session: the Samsung is PIN-locked and no credentials were
   available** (the release APK was installed and the Workbench launched behind
   the keyguard; no input reaches a locked screen).
8. [ ] **#156 — the screenshots are stale after the Workbench redesign**
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
