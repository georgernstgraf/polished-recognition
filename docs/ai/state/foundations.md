---
type: State
title: Project State — Foundations
description: Stack, storage and app-shell state.
tags: [knowledge, state, foundations]
generated: { by: opencode/knowledge-persistence, at: 2026-11-25T12:00:00Z }
status: stable
---

# Project State — Foundations

Moved out of `STATE.md` in #206; sections are verbatim, newest first. The current status line, focus and pending items stay in `STATE.md`.

## #200 — ein Settings-Getter darf nicht schreiben: der Flake hatte einen Thread (2026-11-24, shipped)

- **Was:** `SettingsStoreTest` flakte 2× in ~21 vollen Läufen (`expected: 0 but was : 2592000`, in `evidenceRetentionSeconds defaults to 0` und `legacy audio_evidence toggle migrates OFF to 0`). Die #200-Hypothese — ein verspäteter `SharedPreferences.apply()` aus Test A landet nach dem `@Before`-`clear()` von Test B — war **falsch**.
- **Die Messung, die es entschied:** Robolectric 4.14.1 legt je Testmethode ein neues `TempDirectory` an (`Files.createTempDirectory("robolectric-<pkg>")`) und `ShadowSharedPreferences` ist dort eine **leere** Klasse, es läuft die echte `SharedPreferencesImpl`. Ein verspäteter Disk-Write trifft also eine gelöschte Datei und ist für den nächsten Test unsichtbar; gelesen wird aus der In-Memory-Map, die `clear().commit()` auf derselben Instanz leert. Sichtbar ist nur ein **gleichzeitiger** Schreiber.
- **Der Schreiber:** `PolishedRecognitionApp.onCreate` startet einen MIN_PRIORITY-Thread, der `audioEvidenceStore.evict()` aufruft — und `evict()` liest als Erstes `retentionSeconds()` → `settingsStore.evidenceRetentionSeconds`. Dieser Getter **schrieb beim Lesen**: fehlten Key und Legacy-Toggle, persistierte er `30 days` im Debug-Build, also **2592000** — genau der gemeldete Wert. Landete der Write zwischen `clear()` und Assertion, war der Test rot.
- **Der Fix (Produktion, keine Test-Kosmetik):** der Getter ist jetzt ein **reiner Lesevorgang**. Key vorhanden → clamp+return; **kein** Key und **kein** Legacy-Toggle → `evidenceRetentionDefaultSeconds` zurück, ohne zu schreiben; Legacy-Toggle → einmalige Migration wie bisher. Nebeneffekt (gewollt): ein injizierter Default ≠ 30 Tage wird geehrt statt auf 30 Tage normalisiert. Nutzerverhalten unverändert — die Settings-Seite schreibt beim Speichern explizit.
- **Testseitig:** der Startup-Thread heißt `app-startup-maintenance` (`PolishedRecognitionApp.STARTUP_MAINTENANCE_THREAD_NAME`, Companion `internal`) und `SettingsStoreTest.@Before` joint ihn — ein künftiger Schreiber soll benannt sein, nicht geraten. Zwei Regressionstests (`reading the default does not persist it (#200)`, 0- und 30-Tage-Variante) waren vor dem Fix rot (die `contains`-Assertion) und danach grün.
- **Doku-Drift korrigiert:** die #142-Lehre „Robolectric-Prefs leaken zwischen Testmethoden" (PITFALLS) war falsch, ebenso der „known flake"-Eintrag aus #178; beide sind ersetzt. Neue Konventionsregel: ein `SettingsStore`-Getter ist ein reiner Lesevorgang, nur einmalige Legacy-Migrationen persistieren.
- **Verification:** die zwei Regressionstests waren vor dem Fix rot und danach grün; `SettingsStoreTest` 30× hintereinander 0 Fehler; volles `test` **1035 Tests / 0 Failures / 0 Errors** (`:app` 673 mit 12 skipped, `:core` 307, `:analyzer` 55); `lintDebug` mit `--no-build-cache` „No issues found." (der erste Lauf war ein UP-TO-DATE-Report von 00:31 — siehe PITFALLS-Cache-Eintrag); `assembleRelease` grün (APK 2 581 830 B); pre-push. Kein Versions-Bump.

