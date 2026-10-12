# Synthetic analyzer fixture (#129)

One hand-written arm directory mimicking what `run_quality.sh` collects per
arm (`arm.json` + `harness.json` + `stt-fragment.json` + the merged
`prepare_all.json`), with KNOWN expected outcomes. `python3 ../analyze.py
--selftest` asserts every value below; `python3 ../analyze.py .` runs the
unchanged #122 analysis on it.

## The scenario (3 fragments, 2 seams)

- **Seam 1 (fragment 0 → 1) — the UNCLEAN case.** The prepare record of
  fragment 0 (it describes the cut at fragment 0's END = this seam) has
  `Q = 2.0` (a shallow pause) → under `qgate` with `qualityMin = 10.0` it
  stitches (`stitched: true`, pre-roll + prompt composed). The joined
  transcripts still duplicate **"fieber und"** (SeamOverlap = 2 — a word
  straddling the cut or a surviving echo; the metric deliberately cannot
  tell those apart), and fragment 0's text ends without sentence-final
  punctuation while fragment 1 begins lowercase (word-split flag).
  → unclean via BOTH signals, Q = 2.0.
- **Seam 2 (fragment 1 → 2) — the CLEAN case.** Fragment 1's record has
  `Q = 40.0` (a solid pause) → bare (`stitched: false`, no pre-roll/prompt).
  No overlap, sentence-final punctuation before the boundary.
  → clean, Q = 40.0.
- **Trim false positive at fragment 2.** Its `echoTokens = 2` ("ist
  schlecht" was trimmed as an assumed echo of fragment 1's tail), but the
  shadow reference DID hear those words — the loop-masked diff attributes
  2 missing tokens to fragment 2's span → `trim_fp = 1` (flag for review).
- **Fragment 2 is the stop-tap tail** → its prepare record carries no
  contract block (the tail is never searched) — seam 2's evidence comes
  from fragment 1's record only.

## Expected values (all asserted by --selftest)

| field | value | why |
|---|---|---|
| fragments / seams | 3 / 2 | |
| join / shadow | 18 / 20 tokens | shadow contains the trimmed "ist schlecht" |
| ratio / reliable | 0.9 / true | inside [0.80, 1.25] |
| extra / missing / err100w | 0 / 2 / 10.0 | 2 missing at fragment 2 (the trim) |
| seam_overlap_total | 2 | "fieber und" |
| seams_unclean / word_splits | 1 / 1 | seam 1 only |
| q_median / q_p25 / q_p75 | 21.0 / 2.0 / 40.0 | ceil-index percentiles of [2, 40] |
| q_stitched_median / q_bare_median | 2.0 / 40.0 | |
| q_unclean_median / q_clean_median | 2.0 / 40.0 | THE qualityMin decision input |
| q_spearman | −1.0 | Q vs defect over the 2 seams |
| trim_fp | 1 | fragment 2 (echo 2, missing 2) |
| prepare_captured / expected | 3 / 3 | complete rotation capture |
| tokmirror_mismatch | 0 | the on-device `seamOverlapTokens` fields match the Python mirror |
| forced_seams / silence_seams | 0 / 2 | both cuts silence-aligned |

#122-path values that must stay unchanged: `class = fixture60`,
`duration = 60` (parsed from the label), `join_loops = 0`,
`shadow_loops = 0`.
