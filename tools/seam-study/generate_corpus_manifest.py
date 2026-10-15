#!/usr/bin/env python3
"""Generates corpora/seam-study-01/manifest.json (#146 Korpus #1 import).

Reads the #122 corpus manifest (tools/seam-study/corpus/samples.json —
class, source, cut offset, durations, sha256 per file) plus the per-class
confidence table from corpus/REPORT.md (embedded verbatim below) and emits
the analyzer's corpus convention:

    corpora/<name>/manifest.json
    {
      "name", "createdAt", "description", "source", "kinds", "audio",
      "classes": [{name, kind, confidence, note, source, startSeconds,
                   files: [{path, seconds, sha256}]}]
    }

One-time migration aid — the analyzer's `run` command reads this manifest
(sha256-verified) and never needs samples.json itself.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))

# From corpus/REPORT.md "Per class: confidence & uncertainty" (2026-10-14).
CONFIDENCE = {
    "de_monologue_male": (
        "high",
        "German single-speaker lecture ('Erlebnisschule'). Gender inferred "
        "from name/format (male); metadata silent.",
    ),
    "de_interview": (
        "high",
        "Title explicitly a two-person Gespräch (Liessmann ↔ Jochum); 20+ "
        "pauses/600 s consistent with dialogue.",
    ),
    "en_monologue_male": (
        "high",
        "English audiobook, Hunter S. Thompson; single read voice. Narrator "
        "gender assumed male (not in tags).",
    ),
    "en_monologue_female": (
        "medium-high",
        "Gangaji (US female teacher); rollout spectra flat, pause structure "
        "of continuous talk. Uncertainty: this 'Who Are You' CD may include "
        "audience Q&A, so not guaranteed pure monologue.",
    ),
    "en_interview": (
        "low",
        "No genuine English interview exists in the collection. Best 2+ "
        "speaker substitute: an Australian two-man live comedy act; may "
        "contain musical/song segments. Flagged as the weakest class.",
    ),
    "panel_multispeaker": (
        "medium",
        "A German lecture plus audience 'Frage und Antwort' (Q&A, several "
        "voices); not a formal panel but genuinely multi-speaker. No English "
        "panel present.",
    ),
    "phone_radio": (
        "high",
        "High confidence for BAND-LIMITING: 99% of spectral energy < 3.6 kHz "
        "(telephone band, vs > 5 kHz for the mono/interview sources). "
        "Content/language (likely German phone prank) not audibly confirmed.",
    ),
}


def main():
    samples_path = os.path.join(REPO, "tools", "seam-study", "corpus", "samples.json")
    out_path = os.path.join(
        REPO, "corpora", "seam-study-01", "manifest.json"
    )
    with open(samples_path, encoding="utf-8") as fh:
        samples = json.load(fh)

    classes = {}
    for s in samples:
        cls = classes.setdefault(
            s["class"],
            {"name": s["class"], "kind": "wav", "source": s["source"],
             "startSeconds": s["start"], "files": []},
        )
        if cls["source"] != s["source"]:
            sys.exit(f"class {cls['name']}: inconsistent source in samples.json")
        cls["files"].append(
            {
                "path": f"wavs/{s['file']}",
                "seconds": float(s["duration"]),
                "sha256": s["sha256"],
            }
        )
    for name, cls in classes.items():
        confidence, note = CONFIDENCE[name]
        cls["confidence"] = confidence
        cls["note"] = note

    manifest = {
        "name": "seam-study-01",
        "createdAt": "2026-10-15",
        "description": (
            "The #122 seam-study corpus (37 WAVs, 7 acoustic classes, nested "
            "cuts 10-600 s, 16 kHz/mono/s16le, sha256-verified): the "
            "clean-baseline reference corpus. Built on host 'think' from the "
            "murl music collection via tools/seam-study/corpus/build.sh; "
            "confidence + provenance from corpus/REPORT.md."
        ),
        "source": (
            "tools/seam-study/corpus (build.sh: sshfs murl -> ffmpeg cuts -> "
            "ffprobe verify -> sha256; host think, 2026-10-14)"
        ),
        "kinds": ["wav"],
        "audio": {"sampleRate": 16000, "channels": 1, "encoding": "pcm_s16le"},
        "classes": [classes[name] for name in sorted(classes)],
    }

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    n_files = sum(len(c["files"]) for c in manifest["classes"])
    print(f"wrote {out_path}: {len(manifest['classes'])} classes, {n_files} files")


if __name__ == "__main__":
    main()
