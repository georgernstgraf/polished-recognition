---
type: Handoff
title: Handoff
description: Open tasks for the next agent session.
tags: [knowledge, handoff]
generated: { by: opencode/knowledge-persistence, at: 2026-11-20T13:52:31Z }
status: stable
---

# Hand Off

Only open tasks. **#182 shipped and closed on 2026-11-20**, #123/#177/#178 are
closed too. What remains are owner checks on the device, the rest of the #152
program, and #156.

## #152 — five sub-issues, dependency order (1/5 done)

Epic #152 ("never lose a dictation") is decomposed and formally linked.
**#182 (durable journal) is done.** Start with **#184** next — independent and
the smallest — then #183 → #185 → #186.

1. [x] **#182 — durable fragment journal** (closed 2026-11-20):
   `filesDir/journal/<sessionId>/`, composed upload, 256-MB cap.
2. [ ] **#184 — idempotent uploads** (`session_id` + `fragment_index`).
   Independent, smallest. Planned detail: one `@PartMap` on the Retrofit
   methods (NOT three `@Part`s), empty map = today's wire format; the arity
   change touches ~36 mock sites in `TranscriptionPipelineTest`,
   `VoiceSessionControllerTest`, `VoiceSessionControllerSecondaryTest`.
3. [ ] **#183 — STT failure classification** (transient / auth / protocol,
   `Retry-After`, 401 resume). Writes `NEEDS_MANUAL` into the #182 journal
   state that already exists.
4. [ ] **#185 — recovery UI** ("pending transcriptions", platform Views).
   **Owning the unfinished-session scanner, which #182 deliberately did NOT
   build** (`FragmentJournal` has no `unfinishedSessions()`; the journal root
   + `readRecord(sessionId, index)` are there to build it on).
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
   while the bar is still recording, then
   `adb shell run-as … ls` is impossible on a release build — verify via
   `adb shell dumpsys package …` + the next app start's `journal prune` log
   line, or install a debug build on the host. Expect the session dir under
   `files/journal/<sessionId>/` with `session.json`, `fragment_*.json`
   (`PENDING`/`DONE`) and the `upload_*.wav` files. NOT verifiable on claw (no
   device attached).
4. [ ] **#156 — the screenshots are stale after the Workbench redesign**
   ([#156](https://github.com/georgernstgraf/polished-recognition/issues/156),
   open). Re-capture from a current release build; the issue carries the
   authoritative shot list (`docs/img/ime-recording.png` + `settings.png`), do
   not duplicate it here.
