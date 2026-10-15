# Korpus `bist-01` — Bist-Klassen-Sick-Seam-Material (#129 Track A)

Zweck: die **Gegenprobe** (Owner-Entscheidung 2026-10-15: „beides doppelt prüfen") für die
`qualityMin`-Entscheidung aus #129. Der Naturfund aus echten Diktaten **entscheidet** das Gate
(siehe #129-Body, Track B); dieses Korpus **klammert** es und **gegenprüft** die confirmten
Knobs (axisK=0.75, axisC=100.0, marginKMs=150, searchSpan=lateHalf).

Hintergrund (#126): die kranke Naht entsteht, wenn der Silence-Run-Center-Cut in den
stimmlosen Konsonantenschwanz eines wortfinalen /st/ fällt — das Wort dupliziert sich am
Fragment-Join („…informiert bist ⏸ Jedenfalls…" → „bist bist"). Das `quality`-Block-Q
misst das; `qualityMin` entscheidet, ob die Naht gestitcht wird.

## Aufnahme-Anleitung (Owner)

- **Material:** deutsche Sätze, die auf **ist / bist / kannst / musst / erst** enden,
  direkt gefolgt von einer **bewussten Pause (~0,8–1,5 s)**. Danach geht der nächste Satz
  normal weiter.
- **Beispielsätze:** „Ich habe dich verstanden bist du bereit" (Pausen nach „bist"),
  „Was du heute kannst das Morgen auch", „Wer das lesen musst hat Glück", „Erst gestern
  war ich dort" — frei variieren, natürlich sprechen, KEINE Studioluft.
- **Aufnahmeeinheiten:** 3–8 Sätze pro Datei (~10–60 s zusammenhängendes Material pro
  Datei) — die Pipeline auto-Sized Fragmente bei ~10 s; mehrere Naht-Positionen pro Datei
  erhöhen die Trefferwahrscheinlichkeit.
- **Bedingungen:** ruhiger Raum, normaler Sprechabstand zum Mikrofon, normale Lautstärke
  (kein Flüstern — das 6-dB-Whisper-Regime ist bereits aus #126 dokumentiert; hier geht es
  um die normale Sprechlage, die der Daily Driver sieht).
- **Dateinamen-Konvention:** `<wort>_<NN>.<ext>` — z. B. `bist_01.m4a`, `kannst_02.wav`,
  `ist_03.ogg`, `erst_04.m4a`, `musst_05.m4a`. Die Klasse wird aus dem Präfix abgeleitet
  (bist/ist/kannst/musst/erst/other — `other` = Kontrollmaterial ohne /st/-Finale).
- **Rohmaterial:** einfach nach `raw/` legen (beliebiges Format — m4a/ogg/wav; der
  Gerät Recorder, ein Desktop-Mikrofon, egal). `raw/` ist git-ignored.

## Build (Blaupause aus tools/seam-study/corpus/build.sh)

```bash
cd corpora/bist-01 && ./build.sh
```

`build.sh` konvertiert alles aus `raw/` per ffmpeg nach **16 kHz / mono / pcm_s16le**
nach `wavs/`, verifiziert jede Datei per ffprobe (Parameter + Dauer), berechnet die
sha256 und generiert `manifest.json` (Klassen aus den Dateinamen-Präfixen). Danach —
analyzer einmal bauen, dann die Sweeps (der `run`-Befehl ist resume-safe: fertige
Runs werden per `results/<run-id>/` übersprungen):

```bash
BIN=$REPO/analyzer/build/install/analyzer/bin/analyzer    # nach: ./gradlew :analyzer:installDist

# Bracketing-Sweep über qualityMin (jeder Wert = eigener run-id):
for qm in 1 2.5 5 10 20 40; do
  ANALYZER_STT_TOKEN=<token> $BIN run --corpus bist-01 --run-id sweep-qm-$qm --quality-min $qm
done

# Gegenprobe der confirmten Knobs (#129: axisK=0.75, axisC=100.0, marginKMs=150, lateHalf):
ANALYZER_STT_TOKEN=<token> $BIN run --corpus bist-01 --run-id doublecheck-prod \
  --axis-k 0.75 --axis-c 100.0 --margin-k-ms 150 --search-span lateHalf

# Cross-Korpus-Vergleich (clean baseline vs bist):
$BIN compare ../../corpora/seam-study-01/results/<run-id> results/<run-id>
```

(Token nie committen — `ANALYZER_STT_TOKEN` oder `--stt-token`; der gregor-Token kommt
via `ssh gregor 'sudo docker inspect whisper | grep -i WHISPER_API_KEY'`.)

## Status

- [ ] Owner-Aufnahmen in `raw/` (Ziel: ≥15 Sätze über alle 5 Wortklassen + 2–3 `other`-Kontrollen)
- [ ] `./build.sh` → `wavs/` + `manifest.json`
- [ ] `run`-Sweep → `results/`
- [ ] `compare` gegen seam-study-01 → Bracketing-Ergebnis auf #129
