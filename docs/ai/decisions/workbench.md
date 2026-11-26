---
type: Decisions
title: Decisions — Workbench
description: The Workbench pages, the entry model, the polish sites, the unfinished-dictation ring and its actions.
tags: [knowledge, decisions, workbench]
generated: { by: opencode/knowledge-persistence, at: 2026-11-26T10:30:00Z }
status: stable
---

# Decisions — Workbench

Moved out of `DECISIONS.md` in #206; entries are verbatim. New decisions in this area are appended here.

## 2026-11-25: #208 — Promote ist ein Zug, kein Kopieren; der MRU-Ring bleibt; nur ＋ erzeugt ein Diktat

Owner-Report (Workbench, zwei gestapelte Symptome): (1) „after promoting a
polished text to be the new raw, it should be cleaned up instead of
duplicated"; beim Durchblättern mit ◀ „was it always the same topic in one
translation or the other". (2) „at least one of the actions promote, edit,
polish cause a new dictation in the mru ring also."

- **Entscheidung: Promote ist ein Zug. (BLEIBT)**
  `DictationStore.replaceRawWithPolished` setzt
  `copy(raw = polished, polished = null)` — der Polished-Slot wird GELEERT.
  Die alte Begründung („Polished ist die polierte Version des NEUEN Raw, also
  nicht veraltet") hielt der Gerätetest nicht: beide Felder zeigten denselben
  Text, und der Ring zeigte dasselbe Thema auf benachbarten Seiten. Strikter
  No-op, wenn `raw == polished` bereits gilt (wie in #178). Der Compose-Pfad
  in `promotePolished()` leert `composePolished` mit; der Stored-Pfad zeigt
  das geleerte Feld SOFORT (`showPolished("")` — `syncPositions` schreibt
  Felder bewusst nie um).
- **RETRACTED gleicher Tag: Creation-Order.** `a7c7d89` hatte den Ring auf
  `createdAtMs` umgestellt („ein Touch reordert nie") — der Owner korrigierte:
  „the ring order should not have been changed … edit SHALL promote to
  youngest". Der MRU-Sprung war das GEWOLLTE Verhalten, nicht der Befund.
  `11e5b56` stellt `updatedAtMs` wieder her: Bump bei jeder echten Änderung,
  `loadAll`/Eviction danach, Edits schützen wieder vor der Eviction.
- **Die neue Invariante (Owner-Klärung): nur ＋ erzeugt ein Diktat.**
  „In the top bar it should only be the ‚+‘ icon that creates a new
  instance … leave all icons where they are." Gefundene `append`-Stellen:
  Compose-Materialisierung (＋/Polish-auf-Compose/Navigation, #173 Modell 2),
  Transkript-Ankunft, Restore-aus-Leftover — KEINE davon liegt an
  Promote/Edit/Polish/◀/▶/⚙ auf einer gespeicherten Seite; der vermeintliche
  „neue Eintrag" war der MRU-Sprung selbst. Gepinnt durch
  `promote, edit and polish stay on their dictation without instantiating a
  new one` (Ring-Größe und Ids unverändert).
- **Tests:** `DictationStoreTest` (Promote leert; MRU-Bumps wie in #173;
  No-Instantiation-Pin; Legacy-Datei ohne Stamp → `createdAtMs`),
  `WorkbenchActivityTest` („edits it and bumps it to the top" zurück,
  Zonen-B/C-Promote mit leerem Polished-Feld + disableden Buttons).
  766 `:app`-Tests grün, `lintDebug` 0, Release auf dem OnePlus
  (`f6de166c`) installiert. Kein Versions-Bump.

## 2026-11-24: #189 — Sektionen klappen wieder zu, Promote zeigt nach oben, die Zonen B und C sind identisch

Owner-Report: „Wenn ich in der Workbench auf **RAW** klicke, hatte ich den
Rohtext bisher zugeklappt wie bei HTML summary/details. Das ist verloren
gegangen … Das Icon (eine Linie mit einem Pfeil nach unten) ist irreführend;
ein Pfeil nach oben wäre besser. Zusätzlich soll das Mülleimer-Icon in der
untersten Leiste unter der Polished-Sektion stehen. Ebenso soll ein Tab für
Polished das Polished-Feld zuklappen können."

- **Entscheidung: das #173-Accordion kommt zurück, für BEIDE Sektionen.** Die
  Label-Zeile ist wieder der Toggle (Chevron `▾`/`▸` · Label · Copy-Button),
  zugeklappt steht darunter eine **einzeilige, ellipsierte Vorschau** des
  Inhalts (leer = keine Zeile, damit kein toter Platz entsteht). Damit ist die
  #178-Regel „the accordion is gone" (samt Test) ersetzt, nicht ergänzt.
- **Entscheidung: der Zustand lebt EINEN Besuch lang** (nicht persistiert,
  nicht in `savedInstanceState`; beide Sektionen starten offen). Das ist die
  #173-Regel „state is not persisted" — ein Zuklappen ist eine Leseposition,
  keine Einstellung.
- **Entscheidung: eine Aufnahme öffnet RAW selbst.** Ein `GONE`-Feld hat keinen
  Input-Connection, der IME-Insert ginge verloren (D10 ist der Lieferweg).
  `onRecordingStarted()` (und `newEntry()`, und der Eintritt) klappt RAW auf;
  Polished bleibt unberührt, weil dort nie hineingeschrieben wird.
- **Entscheidung: Promote zeigt nach oben.** `ic_promote` war Material
  `publish` (Linie oben, Pfeil **nach unten**) — gelesen als „Text nach unten
  schieben", obwohl die Aktion Polished **nach oben** in RAW hebt. Jetzt
  `vertical_align_top`, das exakte Spiegelbild.
- **Entscheidung (Owner, im Plan-Dialog): die beiden Aktionszeilen sind
  identisch** — `promote` + Mülleimer unter RAW **und** im Bottom-Bar unter
  Polished. Der Mülleimer ist damit dort, wo der Owner ihn haben wollte, ohne
  den oberen Zugriff zu entfernen; zwei IDs (`_b`, `_c`), eine Aktion.
- **Tests:** `WorkbenchLayoutPolicyTest`: der „Accordion ist weg"-Test ist
  invertiert (Reihenfolge Toggle → Chevron → Copy → Vorschau → Feld, Chevron
  ohne Accessibility, Vorschau einzeilig + `gone`), der Zonentest kennt die
  neuen IDs, und ein neuer Test vergleicht die **Kinder** der beiden
  Aktionszeilen über einen Eltern-Index im SAX-Collector (`childrenOf`).
  `WorkbenchActivityTest` +5 (zugeklappt/aufgeklappt + Vorschau für Raw und
  Polished, leere Sektion ohne Vorschau, Aufnahmestart öffnet RAW, Mülleimer in
  Zone C).
- **Kein Versions-Bump** — die App bleibt 1.5.1 / 10501.

## 2026-11-24: #202/#203 — ein RAW-Edit wird beim Tippen gespeichert, und Polish committet zuerst

Owner-Report: „In der Workbench wirkt es, als ob das Editieren des RAW-Textes
nicht funktioniert … wenn ich im Textfeld editiere und dann sofort auf Polish
gehe, nicht der aktuellste Text aus dem Eingabe-RAW-Feld verwendet wird."

- **Befund (kein Feld-, sondern ein Commit-Fehler).** `polishCurrent()` las den
  Feldtext korrekt, aber `commitEdits()` lief nur beim Feldverlust (und bei
  Pause/Navigation). Ein Klick auf einen Button verschiebt den Fokus im
  Touch-Modus nicht, also stand im Store noch der ALTE raw: das Polish-Ergebnis
  hing per `updatePolished(id, …)` am alten Text, und das `reload` nach der
  Antwort setzte den alten Text zurück. Genau das liest sich als „mein Edit ist
  weg".
- **Entscheidung: implizites Speichern heißt Speichern beim Tippen.** Der
  TextWatcher startet eine 500-ms-Debounce (`persistRawDraft`), die den Draft
  über `DictationStore.updateRaw` schreibt — und damit wie jeder Raw-Edit den
  Polished-Slot verwirft. Bewusst NICHT Teil davon: `shownRaw` (der D11b-Marker
  „schon an die Zwischenablage geliefert") und das Re-Mergen des Rings; sonst
  würde die Seite unter dem Nutzer springen bzw. die Lieferung beim
  Fokusverlust verschluckt.
- **Zusätzlich: jeder Leser committet zuerst.** `polishCurrent()` beginnt jetzt
  mit `commitEdits()`; damit hängt das Ergebnis am editierten Text und das
  Reload zeigt ihn. Ein danach folgender Fokusverlust liefert den Text nicht
  doppelt (der Marker ist gesetzt).
- **Verworfen (Owner-Vorschlag aus dem Issue, nicht bestätigt):** ein
  Disk-Button über/unter RAW. Die bestehende Policy ist implizites Speichern;
  ein Button wäre ein zweiter Speicherweg und eine zusätzliche Erklärung im UI
  — #201/#204 gingen gerade in die Gegenrichtung.
- **Tests:** 1035 → 1037 (`:app`); beide neuen Tests waren gegen die alte
  Produktionsdatei rot (verifiziert per `git stash`).

## 2026-11-23: #191 — auf einer unfertigen Seite stehen Grund und Ausweg zusammen; der Banner ist der EINE Weg dorthin

Der Gerätetest des #186-Fluges (Samsung, Flugmodus) hat die Anordnung aus #188
widerlegt: auf einer unfertigen Seite standen die vier Aktionen (Retry STT,
Restore, Share, Dismiss) ganz unten — der Owner blätterte erst 1/3 · 2/3 · 3/3,
bevor er Retry STT fand. Der Banner nannte Zahl und Zeit, las sich aber nicht
als etwas, das irgendwohin führt. Zwei Regeln, die daraus folgen; sonst ändert
sich nichts (gleiche Aktionen, gleiche `RecoveryActions`-Regel, gleiche
`pendingTarget`/Retry-Pfade, kein automatischer Seitenwechsel).

- **Reihenfolge auf einer unfertigen Seite:** Header-Icons → Banner (falls
  vorhanden) → When-line mit dem Grund → **Aktionen** → Sprach-/Polish-Zone →
  Raw. Nichts steht zwischen dem Grund und dem Ausweg; die Aktionen sind
  `0dp` + `layout_weight=1`, damit die langen Labels auf einem schmalen Schirm
  im Button umbrechen statt aus der Seite zu laufen (kein eigener Scroller —
  nur die äußere ScrollView scrollt, #173 Entscheidung 8).
- **Der Banner ist die EINZIGE Affordanz, die zu einem Leftover führt.** Er
  sitzt direkt unter dem Header, ist voll breit, trägt Zahl + neuesten Stamp +
  „tap to open" und einen Ripple. Er ist **nicht** mit der Akzentfarbe gefüllt:
  DeviceDefault-Akzent ist im hellen Theme dunkel (#475D92 in AOSP) und im
  Nacht-Theme sehr hell (#D9E2FF) — weder `textColorPrimary` noch
  `textColorPrimaryInverse` bleibt auf beiden lesbar. Deshalb Akzent-Text +
  2-dp-Akzent-Rahmen auf `?android:attr/colorBackground`, Höhe aus
  `?android:attr/listPreferredItemHeightSmall`, Innenabstand aus
  `listPreferredItemPadding{Start,End}` — alles Theme-Attribute, kein
  Farbliteral (`XmlColorPolicyTest`).
- **Kein automatischer Seitenwechsel** (Owner-Entscheidung, bewusst): die
  Compose-Seite bleibt die Landeseite; der Banner ist der erklärte Weg zum
  Leftover, nicht ein Sprung beim Betreten.

## 2026-11-22: #188 — die unfertigen Diktate sind Seiten des Workbench-Rings

#185 gab den Leftovers einen eigenen Screen (Activity + Layout + eigene Liste) neben dem Ring.
Das waren zwei Listen über dieselbe Frage — „was wartet noch auf mich?" — und
der Nutzer musste zwischen ihnen springen. #188 legt sie zusammen: ein Ring,
zwei Seitenarten. Vier Entscheidungen, alle vom Owner am 2026-11-22 bestätigt.

- **Zusammengelegte Sicht, KEIN Schema-Wechsel.** Ein `WorkbenchPage` ist eine
  Sicht über seine Quelle — `DictationStore` bleibt die eine Wahrheit für Text,
  `FragmentJournal` die eine für Audio. Keine Seite wird persistiert, kein Feld
  kommt dazu, kein zweiter Store entsteht. Ein Schema-Umzug hätte die
  Audio-Wahrheit in die Text-Welt kopiert, und der nächste abgebrochene Lauf
  hätte zwei Zustände, die auseinanderlaufen können.
- **Chronologisch nach der letzten Änderung.** Aufsteigend wie
  `DictationStore.loadAll()` (`updatedAtMs` für gespeicherte Diktate,
  `startedAtMs` für ein Leftover, ein undatiertes Leftover ans ENDE, Gleichstand
  über die id). Das letzte Element ist „neueste". Begründung: ein Leftover hat
  keinen Inhalt, den man anfassen könnte — sein Start ist sein ganzes Sein, und
  eine Seite, die von heute sein könnte, darf nicht unter allem anderen
  begraben werden. Ein Sonderfall „Leftovers immer oben" wurde verworfen: er
  hätte zwei Ordnungen in einem Ring erzeugt, und der MRU-Begriff (#173) gilt
  für Text, nicht für Seiten.
- **Die Leftover-Seite ist read-only und nicht fokussierbar.** Raw ist leer,
  `isEnabled`/`isFocusable` false; `DictationTarget` ist dort `NEW`, also legt
  ein geliefertes Transkript ein NEUES Diktat an, statt eine Seite zu editieren,
  die nichts zu editieren hat. Die Alternative (Raw editierbar lassen) hätte
  bedeutet, dass die IME in ein Feld schreibt, dessen Inhalt nie gespeichert
  werden darf — der `read()`-Invariant von `DictationStore` (leeres `raw` wird
  verworfen) macht daraus sonst eine still verlorene Eingabe.
- **Der eigene Screen aus #185 wird eingestellt, die Aktionen ziehen auf die Seite.**
  Die Activity, `activity_recovery.xml`, `item_recovery.xml`, ihr Manifest-Eintrag,
  `ContrastMatrix.SCREEN_RECOVERY` und ihr Test sind gelöscht;
  Retry STT / Restore text / Share audio / Dismiss sitzen auf der Seite
  (`RecoveryActions`-Regel unverändert). Die zwei nicht-trivialen Abläufe sind
  als `recovery/RecoveryRestore` und `recovery/RecoveryShare` extrahiert, damit
  die Workbench keine zweite Kopie trägt und die Verträge (Store vor
  Journal-Delete; SEND vs SEND_MULTIPLE) testbar bleiben. `RecoveryCoordinator.onFinished`
  hat jetzt genau EINEN Besitzer (die Workbench).
- **Der Banner ist Zähler + Sprung.** `"<n> incomplete dictations · <stamp des
  neuesten>"`, Tap springt auf `WorkbenchPages.newestLeftoverIndex` — es gibt
  keine Liste mehr, hinter die er führen könnte.
- **Verworfen:** ein `updatedAtMs`-Feld im Journal (Schema), eine dritte
  Store-Instanz als Merge-Cache (die drei bestehenden Instanzen und ihr
  per-Instanz-`@Synchronized` sind schon eine Falle), und ein Auto-Öffnen der
  neuesten Leftover-Seite beim Betreten (das würde den Nutzer aus der
  Compose-Slot-Zone reißen, D15).

## 2026-11-20: #178 — Die Workbench hat zwei Polish-Sites und ein read-only Polished-Feld

- **Zwei Polish-Knöpfe, eine Sprache:** `workbench_polish_a` (Zone A, über
  Raw) und `workbench_polish_b` (Zone B, zwischen Raw und Polished) sind beide
  `Button`s mit `ic_polish` und dem Text "Polish"; die beiden
  Sprach-Spinner (`workbench_language_a`/`_b`) teilen EINEN per-Besuch
  `polishLanguage` — D9 aus #173 (die per-Diktat-Sprache des Spinners, nie in
  `settings.targetLanguage` zurückgeschrieben) bleibt gültig. Ein dritter
  Trigger existiert nicht.
- **Polished ist read-only:** `workbench_polished` ist ein `TextView`
  (`focusable=false`), kein `EditText`; das Feld kann keinen Fokus halten, also
  ist `POLISHED_EDIT` als Ziel entfallen — `DictationTarget` ist nur noch
  `NEW` / `RAW_EDIT`.
- **Promote und Clear sind Store-Aktionen an Ort und Stelle:**
  `DictationStore.replaceRawWithPolished(id)` setzt Raw = Polished (der
  Polished-Text bleibt stehen, MRU-Bump nur bei echter Änderung, `null` ohne
  Polished-Text — **superseded by #208**: Promote leert den Polished-Slot,
  ein Zug statt einer Kopie; siehe den Top-Eintrag);
  `clearPolished(id)` setzt Polished auf `null` (Bump nur bei
  Änderung).
- **Enablement lebt in reinem Code:** `ui/WorkbenchActions.enabled(hasRaw,
  hasPolished, polishing, ringSize, onCompose)` → `Actions(polish, promote,
  clearPolished, copyRaw, copyPolished, prev, next, new)`; die Activity
  schreibt keine Enablement-Regel. Die Asymmetrie ist Absicht: ein einziger
  gespeicherter Eintrag ist von der Compose-Seite erreichbar
  (`prev/next = ringSize >= 1`), nicht innerhalb des Rings (`>= 2` — ein
  Ein-Eintrag-Ring wäre ein Self-Loop).
- **Chrome:** nur Icon-Header (◀ · Positionslabel · ⚙ · ＋ · ▶) und drei
  Aktionszonen (A über Raw, B zwischen Raw und Polished, C unter Polished); die
  Accordion-Maschinerie aus #173 ist entfernt, beide Felder stehen dauerhaft.
- **Tests:** neu `WorkbenchActionsTest`, `PostSendParkingPolicy`-Suite; die
  Store-Fälle für `replaceRawWithPolished`/`clearPolished` und die
  `WorkbenchLayoutPolicyTest`-Geometrie sind angepasst. 767 Tests grün
  (`:app` 541, `:core` 171, `:analyzer` 55), `lintDebug` 0 Fehler,
  `assembleRelease` grün.
- **NO version bump (owner policy).**

## 2026-11-20: #176 — the Workbench polishes only when asked: Auto LLM removed

Owner decision after the keyboard handover landed: *"ich habe das so diktiert
und dann war ich eigentlich fertig … ich muss den Tastatur-Wegblenden-Knopf
einfach verwenden."* That settled the send/dismiss flow (#174/#175); the next
question was the Workbench's button clutter and whether Auto LLM earns its
place. **It does not.**

- **Why it goes:** the Workbench is a text manager — dictate, redact, deliver.
  Auto LLM rewrote the Raw text on field-leave, i.e. exactly the text being
  redacted, and it was a second trigger for the same action as **Re-polish**
  (the rule "polish once on field-leave while the Polished slot is empty" is a
  state machine nobody re-derives later). A polish is now a deliberate click.
- **What stays untouched and matters:** Workbench dictations are still raw —
  #168 forces raw inside our own app and the IME bar is disabled there, so the
  "polish happened in the IME" and "polish happened in the Workbench" paths can
  never both fire. The polish itself is `TranscriptionPipeline.polish`,
  driven by Re-polish with the per-dictation language (never written back to
  `settings.targetLanguage`). A raw edit still discards the polished slot, no
  confirmation, no re-polish. **The "Re-polish is the only polish site" reading
  is superseded by #178** — the Workbench now has two polish buttons (above Raw,
  below Polished) sharing one per-visit language; the superseded claim is in
  HISTORY.md.
- **Removed:** the checkbox (`activity_workbench.xml`), `workbench_auto_llm`
  string, `SettingsStore.workbenchAutoLlm` + its key, the checkbox wiring and
  `silenceAutoLlm` guard, `onAutoLlmChanged`, the `onFieldLeft` auto-polish
  block (#173 decision 9) and the auto-polish after a delivered dictation
  (#171). No migration — the stale key is never read.
- **Tests:** four Auto-LLM tests removed; the former "with auto-llm off …" test
  survives renamed as *"a delivery never polishes on its own — a raw edit
  empties the polished slot"*, because that is now the invariant for every
  delivery, not a branch.
- **Supersedes** the Auto-LLM clauses of #171 (D8) and #173 (decision 9); the
  page/MRU model of #173 is unaffected (its accordion was removed later, by
  #178).
- **NO version bump (owner policy).**

## 2026-11-19: #173 — the Workbench entry model: compose slot, page-decided target, MRU ring

Owner report after the first real run on the OnePlus plus the grilling of
2026-11-19. **This entry replaces D4's target rule ("a manually changed Raw
field ⇒ continue that edit") and D4's entry picture ("opening the screen shows
the newest dictation and never creates")** — the page model below is binding.

- **The visible page decides the target, never a text comparison.** Compose slot ⇒ NEW · stored dictation ⇒ RAW_EDIT (at the caret, whether or not the user typed first). The old rule compared the field text with the text shown at render time, so a caret move alone meant "new" and dictating into an old Raw silently created another entry. Consequence: on a stored page a dictation never creates an entry; the compose slot and ＋ are the only creators. **The third branch (focus in Polished ⇒ `POLISHED_EDIT`) was removed by #178** — two targets only, and the read-only polished field cannot hold focus; the superseded text is in HISTORY.md.
- **The compose slot is transient.** It exists in memory only, is the page on entry, is never persisted while empty, never counts against the cap and is not part of the ring. Navigating away from a pristine slot discards it; any content materialises it first (`DictationStore.append`), also when ＋ is pressed. A dictation without raw text is rejected by `read()`, so polished-only slot content becomes the raw text when materialised.
- **A fresh slot on every `onCreate`** — cold start, rotation, dark-mode change, process-death restore alike. No `savedInstanceState` plumbing: `onPause` materialises a typed-into slot, so nothing is lost; the user returns to a fresh entry page.
- **Ordering is last-touched (MRU).** `Dictation.updatedAtMs` is the sort and eviction key (legacy files fall back to `createdAtMs`); `append` sets both stamps, `updateRaw`/`updatePolished` bump only on a real change. Display order and eviction order are the same list, so they cannot disagree. Mere browsing never bumps. (Re-affirmed 2026-11-25 by #208 after a same-day excursion to creation order was retracted — see the top entry.)
- **The ring wraps; the compose slot is outside it.** ◀/▶ cycle the stored dictations in both directions; both arrows leaving the compose slot land on the newest. The label is `n / m` in the ring and "new" on the compose slot. ＋ New sits centred in the header and is always enabled; on an already-pristine slot it only focuses Raw.
- **The fields are permanently visible, stacked in one column.** `wrap_content` + `minLines=2`, no inner scrollbar, no cap — only the outer ScrollView scrolls. **#178 removed the accordion:** each label row carries only the label and the copy icon, the actions live in three zones around the fields, and there is no chevron and no one-line preview any more (superseded text in HISTORY.md). `WorkbenchLayoutPolicyTest` still pins the geometry statically.
- **No automatic polish remains.** The Auto-LLM bullet of this entry (polish once on Raw field-leave while Polished is empty) is gone since #176 — see that entry; a raw edit still discards the polished text.
- **The IME auto-start stays** (decision 4 of the grilling): entering the Workbench starts the recording and shows the soft keyboard. `showSoftInput` from `onResume` was measurably ignored on the OPO (`mShowRequested=false`), so `revealSoftKeyboard()` retries from `onWindowFocusChanged` and also asks the compat insets controller. Verified on the device (`mInputShown=true`, keyboard above the insets-padded content).
- **Swipe navigation is out of scope** (owner, parked 2026-11-19) — it needs its own issue and a decision between ViewPager2, a fling interceptor and chrome-only swipe.
- **NO version bump (owner policy).**

## 2026-11-19: Epic #166 — the Workbench, and what the grilling settled (#167–#172)

The full decision set D1–D15 lives in issue #166's "Grilling complete" comment
(the spec). This entry records the implementation-shaping consequences.

- **The FIFO is a directory, not a preference.** One JSON file per dictation under `filesDir/dictations/`, temp file + rename (#167). SharedPreferences rewrites the whole file on every `apply()` and would lose exactly the edits the persistence exists for; the directory is excluded from cloud backup and device transfer in ALL three rule containers, pinned by `BackupRulesTest` exactly like #101 does for the secrets file.
- **A dictation is created by a transcript, and the target is decided when the recording STARTS.** The IME inserts into the focused field (D10), so after the insertion a user edit and a keyboard insertion are indistinguishable. `WorkbenchActivity` therefore snapshots the target on `StateChanged(RECORDING)`: Polished focused ⇒ edit the polished text; a manually changed Raw field ⇒ continue that edit; otherwise ⇒ a new FIFO entry (D4). Opening the screen never creates or evicts anything. (**SUPERSEDED by #173** — the target rule is now page-decided, the entry page is the transient compose slot; see the top entry.)
- **LLM is forced off inside our own app, session-scoped, never persisted.** `LlmTargetPolicy` + `TranscriptionPipeline.forceRawMode` set by the controller for exactly one run (#168). The bar toggle renders disabled. (**Display rule superseded by #211:** it shows the **effective** state — unchecked inside our own app — not the global one; the old "shows the still-global state, 'off' would be a lie" reasoning held only while the toggle was enabled.) The global `raw_mode` is never written by the rule.
- **The Workbench has no automatic polish.** The pipeline's `polish(raw, language)` is the deliberate polish path; #176 removed Auto LLM entirely (no setting, no field-leave trigger, no post-delivery trigger), and #178 split its one trigger into two buttons sharing one per-visit language (see both entries). A raw edit discards the polished slot with no confirmation and no silent re-polish (#171).
- **Delivery is unconditional and sensitive.** Send ⇒ transcript, changed field on focus loss ⇒ its text, copy button ⇒ the visible field; unchanged field left ⇒ nothing. `ClipboardDelivery` sets `EXTRA_IS_SENSITIVE` on API 33+ — an automatic delivery is not the deliberate IME-salvage copy (#170, #138's asymmetry is intended).
- **The Workbench is the launcher entry; Settings becomes an unexported screen** reached from the Workbench and the IME gear (#169). Exactly one MAIN/LAUNCHER activity, pinned by `LauncherEntryTest`.
- **The screen owns its insets** (`WorkbenchInsets`: bars + cutout + IME, max per side, rebuilt from the XML base) — the first activity that does, because targetSdk 36 lays it out edge-to-edge.
- **Documents amended, not silently overridden:** CONVENTIONS' "the UI never shows raw" is explicitly amended (Raw/Polished are now field labels), DOMAIN's statelessness is scoped to the service (the app is stateful by design), and AGENTS.md's AppCompat/Material claim is corrected (false since #61).

## 2026-09-30: Dictation target recorded at dictation start (#102 amendment)
- **Choice**: Both entry points call `SettingsStore.recordKnownApp(callerPackage)` when a dictation **starts** — IME `PolishedVoiceInputIME.startIfPermitted()` (send-button start + `onStartInputView` auto-start) and bound `PolishedRecognitionService.onStartListening()` (after `resolveCallerPackage`). The insertion/delivery-time calls stay as the authoritative capture/fallback and are absorbed by the 24 h throttle, so still no extra disk I/O. No `SettingsStore` API change, no Settings UI change.
- **Reason**: The MRU/learning was only bumped on a **successful insertion**, so while dictating into an app (RECORDING/PAUSED) and opening Settings via the IME gear — which pauses and launches Settings — the previously-inserted app was still on top, violating the minimum requirement that the currently active app is on top. `currentCallerPackage()` was likewise re-resolved after focus could have moved.
- **Semantics**: "used" now starts at dictation **start** — an app the user starts dictating into but cancels is learned (owner-approved; simpler than an MRU-only start bump that would preserve insertion-only persistence).
- **Tests**: `SettingsStoreTest` +1 ("recording the target at dictation start makes a new app the top entry immediately"). `./gradlew test` → **281 green** (was 280); `./gradlew assembleRelease` green. Owner on-device feel-check **done 2026-10-01** (daily use); #102 closed, shipping in v1.3.4 (10304).

## 2026-09-30: In-memory MRU ordering for the learned-app dropdown (#102)
- **Choice**: `SettingsStore` keeps a `@Volatile` immutable `mruOrder: List<String>` (copy-on-write, mirroring `knownAppsCache`). `recordKnownApp()` moves the package to the front on every successful dictation **before** the 24 h throttle check; `knownAppsByRecency()` returns MRU-ordered apps first and the remaining known apps by descending `lastSeenMs` (MRU entries no longer in the known map are dropped defensively); `forgetKnownApp()` removes the package from the MRU list. Settings UI unchanged — the top entry (now the last-used app) is already auto-selected (`SettingsActivity.kt:199`).
- **Reason**: #100's ordering (descending `lastSeenMs`, rewritten only every 24 h) left a re-used app below a more-recently-inserted one within a day, contrary to the everyday expectation that the app just dictated into is on top. Settings is normally opened from the IME gear in the same process, so an in-memory list is exact for the common flow.
- **I/O**: zero additional disk writes and reads — the MRU is memory-only; the existing `known_apps` throttle/write path is untouched.
- **Tradeoff**: Exact only within a running process; after a process death the order falls back to the stored `lastSeenMs` (up to 24 h coarse). Persisting a scalar `last_used_package` per dictation (Option B) was considered and deferred — addable later without rework.
- **Semantics**: "used" = successful dictation insertion; selecting/editing a width in Settings does not reorder.
- **Tests**: `SettingsStoreTest` +3 (bump inside the 24 h window; A→B→A restores A; forget drops the MRU entry). `./gradlew test` 280 green.

