---
type: State
title: Project State — Workbench
description: The Workbench pages, the entry model, the polish sites and the recovery surface.
tags: [knowledge, state, workbench]
generated: { by: opencode/knowledge-persistence, at: 2026-11-26T16:10:00Z }
verified: { by: opencode/knowledge-persistence, at: 2026-11-26T16:10:00Z }
status: stable
---

# Project State — Workbench

Moved out of `STATE.md` in #206; sections are verbatim, newest first. The current status line, focus and pending items stay in `STATE.md`.

### #213-Icon-Welle (2026-11-26, `b5cefbd`): rahmenlose, größere Icons, Tooltips, Header space-around

- **Was:** Owner-Wünsche zur gleichen #213-Optik-Welle: (1) der Polish-Button
  soll icon-only werden — Text und Rahmen weg, (2) Mistkübel/Promote/Copy
  sollen ohne den eigenen Kasten größer dargestellt werden, wie die
  IME-Buttons, (3) alle Workbench-Buttons sollen Long Press mit Hilfetext
  unterstützen, (4) da Rahmen/Hintergrund wegfallen, größer gezeichnet
  werden.
- **Ursache des „Kastens":** die Workbench-`ImageButton`s deklarierten nie
  ein `android:background` — der sichtbare Rahmen kam vom **Default**-
  Background des ImageButton-Stils unter `Theme.DeviceDefault`. Auch die
  AppButton-Polish-Buttons trugen den 1dp-Border.
- **Fix:** der neue gemeinsame Stil `WorkbenchIconButton` in `themes.xml` —
  `?android:attr/selectableItemBackgroundBorderless` (das IME-Muster,
  transparent bis zum Touch, daher night-mode-sauber), **48dp** Touch-Fläche,
  Icon per `scaleX/scaleY 1.5` gezeichnet (exakt das IME-Pattern). Gilt für
  ALLE Workbench-Icon-Buttons: Header (◀ ⚙ ＋ ▶), Polish ×2, Mistkübel,
  Promote, 3× Copy, Copy-Raw.
- **Polish icon-only:** `Button` + AppButton + Text + `drawableEnd` →
  textloses `ImageButton` mit `ic_polish`, `workbench_polish` ist jetzt
  contentDescription **und** Tooltip. Der In-Flight-Zustand („Polishing…")
  steht nicht mehr im Text, sondern dimmt das Icon (`alpha 0.4`, Konstante
  `POLISHING_ALPHA`); `workbench_polishing`-String entfernt. Der
  Zusatz-Tooltip kommt nativ: `android:tooltipText` auf jedem Icon-Button
  (API 26+, Min-SDK 30 — der Tooltip zeigt die Sprechblase beim Long Press
  und löst die Aktion NICHT aus), Text = die jeweilige
  contentDescription-Resource.
- **Header space-around:** die fünf Header-Kinder sitzen in gleichen
  Gewichts-Slots (`0dp` + `weight 1`, Inhalt zentriert) — gleichmäßiger
  Zwischenraum um jedes Element, das Positionslabel mittig im Slot.
- **Tests:** `WorkbenchLayoutPolicyTest` — die alte Regel „ImageButton ohne
  `android:background`" wurde zur Pin-Regel „jeder ImageButton trägt den
  WorkbenchIconButton-Stil + Tooltip"; der Collector liest jetzt auch
  Element-Texte, damit der Stil selbst in `themes.xml` gepinnt wird
  (borderless, 48dp, 1.5×, centerInside). Neu: Header-Space-Around-Pin.
  `WorkbenchActivityTest` — die zwei „Polishing…"-/„Polish"-Text-Assertionen
  wurden zu Alpha-Pins (0.4 in flight, 1.0 danach). `:app`-Suite + lint
  grün, Release auf BEIDEN Geräten installiert (OnePlus `f6de166c` 11:15,
  Samsung `R7SRB06XF5K` 11:16, In-place).
- **Owner-Check (diese Welle):** (1) kein Kasten um Mistkübel/Promote/
  Copy/Polish in beiden Modi, (2) die Icons lesen sich größer, (3) Long
  Press auf jedem Icon zeigt die Sprechblase ohne die Aktion auszulösen,
  (4) der Header verteilt gleichmäßig, (5) laufender Polish dimmt das
  Sternchen.

## #213 — Polished-Zone: Aktionen in der Summary-Zeile, unten nur Copy (2026-11-26, implementiert — offen bis Owner-Gerätecheck; KORREKTUR-Welle `63e2a52`)

- **Was:** Owner-Wunsch: eine Zeile weniger im Workbench-UI. Die
  Polished-Summary-Zeile trägt jetzt **chevron · „Polished" · Mistkübel ·
  Promote · Copy** (genau diese Reihenfolge); die bisherige eigene
  Promote+Mistkübel-Zeile über der Summary (`workbench_actions_b` mit
  48dp-Icons) ist weg. Unter dem read-only Polished-Feld steht **nur noch das
  Copy-Icon** (`workbench_copy_polished_bottom`, 40dp) — die Zone-C-IDs
  `workbench_promote_c`/`workbench_clear_polished_c` sind getestet aus den
  Ressourcen entfernt (`getIdentifier == 0`).
- **IDs gehalten:** `workbench_promote_b`/`workbench_clear_polished_b` zogen
  nur um (in die Summary-Zeile, 40dp) — die Activity-Logik blieb bei den
  bekannten IDs. Neu ist nur der Bottom-Copy-Button: derselbe
  `deliver(polished)`-Pfad wie `workbench_copy_polished`, dieselbe
  Enable-Policy (`actions.copyPolished`).
- **Buttons:** Alle Text-Buttons der App tragen jetzt `@style/AppButton`
  (`app_button_bg`: 1dp-Border in `textColorSecondary`, 6dp-Ecken, Ripple,
  nur Theme-Attribute); die Polish-Buttons wechselten auf `drawableEnd`
  (Text dann Icon). Regel in CONVENTIONS.md „AppButton".
- **Tests:** `WorkbenchLayoutPolicyTest` — Zonen-Pin neu (Zone C nur Copy,
  die _c_-IDs verboten), Summary-Zeilen-Pin (chevron · label · trash ·
  promote · copy); `WorkbenchActivityTest` — Bottom-Copy liefert denselben
  Text wie die Summary-Zeile, Bottom-bar-holds-only-copy-Pin. Suite + lint
  grün, Release auf dem OnePlus (`f6de166c`) installiert; Samsung (A032F)
  war nicht angeschlossen.
- **Owner-Check (Teil 1 von #213):** die Summary-Zeile liest
  chevron · Polished · 🗑 · ↑ · ⧉ in dieser Reihenfolge, eine Zeile weniger
  als vorher; unter dem Polished-Feld nur noch ⧉ mit demselben Effekt; die
  Polish-Buttons sind sichtbar gerahmt mit leicht gerundeten Ecken, Icon
  NACH dem Text. (Das rote IME ist Teil 2, siehe state/ime-field.md.)

### #213-Korrektur (2026-11-26, `63e2a52`): Cursor + Button-Padding

- **Cursor-Befund:** Diktat in das Rohfeld — nach dem Einfügen stand der
  Cursor am ANFANG des Feldes. Ursache NICHT das IME (dessen Terminal-Pfad
  lässt den Cursor korrekt am Ende des Eingefügten), sondern die Workbench:
  `onTranscriptArrived` schrieb den (identischen) Text per `setText` neu,
  was die Selektion auf 0 setzt.
- **Fix:** `writeRaw(text)` — ein UNVERÄNDERTER Text wird nicht mehr gesetzt
  (Selektion bleibt, wo das IME sie gelassen hat), ein VERÄNDERTER Rewrite
  platziert die Selektion ans Ende des neuen Textes. Genutzt in `showRaw`
  und im `rewriteFields`-Block von `reload()`. Zwei neue Pins in
  `WorkbenchActivityTest` (Cursor bleibt am Ende; Rewrite → Ende).
- **Button-Padding-Befund:** die Polish-Buttons hatten viel vertikalen,
  null horizontalen Innenabstand — das 24dp-Icon überragte die 14sp-Schrift
  (zentriert → Luft oben/unten), horizontal klebte der Text an der Border
  (das Shape-Drawable hat kein Padding, der AppButton-Stil setzt keins).
- **Fix:** `AppButton` setzt explizit 4dp Padding an ALLEN vier Seiten +
  `includeFontPadding=false`; `ic_polish` schrumpft auf 16dp intrinsisch
  (nur in den beiden Polish-Buttons genutzt). Border-zu-Inhalt liest sich
  jetzt rundherum gleich.

## #210 — Rotation erhält die gezeigte Seite; kein neues Diktat (2026-11-25, implementiert — offen bis Owner-Gerätecheck)

- **Was:** Owner-Befund: Diktieren in einen Workbench-Edit, dann rotieren —
  der Resume/Rebind erzeugte ein NEUES Diktat statt den Edit fortzusetzen.
- **Ursache:** Die Workbench-Activity verliert ihren Seitenzustand bei
  Rotation (kein `configChanges` im Manifest ⇒ Recreation). `index`,
  `currentId`, `currentSession`, `composeRaw`, `composePolished` waren
  Instanzfelder ohne `onSaveInstanceState`; frisch erstellt fiel
  `currentSelection()` auf den leeren Ring ⇒ Compose-Slot ⇒ der
  auto-resumte Take bekam `pendingTarget = NEW` ⇒ `store.append`.
- **Fix (`246a22b`):** (1) `onSaveInstanceState` + `restore()` im `onCreate`
  — Index, Ids, Session und die Compose-Drafts; die erste `reload` folgt
  einer `pendingReloadSelection` (Id-basiert, der übliche Selection-Regel —
  ein Default-`currentSelection()` liest den noch leeren Ring). (2) Self-Heal
  in `onResume`: ist der Controller beim Attach schon RECORDING (das IME hat
  resumed, BEVOR die neue Instanz ihren Secondary-Listener hängte — das
  `StateChanged`-Event ist weg), läuft `onRecordingStarted()` explizit —
  **nach** `reload()`, weil es den gefüllten Ring liest.
- **Tests:** +2 `WorkbenchActivityTest` (`a rotation restores the shown
  stored page` via `saveInstanceState`+`create(bundle)`; `a take already
  recording at attach targets the restored page` — Controller vor dem
  Rebuild auf RECORDING, Store muss beim `onTranscriptArrived` RAW_EDIT
  treffen, nie appenden). Suite **771 grün** (forced), lint 0, Release auf
  dem OnePlus installiert. Kein Versions-Bump.
- **Owner-Check (Teil 1 von #210):** in einen gespeicherten Edit diktieren →
  rotieren → dieselbe Seite bleibt unter dem Benutzer (`n / m` unverändert),
  das Diktat landet im Edit, kein neuer Ring-Eintrag.
- Der Cold-Start-Fallback (Prozess-Tod, kein Bundle ⇒ Compose-Slot) bleibt
  bewusst bestehen und ist vom Test `a rotated screen starts on a fresh
  compose slot …` gepinnt.

## #209 — Collapse-Chevrons: vom 22sp-Glyph zum 32dp-Drawable (2026-11-25/26, CLOSED — Owner-Urteil)

- **Was:** Owner-Feedback: „the arrows to collapse the raw and polished field
  are visually way too small" — die #189-Glyphen (`▾`/`▸` als Text) standen
  bei 14sp in Secondary-Farbe und lasen sich als Deko, nicht als Bedienelement.
- **Erster Fix (`4bd3f19`):** Glyphen auf **22sp**; der Owner urteilte am
  2026-11-26: „besser geworden, aber noch immer zu klein".
- **Zweiter Fix (`f2847f5` + `1d9b1b4`, der dokumentierte Drawable-Follow-up):** beide
  Chevron-TextViews sind jetzt **`ic_chevron_down` ImageViews**
  (`?android:attr/textColorSecondary`-Tint; das Drawable zeigt nach unten,
  eingeklappt rotiert es −90° nach rechts — die Glyph-Konstanten
  `CHEVRON_OPEN`/`CHEVRON_CLOSED` sind entfernt). Nach Owner-Wunsch „eine
  Stufe größer" stehen beide bei **32dp** (erste Stufe war 24dp).
  `WorkbenchLayoutPolicyTest` pinnt ImageView + `ic_chevron_down` +
  `>= 32dp`. Suite erzwungen grün, `lintDebug` 0, Release auf beiden
  Geräten installiert (gradle + explizites `adb install -r`). Kein
  Versions-Bump. **Owner hat am 2026-11-26 abgenickt — Issue geschlossen.**
- **Falls es je wieder zu klein wirkt:** reiner Größen-Bump (40dp+), keine
  Rückkehr zu Glyphen.

## #208 — Promote leert Polished; der MRU-Ring bleibt; nur ＋ erzeugt (2026-11-25, CLOSED — Owner-Urteil „the functionality is good now")

- **Was:** Zwei Owner-Gerätebefunde: (1) nach dem Promoten hielt RAW **und**
  Polished denselben Text („same topic in one translation or the other" beim
  ◀-Blättern), (2) „promote/edit/polish cause a new dictation in the mru ring".
- **Promote ist ein Zug (bleibt):** `replaceRawWithPolished` leert den
  Polished-Slot (`copy(raw = polished, polished = null)`), strikter No-op bei
  `raw == polished`; Compose-Pfad leert `composePolished`; der Stored-Pfad
  zeigt das geleerte Feld sofort (`showPolished("")` — der Fehler in der
  ersten Welle war genau das fehlende Show, `syncPositions` schreibt Felder
  nie um). Nach dem Promoten sind beide Promote-Buttons disabled.
- **Zwischenstand, wieder zurückgenommen:** `a7c7d89` stellte den Ring auf
  Creation-Order (`createdAtMs`, kein Touch-Bump, `updatedAtMs` gelöscht).
  Owner-Korrektur: „the ring order should not have been changed … edit SHALL
  promote to youngest" — der MRU-Sprung war gewollt. `11e5b56` restauriert
  `updatedAtMs` als MRU-Schlüssel (Bump bei Änderung, Anzeige + Eviction,
  Edits schützen wieder vor Eviction); die Legacy-Aufforderung
  (Datei ohne Stamp → `createdAtMs`) deckt auch die Dateien des
  Zwischenstands ab.
- **Die geklärte Invariante:** nur das ＋-Icon (bzw. die Compose-Materiali-
  sierung) erzeugt ein Diktat — Promote/Edit/Polish/◀/▶/⚙ nie. Alle
  `append`-Stellen geprüft; gepinnt durch einen neuen Store-Test
  (Ring-Größe + Ids unverändert).
- **Tests:** 766 `:app`-Tests grün, `lintDebug` 0, Release auf dem OnePlus
  installiert. Kein Versions-Bump.
- **Owner-Check:** Promoten → Polished-Feld leer, beide Promote-Buttons
  disabled; ein altes Diktat editieren/polieren → es springt ans Ringende
  (`n / m` wird zur jüngsten Position) OHNE dass ein neuer Eintrag entsteht.

## #202/#203 — ein RAW-Edit wird beim Tippen gespeichert, Polish committet zuerst (2026-11-24, shipped)

- **Was:** Der Owner editierte RAW und drückte sofort Polish — das Ergebnis hing am alten Text und das Reload setzte den Edit zurück; „wirkt, als ob Editieren nicht funktioniert". Ursache war kein Feld-, sondern ein Commit-Fehler: `commitEdits()` lief nur beim Feldverlust/Pause/Navigation, ein Button-Klick verschiebt den Fokus im Touch-Modus aber nicht.
- **Fix:** (a) `persistRawDraft` mit 500-ms-Debounce aus dem TextWatcher schreibt den Draft über `DictationStore.updateRaw` — ohne `shownRaw` zu berühren (D11b-Lieferung bleibt) und ohne den Ring zu re-mergen (keine springende Seite); (b) `polishCurrent()` beginnt mit `commitEdits()`.
- **Verworfen:** Disk-Button über/unter RAW (Owner-Vorschlag im Issue) — die Policy ist implizites Speichern.
- **Tests:** 1035 → 1037; beide neuen Tests (`WorkbenchActivityTest`) waren gegen die alte Produktionsdatei rot (`git stash`), danach grün. `lintDebug` clean, `assembleRelease` grün. Kein Versions-Bump.

## #189 — Sektionen klappen wieder zu, Aufwärtspfeil, Mülleimer unten (2026-11-24, shipped)

- **Was:** Der Owner vermisste das RAW-Accordion aus #173 („wie HTML summary/details"), das #178 ersetzt hatte; der Promote-Pfeil (Material `publish`, Linie + Pfeil **nach unten**) zeigte für „Polished nach oben in RAW" in die falsche Richtung; der Mülleimer saß in Zone B (unter RAW) statt in der untersten Leiste unter Polished; Polished ließ sich gar nicht zuklappen.
- **Accordion:** beide Label-Zeilen sind wieder `<summary>` (Chevron `▾`/`▸` · Label · Copy-Button) mit einer einzeiligen, ellipsierten Vorschau (`*_preview`) im zugeklappten Zustand. Der Zustand lebt **einen Besuch lang** (nicht persistiert, nicht in `savedInstanceState`); `setSectionExpanded` wird nur durch einen Tap *geändert*, `updateChrome()` frischt Chevron/Vorschau nur nach dem Inhalt auf.
- **IME-Schutz (die eine neue Regel):** ein zugeklapptes Feld hat keinen Input-Connection — der Insert ginge verloren. Deshalb öffnet `onRecordingStarted()` RAW, ebenso `newEntry()` und der Eintritt (`onResume`, D15-Vertrag: Tastatur hoch beim Betreten).
- **Icon:** `ic_promote` ist jetzt `vertical_align_top` (Linie oben, Pfeil nach oben) — exakt das Spiegelbild des alten `publish`.
- **Zonen B und C sind identisch** (Owner-Entscheidung): `workbench_promote_{b,c}` + `workbench_clear_polished_{b,c}`, gleiche Ausrichtung; der Mülleimer steht damit im Bottom-Bar unter Polished.
- **Verification:** 1037 → **1043** Tests (`:app` 675 → 681), `lintDebug` clean (zwei echte Lint-Funde unterwegs behoben: `UseKtx` will `isVisible`, `RtlSymmetry` will `paddingStart` neben dem Chevron-`paddingEnd`), `assembleRelease` grün, pre-push. Kein Versions-Bump.
- **Tests:** `WorkbenchLayoutPolicyTest` invertiert (statt „Accordion ist weg" jetzt „beide Sektionen sind summary-artig und ihre Felder liegen darunter"), neuer Test „die beiden Aktionszeilen sind identisch" (SAX-Collector führt jetzt Eltern-Indizes, `childrenOf(id)`), `WorkbenchActivityTest` +5 (RAW zuklappen/aufklappen + Vorschau, Polished-Tab klappt nur Polished, leere Sektion ohne Vorschau, Aufnahmestart öffnet RAW, Mülleimer in Zone C). Kein Versions-Bump.

**#192 shipped 2026-11-23, amended the same day** (a pause belongs to one app — same app continues, another salvages; see DECISIONS): the owner's two follow-ups are in. (1) **One no-take bar at `0:00`**: `State.IDLE` renders the parked shape always — Send disabled, ↺ live as "New take", the timer visible and pinned to `0:00` — and a ↺ tap starts a take in fresh idle too (it used to require the park flag, i.e. a live control without an effect). **#201/#204 (2026-11-24) removed its stage line**: the bar is a plain pause at `0:00`, no sentence explains the ↺. (2) **A cancel only destroys what it can hand over**: `FragmentTranscriber.peek()` / `VoiceSessionController.salvageableText()` are non-destructive reads, and the foreign-app `FRESH_START` keeps a PAUSED take with buffered audio but no transcripts instead of cancelling it into silence (`pause cause=foreign-app kept=audio-without-transcripts pausedMs=…` in `ime-lifecycle.log`). **Device-verified by the owner on `f6de166c` and CLOSED 2026-11-24** — the whole pause matrix works (same app continues, another app salvages, X continues after minutes, hiding while RECORDING stops the mic, fresh `0:00` bar whose ↺ starts a take). One acceptance item was **replaced, not implemented**: the post-send bar need not be distinguishable from a paused one, because the owner never wanted that state (2026-11-24, answer "A") — since #201/#204 a bar with nothing buffered is a plain pause at `0:00` with no sentence, and the park flag only suppresses the auto-start.

**#191 shipped 2026-11-23** (device finding: on an incomplete dictation the four actions sat at the very bottom, so the owner paged 1/3 · 2/3 · 3/3 before finding Retry STT): they now sit directly under the when-line, and the banner is an accent bar under the header reading `<n> incomplete dictation(s) · <newest stamp> — tap to open`, still GONE at zero and still jumping to the newest leftover page. No automatic page change.

**#118 Phase C shipped and the issue closed 2026-11-23** (the rebind storm's UI churn): `service/RebindChurn` coalesces the four per-cycle works — the render (over a `UiSignature` of `state`/`postSendParked`/`llmForcedOff`), the notification text, the microphone FGS assert (now per instance, so one storm does not re-enter `startForeground`) and the breathing animation, whose cancel+recreate was the most visible churn (the phase jumped back on every cycle). Nothing about text insertion changed. Measured in the existing stream: every `onStartInputView` line in `ime-lifecycle.log` now ends in `churn=render:<r> fgs:<f> notify:<n> breath:<b>` and reports the interval that just ended, so an identical repeat reads all zeros. **#118 Phase B is no longer pending work: it is superseded by #190's terminal classification `MISMATCH` + before-window guard** (the raw-rescue half shipped in #186). Owner check: rotate while dictating and read one `churn=` line.

## #188 — incomplete dictations are pages of the Workbench ring (2026-11-22, shipped)

- **Was:** #185 kept the audio and gave it a screen — but that screen was a second list over the same question ("what is still waiting?"), reachable only through a banner, with its own rows, its own empty state and its own copy of the restore/share code. The ring and the leftovers were two places a dictation could be.
- **Merged ring (`ui/WorkbenchPage`, pure):** `WorkbenchPage` is `Stored(dictation)` or `Leftover(SessionSummary, hasAudio)`; `WorkbenchPages.merge` orders ascending by the page's last change — `updatedAtMs` for stored, `startedAtMs` for a leftover, an undated leftover LAST, ties by id/sessionId (total order) — so the last element is "newest", the direction `DictationStore.loadAll()` already had. No store schema change, no second source of truth: a page is a view over `DictationStore` (text) and `FragmentJournal` (audio).
- **A leftover page has no text.** Raw is empty, `isEnabled`/`isFocusable` false; `DictationTarget` is `NEW` there, so a delivered transcript APPENDS a dictation (the field cannot take focus, so the IME never commits into it). The empty hint reads "Not transcribed yet — Retry STT or start a new dictation".
- **The when-line (`ui/WorkbenchStamp`, pure):** `HH:mm` same local day, `EEE HH:mm` ≤ 7 local days, `d MMM` (+ `yyyy` when the year differs); local DATES decide the tier (a DST night stays one day), 24-hour time, names from the locale. A leftover continues the same line with `seconds · fragments · reason` (`workbench_leftover_detail` + `RecoveryReason`).
- **The four actions moved onto the page:** Retry STT (`RecoveryCoordinator.startManual`, seam `retryRunner` for the tests), Restore text, Share audio, Dismiss — enablement unchanged from `RecoveryActions.enabled(completed, hasAudio, busy, providerConfigured)`. `recovery/RecoveryRestore` (store → verify → journal delete, `Target` interface) and `recovery/RecoveryShare` (`files` + `intent`: SEND / SEND_MULTIPLE / `null`, read grant, authority `<pkg>.recovery`) are the extracted contracts, so the screen is not a second copy.
- **Retired:** the #185 screen, `activity_recovery.xml`, `item_recovery.xml`, its manifest entry, `ContrastMatrix.SCREEN_RECOVERY` and its 4 tests (each assertion carried into `WorkbenchActivityTest`). `RecoveryCoordinator.onFinished` has exactly ONE owner now; after a run whose session disappeared the Workbench selects the newest stored page (the "moved to the top" effect).
- **Banner:** `<n> incomplete dictation(s) · <stamp of the newest>`, GONE at 0, tap jumps to `WorkbenchPages.newestLeftoverIndex`. The auto-run and its pre-request Toast are unchanged.
- **Strings/help drift fixed en route:** the eight strings only the deleted screen used are gone; `docs/help.html` described "a list you open" — it now describes the page, Retry STT and the never-aged-away rule.
- **Tests:** 932 → **962** (`:app` 580 → 610: `WorkbenchPageTest` 6, `WorkbenchStampTest` 8, `RecoveryRestoreTest` 4, `RecoveryShareTest` 4, `WorkbenchActivityTest` +10, `WorkbenchRecoveryBannerTest` 2 → 3, minus the screen's 4). No version bump.
- **Not verified:** every device criterion (jump on a real screen, share to a real chooser, a restored text landing under the thumb) — owner checks in HANDOFF.md.


## #185 — the recovery UI: the leftovers become visible and actionable (2026-11-21, shipped)

- **Was:** #182 kept the audio, #183 classified the failure, #184 made a retry idempotent — but nothing in the app could SHOW an unfinished dictation, and the only notice was the one-shot IME Toast. The recovery contract existed on disk and had no user surface.
- **Session state in `session.json` (owner idea: a marker in the bundle):** `completedAtMs` (written by `stopAndTranscribe`'s success branch immediately before the existing delete — NOT reordered, #138 is load-bearing), `dismissedAtMs`/`dismissReason`. Unfinished = neither marker. A marker-less session whose records are ALL `DONE` also counts as completed — nothing is left to send, so the honest action is restore. `recoverableSessions()` returns both kinds (newest first) with count/duration/bytes and the failure class of the LOWEST incomplete fragment; a marker-only dir (crash before the first record) is debris and never listed.
- **Dismissal:** `markDismissed` writes the reason FIRST, then deletes every other file — the `session.json` tombstone stays (`no silent drops`). Tombstones are capped by COUNT (`MAX_TOMBSTONES` = 50, newest win) inside `prune` and never consume the 256-MB audio cap.
- **Re-run (`JournalReplay`, `:core`):** replays ONLY the fragments without a stored transcript, splices the stored `DONE` text in index order. Each request is the journal's own composed upload (byte-identical), the RECORDED prompt, `session_id`/`fragment_index` unchanged (the `attempt` counter restarts at 1 — the dedupe identity is the pair); echo trimmed by the newly extracted pure `EchoTrim` (the live worker delegates to it now) against the previous stored transcript, `LoopGuard` on a forced seam, the #117 400-without-prompt fallback. Stops at the FIRST failure (`markFailed` with class + HTTP code); every `DONE` flip is durable, so a cancelled run resumes. `beforeFragment` is a suspend gate.
- **Auto-recovery on Workbench entry (`RecoveryCoordinator`):** scan → banner (GONE at 0) → Toast BEFORE the first request → run. Only `TRANSIENT`/interrupted sessions are retried (`RecoveryReason.autoRetryable`); a dead token, a protocol problem or a rejected request stay for the list. One run at a time, one AUTO attempt per session per PROCESS (`autoAttempted`; no persisted backoff — a restart is a new chance), and the run yields at every fragment boundary while the controller is RECORDING/PROCESSING so the live path keeps the provider.
- **Result path:** STT text → `finishTranscription` with the CURRENT settings (raw/LLM/wrap) → append to `DictationStore` → verify → `markCompleted` → `deleteSession`. A failed polish stores the RAW text; a failed store keeps the journal. Never the other way round.
- **Screen (a separate pending-transcriptions screen — retired by #188):** rows(time · seconds · fragments · reason) with Re-run / Restore-text / Share / Dismiss; enablement from pure `ui/RecoveryActions`, reasons from `recovery/RecoveryReason`; dismissal asks via `AlertDialog`; the empty state has no dead UI. Platform Views only, theme attributes only, and the screen joined `ContrastMatrix` (`SCREEN_RECOVERY`).
- **Share (`recovery/RecoveryAudio`):** a WAV session becomes ONE reconstructed file (pre-rolls cut at 32 bytes/ms, `WavReader`/`WavWriter`) in `cacheDir/recovery-share/`; an OGG session shares its pieces (no decoder on device). New `FileProvider` (`<pkg>.recovery`, `res/xml/recovery_paths.xml`).
- **Tests:** 840 → **902** (`:core` 270: `EchoTrimTest` 10, `JournalReplayTest` 13, `FragmentJournalTest` +14; `:app` 577: `RecoveryActionsTest` 5, `RecoveryReasonTest` 4, `RecoveryAudioTest` 5, `RecoveryCoordinatorTest` 8, the screen's own 4, `WorkbenchRecoveryBannerTest` 2). No version bump.
- **Docs drift fixed en route:** `docs/help.html` still said Polished is editable (contradiction since #178) and called the polish button "Re-polish" — corrected in the same commit.
- **Not verified:** every device criterion (force-stop leftover, airplane-mode auto-recovery, share on a real chooser, tombstone across a restart) — claw has no device; owner checks in HANDOFF.md.

## #178 — the Workbench redesign (2026-11-20, shipped; children #179/#180/#181)

- **Store (#179):** `DictationStore.replaceRawWithPolished(id)` (raw = polished, the polished text stays, MRU bump only on a real change, `null` without polished text) and `clearPolished(id)` (polished = `null`, bump only on change). New pure `ui/WorkbenchActions.enabled(hasRaw, hasPolished, polishing, ringSize, onCompose)` → `Actions(polish, promote, clearPolished, copyRaw, copyPolished, prev, next, new)`; the Activity writes no enablement rule of its own.
- **Drawables (#181):** six new vectors `ic_arrow_left`, `ic_arrow_right`, `ic_add`, `ic_copy`, `ic_polish` (Material `auto_awesome`), `ic_promote` (Material `publish`); `ic_settings`/`ic_delete_outline` reused; no tint inside the drawable (`fillColor #FF000000` only), the tint comes from the layout.
- **Layout (#180):** icon header only (◀ · position label · ⚙ · ＋ · ▶), three action zones (A above Raw, B between Raw and Polished, C below Polished), two polish sites (`workbench_polish_a`/`_b`) sharing one per-visit language, `workbench_polished` as a read-only `TextView`, the #173 accordion machinery removed, `DictationTarget` reduced to `NEW`/`RAW_EDIT`, promote/clear only through the store.
- **Regression fixed in `1cf992c`:** with exactly one stored dictation both arrows were dead and the card was unreachable from the compose slot; now `prev/next = ringSize >= 1` on the compose slot and `>= 2` on a stored card (a one-entry wrap would be a self-loop).
- **Tests:** `WorkbenchActionsTest` new; store and layout tests adjusted. No version bump.

## #176 — the Workbench polishes only when asked (2026-11-20, shipped)

- **Trigger:** the owner's own flow ("dictated, sent, done, hide the keyboard with the system button") made the Workbench's automation look wrong: Auto LLM rewrote the Raw text on field-leave — the very text being redacted — and duplicated Re-polish as a second trigger with the state rule "once per field-leave, only while Polished is empty".
- **Removed:** `activity_workbench.xml` checkbox, `workbench_auto_llm` string, `SettingsStore.workbenchAutoLlm` + `WORKBENCH_AUTO_LLM_KEY` (no migration), `WorkbenchActivity`'s `autoLlmCheckbox`/`silenceAutoLlm`/`onAutoLlmChanged`, the `onFieldLeft` auto-polish block and the auto-polish after a delivered dictation.
- **Unchanged:** dictations are raw inside our own app (#168 gives the IME no LLM toggle there), `TranscriptionPipeline.polish` stays the only polish call and is driven by Re-polish with the per-dictation language, a raw edit discards the polished slot silently.
- **Tests:** four Auto-LLM tests deleted; `a delivery never polishes on its own — a raw edit empties the polished slot` replaces the old "with auto-llm off" branch test. Docs: help page, ARCHITECTURE workbench row, CONVENTIONS "only polish site" bullet, DOMAIN vocabulary via a new Re-polish row, plus the DECISIONS entry superseding #171 (D8) / #173 (decision 9).
- **Verification:** `test` + `lintDebug` + `assembleRelease` green; pre-push green. No version bump.

