---
type: Handoff
title: Handoff
description: Open tasks for the next agent session.
tags: [knowledge, handoff]
generated: { by: opencode/knowledge-persistence, at: 2026-11-22T19:00:00Z }
status: stable
---

# Hand Off

**#191 (2026-11-23, shipped):** the owner's device check of #186 paged 1/3 ·
2/3 · 3/3 before finding Retry STT, so the four leftover actions now sit
directly under the when-line and the banner is an accent bar under the header
(`<n> incomplete dictation(s) · <newest stamp> — tap to open`, GONE at zero,
tap jumps to the newest leftover). Owner: judge the accent bar on the device
(`workbench_recovery_banner`); #189 (RAW/Polished collapse, arrow icon, trash
position) stays a separate batch.

Only open tasks. **#186 (offline sessions) is closed and device-verified
(2026-11-23) — epic #152 is complete, 6/6 children.** On the Samsung SM-A032F
with airplane mode on, three takes logged `rawRescue` + `No usable network —
saved for later` and made **zero** requests; after the network returned the
retry sent exactly the three fragments (`attempt 1`, `200`) and stored the text.
Two defects the flight exposed were fixed before closure: a raw-mode rescue
treated an all-failed session as a completed success (journal deleted, nothing
shown), and `onDestroy` could then delete the journal the rescue had just saved
(see STATE.md, PITFALLS.md). #188 (incomplete dictations in the Workbench)
closed 2026-11-22; #75 on 2026-11-22; #182–#184 on 2026-11-20; #185 on
2026-11-21; **#187 was reopened and closed again on 2026-11-22**. #191 (leftover
actions under the when-line, unmissable banner) is shipped and waits only for
the owner's visual verdict. What remains are owner checks on the device, #191's
verdict and #156.

## #152 — complete: six children, all closed (the last one device-verified)

Epic #152 ("never lose a dictation") is decomposed and formally linked.
**#182, #183, #184, #185 and #188 are done**; **#186 is fixed but NOT verified on
a device**, so neither #186 nor #152 may be closed yet.

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
6. [x] **#186 — offline sessions** (closed 2026-11-23, device-verified): the
   `NetworkGate` (VALIDATED, `reconnectFires` edge rule), `SttFailure.OFFLINE` +
   `isOnline` in the runner and the pipeline, the OFFLINE latch in
   `FragmentTranscriber`, `network/AndroidNetworkStatus` +
   `network/ReconnectMonitor` (started in the Workbench's `onResume`, stopped
   in `onPause`), `RecoveryReason.Kind.OFFLINE` (auto-retryable) and
   `ime_stt_offline`. **No journal age rule** — the 256-MB cap keeps
   everything, so nothing expires.
   **Device defect found by the owner and fixed here:** in raw mode a fully
   failed session was delivered as an empty "rescue success" (journal marked
   complete + deleted, no page, no Toast). A rescue now keeps its journal
   (PENDING, failure class intact), an empty rescue inserts nothing, and one
   Toast names the reason and the Workbench. The airplane-mode flight has to be
   repeated on the device before #186 (and #152) can close — item 7 below.

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
7. [ ] **#186 device check (owner, USB host): a dictation recorded offline —
   raw mode ON, the defect case.** `adb shell cmd connectivity airplane-mode
   enable` → dictate with the **LLM checkbox unchecked (raw mode)** → the device
   must show ONE Toast ("Offline — saved, will transcribe when online" + "No
   usable network — saved for later" + "The recording is saved in the
   Workbench — Retry STT there."), insert no text, and NOT lose the dictation.
   Open the Workbench: the page must show "Offline — saved, will transcribe when
   online" and `stt-latency.json` must gain **no** record for that session
   (nothing went out; only `stt-upload.json` stays untouched too), while
   `insertion.json` reads `RAW_RESCUE_PARTIAL` with `length 0` and the journal
   session (`files/journal/<sid>/session.json`) has NO `completedAtMs`. Then
   `airplane-mode disable`: with the Workbench visible the page must transcribe
   right away (Reconnect Toast), otherwise the next entry or Retry STT must do
   it — and the page must disappear once it has. Repeat once in LLM mode (the
   session parks PAUSED with the offline failure Toast, as before). Evidence:
   `/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/logs` +
   `files/audio` + `files/journal`. Use the Samsung `R7SRB06XF5K`, never the
   OnePlus. **The first run of this check (release `147d404`) found the
   raw-mode defect — fixed in the working tree; the re-run is what closes
   #186 and #152.**
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
