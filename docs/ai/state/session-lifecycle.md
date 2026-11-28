---
type: State
title: Project State — Session Lifecycle
description: Pause/resume/cancel, auto-start, rotation resilience, the journal and recovery.
tags: [knowledge, state, session-lifecycle]
generated: { by: opencode/knowledge-persistence, at: 2026-11-28T08:40:00Z }
status: stable
---

# Project State — Session Lifecycle

Moved out of `STATE.md` in #206; sections are verbatim, newest first. The current status line, focus and pending items stay in `STATE.md`.

## #218 — der PAUSED-Take zeigt „Pausiert…" statt „Listening…" (2026-11-28, shipped `a23f9d4`)

- **Der Befund (Owner-Screenshot):** die Tastatur stand im Pausezustand
  (Take bei 0:05), aber die FGS-Notification las sich weiter „Listening… ·
  2m" und das Statusleisten-Icon blieb das Mikro. Wurzel: beide
  `StateChanged`-Handler (IME + `PolishedRecognitionService`) mappten nur
  RECORDING/PROCESSING/IDLE — `State.PAUSED` fiel in `else -> Unit`; der
  „2m"-Zeitstempel war die #118-Churn-Suppression, die identischen Text nie
  neu postet.
- **Die Regel (Owner-Entscheidung, Text + Icon):** im PAUSED-Zustand
  zeigt die Notification den Text `paused_notification` („Paused…") mit
  dem neuen Small-Icon `ic_notification_pause`. Das FGS selbst BLEIBT UP —
  der #83-Pitfall (Oplus reaped den Prozess ohne Mic-FGS mitten in der
  Rotation) ist unangetastet; korrigiert wird nur die Anzeige. Der
  Pause/Resume-Wechsel ist jetzt ein echter Textwechsel und bekommt damit
  ehrliche, frische Zeitstempel unter der Churn-Suppression.
- **Umsetzung:** `buildNotification`/`updateNotification`/`postNotification`
  tragen einen Icon-Parameter (Default `ic_notification_mic`) in beiden
  Services; der `PAUSED`-Zweig postet den Paused-Look. `onDestroy`
  unverändert (FGS-Release nur bei IDLE).
- **Tests:** neuer Fall in `ImeRebindChurnTest` — PAUSED postet
  „Paused…" + Pause-Icon (über `ShadowIcon.resId`), Wiederholung ist
  suppressed, Resume repariert den Text zurück zum Mikro-Look; der erste
  „Listening…"-Post reitet auf dem FGS-Assert und zählt als `fgs`, nie als
  `notify`. Suite + `lintDebug` grün (forced), Release auf beiden Geräten
  (OnePlus `f6de166c`, A03 `R7SRB06XF5K`). **Owner-Check offen:** Take
  pausieren → „Paused…" mit Pause-Icon, kein Mikro-Symbol in der
  Statusleiste; Resume → „Listening…" mit frischem Zeitstempel.

## #217 — der Cross-App-Einstieg released einen uncommitteten Take an die Werkstatt (2026-11-27, shipped `1edb349`)

- **Der Befund (Owner-Report + Device-Logs):** in Markor kam das IME im
  Pause-Look hoch und blieb dort (länger als 1 s, nicht reproduzierbar). Die
  `ime-lifecycle.log`-Episode (f6de166c, 2026-11-26) bewies den Pfad: der
  #152-Kept-Zweig hielt einen fremden PAUSED-Take (11,976-s-Keyboard-Switch-Rest
  aus der eigenen Workbench) fest und unterdrückte den Auto-Start — dreimal
  hintereinander über ~1 min. Nichts war kaputt; die Regel stallte nur.
- **Die Regel (Owner, 2026-11-26):** in die Workbench wird gesichert, was der
  Take hergibt — immer der rohe Text, beziehungsweise das Audio, wenn noch
  ein untranskribiertes Fragment in der Kette ist. Umgesetzt als reines
  `ImeLeftoverPolicy`-Matrix + `VoiceSessionController.releaseToRecovery()`
  (Tail-Flush + Backlog-Journaling + Journal-Überleben; `cancel()` unangetastet).
  Details: [decisions/session-lifecycle.md](../decisions/session-lifecycle.md)
  (2026-11-27).
- **Der Toast** („Leftover take (%s) saved — the Workbench will transcribe
  it", `Toasts.show`, 5,5 s) erscheint, während das neue Diktat schon
  recordet; die Out-of-band-Transkription läuft über
  `RecoveryCoordinator.startManual` und weicht dem Live-Diktat an
  Fragmentgrenzen aus. Der Workbench-Eintrag kommt als EIN voller Rohtext aus
  der Recovery (inkl. des provisorisch gestreamten Texts).
- **Der zweite Befund im selben Zug:** der Owner las das Switch-X als
  „alles verwerfen" — der Button trägt wieder das #65-Tastatur-Icon
  (`ic_close` entfernt, #195-Regel; statischer XML-Guard `ImeSwitchGlyphTest`).
- **Tests:** +3 (`ImeLeftoverPolicyTest`), +2 (`VoiceSessionControllerTest`:
  Release hält Journal, kein Downgrade), `ImePauseEntryTest` umgeschrieben
  (Release + frischer Start + Kick + Trace), +2 (`ImeSwitchGlyphTest`).
  Suite + `lintDebug` grün; Release auf der A03 installiert (OnePlus beim
  nächsten Anstecken). **Offen:** Owner-Gerätecheck (HANDOFF.md).

## #207 — an UNREADABLE delivery is HELD, never silently dropped (2026-11-25, shipped `7e0b244`)

- **The drop the device logs exposed:** two same-evening sends parked with `terminal outcome=UNREADABLE` (`COMPLETED_SUCCESS parked=true`) and were cleared by the owner's `USER_TAP_RESUME` without delivery. Root cause (file:line evidence from the wave-2 trace): the park itself holds NO text (`applyParkingEvent` sets only the bar flag; `PostSendParkingPolicy`), and the IME's delivery path ran `insertResultText` → `commitWithSpacing` → `currentInputConnection ?: return` — a null connection was a silent no-op. The controller-level `pendingResult` redelivery never applied (the IME callback WAS bound; the event was delivered live).
- **The fix:** new pure `service/HeldDeliveryPolicy.kt` — `KEEP` (field not readable / a live take owns it), `DELIVER` (bound field == origin field via `RotationGate.isSameField`), `SALVAGE` (foreign field or unknown origin — #192 rule c: never "any field"). The IME holds an UNREADABLE result in `heldDelivery` (+ `ime_delivery_pending` toast), `attemptHeldDelivery(trigger)` fires on the resume-tap (`:323`, BEFORE the fresh recording starts) and on bind (`:631`): same field → insert, foreign field → clipboard + `ime_delivery_salvaged` toast. A second hold salvages the older one first ("superseded"); `onDestroy` is a last-resort salvage. Never silently dropped.
- **Deliberately stricter than the work order:** delivery only at `controller.state == IDLE` (a same-field bind during a paused take is `keep` — conservative #192 guard).
- **Tests:** +13 (`HeldDeliveryPolicyTest` 6, `ImeHeldDeliveryTest` 7 incl. resume-tap → delivered, foreign-bind → salvage, instance-teardown → salvage, older-hold-first). Robolectric double-`onDestroy` needed a test-subclass guard (`SoftInputWindow.dismissForDestroyIfNecessary` is illegal twice) — test artifact only.
- **Open:** the owner device check (unreadable field → send → pending toast → resume tap delivers); the `DISABLED` kill-switch path keeps the old null-connection drop byte-identically (#205 promise) — closing it there would be a one-liner if the owner wants it.

## #186 device defect — a raw-mode rescue was delivered as an empty "success" (2026-11-22, fixed and device-verified 2026-11-23; #186 closed)

- **Was (measured on the Samsung SM-A032F, airplane mode on, release `147d404`, raw mode on):** `runTranscription` returns `Result.success(...)` for a rescued session, so the success branch of `stopAndTranscribe` ran `journal.markCompleted(sid)` + `discardFragmentSession()`. A session whose EVERY fragment failed was therefore "completed" and its journal deleted; the IME inserted `""` and said nothing. Evidence: `prepare.json` = `{"rawRescue":true,"failed":"fragment 1/1","failedIndex":1,"chunkCount":1,"error":"No usable network — saved for later"}`, no `stt-latency` record (the #186 gate made ZERO requests — correct, unchanged), `insertion.json` = `{"reason":"RAW_RESCUE_PARTIAL","length":0,…}`, `stt-text.json` = `{"text":"","textLength":0}`, no incomplete dictation in the Workbench, no Toast.
- **The fix is one condition, not a new path:** `markCompleted` + discard run only when `rescuePartial == null`. A rescue drops the transcriber/preparer caches as before but keeps the journal (`discardFragmentSession(keepJournal = true)`) and then FORGETS the session (`sessionId`/`preparer`/`sessionBundle` = null), so the session stays PENDING with every fragment's recorded failure class and `recoverableSessions()` lists it — and a later idle `cancel()` (the IME's `onDestroy` calls it) can no longer delete the journal the Workbench now owns. One `Log.w` line names the failed fragment, its class and its message.
- **The reason travels:** `Event.PartialInfo` gained `failure: SttFailure? = null` and `message: String? = null` (additive, defaulted), filled from the drained failure. Nothing else changed in the delivery path — `deliver()` still consumes the marker exactly once, and `markCompleted` still sits immediately before the delete (#138).
- **The IME:** `handleCompletedSuccess` inserts the text only when it is not blank and always shows exactly ONE Toast — `SttFailureText.resFor(partial.failure)` hint (when mapped) + `partial.message` + the new `ime_rescue_saved` ("The recording is saved in the Workbench — Retry STT there."). No generic failure Toast in addition; the bar still parks as after any send.
- **The delegated mic path:** `PolishedRecognitionService` reports a blank rescue as `error` (reason mapped by `mapRecognitionError`, so OFFLINE → `ERROR_NETWORK`) instead of an empty `results` success — and deliberately does NOT `controller.cancel()`, which would delete the very journal the rescue saved. Pure rule: `isBlankRescue(partial, text)`.
- **Tests:** 979 → **985** (`VoiceSessionControllerTest` +2: the rescue keeps the journal recoverable — OFFLINE class, zero requests, `recoverableSessions()` lists it; and the clean raw-mode success still completes + deletes; `ImeCompletedHandoverTest` +2: blank rescue → zero insertions + one Toast, non-blank → text + the same Toast; `PolishedRecognitionServiceTest` +2: `isBlankRescue` halves + the OFFLINE reason maps to `ERROR_NETWORK`). No version bump.
- **Not verified:** the repeated airplane-mode flight on the device ("nothing lost, the page is there, the Toast explains it").

## #186 — a dictation recorded offline is saved, not failed (2026-11-22, shipped; device-verified 2026-11-23 — see the device-defect section above)

- **Was:** without a network the app asked anyway. Every fragment spent its three transient attempts (or one, if DNS failed fast), the session parked as a failure, the IME said "transcription failed", and the audio sat in the journal with a `TRANSIENT` label. The user had to reconnect and find the Workbench themselves.
- **The gate is a pure read (`:app` `network/NetworkGate`):** `shouldAttempt(online)` and `reconnectFires(validated, previouslyValidated) = validated && !previouslyValidated` — the one-shot edge, because `onCapabilitiesChanged` repeats for the same network. `AndroidNetworkStatus` reads `activeNetwork → getNetworkCapabilities → NET_CAPABILITY_VALIDATED` (every null step = false), the honest bar: connected-but-captive is not online. No new permission (`ACCESS_NETWORK_STATE` was already there); the app had NO `ConnectivityManager` usage before.
- **`SttFailure.OFFLINE` (`:core`):** `classify(...)` gained `offline: Boolean = false` LAST (every existing call site compiles unchanged) and `offline = true` dominates every other input; `attemptsFor(OFFLINE)` = 1, documented as unreachable-by-request — the gate returns before the loop. Message: `No usable network — saved for later`, `httpCode = null`.
- **The runner asks first:** `SttRequestRunner` ctor gained `isOnline: () -> Boolean = { true }`; at the TOP of `run(...)` a closed gate returns the OFFLINE failure with **no request, no `stt-upload` and no `stt-latency` record** (nothing went out — evidence would be a lie). In the `catch` branch the gate is re-read: a connection that died while the network vanished is OFFLINE, the same exception with the gate open stays TRANSIENT.
- **One transition per session:** `FragmentTranscriber.blockedFailure` covers AUTH, BUDGET and OFFLINE — a 3-fragment offline session makes ZERO requests and journals all three PENDING with class `OFFLINE`, each with its composed upload. `resetFailures()` clears it like the other latches.
- **The polish is gated too:** `TranscriptionPipeline` gained the same `isOnline` (default `{ true }`); `finishTranscription` returns an `SttRequestException(failure = OFFLINE)` before the chat call and still reports `PipelineOutcome(rawText = …)` — the raw text is the half that cost the recording. The manual `polish()` refuses the same way.
- **Foreground-only reconnect (`:app` `network/ReconnectMonitor`):** an interface with `start(onValidated)`/`stop()`, implemented over `registerDefaultNetworkCallback`; the Workbench starts it in `onResume` and stops it in `onPause`, and a fired edge calls `recoverLeftovers()` — the SAME entry path, so the ring's reason, the auto rule and the Toast stay in one place. No service, no WorkManager, no background upload (owner decision).
- **No new UI:** `RecoveryReason.Kind.OFFLINE` (auto-retryable, "Offline — saved, will transcribe when online") renders through the #188 when-line; `SttFailureText` maps OFFLINE to `ime_stt_offline`, so the IME shows hint + detail unchanged. The parked IME bar after a failure is untouched.
- **No age rule (deliberate):** the journal's 256-MB cap keeps everything and nothing expires, so nothing can be dropped silently — the issue's "kept or dismissed with a recorded reason" is satisfied by keeping. There is no `offline_expired` reason.
- **Tests:** 962 → **979** (`:core` 297 → 304: classifier +2, runner +4, transcriber +1; `:app` 610 → 620: `NetworkGateTest` 4, pipeline +2, `RecoveryReasonTest` +1, `SttFailureTextTest` +1, `WorkbenchActivityTest` +2). No version bump.
- **Not verified on the device:** the flight (airplane mode → dictate → leftover page with the offline reason and no `stt-latency` entry → reconnect → transcribes). The Samsung `R7SRB06XF5K` is **PIN-locked** and no credentials were available; the release APK was installed and the Workbench launched behind the keyguard, but no input reaches a locked screen. Owner check in HANDOFF.md. **The check was later run and found the raw-mode rescue defect documented in the section above.**

## #182 — durable fragment journal (2026-11-20, shipped)

- **Was:** the fragment audio lived only in `cacheDir/fragments/<sessionId>/` — a cache dir the OS may clear at any time — and the forensic `session.json` (#142) is gated by the retention slider (release default 0). So "the audio is still there" after a failed upload meant "until the next cleanup". The journal closes that gap.
- **Layout:** `filesDir/journal/<sessionId>/` = `session.json` (marker, written first) + `fragment_%04d.json` + `upload_%06d.<ext>`. States `PENDING` → `DONE` | `NEEDS_MANUAL` (the last one is only ever set by #183). Atomic `.tmp` + rename everywhere; the audio is written BEFORE its record, so a crash leaves an orphan file and never a record whose audio is missing.
- **What is journaled:** the **composed upload** (pre-roll + fragment), not the bare fragment — ~1 s pre-roll per fragment (≈2 %), and it makes a retry byte-identical to the original attempt, the property #184's idempotency rests on.
- **Wiring:** `FragmentTranscriber` takes `journal` + `sessionId`; the write-ahead sits in `process()` right after `composeUpload()` and before `sttRunner.run(...)`, the flip after `transcripts[index] = stored`. All journal IO on `Dispatchers.IO` (the worker is Main-dispatched). Best-effort: a journal failure logs and never fails the fragment.
- **Lifecycle:** `VoiceSessionController` creates nothing explicitly (the journal creates its session dir on the first write) and deletes the session inside `discardFragmentSession()` — the single funnel for delivered / cancelled / flushed. The pipeline-**failure** path never reaches it (it parks PAUSED), which is the contract: a failed session keeps its journal until a retry delivers or the user discards it.
- **Cap:** 256 MB over the whole root (`prune`), **no age rule** — an unfinished dictation is never aged away; the newest session and a named live session are never evicted; every eviction logs its reason. Prune runs at app start and after each `deleteSession`.
- **Backup:** `journal/` is excluded in `backup_rules.xml` and in BOTH containers of `data_extraction_rules.xml` (`BackupRulesTest` extended — it pins the exact exclude set and fails on any drift).
- **Deliberate scope cut:** #182 ships **no** unfinished-session scanner (`unfinishedSessions()` is not in the class); cold-start discovery belongs to #185 and is recorded there as its own task.
- **Refactor:** the #132 session-dir clock moved out of `AudioEvidenceStore` into `SessionDirTime` — both stores now order session dirs by the same rule, and `AudioEvidenceStoreTest` stayed green as the regression proof.
- **Tests:** 777 → **802** (`FragmentJournalTest` 13, `SessionDirTimeTest` 4, `FragmentTranscriberTest` +5, `VoiceSessionControllerTest` +2, `BackupRulesTest` +1). No version bump.
- **Not verified:** the force-stop acceptance criterion needs a device (claw has none) — it is an owner check in HANDOFF.md.

## #177 — the IME parks after a send (2026-11-20, shipped)

- **Trigger:** #174 keeps the bar visible after a successful send, but the next bind ran the #165 auto-start and began a fresh take; the bar read as a ready recorder instead of "done, start again when you want".
- **Fix:** pure `service/PostSendParkingPolicy.decide(state, parked, event)` plus the instance flag `postSendParked`, set in `handleCompletedSuccess`. Parked bar = ▶ (`ic_resume`) enabled, Send and Flush disabled, Gear/X enabled, timer hidden. `requestAutoStart` exits on the flag BEFORE the whole #165 path, the deferred first-draw start included; ▶/Send clear the parking and start a fresh take.
- **Field identity decides:** `onStartInputView` clears the flag on a field change; the same field (rotation, hide/show) stays parked; `DISMISSED` changes nothing. The controller is untouched — after a send it is genuinely IDLE, and forcing PAUSED would break the repo-wide meaning of "audio ready, resume pending" (#67/#84/#134) and could revive a delivered dictation.
- **Limit:** the flag is IME instance state — if the service dies after a send, the next bind behaves as before #177. `▶` is acoustically a restart; the content description stays `ime_resume_desc`.
- **Tests:** `PostSendParkingPolicyTest` (12), `ImeCompletedHandoverTest` 3→6. No version bump.

## #165 — auto-start recorded silence on a cold start (2026-11-19, fixed, emulator-proven)

- **Reproduced before the fix on AVD `Medium_Phone_API_36.1`**: `am force-stop` + open a text field → `W ActivityManager: Foreground service started from background can not have location/camera/microphone access` + `E … FGS logic changed: [WIU changed] Orig WIU:PROC_STATE_PERSISTENT New WIU:DENIED`, 19 ms after `onStartInputView`, with the window's `mDrawState=HAS_DRAWN` only afterwards. Emulator precondition without which nothing shows: `settings put secure show_ime_with_hard_keyboard 1` (the AVD advertises a hardware keyboard, so the soft IME never appears) and an auxiliary-IME-aware flow — with only Polished enabled the system refuses the show, so Gboard stays enabled and `ime set` is issued right before the activity launch.
- **Fix (`4955ccc`)**: `AutoStartTiming` parks the auto-start until the input view's first draw; `onWindowHidden`/`onDestroy`/a user tap discard it; the 1500 ms timeout keeps waiting while `isInputViewShown()` and drops otherwise. `MicAccessPolicy` + `AudioRecorder.isClientSilenced()`/`isCapturing()` abort a take whose mic is blocked, 400 ms after the start. New trace rows in `ime-lifecycle.log`: `onWindowShown`, `onWindowHidden`, `firstDraw run=…`, `autostart deferred|dropped|cancelled|still-waiting`, `micGuard mode=… silenced=… capturing=… blocked=…`.
- **Emulator proof after the fix**: same cold start → 0 denial lines, `autostart deferred … drawn=false` → `onWindowShown` → `firstDraw run=FRESH_START` (1.1 s later) → `micGuard mode=0 silenced=false capturing=true blocked=false`; the bar shows a running take (`0:15`).
- **Tests**: `AutoStartTimingTest` (8, incl. the timeout asymmetry), `MicAccessPolicyTest` (7), `ImeAutoStartGateTest` (4 Robolectric on the real service: cold bind starts nothing, the draw starts it, a window hidden before its draw drops it, a stray draw does nothing). The IME test tears its session down in `@After` — a leaked live session polluted `SettingsActivityTest`'s toast assertion once.
- **Closed 2026-11-19 (owner: close autonomously).** Not verified: the S24 Ultra itself (the reporter's device) and Android 12/13 devices, which predate the while-in-use FGS restriction and were never affected. A recurrence reopens the issue; the trace rows name the stage.

## #138 — what shipped (`d0e7dbe`, 2026-10-14)

- **The regression:** after #133 the successful send ends with `switchInputMethod` → IME destroyed → `onDestroy()` IDLE branch → `controller.cancel()` → the never-cleared transcriber success cache was salvaged (`CancelledWithTranscripts`) — error toast + clipboard copy of already-inserted text. Owner report after v1.4.0 daily use.
- **Fix A (root):** consume-once — the success branch of the transcribe job (`VoiceSessionController.stopAndTranscribe`) calls `discardFragmentSession()` (salvage return ignored) after `clearPrepared()`, BEFORE `deliver()`; the transcriber lazily rebuilds per session (unchanged).
- **Fix B (invariant):** `cancel()` salvages only when `state != IDLE` on entry; IDLE cancel = pure teardown no-op (spool/snapshot cleanup preserved). The genuine #113 salvage paths (cancel from RECORDING/PAUSED/PROCESSING) are untouched.
- **Clipboard policy (owner decision):** after a successful insertion the clipboard stays UNTOUCHED — no salvage write, no clearing (clearing would destroy the user's prior clipboard content).
- Tests +1 → **481 green** (regression: success → Completed → teardown cancel emits nothing); `assembleRelease` + pre-push green.

## #134 — what shipped (`c782e88`, 2026-10-13)

- **Entry rule change:** a PAUSED leftover entered on a DIFFERENT field/app is no longer auto-resumed — it is discarded through the normal cancel path (#113 salvage keeps already-transcribed fragments in the clipboard) and a fresh empty recording auto-starts. Same-field return keeps auto-resume (#65 pause→detour→return flow intact); the discriminator is the existing `RotationGate` same-field identity.
- **Shape:** `AutoStartPolicy` collapsed into one pure `entryAction(state, sameField, permission)` → `RESUME | FRESH_START | NONE` (the two booleans are gone); the IME tail maps it — cancel-iff-PAUSED then `startIfPermitted()`. `ImeStartDecision` decision logic untouched (only its PROCEED doc updated); FREEZE (live sessions, rotation, restored snapshots) and PROVISIONAL_FREEZE (same-field PAUSED delayed resume) paths unchanged; the delegated RecognitionService route already always started fresh.
- Tests: `AutoStartPolicyTest` rewritten for the matrix (9 cases) — suite green, pre-push green.


**Also stored elsewhere** (ambiguous placement, cross-referenced per #206):
- [workbench.md](workbench.md) — „#185 — the recovery UI: the leftovers become visible and actionable (2026-11-21, shipped)"
