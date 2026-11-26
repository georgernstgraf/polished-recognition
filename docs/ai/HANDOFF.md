---
type: Handoff
title: Handoff
description: Open tasks for the next agent session.
tags: [knowledge, handoff]
generated: { by: opencode/knowledge-persistence, at: 2026-11-26T10:30:00Z }
status: stable
---

# Hand Off

**#211 (2026-11-26, implemented — OPEN until the owner's glance): the disabled LLM checkbox shows the effective state — unchecked in the Workbench context.**
Owner finding: the #168-disabled toggle still rendered the GLOBAL preference — checked with the LLM globally on, while the Workbench take delivers raw only. `46761a3` flips `refreshQuickSettings()` to `isChecked = !llmForcedOff && !settings.rawMode`: inside our own app the grayed box reads OFF, a foreign bind restores the global state. The `raw_mode` write-guards are untouched (`ImeLlmTargetTest` re-pins both directions). Tests forced green + lint 0; NOT yet installed (no device attached at push time). Owner: (1) Workbench with LLM globally ON → box gray + unchecked, raw dictation; (2) Markor → box enabled + checked, polish runs; (3) back to a foreign app → the global state shows again.

**#210 (2026-11-25, implemented — OPEN until the owner's device checks): rotation keeps the edited dictation; landscape never goes fullscreen.**
Two OnePlus findings from the same flow, fixed in `246a22b`, 771 `:app` tests forced green + lint 0, release installed on the OnePlus:
1. **Resume/rebind after rotation instantiated a NEW dictation** — `WorkbenchActivity` lost its page state on recreation (no `configChanges`, instance fields without `onSaveInstanceState`) and fell back to the compose slot, so the IME's resumed take got `pendingTarget = NEW` and appended. Fix: instance-state save/restore (index, ids, session, compose drafts) with an id-based `pendingReloadSelection` for the first reload, plus an attach self-heal in `onResume` (controller already RECORDING ⇒ `onRecordingStarted()` after `reload()`) for the event race.
2. **Landscape showed the platform fullscreen extract editor** (the "white field + Done button" in dark mode) — the inherited `onEvaluateFullscreenMode` default is true in landscape; now overridden to unconditional `false` in `PolishedVoiceInputIME` (the voice bar is an overlay in every orientation), pinned by `ImeFullscreenModeTest`.
Owner checks on the device: (a) dictate into a stored edit → rotate → the same page stays, the dictation lands in the edit, no new ring entry; (b) landscape → the voice bar shows, no extract field, no Done button, no white field.

**#209 (2026-11-25, shipped — OPEN until the owner's glance): the collapse chevrons are readable now.**
Both summary-row chevrons (`▾`/`▸` glyphs, #189) rendered at 14sp secondary — the owner read them as „visually way too small". `4bd3f19` bumps `workbench_raw_chevron`/`workbench_polished_chevron` to **22sp** (label/preview/copy untouched) and `WorkbenchLayoutPolicyTest` pins `>= 22sp` so it cannot regress. 766 `:app` tests green, lint 0, release installed on the OnePlus. Owner: judge the size on the device — too small again is a follow-up with a proper drawable, not a bigger font.

**#208 (2026-11-25, CLOSED — owner verdict „the functionality is good now"): promote clears the polished slot; the MRU ring stays; only ＋ instantiates a dictation.**
Final shape over two commits: `a7c7d89` (promote is a move — `replaceRawWithPolished` sets `copy(raw = polished, polished = null)`, strict no-op when `raw == polished`; compose path clears `composePolished`; the stored path shows the emptied field immediately, `syncPositions` never rewrites fields; promote buttons disable after) + `11e5b56` (the same-day creation-order excursion retracted per owner: `updatedAtMs` restored as the MRU key — bump on every real change, `loadAll` + FIFO eviction by it, edits protect against eviction; legacy file fallback covers what the intermediate build wrote). Header icons untouched; the invariant **only ＋ / the compose materialisation instantiates a dictation** — never promote/edit/polish/◀/▶/⚙ — is pinned by a store test. 766 `:app` tests green, lint 0, release installed on the OnePlus (`f6de166c`).

**#207 (2026-11-25, all four workstreams IMPLEMENTED — issue OPEN until the owner's device checks): Send must leave exactly the polished text in the field.**
Landed in four waves + one review fix, each commit suite-forced green + lint 0 + installed on the OnePlus (`f6de166c`):
`6776fed` (WS1 terminal cascade: new `StreamOutcome.COMMITTED_STREAM_REPLACED` — a field holding the platform-committed streamed text is "already delivered" ONLY when the polished result is identical; differing Q replaces the verified span via `EditorPort.deleteBefore` + verify-then-insert, refusals fall back to the MISMATCH/clipboard path, I5 kept), `7e0b244` (WS2 held delivery: UNREADABLE results are HELD in `heldDelivery` + pending toast, delivered on resume-tap/bind into the same field, salvaged to the clipboard on a foreign field or teardown — the 19:10:55/19:11:31 silent drops are fixed; `HeldDeliveryPolicy` + 13 tests), `aa7a377` (WS3 underline: the bind reconcile now re-composes a fully committed B+P stream — read→delete→read-back→compose with aborts BEFORE any write, behind `imeLivePreview`; documented in state/ime-field.md), `72aef6d` (WS4 toasts: `ui/Toasts.kt` — every production toast routed through it, chained ~5.5 s readable default, `short` for trivial confirmations, the #88 hint folded in, zero remaining `Toast.makeText` sites), `96e5bf5` (review fix: `show`/`short` dismiss a live hint — the "one toast alive" invariant is now real, 3 new invariant tests). Suites: 737→750→754→763 `:app` tests, all forced; aggregate re-run `--rerun-tasks --no-build-cache` green. Code review (Standards+Spec, parallel): no hard violations; three deliberate deviations documented (IDLE-gate stricter than the work order, SUFFIX-prefix replace extends I2 with issue sanction, partial-delete → MISMATCH fallback).
**Open: the owner device matrix on the OnePlus:** (1) the exact incident sequence in Markor (raw→polished + language + pause → Send → the FIELD must end with the polished German text); (2) held delivery (unreadable field → Send → pending toast → resume tap → text in the field); (3) the underline re-compose during a paused take (no duplicate, underline back); (4) toast readability (~5.5 s). The polish itself is verified correct — exactly one session-level full-context LLM pass at Send (ring timestamps, see pitfalls/device-lab.md).

**#205 (2026-11-25, implemented, tests green — OPEN until the owner's device matrix passes): live preview in the target field.**
Implemented in one wave: `service/LivePreview.kt` (the app-wide state next to the controller + the
pure `LivePreviewReconcile`/`LivePreviewTerminal` policies + `StreamOutcome`), `service/EditorPort.kt`
(the new editor seam with the `InputConnection` adapter — no fake connection existed before),
`FragmentTranscriber.displayText()/pendingMarkerCount()` + the per-fragment `onDisplayChanged`
callback, `SettingsStore.imeLivePreview` (key `ime_live_preview`, default ON) + the Settings
checkbox, `InsertionTrace.InsertionReason.STREAM`, `Event.DisplayChanged` and
`Event.Completed.streamed/streamOutcome`, and the IME's reconciliation (`onStartInput`), display
tick, terminal cascade (E → B → A) and the clear/restore on every non-delivering path. **The one
deviation from D3's table:** a bind that finds `B + P` treats the provisional as the COMMITTED
prefix K = P and composes nothing — the platform commits on every field end (`onFinishInput`), and
re-sending it would be the forbidden duplicate (I5); the "composition survived" tick case is handled
on the live connection instead (D4's E row only needs K = ∅ or K = P). **Left for the owner (device
matrix, part of the issue):** Markor, Telegram and one webview field; rotation mid-stream; nav-bar
close mid-stream; Flush mid-stream; X mid-stream; kill switch OFF; a take resumed after hide/show;
correlate `insertion.json` (the new `reason=STREAM` appends + `streamOutcome` on the terminal
record) with `ime-lifecycle.log` (`livePreview ...` lines). **First owner finding
already fixed (`8fcc84b`, 2026-11-25):** the freeze after the first fragment was the
constant-64 probe — a composition outgrows it on the second tick and every later
tick aborted. The probe is now sized to B + K + sent (bind/terminal: B + P) and the
B-boundary is located tolerantly; +5 regression tests, suite + `lintDebug` green.
The matrix above must now show the raw text GROWING fragment by fragment; if any
field still freezes, pull `ime-lifecycle.log` — the abort lines now carry
`window=<len> tail=<24>`, so the cause is readable instead of guessable.

**#189 (2026-11-24, shipped): the Workbench sections collapse again.** Both label rows are
`<summary>`-style toggles (chevron · label · copy) with a one-line, ellipsised preview while the
field is hidden — the #173 shape #178 had dropped, now for **Raw and Polished**. The state lives
for one visit (never persisted) and a take always opens Raw, because a collapsed field has no
input connection for the IME. The promote icon is an **up** arrow (it was Material's `publish`, a
line with a down arrow, which read as the wrong direction on the device), and the two action rows
below are identical: promote + trash can under Raw **and** in the bottom bar under Polished.
`WorkbenchLayoutPolicyTest` now pins the accordion instead of its removal, and
`WorkbenchActivityTest` covers both toggles, the empty preview and the auto-open on a take.
Owner check: item 10 (a glance on the device — nothing else). **#156's screenshot capture is
unblocked by this.**

**#201/#204 and #202/#203 (2026-11-24, shipped — both pairs were duplicates):** the parked IME bar lost
its explaining sentence: a bar with nothing buffered is a plain pause at `0:00` (#201 = #204). And a RAW
edit is now saved while it is typed (500 ms debounce) and committed before Polish reads anything, so the
result attaches to the edited text and survives the reload (#202 = #203). The owner's Disk-button idea was
not built — the implicit-save policy stays. Owner checks: items 1 and 9.

**#193 (2026-11-24, shipped, decomposed, CLOSED with all four children):** the lint baseline is gone — 77 entries
worked off, 71 real fixes and 6 documented exceptions (four inline `@Suppress`,
two commented `lint.xml` ignores: the permissive cleartext, which stays, and the
IME overdraw, because the overlay is 90 % opaque by design under a theme three
activities share). `warningsAsErrors = true`, `lintDebug` reports zero findings,
and `XmlColorPolicyTest` rule 6 now fails the suite if `app/lint-baseline.xml`
ever reappears. Children #194–#197 all closed; note for the owner: the only
visually observable changes are the settings wrap-preset row / crash dialog (should
look identical) and the launcher icon (now adaptive-only, with a monochrome layer
for Android 13+ themed icons) — a glance is enough.

**#198 / #199 (2026-11-24, shipped — two real test-suite defects found by the
gates):** `exit value 2` was never a heap wall. The app's JVM-global crash handler
ends in `System.exit(2)`, and a genuine throwable — `PCM range […] beyond buffer
(0B)` from the fragment worker while `flush()` truncated the spool under it — was
reaching it. Now: worker stopped+joined before truncation, no throwable escapes
worker or capture thread, a capture failure parks the take PAUSED (with its audio)
instead of crashing, the handler logs before exiting, and `CaptureThreadGuard`
turns a captured throwable into a readable test failure in the live-take classes.
Plus one racy controller test made deterministic (**#199**).
**#200 (2026-11-24, closed — the flake had a thread):** the hypothesis in the issue (a deferred `SharedPreferences.apply()`) was wrong. `PolishedRecognitionApp.onCreate` starts a MIN_PRIORITY thread whose `audioEvidenceStore.evict()` reads `settingsStore.evidenceRetentionSeconds` — and that getter **wrote while reading**, materialising the debug default `2592000` between the test's `clear()` and its assertion. The getter is now a pure read (only the one-shot legacy migration persists), two regression tests pin it, the startup thread is named `app-startup-maintenance` and joined by `SettingsStoreTest`. `SettingsStoreTest` 30× green. See STATE.md.

**#192 (2026-11-23, shipped + amended):** a pause belongs to ONE app — the same app always
continues it („always continue, no exceptions": pause button, X, nav-bar close,
restored snapshot; no time window, no ↺ requirement), another app ends it
through the #113 cancel and starts fresh. Nothing is written for a paused take.
The parked post-send bar now ends with the keyboard and no longer reads as a
pause. **Two owner follow-ups, shipped in the same issue (2026-11-23):** (1)
there is only ONE no-take bar — a fresh idle is parked at `0:00` (Send disabled,
↺ live as "New take", timer visible, **no stage line since #201/#204**), and ↺
really starts a take there; (2) an
automatic cancel may only destroy audio it can hand over — a PAUSED take with
buffered audio but nothing transcribed stays PAUSED instead of being cancelled
into silence (`salvageableText()`, trace `kept=audio-without-transcripts`).
Owner check: item 0 below.

**#191 (2026-11-23, shipped):** the owner's device check of #186 paged 1/3 ·
2/3 · 3/3 before finding Retry STT, so the four leftover actions now sit
directly under the when-line and the banner is an accent bar under the header
(`<n> incomplete dictation(s) · <newest stamp> — tap to open`, GONE at zero,
tap jumps to the newest leftover). Owner: judge the accent bar on the device
(`workbench_recovery_banner`); #189 (RAW/Polished collapse, arrow icon, trash
position) shipped on 2026-11-24 — see the top entry.

Only open tasks. **#118 is closed (2026-11-23)** — Phase C shipped (the rebind
storm's UI churn): `service/RebindChurn` skips identical per-cycle render/FGS/
notification/breath work, the FGS assert is per instance, and the existing
`onStartInputView` trace line in `ime-lifecycle.log` now ends in
`churn=render:<r> fgs:<f> notify:<n> breath:<b>`. **Phase B was NOT built — it
is superseded by #190** (`MISMATCH` + before-window guard; the raw-rescue half
shipped in #186). The one owner check left for #118: **rotate while dictating
and read one `churn=` line — the repeated rebind cycle must read all zeros.**
**#186 (offline sessions) is closed and device-verified
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
verdict, #156 (now with the Workbench shots) and the #200 test flake.

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

0. [x] **#192 device check (owner, OnePlus `f6de166c`) — PASSED 2026-11-24; issue closed.**
   The pause has a home: a nav-bar close into the SAME app continues the take
   (old audio included), another app salvages it — or keeps it PAUSED with
   `kept=audio-without-transcripts` when nothing was transcribed yet; X
   continues after any length of time; hiding the keyboard while RECORDING
   stops the microphone; a fresh keyboard shows the plain `0:00` bar whose ↺
   starts a take. No stage line (#201/#204), and no post-send "deep pause" left
   to distinguish — owner answer 2026-11-24: that state was never wanted, a
   plain `0:00` pause is the whole target.
1. [ ] **Owner: check the parked IME bar on the device.** Send a dictation → the
   bar stays visible and parked (↺ enabled under "New take", Send and Flush
   greyed out, Gear/X live, **timer visible at `0:00`**, no stage line — since
   #201/#204 it is a plain pause look) → no recording starts by itself. Tapping
   ↺ starts a fresh take.
   Focusing a different text field resumes the normal auto-start (#177); since
   #192 closing the keyboard with nav also ends the park (the next entry starts
   a fresh take instead).
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
8. [ ] **#156 — the screenshots are stale after #154/#155 *and* the Workbench
   redesign** ([#156](https://github.com/georgernstgraf/polished-recognition/issues/156),
   open). **Amended 2026-11-23:** the issue now carries the authoritative shot
   list — `docs/img/ime-recording.png` + `settings.png` **plus the two new
   Workbench shots** `workbench.png` (a stored dictation) and
   `workbench-recovery.png` (banner + leftover page), each with the framing rules
   and the README row to add. **Sequencing: #189 has LANDED (2026-11-24)** —
   capture now, so the Workbench shots show the restored RAW/Polished collapse,
   the up-arrow promote icon and the trash can in the bottom bar. Also stale,
   same capture session, decided separately in the
   issue: `fastlane/…/phoneScreenshots/{1-ime-recording,2-settings,3-settings-providers}.png`.
   Do not duplicate the list here.
9. [ ] **#202/#203 device check (owner): the edit survives the immediate Polish.**
   Open a stored dictation in the Workbench → edit RAW → tap Polish **without
   leaving the field** → the request must carry the edited text, the page must
   still show it, and the polished result must be the polish of the edited
   text, not of the old one. Then: edit RAW, wait ~½ s, kill the app from the
   recents/`am force-stop` → reopen: the edit is still there (the debounced
   draft save). The clipboard-on-field-leave behaviour is unchanged.
10. [ ] **#189 device check (owner): the two summary rows on a real screen.**
   (a) Tap the Raw row → the field folds away and a one-line preview with the
   text takes its place; the chevron points right (`▸`), a second tap opens it
   again (`▾`). (b) The same on the Polished row — and both rows are
   independent. (c) Collapse Raw, then dictate: Raw must open by itself so the
   text can be inserted. (d) The promote icon must read as an **up** arrow at
   both sites (under Raw and under Polished); the trash can sits next to it in
   the bottom bar too. (e) Leaving and re-entering the Workbench starts with
   both sections open (the state is per visit, not persisted).
11. [ ] **#205 device check (owner): the live preview matrix.** With the kill
   switch ON (default): dictate a multi-fragment take (a slice is ~21 s at the
   auto default) into **Markor**, **Telegram** and **one webview field** — the
   raw text must grow in the field while you speak, the `<pending>` marker must
   appear for a failed fragment, and Send must leave exactly the polished text
   (no duplicate, no leftover). Then: **rotation mid-stream** (the raw text
   stays, Send completes it or copies Q with `ime_raw_kept`), **nav-bar close
   mid-stream** (the composition survives; the same field continues), **Flush
   mid-stream** (the preview disappears, the selection comes back), **X
   mid-stream** (keyboard switch — the field keeps the raw text, the terminal
   classifies on return), **kill switch OFF** (zero composing writes, one
   `commitText` at the end), and a take **resumed after hide/show**. Evidence:
   `insertion.json` now carries `reason=STREAM` per display append and
   `streamOutcome`/`streamed`/`streamAborted`/`reconciled` on the terminal
   record; `ime-lifecycle.log` carries `livePreview ...` lines. A blind insert
   into a field that already holds the raw text is a test failure, not a
   follow-up.

## Not scheduled (owner-gated, no issue yet)
