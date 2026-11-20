---
type: Handoff
title: Handoff
description: Open tasks for the next agent session.
tags: [knowledge, handoff]
generated: { by: opencode/knowledge-persistence, at: 2026-11-20T11:14:58Z }
status: stable
---

# Hand Off

Only open tasks. Everything from #177/#178 (and #179/#180/#181) is shipped and
closed, and #123 (loop guard) shipped and closed on 2026-11-20; what remains are
owner checks on the device. No agent work is pending.

1. [ ] **Owner: check the parked IME bar on the device.** Send a dictation → the
   bar stays visible and parked (▶ enabled, Send and Flush greyed out, Gear/X
   live, timer hidden) → no recording starts by itself. Tapping ▶ starts a fresh
   take. Focusing a different text field resumes the normal auto-start (#177).
2. [ ] **Owner: judge the icon-only actions on the device.** `ic_promote`
   (Material `publish`) and `ic_delete_outline` for "clear polished" carry no
   text label, and `ic_polish` (Material `auto_awesome`) sits next to the
   "Polish" label — all three must read correctly on a real screen. If they do
   not, that is a follow-up issue, not a code fix on the spot (#178, #181).
3. [ ] **#156 — the screenshots are stale after the Workbench redesign**
   ([#156](https://github.com/georgernstgraf/polished-recognition/issues/156),
   open). Re-capture from a current release build; the issue carries the
   authoritative shot list (`docs/img/ime-recording.png` + `settings.png`), do
   not duplicate it here.
