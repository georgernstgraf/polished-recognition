---
type: State
title: Project State — Build, Release and Distribution
description: Gradle, CI, lint gates, releases and what shipped in them.
tags: [knowledge, state, build-release]
generated: { by: opencode/knowledge-persistence, at: 2026-11-25T12:00:00Z }
status: stable
---

# Project State — Build, Release and Distribution

Moved out of `STATE.md` in #206; sections are verbatim, newest first. The current status line, focus and pending items stay in `STATE.md`.

## #187 — the release-build output is warning-free (2026-11-20, extended 2026-11-22)

- **2026-11-22 — AAPT:** `slice_seconds_hint`/`search_percent_hint` (and, for consistency, `search_percent`, `search_percent_error`, `compress_audio_hint`) carry literal `%`, so AAPT read them as format strings and printed "Multiple substitutions specified in non-positional format" on every `merge*Resources`. They now declare `formatted="false"` (none is used with format arguments) — the message is gone and the fatal `StringFormatInvalid` it duplicated is gone with it.
- **2026-11-22 — drift that CI could not see:** new *warnings* never fail `lintDebug`, so two findings from the #172 work sat unseen: `Autofill` on `app_wrap_width` (now `importantForAutofill="no"`, the layout's existing convention) and `SetTextI18n` on the Workbench FIFO prefill (now `String.format(Locale.ROOT, "%d", …)`; a localized separator would break the later `toInt()`).
- **2026-11-22 — the baseline's version entries:** `AndroidGradlePluginVersion`/`GradleDependency`/`NewerVersionAvailable` are update notices whose MESSAGE changes whenever upstream publishes (the baseline's 9.9.1/4.17 entries were reported "fixed" while the same notices came back as new). They are now `severity="ignore"` in `app/lint.xml`, never baselined; the owner keeps dependency upgrades manual.
- **OBSOLETE since #193 (2026-11-24) — there is no baseline any more, so the message cannot appear.** Kept for the record: **2026-11-22 — accepted, not fixed:** `installRelease`/`assembleRelease` still print "77 errors/warnings … not found" + "Creation variant: lint, Current variant: lintVitalRelease". The baseline is written by the global `lint` task (`variant="all"`), while `lintVitalRelease` runs fatal checks only — AGP 9.1.1 has no per-variant baseline. Owner decision: keep `checkReleaseBuilds` at its default and the fatal gate, accept the notes.
- **Baseline:** regenerated (`:app:updateLintBaseline`) — 91 → **77 entries** (1 error `MissingPermission` + 76 warnings). `lintDebug` now says "No errors or warnings (and 1 error, 76 warnings filtered)", with no "not found" line.
- **Was (2026-11-20):** a plain `installRelease` printed four `w:` lines — `isClosedForSend` ("delicate API", `:core`), a `String?`-to-`String` Java-interop mismatch on `File(dir, sessionId)` and an `Unchecked cast` in `readManifest` (`:app`), plus the deprecated `unsafeCheckOpNoThrow`.
- **`FragmentTranscriber`:** an own `@Volatile closed` set in `cancel()` (the only `channel.close()` site) replaces the delicate query; `drain()` checks it instead. Semantics unchanged — the existing `drain after cancel returns an empty snapshot` test is the cover (a regression HANGS it, so the test now says so).
- **`VoiceSessionController`:** `buildPreparer(sid: String)` + a small `ensureSessionId()` put the "preparer dir = journal key = bundle id" invariant into the type instead of leaving a `String?` to Java interop.
- **`prepared/<hash>/manifest.json`:** parsed through `ManifestJson` (all fields nullable, `bytes` as `Long?`) and validated all-or-nothing; `writeManifest` writes through the same DTO, so the file stays byte-identical (`{"files":[{"name":…,"bytes":…}]}`). New test: a manifest with ONE nameless entry invalidates the list and rebuilds — the fixture's `stale.wav` would have been handed to the pipeline by a partial trust, so the test distinguishes the two behaviours.
- **`checkOpNoThrow`** in the mic guard (the replacement has been public since API 29; `minSdk` is 30), KDoc in `MicAccessPolicy` updated.
- **Left alone deliberately:** the two `Unchecked cast` warnings in `VoiceSessionControllerTest` (Gson-map assertions over `session.json`) — test-only, absent from the release compile.
- **Tests:** 806 → **807** (one new controller test; the transcriber test only gained the #187 comment). No version bump.

## v1.5.1 release — what shipped (2026-10-19, #151)

- **Bump:** `versionCode 10501` / `versionName 1.5.1`, bump commit `a9fc136`, tag `v1.5.1` pushed separately. Whatsnew 404 chars (Play limit 500).
- **Scope vs v1.5.0:** 21 commits, 95 files — #148 (STT timing fields), the `:analyzer` CLI (#146/#149, `:core` extraction), harness removal (#147), and the save-path robustness fix.
- **Save-path robustness (`f3cbae0`, from the #150 triage):** every early return in `SettingsActivity.saveAndClose()` now calls `saveAborted(check)` → Toast `R.string.settings_save_blocked` naming the check + a `settings-save-aborted` line in `logs/settings.json` (via `jsonLogger`) + logcat. Checks: `app-wrap-width`, `stt-model-empty`, `stt-model-not-in-list`, `llm-model-empty`, `llm-model-not-in-list`, `wrap-width`, `fragment-seconds`.
- **Tests:** new `app/src/test/.../ui/SettingsActivityTest.kt` (6 Robolectric tests): each abort names its check in toast + log + field error, the screen stays open, the stored provider config is never deleted; happy path saves both providers and logs nothing.
- **CI:** release.yml, fdroid-apk.yml, build.yml all green; **Play upload verified in the log: `Successfully committed 00649311535496813694`**; GitHub release `v1.5.1` AAB 1,900,684 B + APK 2,431,254 B.
- **Device:** `ANDROID_SERIAL=f6de166c ./gradlew installRelease` on think → 1.5.1/10501 in place, non-debuggable, `firstInstallTime` 2026-08-22 preserved, RECORD_AUDIO granted.
- **#150 stays OPEN:** triaged but not reproducible on the owner's OxygenOS device (daily use of the same save path). Triage questions posted (toast/field error, do other settings survive a restart, logcat from the save tap, was the model actually selected). Key analytical finding: the provider write in `saveAndClose()` precedes only the `wrap-width`/`fragment-seconds` checks — an abort at an earlier check leaves the provider untouched, an abort at a later one has already persisted it, so "empty after restart" ⇒ an EARLY abort (or lost prefs), not a late one.

## #147 Phase 3 — what shipped (2026-10-15)

- **`app/build.gradle.kts`:** the whole `create("harness")` build type deleted (incl. its doc comment + the 30-d retention buildConfigField); `debug` KEEPS its 30-d retention default (the owner's test path).
- **Deleted:** `app/src/harness/` (AndroidManifest + HarnessActivity — WavPcm already in :core since Phase 1) and `.github/workflows/harness.yml` (it fired on every main push and produced dead `harness-N` releases).
- **`scripts/cleanup-github-assets.sh`:** steps 2+3 back to `build`-only KEEP=7; header notes that `harness-N` releases/tags remain until pruned by hand or they age out (deliberate — historical releases stay fetchable).
- **`tools/seam-study/`:** the superseded Python device-driven tooling DELETED (`analyze.py`, `merge_prepare.py`, `run_matrix.sh`, `run_prompt.sh`, `run_prompt2.sh`, `run_quality.sh` — ported into `:core` LoopMask + `:analyzer` analysis/`run`); KEPT: `corpus/` (build.sh + samples.json + REPORT.md — Korpus-1 provenance + the build blueprint), `testdata/quality-fixture/` (provenance; the analyzer's own copy in `analyzer/src/test/resources/` is self-sufficient for tests), `quality_subset.txt`, `results.csv`, `generate_corpus_manifest.py`, `pull_retained.sh` (still the SAMPLES.md workflow; the analyzer `pull` command writes into `corpora/<name>/bundles/`), `SAMPLES.md` (updated). README rewritten: research runs go through `:analyzer` (`run` corpus mode, `scan` bundle Q-scan, `pull` adb, `compare`/`report`).
- **Comment-only cleanup:** harness wording reworded to "study/analyzer" semantics in PolishedRecognitionApp, VoiceSessionController, SettingsStore, TranscriptionPipeline, FragmentTranscriber, FragmentPreparer, SeamPolicy, SilenceQuality, WavPcm, SettingsStoreTest, analyzer/README + RunCommand — NO code changes.
- **Verification:** 523 tests green (332 :app + 169 :core + 22 :analyzer — the harness variant contributed none), `assembleRelease` green, `grep -ri harness` clean outside historical records + the deliberate tag comment, `./gradlew tasks` lists no `installHarness`/`assembleHarness` variants.

- **DEVICE RETURN EXECUTED (2026-10-15, on think, local build):** the OnePlus (`f6de166c`, daily driver) now runs **1.5.0/10500 release** — installed in-place via `ANDROID_SERIAL=f6de166c ./gradlew installRelease` (checkout `dcd704d`, keystore material in-tree, key == CI, config preserved, non-debuggable verified). The 2026-10-14 "keyless/no local signing" facts were claw-only and are corrected (DECISIONS 2026-10-15); the "exclusively CI" deployment rule was an agent overgeneralization of a one-time owner availability statement. **Owner raised the #142 retention slider to 6 MONTHS** — stated use of the retained data: server timing + stitching/seam analysis (#129 Track B + the upload/STT timing records in session.json). Next: bist-class dictations → `analyzer pull` → `scan` Q-scan → Gegenprobe.

## #146 Phase 2 — what shipped (2026-10-15)

- **`:core` `pipeline/LoopMask`** (`314e0b1`): `loop_spans`/`compress_loops` ported verbatim from `tools/seam-study/analyze.py` (phrase runs len 1–8 repeated ≥4× → one `<loop>` sentinel BEFORE any diff). Pure function over token lists; doubles as the #123 production loop-guard core. 16 unit tests.
- **`:analyzer` CLI** (`047f5bc`): Kotlin-JVM application module on :core — commands `run` / `scan` / `report` / `compare` / `pull`, plain `when` dispatch, no arg-parsing deps. Runs the REAL production pipeline (FragmentPreparer + FragmentTranscriber + SttRequestRunner + SessionBundle) per corpus WAV; full-context reference via ≤300-s WavChunker slices; canonical `result.json` (format documented once in analyzer/README.md); session.json written per sample (the app's own shape). STT: gregor `http://10.8.0.16:11437/v1/` large-v3, strictly serial, token via `ANALYZER_STT_TOKEN` only.
- **Corpus convention**: `corpora/<name>/{manifest.json,wavs/,bundles/,results/}` — **the WHOLE `corpora/` tree is git-ignored since 2026-10-15 (owner privacy decision: real-life, probably private, spoken words; nothing under corpora/ is ever committed, including manifests)**. Korpus #1 = `seam-study-01` (37 WAVs, 7 classes, 10–600 s, sha256-verified, confidence/provenance from REPORT.md). Manifest generator: `tools/seam-study/generate_corpus_manifest.py`.
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

## v1.4.0 release — what shipped (2026-10-14, #137)

- **Version:** 1.4.0 / 10400 (minor per owner). Bump commit `f6044f8` + trimmed whatsnew `8e2b966` + temp-workflow removal `0956228`.
- **Scope (user-facing):** live fragment transcription with silence-aligned seams + prompt carry-over + Q-gated stitching + adaptive sizing + 5-min upload cap (#116–#129); auto-switch back to the typing keyboard after send (#133); different-field fresh start (#134); disk-spooled capture — session RAM O(1), long dictations no longer heap-bounded (#135/#136).
- **CI:** release.yml `38474591510` green (GitHub release AAB+APK; Play step FAILED silently — continue-on-error), fdroid-apk.yml `38474591509` green, build.yml `38474479620` green. Recovery: play-reupload run `38475216594` → `Successfully committed 03948756826535750989`. Temp workflow deleted.
- **Ship notices:** comments on all 23 issues referenced since v1.3.6 (closed + open, open ones left open with reason) — owner request.


**Also stored elsewhere** (ambiguous placement, cross-referenced per #206):
- [ime-field.md](ime-field.md) — „#158 — UI contrast gate COMPLETE (2026-11-14; #161–#164 shipped)"
