# Project State

Current status as of 2026-10-15: **#147 Phase 3 SHIPPED (harness build type REMOVED — app, CI workflow, cleanup symmetry, tooling docs, superseded Python study tooling; research runs live in `:analyzer`). 523 tests green. #144 CLOSED (fully delivered by the `:analyzer` — inspection = `scan`, Gegenprobe = `run` reference slices, provider performance = `report`; closing comment on #144). REMAINING: the OWNER's device return — install the release APK over the OnePlus harness build AND turn the #142 retention slider up himself (HARD prerequisite for the #129 natural-find track); #147 stays OPEN until then. #146 stays OPEN with exactly ONE unverified acceptance item: `pull` + `scan` against a real device (no device attached at session end 2026-10-15). #129's real qualityMin still owner-gated (bist dictations; natural find decides, corpus sweep double-checks — see the updated #129 issue body).**
## #129 first real-dictation scan (2026-10-15, on think)

- The A03 (`R7SRB06XF5K`, attached on think) carries **3 retained dictations from 2026-10-14** in the PRE-#142 format (`session.wav` + `fragments/`, no `session.json`) — created before the v1.4.1 in-place install and preserved by it. Corpus `corpora/a03-evidence-2026-10/` (bundle + wav kinds), run-id `a03-reanalysis-20261015`, production knobs, gregor reference in ≤300-s slices.
- Result: **zero real seam duplication** (`overlap=0` across 47 seams); 6/34 splitFlag heuristics on the 12.8-min session (documented false-positive-prone, no overlap backing); that session's gregor REFERENCE looped 40× (refLoops=40 vs joinLoops=0) — the loop-mask held the diff (`missing=275` instead of an explosion; #122 discipline proven on live data). Session 3 (5.2 min) has a REAL concentrated diff: fragment 4 missing 46 tokens, fragment 12 missing 25 (echo=0, not seam-related — Whisper derailment class, flagged for manual review; a #123 data point).
- **No qualityMin conclusion** — no real sick seams in this sample. The decisive source is the OnePlus (post-#142 session.json with per-seam Q, days of daily dictations, 30-d retention): once attached on think → `analyzer pull` → `scan`.
- **PullCommand hardened en route (`2f70422`):** (a) waitFor-before-read pipe deadlock — every output > ~64 KB (i.e. every session.wav) burned the 60-s timeout; output now drains on a background thread before `waitFor`; (b) directory entries were catted as files because `adb exec-out` does not propagate remote exit codes — `ls -1p` dir detection + one-level tree walk now handles both bundle formats (post-#142 `uploads/`+`session.json` AND pre-#142 `session.wav`+`fragments/`). 4 regression tests in `PullProcessTest`; 527 tests green.

## #147 Phase 3 — what shipped (2026-10-15)

- **`app/build.gradle.kts`:** the whole `create("harness")` build type deleted (incl. its doc comment + the 30-d retention buildConfigField); `debug` KEEPS its 30-d retention default (the owner's test path).
- **Deleted:** `app/src/harness/` (AndroidManifest + HarnessActivity — WavPcm already in :core since Phase 1) and `.github/workflows/harness.yml` (it fired on every main push and produced dead `harness-N` releases).
- **`scripts/cleanup-github-assets.sh`:** steps 2+3 back to `build`-only KEEP=7; header notes that `harness-N` releases/tags remain until pruned by hand or they age out (deliberate — historical releases stay fetchable).
- **`tools/seam-study/`:** the superseded Python device-driven tooling DELETED (`analyze.py`, `merge_prepare.py`, `run_matrix.sh`, `run_prompt.sh`, `run_prompt2.sh`, `run_quality.sh` — ported into `:core` LoopMask + `:analyzer` analysis/`run`); KEPT: `corpus/` (build.sh + samples.json + REPORT.md — Korpus-1 provenance + the build blueprint), `testdata/quality-fixture/` (provenance; the analyzer's own copy in `analyzer/src/test/resources/` is self-sufficient for tests), `quality_subset.txt`, `results.csv`, `generate_corpus_manifest.py`, `pull_retained.sh` (still the SAMPLES.md workflow; the analyzer `pull` command writes into `corpora/<name>/bundles/`), `SAMPLES.md` (updated). README rewritten: research runs go through `:analyzer` (`run` corpus mode, `scan` bundle Q-scan, `pull` adb, `compare`/`report`).
- **Comment-only cleanup:** harness wording reworded to "study/analyzer" semantics in PolishedRecognitionApp, VoiceSessionController, SettingsStore, TranscriptionPipeline, FragmentTranscriber, FragmentPreparer, SeamPolicy, SilenceQuality, WavPcm, SettingsStoreTest, analyzer/README + RunCommand — NO code changes.
- **Verification:** 523 tests green (332 :app + 169 :core + 22 :analyzer — the harness variant contributed none), `assembleRelease` green, `grep -ri harness` clean outside historical records + the deliberate tag comment, `./gradlew tasks` lists no `installHarness`/`assembleHarness` variants.

- **DEVICE RETURN (owner action, HARD prerequisite for #129):** the OnePlus (`f6de166c`, daily driver) STILL runs the CI harness APK (`harness-1`). The owner installs the release APK over it — `adb -s f6de166c install -r <release-apk>` (in place, same key, config preserved; download a CI build per the keyless-hosts rule) — AND turns the #142 retention slider up himself (release defaults to 0/off; the slider exists in every build). Until that happens there is NO retention on the daily driver. Only then: bist-class dictations → `analyzer pull` → `scan` Q-scan → Gegenprobe.

## #146 Phase 2 — what shipped (2026-10-15)

- **`:core` `pipeline/LoopMask`** (`314e0b1`): `loop_spans`/`compress_loops` ported verbatim from `tools/seam-study/analyze.py` (phrase runs len 1–8 repeated ≥4× → one `<loop>` sentinel BEFORE any diff). Pure function over token lists; doubles as the #123 production loop-guard core. 16 unit tests.
- **`:analyzer` CLI** (`047f5bc`): Kotlin-JVM application module on :core — commands `run` / `scan` / `report` / `compare` / `pull`, plain `when` dispatch, no arg-parsing deps. Runs the REAL production pipeline (FragmentPreparer + FragmentTranscriber + SttRequestRunner + SessionBundle) per corpus WAV; full-context reference via ≤300-s WavChunker slices; canonical `result.json` (format documented once in analyzer/README.md); session.json written per sample (the app's own shape). STT: gregor `http://10.8.0.16:11437/v1/` large-v3, strictly serial, token via `ANALYZER_STT_TOKEN` only.
- **Corpus convention**: `corpora/<name>/{manifest.json,wavs/,bundles/,results/}` — manifest committed, material git-ignored. Korpus #1 = `seam-study-01` (37 WAVs, 7 classes, 10–600 s, sha256-verified, confidence/provenance from REPORT.md). Manifest generator: `tools/seam-study/generate_corpus_manifest.py`.
- **Fixture regression**: `analyzer/src/test/resources/quality-fixture/` mirrors the #129 fixture; FixtureRegressionTest asserts every analyze.py --selftest value; SessionBundleRoundTripTest guards bundle-DTO drift.
- **Clean-baseline run** (`clean-baseline-20261015`, production knobs): 37/37 succeeded, 12.6 min wall, 32/37 reliable (5 reference collapses: en_interview ×2, phone_radio ×2, panel_multispeaker ×1 — the reference's VAD, exactly the documented caveat). Per class (reliable mean err/100w): de_interview 1.92, en_monologue_male 3.01, en_monologue_female 4.90, de_monologue_male 5.77, en_interview 18.54, phone_radio 18.97, panel_multispeaker 29.59 — overall 9.90. 243 fragments, 206 seams: 82 stitched under Q_GATE (Q<10), 124 bare, 42 unclean (overlap/split flags), 5 trim false positives. Results in `corpora/seam-study-01/results/clean-baseline-20261015/` (git-ignored).

## v1.5.0 release — what shipped (2026-10-14, #145)

- **Version:** 1.5.0 / 10500 (minor — #142 is a user-visible feature: the Retention slider replaces the #127/#132 build-type model). Bump commit `6db018e` (version + whatsnew, 369 chars — no 500-char problem this time).
- **Scope (user-facing):** #142 — Settings-Retention-Slider (0 = aus/nichts geschrieben bis 50 Jahre, dynamisches Label), forensisches Bundle `uploads/` + `session.json`, age eviction + 512-MB-Notbremse. Release-Default 0 (Privatsphäre) → für den Standard-Nutzer ändert sich in 1.5.0 gegenüber 1.4.1 NICHTS. #143 ist release-neutral (shadow/full-context waren per #125-Gate schon OFF).
- **CI:** ALLE 4 Workflows green — release.yml `38634013138` (Play alpha, whatsnew committed `00897222044843544691`, im Log verifiziert — #137-Regel), fdroid-apk.yml `38634012859`, build.yml `38633927767`, harness.yml `38633927800`. GitHub release `v1.5.0`: `app-release.aab` (1,891,674 B) + `polished-recognition.apk` (2,418,681 B), ist GitHub "Latest".
- **Ship notices:** #142 (Slider in allen Varianten, Default 0 in Release) + #143 (harness: genau EIN STT-Request pro Diktation ab `3c4f90a`).
- **Watch:** F-Droid pickup of 1.5.0 (~3–5 days after the 2026-10-14 tag).

## #139 — what shipped (`e1fdff6`, 2026-10-14)

- **`.github/workflows/harness.yml`** — exact mirror of build.yml: `workflow_dispatch` + push on main; temurin 21 + Robolectric cache; keystore decode from the SAME `RELEASE_*` secrets; `./gradlew assembleHarness` only (NO test run — the suite runs in build.yml); artifact `app-harness` (retention-days 7); release tag `harness-<run_number>` (`make_latest: false`, `fail_on_unmatched_files: true`); cleanup script invoked after.
- **`scripts/cleanup-github-assets.sh`** — steps 2+3 refactored into a symmetric `for prefix in build harness` loop: KEEP=7 PER prefix (releases incl. page+tag deletion, orphan tags); artifact pruning already generic; `v*` untouched. Dry-run verified locally (no deletions pending).
- **Verification:** first harness run `38572385302` green → release `harness-1` with `app-harness.apk` (8.3 MB); parallel build.yml run `38572385128` green; APK certificate verified via `apksigner`: `62f9d7b0…a76a85` — identical to the release cert → in-place installs safe. APK staged locally at `/tmp/opencode/polished-139/app-harness.apk`.
- **Pending (owner devices):** OnePlus `f6de166c` ← CI harness APK; A03 `R7SRB06XF5K` ← signed build-448 release APK (`/tmp/opencode/polished-138/app-release.apk`); then the #138 send-check (no rejection toast, clipboard untouched).

## Epic #141 — #142 shipped (`e150a74`, 2026-10-14)

- **The bundle:** `files/audio/<sessionId>/` = `uploads/` + `session.json` ONLY. `SessionBundle` (pure JVM, Gson, fragments as a sorted JSON ARRAY via a serialization DTO): per-fragment boundaries + START-seam decision/quality block (mirrors the prepare contract), uploadFile/preRollMs/sttRaw/sttTrimmed/echoTokens/prompt/sttError; session-level provider info, the LLM block (via the pipeline's `var onOutcome` hook — no signature change, no mock churn), and the delivery outcome (`inserted` | `error`).
- **Retention:** `SettingsStore.evidenceRetentionSeconds` (0 = off; legacy `audio_evidence` migrates ON → 30 d), `EvidenceRetention` log-stops 1 h → 50 years + labels, SeekBar + live label in Settings; `AudioEvidenceStore` age-eviction (#132 prefix, mtime fallback) + invisible 512-MB storage valve (oldest-first, newest survives) + app-start `evict()`; defaults: release 0, debug/harness 30 d. `LOG_ROTATION_DEPTH` unified at 32.
- **Bonus fix:** bare fragment uploads (no pre-roll) are retained NOW — the pre-#142 code skipped the evidence copy whenever the upload WAS the fragment file (WAV mode lost its audio evidence entirely).
- Tests 485 green (SessionBundleTest, the eviction matrix, the migrations, slider mapping; the ring-buffer tests replaced). Next: #144 (the PC analyzer — #129's offline analysis path).
## #138 — what shipped (`d0e7dbe`, 2026-10-14)

- **The regression:** after #133 the successful send ends with `switchInputMethod` → IME destroyed → `onDestroy()` IDLE branch → `controller.cancel()` → the never-cleared transcriber success cache was salvaged (`CancelledWithTranscripts`) — error toast + clipboard copy of already-inserted text. Owner report after v1.4.0 daily use.
- **Fix A (root):** consume-once — the success branch of the transcribe job (`VoiceSessionController.stopAndTranscribe`) calls `discardFragmentSession()` (salvage return ignored) after `clearPrepared()`, BEFORE `deliver()`; the transcriber lazily rebuilds per session (unchanged).
- **Fix B (invariant):** `cancel()` salvages only when `state != IDLE` on entry; IDLE cancel = pure teardown no-op (spool/snapshot cleanup preserved). The genuine #113 salvage paths (cancel from RECORDING/PAUSED/PROCESSING) are untouched.
- **Clipboard policy (owner decision):** after a successful insertion the clipboard stays UNTOUCHED — no salvage write, no clearing (clearing would destroy the user's prior clipboard content).
- Tests +1 → **481 green** (regression: success → Completed → teardown cancel emits nothing); `assembleRelease` + pre-push green.

## v1.4.0 release — what shipped (2026-10-14, #137)

- **Version:** 1.4.0 / 10400 (minor per owner). Bump commit `f6044f8` + trimmed whatsnew `8e2b966` + temp-workflow removal `0956228`.
- **Scope (user-facing):** live fragment transcription with silence-aligned seams + prompt carry-over + Q-gated stitching + adaptive sizing + 5-min upload cap (#116–#129); auto-switch back to the typing keyboard after send (#133); different-field fresh start (#134); disk-spooled capture — session RAM O(1), long dictations no longer heap-bounded (#135/#136).
- **CI:** release.yml `38474591510` green (GitHub release AAB+APK; Play step FAILED silently — continue-on-error), fdroid-apk.yml `38474591509` green, build.yml `38474479620` green. Recovery: play-reupload run `38475216594` → `Successfully committed 03948756826535750989`. Temp workflow deleted.
- **Ship notices:** comments on all 23 issues referenced since v1.3.6 (closed + open, open ones left open with reason) — owner request.

## #135/#136 — what shipped (`c5f2046` + `2c362db`, 2026-10-13)

- **The crash:** A03 (release), ~24-min dictation → `OutOfMemoryError` (46,438,416-B allocation) in `AudioRecorder.copyPcmRange` → `BAOS.toByteArray` on the live fragment-worker thread → process death, dictation lost. Root cause: the ranged read copied the WHOLE session buffer per fragment (+#135: `stopPreservingBuffer` stacked TWO full copies at stop, `snapshotPcm` a third).
- **Stage 1 (`c5f2046`):** in-RAM block chain + `WavWriter.writeHeader` (stop WAV assembled with one copy). **Stage 2 (`2c362db`, owner directive):** recorder file-backed (`cacheDir/recording.pcm` spool, one RAF + lock) — `copyPcmRange` = seek+read, `PcmSource` contract unchanged (`FragmentPreparer` untouched), `stopSessionWavFile(): File` streams the WAV, snapshot = disk copy, restore = RENAME onto the spool. Controller threads `File` through runTranscription/prepareAudioFiles/retainAudioEvidence; legacy WavChunker branch materializes bytes only for empty recordings; stop WAV deleted after retention/cancel.
- **Result:** session RAM O(1) — dictation length no longer heap-bounded; evidence fidelity unchanged. Suite + pre-push green; `assembleRelease` + `compileHarnessKotlin` green; deployed to both devices 2026-10-13 23:48.

## #134 — what shipped (`c782e88`, 2026-10-13)

- **Entry rule change:** a PAUSED leftover entered on a DIFFERENT field/app is no longer auto-resumed — it is discarded through the normal cancel path (#113 salvage keeps already-transcribed fragments in the clipboard) and a fresh empty recording auto-starts. Same-field return keeps auto-resume (#65 pause→detour→return flow intact); the discriminator is the existing `RotationGate` same-field identity.
- **Shape:** `AutoStartPolicy` collapsed into one pure `entryAction(state, sameField, permission)` → `RESUME | FRESH_START | NONE` (the two booleans are gone); the IME tail maps it — cancel-iff-PAUSED then `startIfPermitted()`. `ImeStartDecision` decision logic untouched (only its PROCEED doc updated); FREEZE (live sessions, rotation, restored snapshots) and PROVISIONAL_FREEZE (same-field PAUSED delayed resume) paths unchanged; the delegated RecognitionService route already always started fresh.
- Tests: `AutoStartPolicyTest` rewritten for the matrix (9 cases) — suite green, pre-push green.

## #133 — what shipped (`07d3006`, 2026-10-13)

- **Behaviour:** a successful send no longer leaves the voice keyboard as the default IME — the IME resolves the last-used typing keyboard (the same target the keyboard-switch button resolves) and `switchInputMethod`s to it, then hides. Default ON (owner decision, deviating from the issue's opt-in/OFF proposal), toggleable in Settings → Processing ("Switch back to typing keyboard after send").
- **Decision points:** new `service/PostSendSwitchPolicy` (`SWITCH`/`HIDE_ONLY`); null target (Polished is the only enabled IME) = silent hide — NOT the settings screen the manual button falls back to (owner decision). Fires ONLY in the `Completed` success branch (which precedes the controller's `IDLE` transition); cancel/pipeline-failure keep the voice keyboard; a failed switch degrades to the plain hide.
- `SettingsStore.switchBackAfterSend` (key `switch_back_after_send`, default `true`); no SettingsStore constructor default needed (unlike `audioEvidence` — not build-type dependent).
- Tests: +3 policy, +2 store → suite **479 green**; `assembleRelease` green. The #133 pre-push run hit the unidentified flake ONCE (green on immediate re-run — see PITFALLS; evidence lost to the re-run, capture-before-rerun rule stands).

## #132 — what shipped (`a431a03`, 2026-10-12)

- **Retention capacity is a BUILD-TYPE property:** `AUDIO_EVIDENCE_MAX_SESSIONS` (int) / `AUDIO_EVIDENCE_MAX_BYTES` (long) buildConfigFields — release 3 / 50 MB (the #127 defaults, byte-identical behaviour), debug+harness **99 / 1 GB**; `PolishedRecognitionApp` passes them into `AudioEvidenceStore` (whose own defaults stay as fallback). Rationale: problematic stitches are rare; the owner dictates in daily life and analyses days later, so the corpus must bridge multi-day gaps. The 1-GB byte cap still binds before the session cap on long dictations (by design — storage notary).
- **Timestamped session IDs:** top-level `internal fun newSessionId(now: Date = Date())` in `VoiceSessionController.kt` → `yyyyMMdd-HHmm-<8hex>` LOCAL time + 8-hex uuid suffix (unique within the minute, one path/JSON-safe token); both creation sites use it. Retained evidence dirs are recognisable by dictation date/time at a glance — and harness-run sessions from different runs are distinguishable without log correlation.
- ~~`sessionId` in EVERY `stt-shadow` row~~ — SUPERSEDED (#143): the shadow streams are REMOVED; session correlation now lives in `session.json` (#142).
- **`LOG_ROTATION_DEPTH`:** release 9, debug/harness 99 rows per stream (#132) — [ACTIVE until #142] the build-type distinction loses its role with the forensic bundle; the plan is a unified small depth.
- **`pull_retained.sh --last N`:** device-side `ls -t` selection of the N newest sessions (full listing still shown); safe routine pulls once 99 sessions can accumulate.
- Tests: +1 (`newSessionId` format/prefix), shadow test extended (`sessionId` assertion) — suite green incl. pre-push; `compileHarnessKotlin` + `assembleRelease` green. **Deployed:** harness APK in place on both devices (A03 `R7SRB06XF5K` 15:52, OnePlus `f6de166c` 15:53), provider configs preserved (same applicationId + signing key).

## #129 research run — results (2026-10-12)

- **Winners (`samples/results/winners.txt`):** `axisK = 0.75` (adoptable — moves the axis above the fricative band, strictly safer at zero cost on clean audio; provisional was 0.65), `qualityMin = 40.0` (NOT adoptable as a decision — on clean audio any threshold ≥ the Q floor scores identically; the knob only has meaning where seams are sick), `axisC = 100.0` / `marginKMs = 150` / `searchSpan = lateHalf` (provisional defaults confirmed; flat curves — stability knobs as predicted from the #122 null pattern).
- **Evidence chain:** probe arm green 16 s end-to-end; 222 CSV rows (`tools/seam-study/quality_results.csv`, derived, git-ignored); per-seam Q evidence complete on ≤60-s subset samples; 0 seam overlap / 0 word splits / 0 trim-fp across all arms; `phone_radio_30s` unreliable at every level (hard class, not a knob effect).
- **Runner fixes during bring-up:** health check accepts 401/403 as reachable (`b04cbf3` — the auth token lives on the phone); `samples/results/` + derived CSV git-ignored (`2216942`, `e1c1c36`).
- **Owner dependency (the epic's critical path):** bist-class dictations on EITHER device (both run the harness build with retention ON — sessions auto-land in `files/audio/`, the ring buffer keeps 99/1 GB since #132, so multi-day gaps no longer lose evidence; pull with `pull_retained.sh [--last N]`): German sentences with word-final /st/ + pause near a ~10–20-s boundary ("…informiert **bist**" ⏸ "Jedenfalls…", plus ist/kannst/musst/erst). THEN: `--full-factorial` (3×3 axisK×qualityMin on the full corpus) → parameter proposal.

## #128 — what shipped (`c4c14ab`)

- `SeamPolicy.Q_GATE` (controller default; ALL/FORCED_ONLY/OFF frozen A/B arms; harness `policy` extra accepts `qgate`). Boundary: STRICT `Q < qualityMin` (Q == min → bare); null Q (legacy manifests, fixed cuts) stitches; forced cuts (Q = 0) always stitch.
- `FragmentPreparer.Entry.seamQ` (manifest-round-tripped; Gson omits nulls → legacy manifests parse); `seamStitchedAt` = THE decision (pre-roll composition, 5-arg hook / `Committed.stitched`, `prepare.json`'s now-real `stitched`).
- `FragmentTranscriber` dropped its own `SeamPolicy` — prompt iff `CommittedFragment.stitched` (+ `!promptDisabled`); trim unchanged (keys on the pre-roll file). Pre-roll/prompt/trim can never disagree.
- `VoiceSessionController.fragmentQualityMin` (PROVISIONAL 10.0 = `FragmentPreparer.DEFAULT_QUALITY_MIN`; #126 fixtures bracket it: solid Q ≈ 40 bare, whisper Q ≈ 2.26 stitch; #129 measures the real value).
- Tests +8 → **473 green** (5 skipped integration as always); fixture numbers pre-verified against the #126 Python mirror (`/tmp/opencode/sq-mirror/predict_128.py`). Pre-push suite ran clean first try (2048m heap since #131 — no flake).

## #126 — what shipped (`7ff3800`)

- `audio/SilenceQuality` + `SearchSpan` (pure JVM): per-fragment M/P5-floor/max over the high-passed search span (`PcmConditioner.highPassOnly` — the one-pole extracted, normalization-free), axis `X = k·M + c`, deficit curve, candidates, Q, margin-clamped area-centroid cut; no candidate → forced quietest-frame cut (Q = 0). `candidateFound ⇔ Q > 0` is the interim `silenceAligned` mapping (#128 replaces it with `qualityMin`).
- `FragmentPreparer`: default search span = LATE half (owner decision); the decision always waits for the full window; `energyFallbackEnabled` inert; `prepare.json` carries the contract block `{M, min, max, X, Q, centroidMs, stitched:false}` instead of `silenceAligned`.
- `VoiceSessionController` plumbs `fragmentAxisK`/`fragmentAxisC`/`fragmentMarginKMs`/`fragmentSearchSpan` — PROVISIONAL defaults 0.65 / 100.0 / 150 ms / lateHalf; #129's research pass determines the real values (no Settings UI).
- Tests +14/−11 → **465 green** (5 skipped integration tests, as always); all fixture expectations pre-verified against an exact Python mirror of the metric (`/tmp/opencode/sq-mirror/`).
- Whisper-regime finding: a 6-dB band at whisper level is NOT the forced path with these provisionals (the axis threads/overshoots the band → one shallow candidate, Q ≈ 2.3 — the issue's "Q-gate arms stitching" case); the forced path needs a tight band with `M ≥ c/(1−k) ≈ 286`. Both regimes unit-tested; #129 measures the real boundary.

## #127 — what shipped

- `pipeline/AudioEvidenceStore` (pure JVM, injectable dir + clock): copy-only retention of `session.wav` + `fragments/` + `uploads/` into `<externalFilesDir>/audio/<sessionId>/`, ring buffer ≤ 3 sessions / ≤ 50 MB (newest session always survives), tmp+rename writes, never throws.
- Capture points: controller `retainAudioEvidence()` after `runTranscription` before `clearPrepared()` (success AND failure); `FragmentTranscriber` upload `finally` before `delete()`. `cancel()`/`flush()` = deliberate discard, no retention.
- Gate: `audio_evidence` toggle (`SettingsStore.audioEvidenceDefault` ← `BuildConfig.AUDIO_EVIDENCE_DEFAULT`: false release / true debug+harness); privacy hint in Settings; harness wires the store + gate too.
- Tests +18 → suite 462 green (now 465 after #126).

## #124/#125 — what shipped (`3647554`)

- `VoiceSessionController.resolvedSearchBytes()`: with a `fragmentSizeProvider` wired (production), `search = max(fragmentSearchBytes, resolvedFragmentBytes())` → fragment spans `[S, 2S]`; explicit injected values keep their exact semantics (`0` = legacy fixed cuts); the harness dropped its provider wiring so its explicit `searchSeconds` stays authoritative.
- `SilenceCutter.quietestFrameCut()` + `FragmentPreparer.energyFallbackEnabled` (default true): on a silence-free FULL window, cut at the minimum-energy 10-ms frame center in the late half `[1.5S, 2S]`, tagged FORCED (`FORCED_ONLY` still applies pre-roll + prompt). Harness knob `--ez energyFallback`.
- ~~`BuildConfig.SHADOW_STT` / `FULL_CONTEXT_AT_STOP`~~ — REMOVED in #143 (the fields, the wiring, the streams; `SttEndpointPolicy` was already deleted in #125).
- Tests +8 (444 green); `assembleRelease` + `compileHarnessKotlin` green.

## Energy-fallback extrapolation check (harness A/B, OnePlus → gregor)

Frozen config (frag=10, search=10=S, forced, wav, raw, shadow reference), 8 runs all green, loop-masked:

| arm | frags | forced seams | err/100w |
|---|---|---|---|
| search = 2 s (study config) | 68 | 3 | 6.2 |
| search = S = 10 s (shipped) | 52 | 0 | 4.1 |

The adaptive search eliminates forced seams on Tier-1 classes; the energy fallback NEVER fired there (both arms byte-identical — every 10-s window contains a qualifying pause). The fallback's regime is continuous/background-noise audio, where the shadow reference is itself unreliable — it stays validated at the unit-invariant level. Runs + analysis: `/tmp/opencode/seam-run/results/EF_*`, runner `run_energyfallback.sh`.

## Current Focus

Epic #141 (forensisches Session-Bundle) is the active workstream. #143 (shadow/full-context removal) is DONE (`3c4f90a`, 475 tests green). Next: **#142** (uploads+session.json bundle + the 0–50-years Retention slider) then **#144** (PC analyzer — which becomes #129's offline analysis path: `pull_retained.sh` → bundles → gregor → diff; the old join-vs-shadow staged-run path is gone). #123 (loop guard) stays queued. **The fragment join is the ONLY delivery path (the #125 full-context reversal is now moot — the strategy is removed entirely).**

## Pending
- [ ] **#147 DEVICE RETURN (owner, HARD prerequisite for the #129 track)**: install the release APK over the OnePlus harness build — `adb -s f6de166c install -r <release-apk>` (in place, same key, config preserved; CI download per the keyless-hosts rule) — AND turn the #142 retention slider up on the OnePlus himself (release defaults to 0/off). Until then the daily driver has NO retention. #147 closes after this.
- [ ] **#138 send-check (owner)**: NOW UNBLOCKED — v1.4.1 on the A03, harness-1 on the OnePlus (both carry the fix; the OnePlus returns to release with the #147 device return): send a dictation → text inserts → NO "Diktierung verworfen" toast, clipboard untouched.
- [ ] **#129 FINAL (gated on owner dictations)**: after the device return — bist-class dictations on either device → bundles land in `files/audio/` → analysis via the analyzer path (`analyzer pull` → `scan` Q-scan → offline Gegenprobe, loop-masked) → parameter proposal (adopt `axisK=0.75`; the REAL `qualityMin` from sick-seam data) → epic #130 close-out.
- [ ] **#142 on-device spot check (owner)**: after the bundle rework — `files/audio/<sessionId>/` shows ONLY `uploads/` + `session.json`; Retention slider visible with dynamic label; 0 = nothing retained.
- [ ] **Owner dictation check (~25 s)** on the release build: `session.json` cadence (~10 s nominal, growth ≤ 2S) with REAL `stitched` values, stop→raw ≤ ~2.4 s worst case, Settings fragment field blank. NOTE: release builds still carry the PROVISIONAL defaults (0.65/100/10.0) until #129 concludes.
- [ ] **#120 re-check (owner)**: > 5-min dictation on the release build → `stt-upload`/`stt-latency` every `durationMs ≤ 300_000`, no mid-text cut-off → then close #120.
- [ ] **#123 loop guard** — filed with the #122 evidence; queued behind the seam work.
- [ ] **#118 Phase B** (delivery policy), **#112/#113** owner items, **#116 Phase 2** owner decisions residue (auto-size floor now 10 s; the rest resolved with the #143 shadow removal), **#110**, **#101**, **#99/#101/#75**, **#74 marketing**, **#109** visual check.
- [ ] **F-Droid 1.5.0 pickup watch** (tag `v1.5.0` = 10500, pushed 2026-10-14; ~3–5 days after the tag — supersedes the 1.4.1 watch).

## Device Notes
- `f6de166c` = OnePlus 7T — **harness-1 CI APK installed 2026-10-14** (DEBUGGABLE verified, `run-as` works, versionName 1.4.0/10400 — harness builds don't bump; carries #138 + the removal of shadow/full-context, so every dictation now runs ONE STT request, not two — gregor decongested). The owner's DAILY DRIVER. To return to release: `adb -s f6de166c install -r` a release APK (same signing key, in-place).
- `R7SRB06XF5K` = SM-A032F — **v1.4.1 (10401) release APK installed 2026-10-14** (in-place, config + RECORD_AUDIO preserved; the OOM-crash device now carries the crash fix + #138): `run-as` dead, still release retention (3/50 MB) as shipped in v1.4.1 — #142 replaces it in v1.5.0 (the device picks it up with the next update), no harness knobs. The full study corpus (38 WAVs) stays at `files/samples/`.
- gregor up (2026-10-12): reachable via `htl.graf.priv.at` (nginx 80→443, OpenAI-compatible `/v1/`); the #129 research arms measured ~16–20 s per arm (probe 16 s end-to-end incl. join + shadow reference).
- Research results LIVE IN THE REPO (git-ignored, /tmp is unreliable): `samples/results/` (per-arm dirs + `winners.txt`), corpus `samples/corpus/` (38 WAVs, sha256-verified), retained pulls `samples/retained/` (3 research-run sessions, md5-verified). Derived CSV: `tools/seam-study/quality_results.csv` (git-ignored, regenerated per arm).
