---
type: State
title: Project State
description: Current project status and pointers to the open threads.
tags: [knowledge, state]
generated: { by: opencode/knowledge-persistence, at: 2026-11-28T10:15:00Z }
status: stable
---

# Project State

Current status as of 2026-11-28: **#219 is IMPLEMENTED (five commits `40ddfdc`/`5c766a4`/`7bcbcca`/`a4d4890`/`9bb3cb9`) and stays OPEN (`owner-verdict`, auto-closes after 30 days of silence)** — cross-app leftovers become Workbench dictations automatically: the Advanced checkbox `cross_app_keep` (default ON) decides whether a foreign-app leftover is saved at all; ON confirms with ONE toast naming the app (`SettingsStore.appDisplayName`), OFF discards pre-#217-style and says so (quoting the real checkbox name) on every fresh dictation; a COMPLETED journal session without stored text is DELETED (zombie pruning, `prune: zombie session …`), the leftover hint is reason-aware, and all recovery toasts render in the owned toast strip at ~60 % screen height (IME dock + Workbench overlay, #222). Two-axis code review (Standards + Spec): no hard violations. 876 `:app` tests, lint 0, release on BOTH devices (2026-11-28). Before that: **#218 is IMPLEMENTED (`a23f9d4`, `owner-verdict`)** — a paused take shows „Paused…" with the pause icon instead of „Listening…" + mic; the FGS stays up (#83 untouched). And **#217 is IMPLEMENTED (`1edb349`, `owner-verdict`)** — the cross-app entry releases an uncommitted take to the Workbench instead of stalling the auto-start; the switch button carries the keyboard icon again. Still open, all `owner-verdict`: #222 (the toast strip), #214 (token vault), #153 (reasoning efforts), #207/#210/#205 (owner device matrices), plus #156 (screenshots) — see [HANDOFF.md](HANDOFF.md).

## Shipped work by area

* [Foundations](state/foundations.md) — 1 section, newest first
* [Build, Release and Distribution](state/build-release.md) — 7 sections
* [IME and the Target Field](state/ime-field.md) — 11 sections
* [Session Lifecycle](state/session-lifecycle.md) — 11 sections
* [Audio Pipeline](state/audio-pipeline.md) — 14 sections
* [STT, LLM and Providers](state/stt-providers.md) — 4 sections
* [Workbench](state/workbench.md) — 11 sections
* [Settings](state/settings.md) — 5 sections

Per-issue detail lives in those files; only the status line, the focus, the pending
list and the device notes stay here. A "see the section below" points into the area
file listed above.

## Current Focus

No epic is active. The open surface is the owner-verdict queue: #219, #222, #218, #217, #214, #153, #207, #210 and #205 wait for device checks — the weekly `issue-janitor` automation auto-closes them after 30 days of silence once its retarget lands (see HANDOFF.md). Owner-gated research: #129 (seam-parameter proposal) has no deadline; #156 (screenshot refresh) is unblocked.

## Pending

- [ ] **#129 FINAL (gated on owner dictations; issue OPEN)**: bist-class dictations on either device → bundles land in `files/audio/` → analysis via the analyzer path (`analyzer pull` → `scan` Q-scan → offline Gegenprobe, loop-masked) → parameter proposal (adopt `axisK=0.75`; the REAL `qualityMin` from sick-seam data) → close-out.
- [ ] **Owner dictation check (~25 s)** on the release build (rides #129): `session.json` cadence with REAL `stitched` values, stop→raw ≤ ~2.4 s worst case, Settings fragment field blank. NOTE: release builds still carry the PROVISIONAL seam defaults (0.65/100/10.0) until #129 concludes.
- [ ] **#156 screenshots (owner, issue OPEN)** — the shot list lives in the issue; the IME surface changed again with the toast strip (#222).
- [ ] **#118 churn-line check (owner; issue closed, the check was never run)** — rotate while dictating and read ONE `churn=` line in `ime-lifecycle.log`; the repeated rebind cycle must read all zeros.
- [ ] **Automation retarget (agent)** — retarget the weekly automation to the `issue-janitor` agent; see HANDOFF.md.

## Device Notes
- `f6de166c` = OnePlus 7T — the owner's DAILY DRIVER. **The release build with `9bb3cb9` installed IN PLACE 2026-11-28** (`installRelease`, "Installed on 1 device."; config, permissions and `firstInstallTime` preserved) — carries #219 (all four waves + review fix), #222, #218, #217, #214, #153, #213.
- `R7SRB06XF5K` = SM-A032F — **the same `9bb3cb9` release installed IN PLACE 2026-11-28** ("Installed on 1 device."). The full study corpus (38 WAVs) stays at `files/samples/`.
- gregor up (2026-10-12): reachable via `htl.graf.priv.at` (nginx 80→443, OpenAI-compatible `/v1/`); the #129 research arms measured ~16–20 s per arm (probe 16 s end-to-end incl. join + shadow reference).
- Research results LIVE IN THE REPO (git-ignored, /tmp is unreliable): `samples/results/` (per-arm dirs + `winners.txt`), corpus `samples/corpus/` (38 WAVs, sha256-verified), retained pulls `samples/retained/` (3 research-run sessions, md5-verified). Derived CSV: `tools/seam-study/quality_results.csv` (git-ignored, regenerated per arm).
- F-Droid: the app is LIVE on f-droid.org — v1.5.1 (10501, suggested, added 2026-10-25) and v1.5.0 (added 2026-10-16); the 1.5.0 pickup watch is resolved (verified 2026-11-28).
