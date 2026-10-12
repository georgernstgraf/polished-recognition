#!/usr/bin/env python3
"""Merge a polled dump of the device's rotating `prepare` stream (#129).

The app's `RotatingJsonLogger` keeps ONE record per file and rotates 9 deep:
`prepare.json` = the newest record, `prepare_1.json`..`prepare_9.json` the
previous nine. On the harness restore->send path `prepareTailSync()` encodes
ALL fragments in a burst BEFORE the first upload, so a post-run pull alone
sees only the last ~10 records. run_quality.sh therefore polls the whole
rotation during every arm and pipes each snapshot through this script, which
deduplicates by fragment index into ONE stable array:

    adb ... shell 'for f in prepare prepare_1 ... prepare_9; do
                     echo "###SLOT $f"; cat .../logs/$f.json; echo; done' \\
      | tr -d '\\r' | merge_prepare.py <arm-dir>/prepare_all.json

Input format: `###SLOT <name>` marker lines, each followed by the slot's
single-line JSON record (missing files contribute nothing). Records repeat
across slots and polls; the merge keeps ONE record per fragment index with
the LATEST-seen content winning (polls arrive in order), and preserves
earlier-captured fragments that later snapshots no longer contain. Records
without a `fragment` field (the OGG-fallback / raw-rescue markers) are kept
at the array's tail. The output file is the analyzer's per-seam Q source.

Usage: merge_prepare.py <out.json>   (snapshot on stdin)
"""
import json
import os
import sys


def main():
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    out_path = sys.argv[1]

    merged = {}   # fragment index -> record (latest wins)
    others = []   # records without a fragment field (fallback/rawRescue)
    for line in sys.stdin:
        line = line.strip()
        if line.startswith("###SLOT"):
            continue
        if not line:
            continue
        try:
            rec = json.loads(line)
        except Exception:
            continue  # a torn read mid-rotation; the next poll re-captures
        if not isinstance(rec, dict):
            continue
        if isinstance(rec.get("fragment"), int):
            merged[rec["fragment"]] = rec
        else:
            others.append(rec)

    # Seed with the existing file so re-polls accumulate: a record only in
    # an older snapshot survives; one re-seen in this snapshot was already
    # overwritten above (stdin processed first = newer wins).
    try:
        with open(out_path, encoding="utf-8") as fh:
            for rec in json.load(fh):
                if isinstance(rec, dict) and isinstance(rec.get("fragment"), int):
                    merged.setdefault(rec["fragment"], rec)
                else:
                    others.append(rec)
    except Exception:
        pass

    arr = [merged[k] for k in sorted(merged)] + others
    tmp = out_path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(arr, fh, ensure_ascii=False)
    os.replace(tmp, out_path)  # atomic: a crash never leaves torn JSON
    print(f"prepare merge: {len(merged)} fragment records (+{len(others)} other)")


if __name__ == "__main__":
    sys.exit(main())
