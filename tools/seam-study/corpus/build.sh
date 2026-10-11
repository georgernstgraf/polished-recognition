#!/usr/bin/env bash
# Build the seam-corpus: nested WAV cuts (16k/mono/s16le) for 7 acoustic classes.
set -euo pipefail
MURL="$HOME/Music/murl"
OUT="/tmp/opencode/seam-corpus"

# class | start | durations | relative source path
classes=(
  "de_monologue_male|120|10 30 60 150 300|_VORTRÄGE/Buermann/Buermann 20_11_Erlebnisschule.mp3"
  "de_interview|120|10 30 60 150 300|@Audiobooks/Konrad Paul Liessmann/Denken und Leben I - CD 5/06 Philosophie und Öffentlichkeit - Mit Manfred Jochum im Gespräch.mp3"
  "en_monologue_male|60|10 30 60 150 300 600|@Audiobooks/Fear and Loathing in Las Vegas CD 1 - 01.mp3"
  "en_monologue_female|60|10 30 60 150 300 600|@Esoterik/Gangaji/Gangaji - Who are You - complete/Gangaji - Who Are You - 04 of 19 - Searching and Awakening.mp3"
  "en_interview|30|10 30 60 150 300|Scared Weird Little Guys/Live at 42 Walnut Crescent/21 - Scared Weird Little Guys - Kiss.mp3"
  "panel_multispeaker|120|10 30 60 150 300|_VORTRÄGE/Pöltner/Poeltner - Das Hoeren auf das Ungehoerte und seine Bedeutung fuer die Praxis der Psychotherapie - 2018-01.12 - Frage und Antwort.ogg"
  "phone_radio|30|10 30 60 150 300|@Comedy/Achmed - AOL Hotline.mp3"
)

for entry in "${classes[@]}"; do
  IFS='|' read -r cls start durs rel <<< "$entry"
  src="$MURL/$rel"
  mkdir -p "$OUT/$cls"
  for d in $durs; do
    out="$OUT/$cls/${cls}_${d}s.wav"
    if [ -f "$out" ]; then echo "skip $out"; continue; fi
    ffmpeg -hide_banner -v error -ss "$start" -t "$d" -i "$src" \
      -ar 16000 -ac 1 -c:a pcm_s16le -f wav "$out" -y
    echo "built $out"
  done
done
echo "ALL DONE"
