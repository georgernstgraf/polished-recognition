# Persistence Log

## 2026-11-25 (#205 — die Live-Vorschau im Textfeld)

* **Update**: [STATE.md](STATE.md) — Statuszeile (1083 Tests) + Abschnitt `#205 — die Live-Vorschau im Textfeld`: app-weiter Zustand, Editor-Naht, Reconciliation beim Bind, Terminal-Kaskade, nicht-liefernde Pfade, Selection-Snapshot inkl. B-Kürzung, Marker, Diagnose, Kill-Switch, Testbilanz, inkl. der Regel „nur die IME-eigene Session streamt“.
* **Update**: [DECISIONS.md](DECISIONS.md) — neuer Eintrag `#205 — die Live-Vorschau ist implementiert (eine Abweichung von der Entscheidungstabelle)`: `B + P` beim Bind = committetes K = P (statt Resend), B-Kürzung um die Selektion, Tick-Abbruch vs. Terminal-Klassifikation. Der Entscheidungs-Eintrag vom selben Tag bleibt als D1–D6 stehen.
* **Update**: [ARCHITECTURE.md](ARCHITECTURE.md) — zwei Komponenten-Zeilen (`LivePreview`/`LivePreviewReconcile`/`LivePreviewTerminal`, `EditorPort`/`InputConnectionEditorPort`), `ImeLifecycleTrace` um die `livePreview`-Zeilen ergänzt, neuer Datenfluss-Abschnitt `Live preview in the field (#205)`.
* **Update**: [PITFALLS.md](PITFALLS.md) — drei Einträge: `setComposingText` ersetzt die Region (Duplikat bei Resend nach Client-Commit), B muss um die Selektion gekürzt werden, ein Display-Callback darf nie zur Fragment-Failure werden.
* **Update**: [HANDOFF.md](HANDOFF.md) — #205 als implementiert/green vermerkt, neuer Owner-Gerätematrix-Punkt 11, `#190` aus den offenen Punkten entfernt.
* **No change**: [index.md](index.md), [DOMAIN.md](DOMAIN.md), [CONVENTIONS.md](CONVENTIONS.md), [HISTORY.md](HISTORY.md) — keine neuen/entfernten Konzeptdateien, kein neuer Domänenbegriff außerhalb des STATE-Abschnitts, keine superseded Einträge (der #205-Entwurf wurde ersetzt, nicht widerrufen).

## 2026-11-24 (#200 — der Settings-Getter schreibt nicht mehr beim Lesen)
* **Update**: [STATE.md](STATE.md) — Statuszeile (Tests, #200 zu) + Abschnitt `#200 — ein Settings-Getter darf nicht schreiben: der Flake hatte einen Thread`: falsche QueuedWork-Hypothese, die Messung (TempDirectory pro Testmethode, leerer Shadow, echte `SharedPreferencesImpl`), der Startup-Thread als Schreiber, der reine Lese-Getter, die zwei Regressionstests, die Doku-Korrekturen.
* **Update**: [PITFALLS.md](PITFALLS.md) — der #200-Eintrag ersetzt die falsche `apply()`-Hypothese; der „known flake"-Eintrag aus #178 ist als aufgelöst markiert; die #142-Lehre „Prefs leaken zwischen Testmethoden" ist korrigiert (kein Platten-Leak, sondern ein gleichzeitiger Schreiber); der #150-Eintrag verweist nicht mehr auf materialisierende Getter.
* **Update**: [CONVENTIONS.md](CONVENTIONS.md) — neue Regel: ein `SettingsStore`-Getter ist ein reiner Lesevorgang, nur einmalige Legacy-Migrationen persistieren.
* **Update**: [DECISIONS.md](DECISIONS.md) — Eintrag `#200 — der Retention-Default wird nicht mehr beim Lesen persistiert`: Entscheidung, Begründung (Fremd-Thread liest), gewollte Nebenwirkung (injizierter Default ≠ 30 Tage), Diagnose-Korrektur, Verification.
* **Update**: [HANDOFF.md](HANDOFF.md) — #200 als geschlossen vermerkt, der „Not scheduled"-Eintrag entfernt.
* **No change**: [ARCHITECTURE.md](ARCHITECTURE.md), [DOMAIN.md](DOMAIN.md), [index.md](index.md) — keine neue/entfernte Komponente, kein neuer Begriff, kein neues Konzeptfile.

## 2026-11-20 (#184 — idempotent fragment uploads)
* **Update**: [STATE.md](STATE.md) — status line (806 tests, 2/5 #152 children) + a `#184 — idempotent fragment uploads` section: the one-`@PartMap` shape, empty map = unchanged wire, retry identity, the client-side `putIfAbsent` second line, the Retrofit wildcard pitfall, the `session.json` deviation.
* **Update**: [DECISIONS.md](DECISIONS.md) — entry `#184 — Identität als EIN @PartMap, leerer Map = unverändertes Wire-Format`: one map instead of three parts (future fields cost no signature churn), all-or-nothing identity, 0-based `fragment_index` vs 1-based log `chunk`, client-side guarantee, no duplicated bundle fields.
* **Update**: [CONVENTIONS.md](CONVENTIONS.md) — API Patterns: multipart extras only through the `@PartMap`, `@JvmSuppressWildcards` mandatory, empty map = unchanged wire; plus the matching PITFALLS note about Retrofit's lazy validation.
* **Update**: [PITFALLS.md](PITFALLS.md) — two entries: Retrofit rejecting a Kotlin `Map` parameter only at the first real call (and why a mocked interface proves nothing), and MockK recording defaulted `@Part` params as `null` instead of `any()`.
* **Update**: [ARCHITECTURE.md](ARCHITECTURE.md) — `SttRequestRunner` row: the #184 identity fields, the 0-based index, and the empty-map compatibility contract.
* **Update**: [HANDOFF.md](HANDOFF.md) — #184 checked off (2/5), #183 promoted to next with the `isTransientHttp` seam named.
* **No change**: [index.md](index.md) — no new or removed concept file.

## 2026-11-20 (#182 — durable fragment journal)
* **Update**: [STATE.md](STATE.md) — status line rewritten (802 tests, 1.5.1/10501, no version bump) and a new `#182 — durable fragment journal` section: layout, composed-upload choice, transcriber/controller wiring, the delete-in-`discardFragmentSession` lifecycle, the 256-MB cap without an age rule, the backup exclusions, the deliberate scanner cut, and the `SessionDirTime` extraction.
* **Update**: [DECISIONS.md](DECISIONS.md) — entry `#182 — Das Journal hält den KOMPONIERTEN Upload, nicht das nackte Fragment`: composed upload (byte-identical retry for #184, ~2 % overhead), journal ≠ evidence store, the fragment root stays in `cacheDir`, no age cap, no scanner in #182, `SessionDirTime` extracted.
* **Update**: [CONVENTIONS.md](CONVENTIONS.md) — one bullet in "Configuration" for the journal: `filesDir/journal/<sessionId>/`, always on, atomic writes, audio before record, `PENDING`/`DONE`/`NEEDS_MANUAL`, deleted in `discardFragmentSession()`, 256-MB prune rule, backup exclusion.
* **Update**: [PITFALLS.md](PITFALLS.md) — new top entry `cacheDir/fragments/<sessionId>/ is a CACHE, not durability`: recovery features must read the journal, which must never be gated by a setting nor moved into the preparer's pruned working set.
* **Update**: [ARCHITECTURE.md](ARCHITECTURE.md) — `FragmentJournal` row in the components table + `FragmentJournal`/`SessionDirTime` in the `:core` pipeline list.
* **Update**: [HANDOFF.md](HANDOFF.md) — #182 checked off (1/5), #184 promoted to next with its `@PartMap`/mock-churn note, the scanner explicitly assigned to #185, a new owner device check for the force-stop acceptance.
* **No change**: [index.md](index.md) — no new or removed concept file.

## 2026-11-20 (#177/#178 — IME park state, Workbench redesign)
* **Update**: [STATE.md](STATE.md) — status line rewritten for 1.5.1/10501, 767 tests, no version bump; two new sections `#177 — the IME parks after a send` (parking policy, suppressed auto-start, field identity, the instance-state limit, tests) and `#178 — the Workbench redesign` (store actions, drawables, icon header + three zones, the one-entry ring regression fixed in `1cf992c`).
* **Update**: [DECISIONS.md](DECISIONS.md) — two entries: `#174/#177 — Post-Send ist ein UI-Parkzustand, kein Controller-State` (why PAUSED was rejected, what the parked bar renders, the tradeoff) and `#178 — Die Workbench hat zwei Polish-Sites und ein read-only Polished-Feld` (two buttons/one language, read-only polished, store-based promote/clear, `WorkbenchActions`, the ring asymmetry).
* **Deprecation**: three superseded statements moved to [HISTORY.md](HISTORY.md) — #173 decision 1 (focus in Polished ⇒ `POLISHED_EDIT`), #173 decision 8 (content-sized accordions with chevron and preview), #176's "Re-polish is the only polish site"; each leaves a one-line pointer in [DECISIONS.md](DECISIONS.md).
* **Update**: [CONVENTIONS.md](CONVENTIONS.md) — Workbench section: two dictation targets, permanently visible fields with a read-only `TextView` Polished field, `WorkbenchActions` as the only enablement source, two polish buttons sharing one per-visit language, no background on action rows/icon buttons, tint from the layout (drawable keeps `fillColor #FF000000`).
* **Update**: [PITFALLS.md](PITFALLS.md) — three entries: `claw`'s 1.9-GB `/tmp` tmpfs and the live-`AudioRecord` Robolectric test (disk quota → executor exit 2 with 0 recorded failures), `:app:cleanTest`/`--rerun` being no-ops under Gradle 9.5 + configuration cache, and the known gate flakes (`SettingsStoreTest` retention default, `VoiceSessionControllerTest` IDLE/PROCESSING).
* **Update**: [HANDOFF.md](HANDOFF.md) — reduced to open tasks: the parked-bar owner check, the two unlabeled icon buttons, and #156 (stale screenshots).
* **Update**: [ARCHITECTURE.md](ARCHITECTURE.md) — `WorkbenchActivity` row (icon header, three zones, read-only polished, `WorkbenchActions`, store-based promote/clear), the IME success path (no switch/hide, parked bar since #177), `SettingsStore` no longer lists `switchBackAfterSend`.
* **Update**: [DOMAIN.md](DOMAIN.md) — Raw/Polished row (read-only polished), one Polish row replacing the duplicated Re-polish rows, new Promote/Clear row.
* **No change**: [index.md](index.md) — no new or removed concept file; every pointer still describes the bundle.
* **Update (not knowledge)**: none — this run touched `docs/ai/` only.

## 2026-11-20
* **Update**: [STATE.md](STATE.md) — status line + `#176 — the Workbench polishes only when asked` section: what was removed, what invariants remain, the test rename, verification.
* **Update**: [HANDOFF.md](HANDOFF.md) — new top entry for #176 with the open button-layout question and the owner device check.
* **Update**: [DECISIONS.md](DECISIONS.md) — #176 entry, superseding the Auto-LLM clauses of #171 (D8) and #173 (decision 9); the page/accordion/MRU model of #173 is unaffected.
* **Update**: [ARCHITECTURE.md](ARCHITECTURE.md) — the `WorkbenchActivity` row no longer lists an Auto LLM checkbox and names re-polish as the only polish trigger.
* **Update**: [CONVENTIONS.md](CONVENTIONS.md) — the "only polish site" bullet drops the Auto-LLM exception and states that nothing polishes automatically.
* **Update**: [DOMAIN.md](DOMAIN.md) — the `Auto LLM` vocabulary row becomes `Re-polish` (the single explicit polish action).
* **Update (not knowledge)**: `docs/help.html` — the Auto LLM bullet replaced by the explicit Re-polish description.

## 2026-11-19
* **Update**: [STATE.md](STATE.md) — status line + a `#174/#175 — the keyboard handover` section: Send inserts and nothing else (no switch, no hide, `PostSendSwitchPolicy` deleted), `switchBackAfterSend` removed end to end, the `open`/`internal` test seam, the settled Workbench/IME boundary, the #116 option-C confirmation, and the flake note.
* **Update**: [HANDOFF.md](HANDOFF.md) — new top entry for #174 (`b0ab577`) + #175 (`20f7141`) with the contract table and the owner spot check (think + OPO).
* **Update**: [DECISIONS.md](DECISIONS.md) — two entries: #174 (Send behaves like a normal keyboard, why the policy object died instead of inverting, hiding is the platform's contract, the testability seam) and #175 (the exit decides the keyboard, no prefs migration, the #166 boundary clarified, #116 option C confirmed).
* **Update**: [PITFALLS.md](PITFALLS.md) — the "single insertion site" entry now names `handleCompletedSuccess`/`insertResultText`; the `requestHideSelf` entry drops the "correct in the post-send path" clause; the #138 teardown entry marks the switch as removed in #174 while keeping the consume-once guard for rotation/process death/a later X.
* **Update**: [ARCHITECTURE.md](ARCHITECTURE.md) — the `PostSendSwitchPolicy` row removed, the `SwitchTargetPolicy` row notes the post-send path no longer uses it.
* **Update (not knowledge)**: `README.md` + `docs/help.html` — the user-facing contract (Send keeps the keyboard; the gear exception; the Workbench dictation route via the keyboard's mic).
* **Update**: [STATE.md](STATE.md) — status line for 2026-11-19 (#165 fixed on `main` `4955ccc`, 422 `:app` tests green, no version bump) plus a `#165 — auto-start recorded silence on a cold start` section: the emulator repro (denial lines, the `show_ime_with_hard_keyboard` precondition, the auxiliary-IME flow), the fix layering, the after-trace, the tests, and what stays unverified (S24, Android 12/13).
* **Update**: [HANDOFF.md](HANDOFF.md) — new top entry (2026-11-19) for the shipped fix and three open tasks (19: reporter confirmation blocks the close; 20: optional `PolishedRecognitionService` guard reuse; plus the existing owner items renumbered around them).
* **Update**: [PITFALLS.md](PITFALLS.md) — one entry: "input view started" is not "window visible" — the mic FGS loses its while-in-use type when started before the first draw (AOSP line numbers for `onWindowShown`/`isInputViewShown`, the `AutoStartTiming` gate, `MicAccessPolicy`, and the emulator recipe).
* **Update**: [DECISIONS.md](DECISIONS.md) — the #165 decisions: the first draw is the gate (not a platform callback), warm windows keep the old behaviour, the 1500 ms timeout drops only a window that is not showing, layer 2 aborts without retry, no version bump.
* **No change**: [index.md](index.md), [ARCHITECTURE.md](ARCHITECTURE.md), [CONVENTIONS.md](CONVENTIONS.md), [DOMAIN.md](DOMAIN.md), [HISTORY.md](HISTORY.md) — no new concept file, no structural change, no superseded entry.
* **Update (same run)**: [#165](https://github.com/georgernstgraf/polished-recognition/issues/165) closed 2026-11-19 on the owner's instruction — [HANDOFF.md](HANDOFF.md) task 19 and the top entry now record the closed state plus the reopen-on-recurrence route; [STATE.md](STATE.md) carries the same note.
* **Update (same run)**: [HANDOFF.md](HANDOFF.md) task 20 now carries the #166 cross-reference — the optional `MicAccessPolicy` guard for the bound `RecognitionService` is moot if the editor-mode epic retires that service (a "one surface only" answer there would also close the deferred `RECOGNIZE_SPEECH` activity route #110).
* **Creation**: `dictation/` (`Dictation`, `DictationStore`) — one JSON file per Workbench dictation under `filesDir/dictations/`, temp file + rename, strictly increasing `createdAtMs`, FIFO cap from the settings; `delivery/ClipboardDelivery` (sensitive on API 33+, empty no-op); `ui/WorkbenchActivity` + `layout/activity_workbench.xml`, `ui/FifoNavigation`, `ui/WorkbenchInsets`; `service/LlmTargetPolicy` (#167–#171).
* **Update**: [CONVENTIONS.md](CONVENTIONS.md) — new "Workbench" section (launcher single-entry rule, dictations are files not prefs, target decided at recording start, clipboard only via `ClipboardDelivery`, LLM forced off without writing `raw_mode`, the Workbench is the only polish site, own insets + contrast matrix); the raw-label rule (`UI never shows raw`) is explicitly amended (#169).
* **Update**: [PITFALLS.md](PITFALLS.md) — four entries: `:app:test` does not accept `--tests` (use `:app:testDebugUnitTest`), relaxed MockK mocks do not store property values, `applyUiState` re-enables quick settings so a per-target disable must live in `setQuickSettingsEnabled`, and `AutoCompleteTextView` is not an `AdapterView` (#168, #171).
* **Update**: [DECISIONS.md](DECISIONS.md) — the 2026-11-19 #166 entry: FIFO is a directory, target decided at recording start, session-scoped forced raw, the Workbench as the only polish site, delivery asymmetry vs #138, launcher move, own insets, the three explicit document amendments.
* **Update**: [DOMAIN.md](DOMAIN.md) — statelessness scoped to the service/pipeline (the app is stateful by design) plus the Workbench vocabulary (dictation, delivery, re-polish, Auto LLM) shared with `diktatwerkstatt`.
* **Update**: [ARCHITECTURE.md](ARCHITECTURE.md) — Workbench row in the UI table, the entry-point paragraph now names `WorkbenchActivity` as the only `MAIN`/`LAUNCHER`, the overview distinguishes the dictation surface from the editing surface.
* **Update**: [STATE.md](STATE.md) and [HANDOFF.md](HANDOFF.md) — epic #166 complete (six sub-issues, five commits), 712 tests green, the API-35 AVD smoke test, the owner device check as the open item; task 20 keeps the `MicAccessPolicy` follow-up (the epic kept the bound service).
* **Update**: `AGENTS.md` (repo root) — the false "AppCompat + Material" tech-stack line is corrected (platform Views only since #61), and storage now names the dictation files (#166, observed drift).
* **No change**: [index.md](index.md) — no new concept file; the existing pointers still describe the bundle.

## 2026-11-14
* **Update**: [STATE.md](STATE.md) — status line for 2026-11-14 (epic #158 complete, 392 `:app` / 171 `:core` / 55 `:analyzer` tests, `lintDebug` green, both devices carry the palette) plus a `#158 — UI contrast gate COMPLETE` section (the 16-failure baseline, the palette pairs, the gate asymmetry, the render/static/device proofs); the old "four open sub-issues" line is marked superseded (#158, #161–#164).
* **Update**: [HANDOFF.md](HANDOFF.md) — new top entry (2026-11-14): the epic summary, the six commits, the device state (opo + A03 on the palette build, in place, config preserved), the four open items (no version bump, below-the-fold pixel gap, `UNMEASURED` button class, inert `TextContrast`) and the observed `SettingsStoreTest` retention flake; open task 12 (#158) marked done, "Last cleared" moved to 2026-11-14 (#158).
* **Update**: [PITFALLS.md](PITFALLS.md) — six new entries: `?android:attr/windowBackground` can be a drawable (`getColorStateList` throws; fall back to `?android:attr/colorBackground`), `View.draw(Canvas)` does not apply the view's own alpha, Robolectric's default density is mdpi, the platform `textColor*` are translucent and fail WCAG in light mode, `TextContrast` is not a lint check in AGP 9.1.1, and `uiautomator dump` prunes off-screen `ScrollView` children (#161–#164).
* **Update**: [CONVENTIONS.md](CONVENTIONS.md) — new "Contrast gates" section: the device script (thresholds incl. the large-text rule, measurement rules, exit codes, never fake a mode, unlock requirement, one screenful), the opt-in render test's promotion and flake criteria, and the shared `ui/contrast/` test-helper package (#161–#164).
* **Update**: [DECISIONS.md](DECISIONS.md) — the owner decisions of 2026-11-13/14: the app owns the text palette (own selectors, OEM-proof), OEM-theme runs measured but not gated, disabled text must reach 3.0, the inert `TextContrast` declaration stays, the render test stays opt-in, no version bump (#161–#164).
* **No change**: [index.md](index.md) — no new concept file; the existing pointers still describe the bundle (#158).

## 2026-11-13
* **Creation**: [index.md](index.md) — bundle entry point with `okf_version: "0.2"` (#159).
* **Creation**: [log.md](log.md) — this per-run persistence log (#159).
* **Migration**: Added the OKF v0.2 frontmatter envelope (`type`, `title`, `description`, `tags`, `generated`, `status`, and `verified` where the content was re-checked against the codebase) to [HANDOFF.md](HANDOFF.md), [STATE.md](STATE.md), [DECISIONS.md](DECISIONS.md), [ARCHITECTURE.md](ARCHITECTURE.md), [CONVENTIONS.md](CONVENTIONS.md), [PITFALLS.md](PITFALLS.md), [DOMAIN.md](DOMAIN.md) and [HISTORY.md](HISTORY.md). Bodies unchanged (#159).
* **Update**: README entry point now points at `docs/ai/index.md`; `AGENTS.md` bootstrap starts at the index (#159).
* **Update**: Added one entry to [PITFALLS.md](PITFALLS.md) — theme attributes must never be taken as an `Int` (`TypedValue.data` is a resource id for selector/OEM night themes), a read-only display needs the enabled variant, and a contrast test is only meaningful under a selector-based theme (#160).
* **Update**: Regenerated [STATE.md](STATE.md) (status line + a `#158` section) and added open task 12 to [HANDOFF.md](HANDOFF.md) — the UI-contrast epic #158 with its four remaining sub-issues (#161–#164); #159 and #160 closed (#158, #159, #160).
* **Update**: Added two entries to [PITFALLS.md](PITFALLS.md) — the IME window is edge-to-edge from API 35 (up to API 34 the platform insets the IME content itself, so the same listener is inert there; rebuild padding from the XML base, `max` not sum), and the emulator recipe that produced the evidence (3-button nav via `cmd overlay enable-exclusive`, the API-31 SystemUI ANR, the `ime enable` retry). [DECISIONS.md](DECISIONS.md) records the "no SDK gate" decision; [STATE.md](STATE.md) has the `#157` section, [HANDOFF.md](HANDOFF.md) the run entry (#157).

## 2026-11-21
* **Update**: [STATE.md](STATE.md) — new `#185` section (journal session marker, recovery scan, `JournalReplay`, auto-recovery on Workbench entry, the recovery screen, share) + refreshed status line (902 tests, no version bump).
* **Update**: [HANDOFF.md](HANDOFF.md) — #185 done (4/5 of #152), #186 is next, plus an owner device check for the recovery paths.
* **Update**: [ARCHITECTURE.md](ARCHITECTURE.md) — `EchoTrim` + `JournalReplay` rows, the `FragmentJournal` session states, the recovery-screen row, and the Workbench banner.
* **Update**: [CONVENTIONS.md](CONVENTIONS.md) — new "Recovery / pending transcriptions (#185)" section (journal is the only source, marker states, record-before-delete, replay the gaps, yield to the live path, store-before-delete, `RecoveryActions` enablement).
* **Update**: [DOMAIN.md](DOMAIN.md) — "Recovery (leftovers)" vocabulary table (pending transcription, finished/completed, dismissed, re-run, restore, yield).
* **Update**: [DECISIONS.md](DECISIONS.md) — 2026-11-21 entry: the bundle marker as the session state, no separate audit file, gap-only replay, auto-retry policy + once-per-process guard, WAV reconstruct only for sharing.
* **Update**: `docs/help.html` — a "Pending transcriptions" section plus the #178 drift fix (Polished is read-only, the button is "Polish").

## 2026-11-22
* **Update**: [STATE.md](STATE.md) — status line + the #187 section extended: AAPT literal-`%` strings declare `formatted="false"`, the two unseen warnings fixed, update notices ignored instead of baselined, baseline 91 → 77, the `lintVitalRelease` notes accepted and documented; the #163 gate line carries the amendment.
* **Update**: [CONVENTIONS.md](CONVENTIONS.md) — "Compiler warnings (#187)" became "Build-output warnings (#187)" (read the build log, literal `%` → `formatted="false"`, `Locale.ROOT` prefill, ignored update notices, `checkReleaseBuilds` stays default, test-only warnings outside the claim).
* **Update**: [DECISIONS.md](DECISIONS.md) — 2026-11-22 entry: the release-build output is a gate, update notices are never baselined, the vital-baseline notes are accepted (owner decision).
* **Update**: [PITFALLS.md](PITFALLS.md) — three entries: `lintVitalRelease` is fatal-only so a global baseline always looks stale there; a literal `%` in `strings.xml` is a format specifier; a baselined "newer version" notice stops matching on its own.
* **Update**: [HANDOFF.md](HANDOFF.md) — #187 reopened and closed again; the baseline burn-down recorded as not scheduled.
* **No change**: [index.md](index.md) — no new concept file.

## 2026-11-22 (#188 — incomplete dictations in the Workbench ring)
* **Update**: [STATE.md](STATE.md) — status line (962 tests, #152 at 4/5 with #186 as the only open child) + a `#188` section: merged ring, the read-only leftover page, the when-line, the four actions on the page, the retired screen, the count+jump banner, the string/help drift fixed en route.
* **Update**: [HANDOFF.md](HANDOFF.md) — #188 checked off (5 of 5 done), the owner device check for the merged ring (banner jump, editable-again Raw, a delivery on a leftover page).
* **Update**: [ARCHITECTURE.md](ARCHITECTURE.md) — the recovery-screen row is gone; new `WorkbenchPage`/`WorkbenchPages`, `WorkbenchStamp` and `RecoveryRestore`/`RecoveryShare` rows; the `WorkbenchActivity` row carries the merged ring.
* **Update**: [CONVENTIONS.md](CONVENTIONS.md) — "Recovery / pending transcriptions" became "Incomplete dictations"; a new "The merged ring (#188)" section (one ring, two page kinds; the two truths; a leftover page cannot take focus; the tiered stamp; restore/share contracts).
* **Update**: [DOMAIN.md](DOMAIN.md) — "incomplete dictation" is THE term ("pending transcription" retired), plus `leftover page`, `Retry STT`, `when-line`.
* **Update**: [DECISIONS.md](DECISIONS.md) — 2026-11-22 entry: merged view without a schema change, chronological ordering by last change, a read-only leftover page, retiring the screen into the page.
* **Update**: [PITFALLS.md](PITFALLS.md) — three entries: a page without text must not be focusable; the coordinator's single `onFinished` slot; three `DictationStore` instances with a per-instance lock.
* **Update**: `docs/help.html` — the "Pending transcriptions" section became "Unfinished dictations": the page, Retry STT, the banner jump, and the never-aged-away rule.
* **No change**: [index.md](index.md) — no new or removed concept file.

## 2026-11-24 (#193 — the lint baseline is gone; #198/#199 test defects)
* **Update**: [STATE.md](STATE.md) — status line (1033 tests, no baseline, `warningsAsErrors`, #193/#198/#199 shipped, #200 open), a `#193` paragraph (71 fixes + 6 documented exceptions) and a `#198`/`#199` paragraph; the old "baseline accepted" notes marked obsolete/superseded.
* **Update**: [HANDOFF.md](HANDOFF.md) — new top sections for #193 and #198/#199, #200 as the one known red-spot in the push gate, #156 amended with the two Workbench shots and the #189 sequencing, the lint burn-down bullet removed (done).
* **Update**: [DECISIONS.md](DECISIONS.md) — 2026-11-24 entries: the backlog at zero with six documented exceptions and the inverted `XmlColorPolicyTest` rule 6; a capture/worker throwable parks PAUSED instead of killing the process.
* **Update**: [PITFALLS.md](PITFALLS.md) — four new entries: `exit value 2` is the app's crash handler (not the heap), `flush()` must not truncate the spool under the worker, a Robolectric test must not rely on the shadow `AudioRecord` having written, `SharedPreferences.apply()` is really deferred in Robolectric (#200); the baseline entries marked obsolete.
* **No change**: [ARCHITECTURE.md](ARCHITECTURE.md) — resources and build config only, no new module or entry point. [CONVENTIONS.md](CONVENTIONS.md), [DOMAIN.md](DOMAIN.md), [index.md](index.md) — no new vocabulary or concept file.
