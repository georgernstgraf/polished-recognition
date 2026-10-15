#!/usr/bin/env bash
# Build the bist-01 corpus (#129 Track A double-check): convert the owner's raw
# bist-class recordings to 16 kHz/mono/pcm_s16le WAVs, verify, hash, manifest.
# Blueprint: tools/seam-study/corpus/build.sh (the #122 corpus build).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
RAW="$HERE/raw"
WAVS="$HERE/wavs"

if [ ! -d "$RAW" ] || [ -z "$(ls -A "$RAW" 2>/dev/null)" ]; then
  echo "ERROR: $RAW is empty or missing — record bist-class material first (see README.md)" >&2
  exit 1
fi

mkdir -p "$WAVS"

for src in "$RAW"/*; do
  base="$(basename "$src")"
  stem="${base%.*}"
  out="$WAVS/$stem.wav"
  if [ -f "$out" ]; then echo "skip $out"; continue; fi
  ffmpeg -hide_banner -v error -i "$src" \
    -ar 16000 -ac 1 -c:a pcm_s16le -f wav "$out" -y
  echo "built $out"
done

echo "--- verifying (ffprobe: 16000 Hz, 1 ch, pcm_s16le, duration) ---"
fail=0
for wav in "$WAVS"/*.wav; do
  sr="$(ffprobe -v error -select_streams a:0 -show_entries stream=sample_rate -of csv=p=0 "$wav")"
  ch="$(ffprobe -v error -select_streams a:0 -show_entries stream=channels -of csv=p=0 "$wav")"
  codec="$(ffprobe -v error -select_streams a:0 -show_entries stream=codec_name -of csv=p=0 "$wav")"
  dur="$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$wav")"
  if [ "$sr" != "16000" ] || [ "$ch" != "1" ] || [ "$codec" != "pcm_s16le" ]; then
    echo "FAIL $wav: sr=$sr ch=$ch codec=$codec"; fail=1
  else
    echo "ok   $wav: ${dur}s"
  fi
done
[ "$fail" = "0" ] || { echo "VERIFICATION FAILED"; exit 1; }

echo "--- generating manifest.json ---"
python3 - "$HERE" <<'PYEOF'
import hashlib, json, os, re, sys, wave

here = sys.argv[1]
wavs = os.path.join(here, "wavs")
CLASSES = {"bist", "ist", "kannst", "musst", "erst", "other"}

def cls_of(stem: str) -> str:
    m = re.match(r"^([a-zA-Z]+)_", stem)
    return m.group(1).lower() if (m and m.group(1).lower() in CLASSES) else "other"

classes = {}
for fn in sorted(os.listdir(wavs)):
    if not fn.endswith(".wav"):
        continue
    stem = fn[:-4]
    path = os.path.join(wavs, fn)
    sha = hashlib.sha256(open(path, "rb").read()).hexdigest()
    with wave.open(path, "rb") as w:
        secs = w.getnframes() / w.getframerate()
    c = cls_of(stem)
    classes.setdefault(c, []).append({
        "path": f"wavs/{fn}",
        "seconds": round(secs, 2),
        "sha256": sha,
    })

manifest = {
    "name": "bist-01",
    "createdAt": "2026-10-15",
    "description": "Bist-class sick-seam corpus for #129 Track A (the qualityMin double-check): owner-recorded German sentences ending in ist/bist/kannst/musst/erst with deliberate pauses, plus other-class controls. Built via build.sh from raw/ (16 kHz/mono/pcm_s16le, ffprobe-verified, sha256).",
    "source": "owner recordings (raw/, converted by corpora/bist-01/build.sh)",
    "kinds": ["wav"],
    "audio": {"sampleRate": 16000, "channels": 1, "encoding": "pcm_s16le"},
    "classes": [
        {
            "name": c,
            "kind": "wav",
            "confidence": "pending — owner confirms after listening",
            "source": "owner dictation",
            "files": files,
        }
        for c, files in sorted(classes.items())
    ],
}
with open(os.path.join(here, "manifest.json"), "w") as f:
    json.dump(manifest, f, indent=2, ensure_ascii=False)
    f.write("\n")
total = sum(len(v) for v in classes.values())
print(f"manifest.json written: {total} wav(s) across {len(classes)} class(es)")
PYEOF

echo "ALL DONE"
