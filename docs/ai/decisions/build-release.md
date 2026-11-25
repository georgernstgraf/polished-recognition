---
type: Decisions
title: Decisions — Build, Release and Distribution
description: Gradle and toolchain, signing, CI, lint gates, versioning, the Play and F-Droid channels, listing assets and the repo docs.
tags: [knowledge, decisions, build-release]
generated: { by: opencode/knowledge-persistence, at: 2026-11-25T18:20:00Z }
status: stable
---

# Decisions — Build, Release and Distribution

Moved out of `DECISIONS.md` in #206; entries are verbatim. New decisions in this area are appended here.

## 2026-11-25: #205 — GIT_HASH/VERSION_DISPLAY statisch, CI übergibt die echten Werte

Der About-Screen zeigte den per Commit generierten Build-Hash
(`git rev-parse HEAD`) und die Commits seit dem letzten Tag
(`rev-list --count`) aus der `BuildConfig`. Ein per Commit einzigartiger
BuildConfig-Inhalt garantierte einen Cache-Miss auf compile → R8 → package
für **jeden** Commit: ein Push-Install zahlte ~50 s Rebuild, obwohl sonst
nichts geändert hatte. Der Owner entschied (2026-11-25, im Zuge der neuen
„Install nach jedem Push"-Regel in `AGENTS.md`):

- **GIT_HASH/commitCount kommen aus Gradle-Properties**
  (`-PbuildGitHash` / `-PbuildCommitCount`), die `release.yml` beim
  Release-Bau mit den echten Git-Werten übergibt — die Traceability bleibt
  für Release-Builds exakt.
- **Lokale Builds nutzen statische Defaults** (letzter Release-Hash, count 0,
  bei Release gebumpt wie `versionCode`): der Push-Install fällt auf ~9 s
  (43/47 Tasks up-to-date, gemessen `b1a30e5`), der About zeigt lokal den
  Hash des letzten Releases — akzeptiert, weil die Owner-Diktate auf dem
  Gerät die Build-Traceability über die Diagnosedateien ohnehin führen.
- Die beiden `ValueSource`-Klassen (`GitHashSource`, `CommitCountSource`)
  sind gelöscht; die Release-Instructions in `app/build.gradle.kts` nennen
  den statischen Hash als Teil des Version-Bumps.

## 2026-11-24: #193 — der Lint-Backlog ist bei null: keine Baseline mehr, `warningsAsErrors`, sechs dokumentierte Ausnahmen

Die 77 Einträge der `app/lint-baseline.xml` (#163) sind abgearbeitet und die Datei
ist gelöscht. Ab jetzt ist jeder Lint-Befund entweder an der Quelle behoben oder
eine **sichtbare** Ausnahme mit Begründung an der Zeile.

- **Entscheidung: 71 echte Fixes, 6 dokumentierte Ausnahmen.** Echte Fixes wo
  möglich (`UseKtx` → `prefs.edit { }`, tote Ressourcen gelöscht, `autofillHints`
  via `importantForAutofill="no"` auf den Endpoint/Token-Feldern, `<plurals>`,
  Legacy-Launcher-PNGs gelöscht statt neu gepixelt, `mipmap-anydpi-v26` →
  `mipmap-anydpi`, `<monochrome>`-Layer). Ausnahmen nur, wo der Fix eine bewusste
  Owner-Entscheidung wäre: `ClickableViewAccessibility` (Observer-Listener, der
  `false` gibt; der echte `OnClickListener` existiert), `MissingPermission`
  (`AudioRecorder` hat keinen `Context`, der Guard sitzt bei den Aufrufern),
  `InflateParams` (eine IME-InputView hat beim Inflate keinen Parent),
  `SetJavaScriptEnabled` (gebündeltes `assets/help.html`, kein Netz, kein
  Fremdinhalt), `InsecureBaseConfiguration` (permissives Cleartext ist
  dokumentierte Absicht, siehe PITFALLS #114) und `Overdraw` (das Overlay ist zu
  90 % deckend **per Design**, das abgeleitete Theme teilen drei Activities).
- **Warum überhaupt.** Ein Baseline-Eintrag ist unsichtbar: er versteckt den
  Befund, wächst still weiter und macht `warningsAsErrors` zu einer Lüge („nur
  neue Findings failen"). Sechs Ausnahmen mit Begründung an der Zeile sind
  billiger zu lesen als 827 Zeilen generiertes XML.
- **Der Riegel gegen den Rückfall.** `warningsAsErrors = true` in `app/build.gradle.kts`,
  und `XmlColorPolicyTest` Regel 6 ist invertiert: sie behauptet jetzt, dass
  `app/lint-baseline.xml` **nicht existiert** (und dass `TextContrast` weiter als
  ERROR deklariert bleibt). Eine wieder eingeführte Baseline lässt also die Suite
  fallen, mit einer Meldung, die #193 nennt.
- **Grenze der Aussage.** `lint.xml` bleibt für die deklarativen Ausnahmen
  zuständig (`UnknownIssueId`, die drei Update-Notices, die zwei oben) — die
  Wahrheit über die Lint-Lage steht damit an genau zwei Orten: den Suppressions im
  Code und den kommentierten Ignores in `app/lint.xml`.

## 2026-11-20: #187 — Compiler-Warnungen als Gate, nicht als Rauschen; Manifest all-or-nothing

Vier Warnungen eines normalen Release-Builds waren billig zu beheben, zwei davon
markierten aber echte Schwachstellen. Die Entscheidungen dahinter:

- **Eigenes `closed`-Flag statt `Channel.isClosedForSend`:** Die Abfrage ist
  `@DelicateCoroutinesApi` (per Vertrag rennanfällig, bekannter
  Migrationskandidat). Der Channel wird in `FragmentTranscriber` **nur** von
  `cancel()` geschlossen, also ist ein `@Volatile closed` die ehrlichere und
  zukunftssichere Form — gleiche Semantik, kein Delicate-Surface.
- **Die Session-Id wird Parameter, nicht Feld:** `buildPreparer(sid: String)`
  plus `ensureSessionId()`. Vorher ging `sessionId: String?` als
  Java-Interop-Warnung in `File(dir, …)` durch — eine latente NPE, die nur
  deshalb nie auftrat, weil beide Aufrufer die Id vorher setzten. Die Invariante
  („Preparer-Verzeichnis = Journal-Key = Bundle-Id") steht jetzt im Typ.
- **`prepared/<hash>/manifest.json` wird typisiert und GANZ ODER GAR NICHT
  gelesen:** ein `ManifestJson`-DTO mit ausschließlich **nullbaren** Feldern,
  `bytes` als `Long?`; ein einziger unvollständiger Eintrag macht die ganze
  Liste unbrauchbar (`null` → Verzeichnis wird neu gebaut). Vorher entschied ein
  `Map<String, Any>`-Cast plus `bytes as Double` über Gültigkeit — inklusive
  stiller Abhängigkeit von Gsons Map-Zahltyp. Ein teilweise vertrauenswürdiges
  Manifest wäre der schlechtere Kompromiss: es würde eine fremde Datei
  weiterreichen.
- **`checkOpNoThrow` statt `unsafeCheckOpNoThrow`:** der Ersatz ist seit API 29
  öffentlich, `minSdk` ist 30 — keine Versionsweiche, kein `@Suppress`.
- **Testquellen bleiben außen vor:** die zwei verbleibenden `Unchecked cast`-
  Warnungen in `VoiceSessionControllerTest` (Gson-Map-Assertions auf
  `session.json`) erscheinen im Release-Compile nicht und wurden nicht
  angefasst — sie zu „fixen" hieße, Assertions über ein Forensik-Format
  umzuschreiben, ohne dass eine Warnung im Produktions-Build verschwindet.

## 2026-10-19: #151 release 1.5.1 — patch bump carrying #148 + the save-path robustness fix; #150 NOT shipped
- **Scope decision (owner):** 1.5.1 = #148 (STT timing reaches devices, HANDOFF-announced as "next release build") + the save-path robustness fix; everything else since v1.5.0 rides along unchanged (:analyzer CLI, harness removal, :core extraction). A PATCH, not a minor bump — no user-visible feature was added on the app side.
- **#150 is NOT a blocker for the release** (owner's instruction when choosing "triage #150 first"): triaged, not reproducible on OxygenOS, left OPEN pending the reporter.
- **Save-path robustness (the #150 finding that DID warrant code):** `saveAndClose()` had seven silent early returns; the offending field can be off-screen (per-app wrap width at the bottom of the list), so "Save and Back does nothing" was a plausible UI reading. Owner decision: every abort names the failed check in a TOAST as well as the red field error, and appends a `settings-save-aborted` record to `logs/settings.json` (the adb-readable rotating log via `RotatingJsonLogger`, base name `settings`) plus one logcat line. Rationale: the next bug report of this class self-diagnoses without a round-trip.
- **Provider-write ordering is a diagnostic FACT, not a design goal:** the provider config is written BEFORE the `wrap-width`/`fragment-seconds` checks but AFTER all provider checks — so "config empty after restart" can only mean an EARLY abort (or lost prefs), never a late one. Recorded so future triage does not re-derive it.
- **The toast carries the check key** (`R.string.settings_save_blocked`, `%1$s` = key like `stt-model-not-in-list`) — deliberately technical: it is what a reporter quotes back, and the log line uses the same string.

## 2026-10-14: v1.4.1 patch release (10401) — same-day re-ship after the v1.4.0 regression
- **Trigger (owner directive):** v1.4.0 shipped a gross regression — the #133 auto-switch-back teardown salvaged the already-delivered text ("Diktierung verworfen" toast + clipboard overwrite). #138 fixed it on main the same day (`d0e7dbe`); the owner ordered a fast patch so both Play AND F-Droid pick up the fix: **1.4.1 / versionCode 10401** one day after the minor.
- **Release shape (#140):** bump commit `805c732` (version + patch whatsnew, 418 chars — under the 500 limit by design), annotated tag `v1.4.1` pushed SEPARATELY (established pattern). Scope since v1.4.0: #138 (user-facing), #139 harness CI (not user-facing), docs.
- **Verification done UP FRONT this time:** the Play-upload step's conclusion checked directly (`success`) AND the log grepped for `Successfully committed 16065703470121614937` (alpha edit id) — the #137 silent-failure incident is now a standard release checklist item, not a post-mortem.
- **GitHub release assets verified:** `app-release.aab` (1.9 MB, release.yml) + `polished-recognition.apk` (2.4 MB, fdroid-apk.yml). Ship notices on #138 and #139 (the only issue-referencing commits since v1.4.0).

## 2026-10-15: #147 — deployment rule corrected: local `gradlew installRelease` on USB hosts; "keyless/signing" facts were claw-only

- **Provenance correction (owner, binding):** the 2026-10-14 rule "deployments exclusively via CI release downloads" was an agent overgeneralization of the owner's one-time statement *"I have currently *only* github actions available to install an apk"* — a capability statement made from the headless VPS, never a standing deployment policy.
- **Verified signing facts (think, 2026-10-15):** `app/release.keystore` (PKCS12, 2740 B) and the root `keystore.properties` (`storeFile`/`storePassword`/`keyAlias`/`keyPassword`) EXIST in the think working tree; a local `./gradlew assembleRelease` signs with this keystore (the `if (releaseKeystore.exists() && storePassword != null)` branch in app/build.gradle.kts). PROOF of key equality with CI: the locally built 1.5.0/10500 APK installed in-place (`adb install -r`) over the CI-signed harness-1 APK — Android rejects foreign signatures on update, so local key == CI `RELEASE_KEYSTORE` key. The 2026-10-14 claim "claw AND think hold NO signing keys — local builds are UNSIGNED" was verified on claw only and is WRONG for think.
- **USB-host deployment rule (owner, binding):** on hosts with physical USB devices attached — `think`, `dell`, `elite` — build and install locally: `ANDROID_SERIAL=<serial> ./gradlew installRelease` (pin the serial when several devices are attached; a device stays in-place-upgraded, config preserved). On headless hosts (e.g. `claw`) there is no USB → CI release downloads remain the only install path there.
- **Agent rule (owner, binding):** the agent determines its own host itself (`hostname`) before device work — the hostname decides the deployment path; no guessing, no asking the owner where "we" are. Host labeling of commands (`think:` / `claw:`) stays binding.


## 2026-10-14: v1.4.0 minor release (10400) — Play whatsnew overrun recovered WITHOUT moving the tag
- **Release shape (#137):** minor bump 1.3.6 → **1.4.0** / versionCode **10400** (owner directive "minor release"; scheme = `major*10000 + minor*100 + patch`). Bump commit `f6044f8` (version + whatsnew), tag `v1.4.0` annotated and pushed SEPARATELY (same-push tags can miss release.yml — established pattern). Scope since v1.3.6: the whole fragment-pipeline era (#116–#129), #133 auto-switch-after-send, #134 different-field fresh start, #135/#136 disk-spooled capture (OOM fix). Ship-notice comments posted on all 23 issues referenced since v1.3.6 — CLOSED ones included (owner request), OPEN ones left open with their remaining work named.
- **Incident:** release.yml went GREEN but the Play alpha upload FAILED silently (`continue-on-error: true`) — the en-GB whatsnew was **529 chars vs Play's 500-char limit** ("The release created has notes in language en-GB with length 529, which is too long (max: 500)"). GitHub release assets (AAB + APK) were fine; only the Play upload was missing. Found by finally grepping the upload step's log per the standing PITFALL.
- **Recovery decision — do NOT force-move the tag:** the tag had been public for ~1 day and fdroidbot may already have queued/built it (PITFALL: never force-move a tag F-Droid may have built). Instead: (1) trim the whatsnew to **487 chars** (issue refs dropped) and commit on `main` (`8e2b966`); (2) a TEMPORARY `.github/workflows/play-reupload.yml` (workflow_dispatch) downloaded the ALREADY-RELEASED `v1.4.0` `app-release.aab` from the GitHub release and re-uploaded it with the fixed `whatsNewDirectory`; (3) verified `Successfully committed 03948756826535750989` (edit id, alpha, status completed); (4) deleted the workflow file (`0956228`). No rebuild — the AAB was signed by CI with the upload keystore and whatsnew is not packaged into the AAB.
- **Watch:** F-Droid pickup of 1.4.0 (`AutoUpdateMode: Version`, ~3–5 days after the tag); the owner's on-device checks from HANDOFF ride on whatever build is installed.

## 2026-10-05: The build pins a Java 21 toolchain; the pre-push hook is required (#107)
- **Choice**: `app/build.gradle.kts` declares `java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }`, so compile and test tasks run on a JDK 21 regardless of the host's default `java`. `AGENTS.md` marks the `pre-push` hook (`scripts/pre-push`) as **required** and documents activating it; `PITFALLS.md` records both gotchas.
- **Reason**: The host's default JDK had moved to 25; Gradle uses the JDK it runs on, and MockK 1.14.4's bundled ASM cannot read Java 25 bytecode, so 143/292 tests failed with `Unsupported class file major version 69` while the suite is green on JDK 21. The fix belongs in the build config (persisted), not in a per-agent workaround.
- **Trade-off**: A JDK 21 must be **installed** and is auto-detected (Gradle does not download one; no toolchain resolver was added to keep F-Droid's build offline). CI already provides Temurin 21 in every workflow.

## 2026-10-05: GitHub Pages renders repo Markdown with URL rewriting; landing page overhaul (#106)
- **Choice**: `docs/index.html` keeps fetching README/INSTALLATION/PRIVACY from `raw.githubusercontent.com` and rendering with `marked`, but (a) rewrites image `src="docs/..."` → `src="..."` and (b) turns relative document links into SPA tab switches (for the three known docs) or GitHub-blob links (otherwise). The page gets a hero (screenshot + CTA), a sticky responsive nav with a real **Help** link, an auto-generated table of contents (GitHub-style heading slugs), and typography/dark-mode polish. The unused Cayman theme was removed from `docs/_config.yml`.
- **Reason**: The site is served from `docs/`, so the README's repo-root-relative paths (`docs/img/...`, `INSTALLATION.md`) 404'd on the page — every screenshot showed as a broken-image icon, which is what made the page look broken.
- **Trade-off**: The URL rewriting is a client-side string/attribute fix tied to the current README conventions; a future move of the docs root would require revisiting it.

## 2026-10-01: Mastodon opens as a two-toot thread; the demo GIF is described in the toot body (#74)
- **Choice**: The Mastodon draft (`docs/marketing/mastodon.md`) is a **two-toot thread** — toot 1 carries `docs/img/demo.gif` plus the pitch, toot 2 replies with the Play-alpha two-step + GitHub issues. The GIF is now also **described in the toot body**, not only attached with alt text.
- **Reason**: Adding the GIF description, the demo model, and the owner's daily use pushed a single toot past the 500-character limit; moving the CTA to a reply keeps the demo post tight. A GIF that carries the whole pitch should be legible without opening it.
- **Rules**: The ongoing copy rules this implies (exact provider/model IDs, the canonical demo description, thread-vs-single, re-checking external claims) live in `CONVENTIONS.md ## Marketing (#74)`.

## 2026-10-01: F-Droid forum dropped as a launch channel; Mastodon opens the wave (#74)
- **Choice**: The F-Droid-forum draft is removed (`docs/marketing/f-droid-forum.md` deleted). The community wave runs **Mastodon first**, then r/fossdroid, r/degoogle, r/selfhosted, and kuketz (DE, second wave), spaced 1–2 days apart.
- **Reason**: The forum's pinned moderator notice states app **advertising is not allowed** — it is for F-Droid-related apps/issues only, and app submissions are discussed in **GitLab (MR/RFP)**, not the forum. The app is already included on F-Droid with no open MR, so a launch thread would violate the rules and serve no purpose.
- **Also**: The same notice explicitly criticises AI-written posts. The drafts are a **factual scaffold only** — the owner rewrites each in his own voice before posting.

## 2026-10-01: Community posts are version-free; alpha onboarding is canonical (#74)
- **Choice**: The community posts in `docs/marketing/` name **no app version** — they link the F-Droid page instead. The Google Group *welcome message* and the **two-step Play-alpha tester instructions** live in a single canonical file `docs/marketing/alpha-welcome.md`; every draft references it and quotes the two-step block verbatim. Alpha-group posting is restricted to owners/managers (broadcast channel); testers' feedback channel is GitHub issues.
- **Reason**: A hard-coded version in a post goes stale immediately — F-Droid serves a version only ~3–5 days after its `v*` tag (and the current F-Droid page is what matters, not the newest tag). The F-Droid link is stable, so version-free copy cannot rot. One canonical onboarding text prevents the welcome message and the six posts from drifting apart.
- **Also**: The 1.3.3-vs-1.3.4 status is context for us only, never post content.

## 2026-10-01: Release v1.3.4 (10304) prepared — bump commit references #102, no release issue
- **Choice**: Bump `versionCode 10303 → 10304` / `versionName 1.3.3 → 1.3.4` (`fee52fe`) with a `whatsnew-en-GB` entry describing the per-app line-wrap (#100/#102). The commit references **#102** directly; the owner opted against a dedicated release-tracking issue. The `v1.3.4` tag is pushed separately on the owner's go-ahead.
- **Reason**: Keeps the established pattern (bump commit + whatsnew, tag pushed separately to trigger `release.yml`/`fdroid-apk.yml`/`build.yml`) without adding issue overhead. Play-alpha testers receive the release automatically; F-Droid picks it up later via `AutoUpdateMode: Version`.

## 2026-09-24: v1.3.3 (10303) — UI-fix release carrying the dark demo GIF to the listings (#98)
- **Choice**: Patch release `10303`/`1.3.3` (commit `377e4a2`, whatsnew "Cleaner voice bar — removed a redundant divider next to the recording timer (#97)"), tag `v1.3.3` pushed **separately**. Sub-issue #98 of #74. release.yml (Play alpha) + fdroid-apk.yml + build.yml green; GitHub assets AAB + APK.
- **Reason**: The only app-facing change since v1.3.2 is #97, but the release's real purpose is to put the dark `docs/img/demo.gif` (#96) into a **built tag** so F-Droid/Play read it as listing metadata (fastlane reads from the built tag). Owner confirmed the accumulated UI already justifies v1.3.3; #91 (v1.3.1 watch) was closed as superseded.
- **Tradeoff**: Minimal user-visible delta for a version bump; justified by the listing/demo asset delivery. **Next work is marketing, not code** — #74 Phase 1 (community wave) is the active epic.

## 2026-09-24: demo.gif re-recorded on a dark background (#96)
- **Choice**: `docs/img/demo.gif` re-recorded on **OnePlus Notes** (black) instead of Jota (white) per owner feedback. Clip ≈12 s: starts at recording 0:02 → flush → speak → send → "Transcribing (STT)…"/"Polishing (LLM)…" → polished text + ~2.8 s hold. 540×1200, 64 colours, 659 KB, commit `3391528`. Fastlane untouched (the GIF is README-only).
- **Reason**: The white Jota backdrop clashed with the dark app look; OnePlus Notes matches the original v1.3.0 asset style.
- **Tradeoff**: The scrcpy raw was VFR → normalized to CFR (`ffmpeg -vf fps=30 -c:v libx264`) before trimming, else `fps`-filter sampling drifted by seconds; the ~27 s dead pause and a trailing Telegram chat overlay were cut. The raw was kept until sign-off, then deleted. **Tag/bump deferred by owner** — more UI work comes first in a later session; only then Tag v1.3.3 (do NOT tag now).

## 2026-09-24: v1.3.2 (10302) — listing-asset refresh + feature graphic (#94)
- **Choice**: Patch bump of the same day's #95 icon work, used as the vehicle for the listing refresh (F-Droid reads fastlane only from a built tag). New assets: 3 screenshots of the post-#90/#92/#95 UI into `docs/img/` **and** `fastlane/.../phoneScreenshots/` (byte-identical, same names), re-recorded `docs/img/demo.gif`, added `fastlane/.../images/featureGraphic.png` (1024×500, **verbatim copy** of `distribution/play-store-feature-graphic.png` per owner decision). `whatsnew-en-GB` reduced to the single #95 bullet. Commits `25a009e` (assets) + `552cb70` (bump+whatsnew), tag `v1.3.2` pushed separately.
- **Reason**: F-Droid's live listing was still v1.3.0-era (pre-#90/#92) and had no feature graphic; the assets must ride a fresh built tag. No fdroiddata MR — screenshots must not go there.
- **Tradeoff**: The demo GIF was captured live on the OnePlus with the owner dictating (scrcpy). First take was unusable (Telegram chat notification overlaid the recording — the same phone hosts the agent-session bridge) and was discarded; a second take succeeded. The raw mp4 was deleted before the owner's trim request, so the "cut first 5 s" edit was re-encoded GIF→GIF (quality held, flat UI). Verified: `release.yml` green (Play alpha edit `07956435961582591479`, versionCode 10302), `fdroid-apk.yml` green, `build.yml` green, GitHub release AAB+APK present.

## 2026-09-20: build.yml publishes signed installable APKs (#82)
- **Choice**: `build.yml` decodes `RELEASE_KEYSTORE` + `RELEASE_*` env vars (same pattern as `fdroid-apk.yml`), so every master push publishes a signed `app-release.apk` in the `build-*` release (verified: build-287, v2 scheme, release-key cert `62f9d7b0…`). Supersedes the unsigned era — `app-release-unsigned.apk` no longer exists as an output.
- **Reason**: Owner decision — Peter (#82) needs an installable GitHub test build, and the unsigned APK could never be installed (its "install via ADB" release note was a lie). Play (`release.yml`, own upload key) and F-Droid (`fdroid-apk.yml`, own path) are independent and unaffected.
- **Tradeoff**: CI APKs carry the F-Droid release key — upgrade-compatible with F-Droid, but NOT with Play-track builds (different production signature): switching sources needs an uninstall first. Commit `18bd2c3`.

## 2026-09-20: v1.3.0 (10300) listing-assets release ships #84 + #88 (#74)
- **Choice**: Minor bump (not patch) per owner 2026-09-14 — listing milestone warrants visibility. Ships: fastlane `images/` (icon 512 + 3 live OnePlus phoneScreenshots 1080×2400), full_description rewrite (IME-first, Gboard warning as own paragraph, BYOK), whatsnew-en-GB (#88 hints, #84 retry-preserved dictation, #74 F-Droid/group). Screenshots: owner opened screens, agent shot via adb (`f6de166c` reachable from agent host — second time after 2026-09-18; still do not assume persistence). Dark-mode IME shot accepted by owner over light-mode retake; shot-2 "scrolled-down" call corrected via layout order (Processing→Prompts→LLM→STT→About). Commits `25140f4` (assets) + `8138990` (bump); tag `v1.3.0` pushed separately (same-push tags can miss release.yml).
- **Reason**: F-Droid reads fastlane from the built tag — assets had to land before the tag. #84/#88 were implemented post-1.2.4-tag, so 1.3.0 is their ship vehicle.
- **Tradeoff**: 3 screenshots (not 2) — extra providers frame added mid-session; README table went 2→3 thumbs. #89's 1.2.4 F-Droid pickup likely superseded (bot serves newest).

## 2026-09-20: Play alpha via Google Group self-join; F-Droid first in install docs (#74)
- **Choice**: Tester onboarding is Group self-join (`polished-recognition-alpha@googlegroups.com`, "Anyone can join", attached to the alpha track) — replaces the manual email-add flow in INSTALLATION.md + README. Install docs now order F-Droid first (badge + package link) and Play closed-testing second (group join → same-account opt-in → store install, with 14-day stay-opted-in note). Opt-in page verified live via screenshot ("Become a tester" renders with icon + GitHub-issues/group feedback channels).
- **Reason**: Owner decisions 2026-09-07 (#74) — no manual Gmail collection; 20–30 tester buffer for the 12×14-day production gate. F-Droid-first matches the launch narrative (F-Droid merged, Play still in closed testing).
- **Tradeoff**: No second-account self-test possible (owner has one Google account) — first external tester doubles as the flow canary; docs carry self-diagnosis notes (same account, Member-not-Pending). Commit `4623642`, docs-only, pre-push suite green.

## 2026-09-19: v1.2.4 (10204) patch release ships features; v1.3.0 stays reserved for listing assets (#89, watch open)
- **Choice**: Owner decision — feature changes since v1.2.3 (#71 REC counter, #81 line-wrap, #83 rotation-proof session, #86 flush button, #87 sine blink) ship as **patch v1.2.4 (10204)**; v1.3.0 stays reserved for the #74 listing-assets release (fastlane images/description not ready). Play target: alpha track (same as 1.2.2/1.2.3; production still gated on 12 testers × 14 days). Release notes via `whatsnew-en-GB` only (EN-only precedent, #70).
- **CI fix in the same push (`1ced0c8`)**: `android-actions/setup-android@v3` gets `packages: platform-tools` explicitly in `release.yml` + `fdroid-apk.yml` — Google removed the legacy `tools` package from the SDK repository manifest (android-actions/setup-android#537, broke GitHub Android CI ~2026-09-14). Tag `v1.2.4` force-moved onto the fix commit before anything was published (tag-triggered workflows use the workflow file from the tag).
- **Verification**: local tests + `assembleRelease` green; `release.yml` green (Play upload edit `02449760292536382042` committed: alpha, status completed, en-GB whatsnew attached); `fdroid-apk.yml` green (reproducible APK attached); GitHub release assets (AAB + APK) verified via API. Live Play-API check left to owner — `scripts/query-play-console.py` needs the service-account JSON, which is GPG-encrypted with the owner's key (no secret key on the agent host); upload success evidenced by the CI "Successfully committed <edit-id>" log line.
- **F-Droid**: package page serves 1.2.3 (fdroidbot pickup confirmed working); v1.2.4 pickup is an automatic, non-blocking watch item on #89.

## 2026-09-16: #78 closed as shipped; 1.2.3 F-Droid pickup left to untracked auto-update
- **Choice**: Closed #78 (v1.2.3 patch release) per owner as implemented — bump `10203`/`1.2.3`, tag `v1.2.3`, both workflows green, Play alpha verified live (1.2.3/10203, status=completed). F-Droid still served 1.2.2 on 2026-09-16; the 1.2.3 pickup happens via fdroidbot auto-update with no manual step, so no watch issue remains — reopen only if the pickup stalls.
- **Reason**: The issue's only open item was the F-Droid close-watch; everything actionable is done and verified. Keeping a tracking issue for a fully automatic bot run adds no value.
- **Tradeoff**: No notification fires when 1.2.3 goes live on F-Droid — discovery is manual (f-droid.org package page) or via the next release's routine check.

## 2026-09-11: v1.2.2 ships without listing assets
- Moved to HISTORY.md — the "listing release becomes v1.2.3" target was retargeted to **v1.3.0** on 2026-09-14 (#74); see the entry below.

## 2026-09-14: v1.2.3 is a patch for #77; listing assets become v1.3.0 (#78)
- **Choice**: Released v1.2.3 (versionCode 10203) as a pure patch to ship the #77 opaque-IME fix. The listing-assets release (fastlane `images/icon.png` + `phoneScreenshots/`, `full_description.txt` rewrite, README/INSTALLATION F-Droid badge) is retargeted from v1.2.3 to a **minor v1.3.0** release tracked in #74.
- **Reason**: Owner decision 2026-09-14 — ship the pulse-contrast fix immediately without bundling the larger listing/marketing work; a listing milestone warrants a visible minor bump rather than another patch.
- **Considered**: Bundling the listing assets into v1.2.3 (rejected — delays the fix; fastlane assets need their own built tag anyway); calling the listing release v1.2.4 (rejected — owner wants a minor bump to signal the listing/marketing milestone).
- **Tradeoff**: v1.2.2 and v1.2.3 reach F-Droid without listing assets; because the v1.2.3 tag landed before fdroidbot's next run, F-Droid will skip 1.2.2 and serve 1.2.3 directly. #76 (v1.2.2 watch) was closed as superseded by #78.

## 2026-09-14: v* releases are never pruned (F-Droid Binaries) (#79/#80)
- **Choice**: `scripts/cleanup-github-assets.sh` prunes only Actions artifacts and `build-*` releases/tags (newest 7). `v*` releases (release page **and** git tag) are **never** auto-pruned. Reverses #79's initial `v*`-page pruning to 7.
- **Reason**: fdroiddata sets `Binaries: https://github.com/georgernstgraf/polished-recognition/releases/download/v%v/polished-recognition.apk` — a **per-version** URL ("This url must be versioned and can be calculated from the versionName and versionCode", F-Droid *Build Metadata Reference*). fdroidbot adds a `Builds:` entry per version and does not remove old ones, so each version's APK must stay reachable for F-Droid's reproducible-build verification / upstream-signed publishing (`Binaries:` + `AllowedAPKSigningKeys`). Ecosystem evidence (fdroiddata apps whose `Binaries:` point at GitHub releases): networksurvey **84** releases / **22** `Builds:` entries, Linthra 62, displaymirror 34, pixelix 28, valet 26, Alibi 12, CalcYou 11 — **none** has a release-pruning workflow. F-Droid itself also retains versions (`ArchivePolicy` default 3 moves older APKs to the archive repo, never deletes).
- **Considered**: keep newest 7 `v*` pages (#79 — rejected: would 404 old `Binaries:` URLs); raise the count to 20 (rejected: still a future 404 with no benefit; `v*` assets are ~1.4 MB each, while the Actions artifacts were the real storage hog that #79 targeted).
- **Tradeoff**: A few MB of `v*` release assets are retained indefinitely; the #79 retroactive deletions of the `v0.1.0`/`v1.0.0` pages were harmless (no APK asset, not in `Builds:`). `build-*` release/tag pruning is unaffected — F-Droid reads only `v*` tags and `v*` assets.

## 2026-09-11: No manual fdroiddata MR for version updates (AutoUpdateMode) (#76)
- **Choice**: Rely on F-Droid's fdroidbot automatic update (metadata has `AutoUpdateMode: Version` + `UpdateCheckMode: Tags ^v` reading versionCode/versionName from `app/build.gradle.kts`); no manual "Add 1.2.2" MR.
- **Reason**: The manual MR (worktree pattern per DECISIONS 2026-06-09) was required for the 1.2.1 **initial inclusion** (MR !40029). Updates are auto-detected; owner has no time pressure. #76 closes only when 1.2.2 is live on both platforms, so the watch is part of the issue.
- **Considered**: Manual MR via `~/repos/schurlix/fdroiddata-mr-*` worktree (faster but unnecessary effort).
- **Tradeoff**: Publication timing depends on the bot's periodic run — potentially hours–days; version-bump commits must keep the `UpdateCheckData` regex format intact.

## 2026-05-29: Kotlin 2.1.20 Upgrade
- **Choice**: Upgrade from Kotlin 1.9.22 to 2.1.20
- **Reason**: Test dependencies (Robolectric, MockK) transitively pull kotlin-stdlib 2.1.20 which is incompatible with Kotlin 1.9.x compiler metadata format. Upgrade was required for compilation.
- **Considered**: Pinning stdlib to 1.9.x (complex, fragile, Gradle resolution conflicts)
- **Tradeoff**: Kotlin 2.x has stricter parameter handling (named args required when default params at front of constructor).

## 2026-05-30: Disable `isShrinkResources`
- **Choice**: Set `isShrinkResources = false` for release builds
- **Reason**: Resource shrinker removed views from layouts that R8 couldn't trace as used, causing `findViewById` to return null for seemingly-referenced views. Disabling it adds ~50KB to APK size but eliminates a class of release-only layout bugs
- **Considered**: `tools:keep` in layout XML, resource keep rules (fragile, per-view)
- **Tradeoff**: Negligible APK size increase (~50KB)

## 2026-06-01: Always install release builds
- **Choice**: Always use `./gradlew installRelease` for deployment. Debug build uses `applicationIdSuffix = ".debug"` → separate app ID that won't match the `voice_recognition_service` system setting
- **Reason**: The debug APK has a different application ID and cannot function as the voice input provider. Release build signs with `signingConfigs.debug` so it installs without extra setup
- **Considered**: Debug build for development (breaks voice input testing)
- **Tradeoff**: Slower install due to R8 minification. Adding extra install target just for testing voice input

## 2026-06-02: GitHub Pages for Privacy Policy hosting
- **Choice**: Host Privacy Policy at `public_html/` on GitHub Pages (`georgernstgraf.github.io/polished-recognition`)
- **Reason**: Google Play requires a publicly accessible Privacy Policy URL for apps with sensitive permissions (RECORD_AUDIO, INTERNET). GitHub Pages is free, version-controlled alongside the code, and requires no external hosting.
- **Considered**: Separate privacy policy service (cost), raw.github.com link (plain text, no formatting)
- **Tradeoff**: Policy is tied to the repo — if the repo is made private or deleted, the policy URL breaks. Jekyll renders markdown to HTML automatically.

## 2026-06-02: Upload keystore for Google Play App Signing
- **Choice**: Generate a dedicated `upload.keystore` (alias: `upload`) for local signing before upload. Google Play manages the production signing key.
- **Reason**: Google Play App Signing is the recommended approach — Google holds the production key, the developer only uses an upload key. If the upload key is lost, it can be replaced via the Play Console (unlike a self-managed production key).
- **Considered**: Self-managed production keystore (irrecoverable if lost)
- **Tradeoff**: Requires internet to sign on Google's servers during distribution. Upload key must still be backed up.

## 2026-06-02: CI debug keystore synchronization via GitHub Secrets
- **Choice**: Export local `~/.android/debug.keystore` as base64 and store it (plus storePassword, keyAlias, keyPassword) in GitHub repository secrets. CI workflow decodes the keystore into `~/.android/debug.keystore` before running `assembleRelease`.
- **Reason**: Local and CI builds had different debug keystores, causing `INSTALL_FAILED_UPDATE_INCOMPATIBLE` when installing CI-built APKs over locally-installed ones. No changes to `build.gradle.kts` required — `signingConfigs.getByName("debug")` on CI finds the same keystore as locally.
- **Considered**: Creating a proper release keystore with signingConfigs.create("release") (cleaner but requires build.gradle.kts change), standard Android debug keystore on CI (different key → signature mismatch)
- **Tradeoff**: Debug keystore passwords are public (`android`/`androiddebugkey`) — no real signature security. Acceptable for development builds. For production signing, a proper release keystore should be created.

## 2026-06-03: CI-only signing (no Gradle signing config) [SUPERSEDED]
- Moved to HISTORY.md — the CI-only rule was replaced the same day by RELEASE_KEYSTORE-based signing.

## 2026-06-03: Restore signing config with RELEASE_KEYSTORE
- **Choice**: Add `signingConfigs.release` back to `app/build.gradle.kts` reading `app/release.keystore` with `RELEASE_*` env var fallback. Restore CI to decode `RELEASE_KEYSTORE` secret and build with `RELEASE_*` env vars.
- **Reason**: Removing the signing config broke `installRelease` (task doesn't exist without signing config on release type). CI was also generating a fresh keytool keystore per run, producing different signatures than local builds. The old approach (decode secret + env vars) worked and produced consistent signatures.
- **Considered**: Keeping CI-only signing and adding `signingConfig = signingConfigs.debug` for local use only (inconsistent approach)
- **Tradeoff**: `app/release.keystore` must exist locally and as `RELEASE_KEYSTORE` secret in GitHub. Both are copies of `~/.android/debug.keystore`.

## 2026-06-04: Upgrade to AGP 9.1.1, Java 21, API 36
- **Choice**: Upgrade AGP from 8.7.3 to 9.1.1, Gradle from 8.9 to 9.5.1, compileSdk/targetSdk from 34 to 36, Java from 17 to 21. Remove Kotlin plugin (AGP 9.x has built-in Kotlin support).
- **Reason**: Play Store requires targetSdk ≥ 35 for new submissions. Zazentimer already uses AGP 9.1.1 + API 36 + Java 21 successfully.
- **Considered**: Targeting only API 35 (AGP 8.7.3 supports it, smaller change)
- **Tradeoff**: AGP 9.x drops the `org.jetbrains.kotlin.android` plugin — `kotlinOptions {}` block no longer available. JVM target derived from `compileOptions`.

## 2026-06-04: Internal track for CI releases (production blocked)
- **Choice**: Change `release.yml` from `tracks: production` to `tracks: internal` with `status: completed`.
- **Reason**: Production track has precondition checks (Content Rating, Data Safety, App Signing) that block commits. Internal track accepts `completed` releases without precondition validation.
- **Considered**: Keeping `status: draft` on production (still fails precondition checks)
- **Tradeoff**: Releases go live on internal testing immediately. Must manually switch back to production when preconditions are resolved.

## 2026-06-04: Direct Play Console API for debugging, GitHub Action for releases
- **Choice**: Use `scripts/upload-aab.py` for testing and debugging uploads locally. Keep `r0adkll/upload-google-play` for CI release automation.
- **Reason**: The GitHub Action returns opaque "Precondition check failed" with no details. The direct API gives exact error messages. Local API calls confirmed internal track works.
- **Considered**: Replacing the GitHub Action entirely with the Python script in CI
- **Tradeoff**: Two code paths for upload (one local, one CI). The Python script exists only for debugging; CI still uses the action.

## 2026-06-04: PNG-based launcher icon (VectorDrawable limitations)
- **Choice**: Replace the flat white-gold VectorDrawable launcher foreground with a `<bitmap>` drawable referencing a high-resolution PNG rendered from `distribution/play-store-icon.svg`.
- **Reason**: Android VectorDrawable (`<vector>`) does not support `<filter>`, `<pattern>`, `<clipPath>`, or `<feDropShadow>`. The high-quality SVG uses all of these for chrome gradients, mesh grille, and gold glow effects.
- **Considered**: Simplifying the SVG to remove filters/patterns (loses visual quality), keeping the flat vector (user rejected as "rudimentary")
- **Tradeoff**: PNGs are resolution-dependent. The source SVG in `distribution/` is the editable source if resolution changes are needed.

## 2026-06-09: F-Droid metadata in fastlane/ structure
- **Choice**: Store app description (title, short_description, full_description) in `fastlane/metadata/android/en-US/` in the main repo, and keep only build configuration in `fdroid/com.georgernstgraf.polishedrecognition.yml`
- **Reason**: F-Droid now requires upstream repos to contain fastlane metadata. The metadata YAML in `fdroiddata` should not contain Description — it's pulled from the upstream repo.
- **Considered**: Triple-T structure (same result, fastlane is more common)
- **Tradeoff**: Two files for store listing (fastlane) vs one YAML field. Updates require changes in our repo, not just the MR.

## 2026-06-09: fdroiddata worktrees in ~/repos/schurlix/
- **Choice**: Store the fdroiddata fork at `~/repos/schurlix/fdroiddata/` (GitLab user namespace) with git worktrees for each active MR under `~/repos/schurlix/fdroiddata-mr-<name>/`
- **Reason**: Avoids re-cloning the 76k-file repo for each MR operation. Worktrees share the `.git` object database. Fork is under `schurlix` (GitLab user) not `georgernstgraf` (GitHub user).
- **Considered**: /tmp clones (slow, ephemeral), single worktree in project directory
- **Tradeoff**: Worktrees must be manually cleaned up after MR merge. The fork is shallow (`--depth 1`) — full history requires separate `git fetch` for CI.

## 2026-06-09: glab for GitLab CLI access
- **Choice**: Use `glab` (GitLab CLI) for all GitLab operations (fork, branch, MR creation). Token stored in `~/.config/glab-cli/config.yml`.
- **Reason**: GitLab API via curl is verbose and error-prone. `glab` handles authentication and API calls consistently.
- **Considered**: Direct API calls via curl (used for MR description update), plain Git
- **Tradeoff**: Requires binary installation (downloaded from GitLab releases). The CLI's default MR creation creates intra-fork MRs; cross-project MRs need explicit API call with `source_project_id`.

## 2026-06-12: GitHub Actions build-* release cleanup filter
- **Choice**: Add `grep '^build-'` to the cleanup pipeline in both `build.yml` and `release.yml` so only `build-*` releases are counted/deleted (v* releases are excluded)
- **Reason**: The existing `build.yml` cleanup listed ALL releases and could accidentally delete `v*` Play Store release tags. `release.yml` had no cleanup at all, allowing build releases to accumulate.
- **Considered**: Separate `gh` list filtering by tag prefix (not supported by `gh release list`)
- **Tradeoff**: Pipeline now depends on `grep`. `gh release list --limit 100` is the API cap — works for repos with <100 releases.

## 2026-06-15: VERSION_DISPLAY + About section in Settings
- **Choice**: Added `GitHashSource` and `CommitCountSource` Gradle `ValueSource` classes (identical to zazentimer) generating `BuildConfig.GIT_HASH` and `BuildConfig.VERSION_DISPLAY`. Added about section at bottom of `SettingsActivity` showing version, commit hash, and the setup guide text. Removed the info button from `VoiceRecognitionActivity` (its content moved to settings).
- **Reason**: Users need to see the installed version for bug reports. The setup guide belongs in the configuration screen, not on the recording overlay. Removing the info button declutters the recording screen.
- **Considered**: Keeping info button on recording screen (redundant after settings about section)
- **Tradeoff**: Users must open Settings to find the README link and credits. Recording screen is cleaner with 3 buttons (cancel/pause-resume/stop) + settings gear.

## 2026-06-09: Remove signingConfigs.release for F-Droid [SUPERSEDED]
- Moved to HISTORY.md — #30 replaced the removal with a file-existence-guarded signingConfig, restoring local installRelease without disturbing F-Droid/CI.

## 2026-06-16: Conditional signingConfig (file-existence guarded)
- **Choice**: Define `signingConfigs.release` and assign `release.signingConfig` in `app/build.gradle.kts` only when `file("release.keystore").exists()`.
- **Reason**: Reconciles three conflicting requirements: (a) local `./gradlew installRelease` needs a signed release variant or AGP won't generate the install task (PITFALL #39); (b) `build.yml` and F-Droid expect unsigned `app-release-unsigned.apk`; (c) `release.yml` signs the AAB via `-Pandroid.injected.signing.*` independently. The keystore is gitignored, so it exists only on the dev machine and is absent in all CI/F-Droid environments — the `exists()` guard makes signing active locally and inert elsewhere.
- **Considered**: Command-line `-Pandroid.injected.signing.*` per local invocation (works but loses the one-command `installRelease` workflow documented in AGENTS.md), unconditional `signingConfigs` (breaks F-Droid — missing keystore file in their container).
- **Tradeoff**: Local `assembleRelease` now yields `app-release.apk` (signed) instead of `-unsigned`; harmless locally, and CI/F-Droid are unaffected (keystore absent → unsigned).

## 2026-06-27: Release 1.1.0 (v1.1.0)
- **Choice**: Shipped 1.1.0 (versionCode 10100; scheme `M*10000 + m*100 + p`). Bundle since v1.0.1: single editable System Prompt + Target Language Clause (#37/#39), whitespace trimming of STT input and final output (#38), rotating request/response JSON logging (#40/#41), build/dev housekeeping (#42).
- **Docs**: `PRIVACY-POLICY.md` updated to disclose the on-device diagnostic logs (rotating JSON of prompts/transcripts/provider responses; app-private; never transmitted to the developer) — this also requires a matching **Play Console Data Safety** form update (manual, not in-repo). Play "What's new" lives in `distribution/whatsnew/whatsnew-en-GB` (≤500 chars).
- **Distribution**: tag `v1.1.0` → `release.yml` builds the signed AAB → Play **alpha** track; F-Droid auto-updates via `AutoUpdateMode: Version` + `UpdateCheckMode: Tags` (no `UpdateCheckData`).
- **Known**: `KEY_TRANSLATE`/JSON `"translate"` and the renamed prompt placeholders are NOT migrated across versions — a saved (pre-`*_clause`) system prompt will leak old placeholder tokens until "Restore all prompts to default" (declined to fix in #43; left as-is).

## 2026-06-27: F-Droid MR !40029 metadata fixes (subdir/output/UpdateCheckData)
- **Choice**: Applied maintainer `linsui`'s (re-requested) review feedback — set `subdir: app`, removed `output:`, removed `UpdateCheckData:` in fdroiddata commit `a3347eab` (MR branch `add-polished-recognition`). Build block is now `subdir: app` + `gradle: [yes]` only; `AutoUpdateMode: Version` + `UpdateCheckMode: Tags` with no `UpdateCheckData`.
- **Reason**: With `subdir` set, `output:` globs a double path and is redundant (fdroidserver auto-detects the APK under `<subdir>/build/outputs/apk/release/`); with `subdir: app` the default tag-check reads `app/build.gradle.kts`, so `UpdateCheckData` is also redundant. These are the standard fdroidserver conventions.
- **Verification**: Installed fdroidserver 2.4.5 in a venv and verified locally before pushing — `fdroid rewritemeta` (canonical form), `fdroid lint` (clean), `fdroid checkupdates` (detects 1.1.0/10100 without `UpdateCheckData`), and a full `fdroid build com.georgernstgraf.polishedrecognition:100` (succeeds; APK auto-detected). Required local toolchain shims (gradlew-fdroid + JDK 21 + ANDROID_HOME) documented in PITFALLS.md — these are fdroidserver-version gaps, not metadata issues.
- **Scope/Play Store safety**: metadata-only edit in the fdroiddata fork (separate repo); zero changes to `app/build.gradle.kts`, signing config, `release.yml`, or tags → no Play Store impact. Also decided to STOP mirroring the metadata yml into this repo (it caused a stale, obfuscated "source of truth"); the mirror is removed (see ARCHITECTURE.md).

## 2026-06-28: Reproducible F-Droid builds + proper release signing key (MR !40029)
- **Choice**: For linsui's reproducible-build request, pinned v1.1.1 with `Binaries:` + `AllowedAPKSigningKeys:`, enabled via (a) a new `fdroid-apk.yml` workflow that builds a signed release APK and attaches it to the v* GitHub release, and (b) disabling AGP `dependenciesInfo.includeInApk` to strip the 'Dependency metadata' block F-Droid's scanner rejects. Reference URL: `releases/download/v%v/polished-recognition.apk`.
- **Signing key**: replaced the **debug keystore** (well-known passwords, used for all prior `build.yml` APKs) with a dedicated **RSA-2048 / ~100y** key (CN=polished-recognition, cert `62f9d7b0…a76a85`), kept backed up. `app/build.gradle.kts` signingConfig refactored to read a gitignored `keystore.properties` (local) or `RELEASE_*` env vars (CI) — dropping the `android`/`androiddebugkey` fallback. Play AAB signing (release.yml upload keystore) is orthogonal and untouched; this key only signs APKs (F-Droid + `build.yml`).
- **Key management**: introduced a `pass`/GPG key wallet under `~/svn/georg/private/password-store` (GPG key `EC23CFDFDDA530BD`, SVN-synced, no git; `PASSWORD_STORE_DIR` in `~/.bashrc`). Layout `android/<app>/{keystore.b64,store-password,key-password,alias,cert-sha256}` + `gitlab/schurlix-fork`. GitHub Secrets (`RELEASE_KEYSTORE`, `RELEASE_*`) are the CI copy; pass wallet is the offline master.
- **R8**: investigated 2026-06-28 (F-Droid forums/docs) — AGP 9.1.1's R8 is past the CPU-core nondeterminism fixes (R8 8.6.33/8.7.20/8.8+); `isShrinkResources=false`. Verified reproducible: `fdroid build :10101` → "compared built binary to supplied reference binary successfully".
- **Tradeoff**: cutting v1.1.1 shipped a new Play alpha (additive; no change to existing Play delivery). Local `installRelease` now requires the new key (one-time reinstall over old debug-signed builds). Going forward, F-Droid ships the release-key-signed APK (reproducibly verified).

## 2026-08-30: Gradle caching + config cache for dev machine and CI (#52)
- **Choice**: Enable `org.gradle.caching`, `org.gradle.parallel`, `org.gradle.configuration-cache` in the repo `gradle.properties`; add `gradle/actions/setup-gradle@v4` to all three workflows; add a scoped Robolectric jar cache to `build.yml`; merge `build.yml`'s three Gradle invocations into one `./gradlew test assembleRelease bundleRelease`; add `workflow_dispatch` + `dry_run` input to `release.yml` (skips GitHub Release creation and Play upload) for safe validation runs.
- **Reason**: CI had zero cross-run caching (107s test step re-downloading deps + Robolectric jars); three separate invocations paid 3 configuration phases. Local no-op build went from minutes to 3s with config-cache reuse; warm CI build step 28s (was ~187s).
- **Considered**: Keeping tests strictly before build in `build.yml` (rejected — merged invocation saves ~10–15s config and is required for config-cache reuse); user-home-only config-cache (rejected — user wanted committed benefit for every dev machine); testing `release.yml` with a throwaway tag (rejected — would upload to Play alpha).
- **Tradeoff**: A test failure in `build.yml` may waste ~20–40s of compile work before aborting. Wrapper validation disabled in the two tag workflows (see PITFALLS — old tags carry unlisted wrapper jars). Caches only affect input sourcing, never inputs — verified byte-identical rebuild of the `v1.1.1` F-Droid APK (sha256 `53d06787…` before/after).

## 2026-08-31: README revamp — IME-first positioning, fresh screenshots (#58)
- **Choice**: README rewritten with the **voice-keyboard (IME) as the headline mode** and the RecognitionService/overlay demoted to a secondary mode ("works everywhere"). New structure: hero pitch → 3 screenshots → how-it-works (5 steps) → benefit bullets → installation link → provider table → features (user-oriented) → privacy → troubleshooting → trimmed "For developers" section at the bottom. Screenshots captured live from the v1.2.0 device into new `docs/img/` (ime-recording, settings) and embedded via relative paths.
- **Reason**: #58 ("README completely out of sync, make it inviting"). Last README edit predated v1.2.0 (2026-06-27) — it missed the IME as primary IME (#46), pulse/stage display (#57), Polish-only rename (#48), custom target languages (#48/#53), reasoning-strip (#47), model cache (#50). Owner explicitly chose "present both modes, favor IME — the RecognitionService is ill-supported by Google and device vendors".
- **Considered**: Keeping the old "Not a keyboard" headline (rejected — no longer true since #46); reusing `distribution/*.png` screenshots (rejected — stale pre-#48/#49 UI, one exposes a private chat); moving dev content to docs/DEVELOPMENT.md (rejected — owner picked trim+bottom).
- **Tradeoff**: `docs/img/` adds ~450 KB of binary PNGs (1080×2400 raw screencaps) to the repo; IME screenshot shows a Simple Notes dialog as background (neutral, but not app-agnostic imagery).

## 2026-08-31: README follow-up — overlay dropped entirely, app is IME-only (#58)
- **Choice**: Removed the overlay screenshot from the README and collapsed the "Voice overlay" feature section into a one-line mention (system voice-input service registration); "How it works" step 1 now references only the IME. `docs/img/overlay-recording.png` deleted.
- **Reason**: Owner: the overlay UI is the **pre-revamp** design — all #43–#59 revamp work landed in the IME, the app "only shows the IME" in practice, and the overlay's busy/legacy look (Material3 pill + buttons) misrepresents the current product in the README.
- **Note**: `VoiceRecognitionActivity` (RECOGNIZE_SPEECH handler) remains in the manifest and functional; only its README exposure was reduced. Do not re-add overlay screenshots until the overlay UI itself is revamped.

## 2026-09-07: Close #62 not-planned + v1.2.1 release with MR !40029 cleanup (#68)
- **Choice**: Closed #62 (re-add bound RecognitionService for HeliBoard) not-planned — the premise is empirically dead: HeliBoard (current) detects Polished's auxiliary voice IME and its mic button works on the OnePlus with no bound service and no `voice_recognition_service` write. Then cut **v1.2.1** (versionCode 10201, tag commit `7f7200a2`): branch push + **separate tag push**; Play verified via service-account API query (1.2.1/10201, status=completed); fdroiddata MR !40029 updated to a **single 1.2.1 Build entry** (old 1.1.1 + 1.2.0 removed per linsui's "Remove the old version" — a New-App MR carries only the newest version), pipeline fully green, reply posted.
- **Reason**: No problem left for #62 to solve (only obsolete-deprecated devices could even use a bound service — Oplus can't write the setting at all). The v1.2.1 flow also exposed that the old pipeline failure was `fdroid rewritemeta` rejecting non-chronological Builds (1.2.0 prepended above 1.1.1); a single newest entry is the canonical New-App form and moots the ordering issue.
- **Discovered**: `release.yml` uploads to the **alpha** track (status=completed) — not `internal` as the 2026-06-04 decision records. Harmless (both are testing tracks, alpha is publicly opt-in), noted here to keep DECISIONS truthful; flip the `tracks:` value if internal is wanted again.
- **Tradeoff**: F-Droid history starts at 1.2.1 — users of the previously-released 1.1.1 APKs (GitHub sideloads) see an upgrade, old builds remain reproducible only via git tags. Play-verification needs the service-account key decrypted from `~/svn/georg/private/iron-country-*.json.asc` into a 0600 temp file (wiped after use).

## 2026-09-20: #82 docs repair — ADB voice-input block restored to INSTALLATION.md
- **Choice**: New INSTALLATION.md §5 "Set as System Voice Input (SpeechRecognizer)" (Settings path System → Language & region → Speech → Voice input + ADB `settings put secure voice_recognition_service <pkg>/.service.PolishedRecognitionService` fallback + verify get + Oplus-blocked note + reboot-enumeration note), "Use It" renumbered §5→§6, plus a "not listed under Voice input?" Troubleshooting entry pointing at §5.
- **Reason**: Peter (#82) installed build-287 and the app was missing under Voice input; the in-thread claim "documented in the installation guides" was false — the ADB block had been dropped in #43 with the bound-service removal and never restored when #82 re-added the service. The old "Set as Voice Input" in-app button mention was deliberately NOT restored (removed in #43, SecurityExceptions on OEM ROMs).
- **Tradeoff**: Docs-only, no code; component string re-verified against the current manifest (`.service.PolishedRecognitionService` unchanged since pre-#43). Comment posted on #82 with correction + retest request; issue stays open pending the on-device feel-check.

## 2026-09-21: v1.3.1 (10301) patch release ships #82 + #90 (#91)
- **Choice**: Patch bump (not minor) per owner — v1.3.0 tag predates the #82 implementation (verified: no v* tag contains `3e38bf7`), so 1.3.1 is the first release with the bound RecognitionService (+ INSTALLATION §5 ADB docs) and the full #90 IME UI batch. whatsnew-en-GB rewritten (2 bullets, EN-only). Tag `v1.3.1` pushed separately from the branch commit (`c1d8b3e`).
- **Reason**: Owner decision — the UI batch + service entry point justify a user-visible release; patch because no listing/marketing changes ride along.
- **Verification**: `release.yml` green (Play alpha edit `12594441127685593678` committed, status completed), `fdroid-apk.yml` green, GitHub `v1.3.1` assets (AAB + APK) present, `build.yml` green. Live Play-API check + F-Droid pickup (may serve 1.3.0 first) remain watch items on #91.
- **Tradeoff**: #82 stays open until Peter confirms the retest on a shipped build; #90 closed (owner visual feel-check passed: "wonderful").

## 2026-10-08: Cross-repo sub-issues ARE permitted (#115 ↔ aitranscribe#81)
- **Choice**: aitranscribe#81 (prompt sync in the sister repo) was formally linked as a sub-issue of polished-recognition#115 via `POST /repos/georgernstgraf/polished-recognition/issues/115/sub_issues` — GitHub accepted the cross-repository child.
- **Reason**: The sync ticket spans both repos; the parent's progress summary now tracks the sister-repo work.
- **Tradeoff**: Sister-repo commits cannot reference the parent number in their commit messages (different repo) — the body reference + formal link carries it.

## 2026-10-15: #146 Phase 2 — the :analyzer CLI is the harness successor; analyze.py compat struck; corpus-first (owner decisions, binding)
- **Choice**: (a) **analyze.py compatibility is STRUCK** — :analyzer defines its OWN canonical result format (documented once in analyzer/README.md); the loop-mask + metric core is PORTED (Kotlin), and the #122/#129 fixtures serve as regression tests of the ported logic (every analyze.py --selftest value re-asserted in FixtureRegressionTest). The Python tools remain for the historical record; no instrumented comparison against old runs. (b) **Corpus-first**: a corpus is a drop-in directory `corpora/<name>/` with `manifest.json` (name/createdAt/description/source/kinds/audio + classes[] each with confidence + note + source + startSeconds + files[sha256]); wavs/, bundles/, results/<run-id>/ are git-ignored, the manifest is COMMITTED (no secrets). Corpus #2/#3/#4 need no code changes. (c) **Korpus #1 = seam-study-01**: the 37-WAV #122 corpus imported one-time from host think (rsync), sha256-verified; manifest generated from samples.json + REPORT.md via tools/seam-study/generate_corpus_manifest.py (committed, reproducible). (d) **The analysis heart is split**: `loop_spans`/`compress_loops` → `:core` `pipeline/LoopMask` (doubles as the #123 production loop-guard core; pure function over token lists, tokenizer stays with the caller); seam attribution + seam cleanliness + pct/median/spearman + reliability filter → `:analyzer` `analysis/`. (e) **Run mode drives the REAL :core pipeline** (zero drift): WavPcm → in-memory PcmSource → FragmentPreparer (frozen production knobs, CLI-overridable for sweeps; wavMode — no platform Opus encoder on the JVM) → FragmentTranscriber (prompt carry, echo trim, stitch evidence, forensic bundle) → SttRequestRunner → full-context reference pass (WavChunker ≤300-s slices) → canonical result.json + the app's own session.json per sample. Strictly serial C=1; resume-safe (successful result.json skipped). (f) **pull encapsulates the adb gotchas**: `adb exec-out cat` per file (binary-safe — `adb shell`'s pty would mangle WAV bytes), `adb shell ls` listings \r-stripped, stdin redirected from /dev/null, `-s` serial pinning, destination the corpus bundles dir. (g) **Corpus BUILDING stays outside the CLI** (no ffmpeg in :analyzer). (h) STT credentials ONLY via env/CLI (`ANALYZER_STT_TOKEN`/`--stt-token`) — never hardcoded/committed.
- **Reason**: Owner directive 2026-10-15 (issue #146): the harness build is being removed (#147) but its CODE lives on in the PC analysis tool; #129's qualityMin decision needs the offline analysis path (bundled evidence + Gegenprobe) on the PC, corpus-first for cross-corpus validation.
- **Tradeoff**: The reference is the same Whisper full-context (NOT ground truth) — reliability filter + loop-mask discipline carried over; the LCS diff may decompose blocks differently than difflib, but extra/missing sums are identical (the attribution consumes only those). Clean-baseline knobs are the frozen production defaults (0.65/100/10.0/150/lateHalf/qgate, 21-s fragments, 2-s search, 1-s pre-roll, 96-char prompt) — #129's final values come from the bist-class runs (owner-gated).
- **Verification**: 523 tests green (332 :app + 169 :core + 22 :analyzer incl. the fixture regression + bundle round-trip guard); assembleRelease green; commits `314e0b1` (LoopMask port) + `047f5bc` (analyzer CLI + corpus convention + manifest); clean-baseline run over all 37 WAVs completed 12.6 min wall (20.4 s/sample avg), 32/37 reliable.

## 2026-11-22: #187 (reopened) — das Ausgabeprotokoll des Release-Builds ist ebenfalls ein Gate

Die vier Kotlin-Warnungen waren weg, aber ein gewöhnliches `installRelease`
druckte weiter drei Blöcke: zwei AAPT-Substitutionswarnungen, die
Baseline-Meldung von `lintVitalRelease` und — unsichtbar für CI, weil neue
*Warnungen* den Build nie brechen — zwei echte Findings aus der #172-Arbeit.

- **Literal-`%`-Strings werden als Nicht-Format deklariert:** `formatted="false"`
  auf `slice_seconds_hint`, `search_percent_hint` und (Konsistenz) `search_percent`,
  `search_percent_error`, `compress_audio_hint`. Keiner davon wird mit Argumenten
  benutzt; AAPT hört auf, sie als Formatstrings zu lesen, und der fatale
  `StringFormatInvalid`-Eintrag verschwindet. Eine Format-Zeichenkette MIT
  Argumenten bleibt positionell (`%1$s`) — niemals `formatted="false"`.
- **Die zwei neuen Warnungen werden behoben, nicht baselined:** `Autofill` auf
  `app_wrap_width` (`importantForAutofill="no"`, die Konvention der Nachbarfelder)
  und `SetTextI18n` auf dem FIFO-Prefill
  (`String.format(Locale.ROOT, "%d", …)`). Bewusst `Locale.ROOT`: das Feld wird
  später mit `toInt()` zurückgelesen, ein lokalisierter Gruppentrenner würde den
  Roundtrip brechen.
- **Update-Hinweise werden ignoriert, nie baselined:** `NewerVersionAvailable`,
  `GradleDependency` und `AndroidGradlePluginVersion` tragen die gefundene Version
  in der MESSAGE. Sobald upstream eine neuere publiziert, matcht der alte Eintrag
  nichts mehr, lint meldet ihn als „fixed" und erhebt denselben Hinweis erneut als
  neues Finding (genau das war am 2026-11-22 zu sehen: Gradle 9.9.1 → 9.9.2,
  robolectric 4.17 → 4.18.1). Sie stehen jetzt als `severity="ignore"` in
  `app/lint.xml`; ein Dependency-Upgrade bleibt eine bewusste, manuelle Entscheidung.
- **Die `lintVitalRelease`-Baseline-Meldungen bleiben (Owner-Entscheidung
  2026-11-22):** Die Baseline wird vom globalen `lint`-Task geschrieben
  (`variant="all"`), `lintVitalRelease` prüft nur FATAL — AGP 9.1.1 kennt keine
  Baseline pro Variante. `checkReleaseBuilds` bleibt deshalb auf dem Default; die
  Fatal-Prüfung bleibt erhalten, die zwei Meldungen sind akzeptiertes Rauschen.
  Die Alternative (`checkReleaseBuilds = false`) hätte das Gate nur verschoben.
- **Tradeoff:** Neue Warnungen brechen den Build weiterhin nicht (`abortOnError`
  greift nur bei Errors). Der Wächter für den Backlog ist `LintBaselineFixed` in
  `lintDebug` — deshalb wurde die Baseline an diesem Tag neu erzeugt und schrumpfte
  von 91 auf **77 Einträge** (1 Error + 76 Warnungen); die 77 sind akzeptierter
  Bestand (26 `UseKtx`, 15 `UnusedResources`, 10 `Autofill`, …), ein Abbau ist ein
  eigenes Vorhaben.
- **Verification:** beide `merge*Resources` ohne Substitutionswarnung;
  `lintDebug` → „No errors or warnings (and 1 error, 76 warnings filtered)"; 902
  Tests grün; `assembleRelease` grün; `installRelease` auf dem angeschlossenen
  SM-A032F installiert. Kein Versions-Bump.

**Also stored elsewhere** (ambiguous placement, cross-referenced per #206):
- [audio-pipeline.md](audio-pipeline.md) — „2026-10-15: corpus privacy — the whole `corpora/` tree is local-only (owner decision)"
- [audio-pipeline.md](audio-pipeline.md) — „2026-10-12: #129 unit 1 — harness knob rework (silence-quality + stitching params as `am start` extras, `qgate` default)"
- [audio-pipeline.md](audio-pipeline.md) — „2026-10-12: #129 staged research run EXECUTED on the Samsung A03 — winners recorded; response curves flat (the corpus cannot discriminate the gate)"
- [audio-pipeline.md](audio-pipeline.md) — „2026-10-11: #121/#122 close-out — production config freeze; adaptive silence search (#124) + BuildConfig-gated diagnostics (#125); fullContextAtStop REVERSED"
- [ime-field.md](ime-field.md) — „2026-09-20: #84 + #88 closed (feel-checks passed); spacing watch verified; social preview built (#74)"
- [session-lifecycle.md](session-lifecycle.md) — „2026-09-19: Flush button discards PCM keeping session mode + unit-test worker heap 1536m (#86, OPEN)"
- [stt-providers.md](stt-providers.md) — „2026-06-27: Log exact LLM request as pretty JSON, skip raw mode (#40)"
- [stt-providers.md](stt-providers.md) — „2026-06-27: Log STT + LLM responses verbatim; generalize logger (#41)"
