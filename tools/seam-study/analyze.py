#!/usr/bin/env python3
"""#122 seam-study analyzer v2 (+ the #129 per-seam quality extension).

Two robustness upgrades over v1:
  * RELIABILITY filter — the shadow full-context pass is known to collapse or
    hallucinate on long/noisy audio, so a run whose shadow word count is far
    from the join's is flagged and excluded from the error totals.
  * PER-FRAGMENT attribution — the join/shadow token diff is mapped back onto
    the fragment boundaries, so "missing/extra" is localized to a fragment
    (whose STARTING seam is either forced or silence-aligned).

The #129 extension adds PER-SEAM evidence on top (all #122 behavior and the
loop-mask discipline are unchanged):
  * per-seam Q / stitched from the runner's merged prepare_all.json (the
    app's prepare stream rotates only 10 records deep — run_quality.sh
    polls+merges it via merge_prepare.py),
  * seam cleanliness heuristics on the adjacent TRIMMED transcripts:
    SeamOverlap (a Python mirror of the Kotlin pipeline/SeamOverlap.kt) plus
    a word-split flag (previous transcript lacks sentence-final punctuation
    AND the next begins lowercase) — both are HEURISTICS with limits,
    documented in the README,
  * Q distributions split by stitched/bare and clean/unclean seams, the
    Q-vs-defect Spearman correlation, and a trim-false-positive flag
    (echoTokens removed that the shadow reference says were real words),
  * --quality-csv / --stage-winners for the staged sweep (run_quality.sh),
  * --selftest against the synthetic fixture in testdata/quality-fixture.

Usage: python3 analyze.py [results_dir] [--csv out.csv] [--detail RUNID]
       python3 analyze.py <results_dir> --quality-csv out.csv    (#129 sweep)
       python3 analyze.py <results_dir> --stage-winners out.txt   (#129 sweep)
       python3 analyze.py --selftest                               (#129 fixture)
"""
import json
import os
import re
import statistics
import sys
from difflib import SequenceMatcher

TOK = re.compile(r"[\w\u00c0-\u024f]+", re.UNICODE)
RELIABLE_LO, RELIABLE_HI = 0.80, 1.25


def toks(s):
    return [t.lower() for t in TOK.findall(s or "")]


def loop_spans(w, minrep=4, maxlen=8):
    """Spans (start,end) of a phrase (len 1..maxlen) repeating >=minrep times."""
    n = len(w)
    spans = []
    L = 1
    while L <= maxlen:
        i = 0
        while i + L * minrep <= n:
            block = w[i:i + L]
            if all(w[i + r * L:i + (r + 1) * L] == block for r in range(minrep)):
                r = minrep
                while i + (r + 1) * L <= n and w[i + r * L:i + (r + 1) * L] == block:
                    r += 1
                spans.append((i, i + r * L))
                i += r * L
            else:
                i += 1
        L += 1
    return spans


def compress_loops(w):
    """Replace each repetition-loop span with a single <loop> sentinel.
    Returns (compressed_tokens, loop_token_count). A loop present in BOTH the
    join and the shadow then matches as one token instead of ~100; a loop in
    only one contributes exactly one error, not a hundred."""
    spans = loop_spans(w)
    if not spans:
        return w, 0
    out = []
    looped = 0
    pos = 0
    for a, b in sorted(spans):
        if a < pos:
            continue
        out.extend(w[pos:a])
        out.append("<loop>")
        looped += b - a
        pos = b
    out.extend(w[pos:])
    return out, looped


# --------------------------------------------------------------------------
# #129 per-seam quality extension
# --------------------------------------------------------------------------

# Mirror of the Kotlin SeamOverlap tokenizer ([\p{L}\p{N}]+, lowercased).
# Python's [^\W_]+ covers Unicode letters and digits minus underscore —
# close, but not guaranteed byte-identical to Kotlin's \p{L}\p{N} for exotic
# number categories (Nl/No). The analyzer therefore cross-checks the mirror
# against the on-device `seamOverlapTokens` field of stt-fragment.json and
# reports a mismatch count (`tokmirror_mismatch`) — on real data it must
# stay 0; a nonzero count means the mirror drifted and every overlap-based
# number is suspect.
SEAM_TOK = re.compile(r"[^\W_]+", re.UNICODE)
SENTENCE_FINAL = set(".!?…")


def seam_tokens(s):
    return [t.lower() for t in SEAM_TOK.findall(s or "")]


def seam_overlap(prev, cur):
    """Length of the longest token run that is a SUFFIX of prev and a PREFIX
    of cur (0 when they share none) — mirror of
    SeamOverlap.longestTokenOverlap. >0 at a seam is the fingerprint of a
    boundary defect: a word straddling the cut (transcribed by BOTH
    fragments) or a surviving (untrimmed) pre-roll echo. It CANNOT tell
    those two apart from a legitimate spoken repetition — that ambiguity is
    the metric's documented limit."""
    a, b = seam_tokens(prev), seam_tokens(cur)
    if not a or not b:
        return 0
    for k in range(min(len(a), len(b)), 0, -1):
        if a[len(a) - k:] == b[:k]:
            return k
    return 0


def word_split_flag(prev, cur):
    """WEAK heuristic for a word/sentence straddling the cut: the previous
    transcript ends WITHOUT sentence-final punctuation AND the next begins
    with a lowercase letter. Limits (documented): Whisper transcripts of
    continuous speech often omit punctuation and start lowercase at CLEAN
    seams (false positives), and German capitalizes nouns (false negatives).
    It is only a secondary signal next to seam_overlap."""
    prev = (prev or "").rstrip()
    cur = (cur or "").lstrip()
    if not prev or not cur:
        return False
    return prev[-1] not in SENTENCE_FINAL and cur[0].isalpha() and cur[0].islower()


def pct(sorted_vals, p):
    """Ceil-index percentile on a pre-sorted list (deterministic, no numpy)."""
    if not sorted_vals:
        return None
    import math
    i = max(0, math.ceil(p * len(sorted_vals)) - 1)
    return sorted_vals[min(i, len(sorted_vals) - 1)]


def _ranks(v):
    order = sorted(range(len(v)), key=lambda i: v[i])
    r = [0.0] * len(v)
    i = 0
    while i < len(order):
        j = i
        while j + 1 < len(order) and v[order[j + 1]] == v[order[i]]:
            j += 1
        for t in range(i, j + 1):
            r[order[t]] = (i + j) / 2 + 1
        i = j + 1
    return r


def spearman(xs, ys):
    """Spearman rank correlation; None for <2 points, 0.0 on ties (undefined)."""
    if len(xs) < 2:
        return None
    a, b = _ranks(xs), _ranks(ys)
    n = len(a)
    ma, mb = sum(a) / n, sum(b) / n
    cov = sum((x - ma) * (y - mb) for x, y in zip(a, b))
    va = sum((x - ma) ** 2 for x in a)
    vb = sum((y - mb) ** 2 for y in b)
    if va == 0 or vb == 0:
        return 0.0
    return round(cov / ((va * vb) ** 0.5), 3)


def prepare_index(d):
    """fragment index -> prepare record, from the runner's merged
    prepare_all.json (absent in #122-era dirs -> {})."""
    prep = load(os.path.join(d, "prepare_all.json"))
    idx = {}
    if isinstance(prep, list):
        for rec in prep:
            if isinstance(rec, dict) and isinstance(rec.get("fragment"), int):
                idx[rec["fragment"]] = rec
    return idx


def seam_records(frags, prep_by_frag):
    """Per-seam evidence list. Seam i (1..n-1) is the boundary BEFORE
    fragment i: its Q/stitched come from fragment i-1's prepare record (that
    record describes the cut at fragment i-1's END = this seam — the
    #126/#128 contract block), its cleanliness from the adjacent TRIMMED
    transcripts in stt-fragment.json."""
    out = []
    for i in range(1, len(frags)):
        rec = prep_by_frag.get(i - 1)
        prev_t = frags[i - 1].get("text") or ""
        cur_t = frags[i].get("text") or ""
        ov = seam_overlap(prev_t, cur_t)
        split = word_split_flag(prev_t, cur_t)
        device_ov = frags[i].get("seamOverlapTokens")
        out.append({
            "seam": i,
            "q": rec.get("Q") if rec else None,
            "stitched": rec.get("stitched") if rec else None,
            "overlap": ov,
            "split": split,
            "unclean": ov > 0 or split,
            # mirror-vs-device cross-check (must stay 0 on real data)
            "tokmirror_mismatch": device_ov is not None and device_ov != ov,
        })
    return out


def load(p):
    try:
        with open(p, encoding="utf-8") as fh:
            return json.load(fh)
    except Exception:
        return None


def fragment_counts(frags):
    return [len(toks(f.get("text"))) for f in frags]


def attribute(join, shadow, counts):
    """Return (extra_per_frag, missing_per_frag) lists, index-aligned to counts."""
    n = len(counts)
    extra = [0] * n
    missing = [0] * n
    # join token index -> fragment index
    bounds = []
    acc = 0
    for c in counts:
        bounds.append((acc, acc + c))
        acc += c

    def frag_of(idx):
        for fi, (a, b) in enumerate(bounds):
            if a <= idx < b:
                return fi
        return max(0, n - 1)

    for tag, i1, i2, j1, j2 in SequenceMatcher(a=join, b=shadow, autojunk=False).get_opcodes():
        if tag == "equal":
            continue
        target = frag_of(min(i1, max(0, acc - 1)))
        if tag in ("delete", "replace"):
            extra[target] += i2 - i1
        if tag in ("insert", "replace"):
            missing[target] += j2 - j1
    return extra, missing


def analyze(rid, d):
    h = load(os.path.join(d, "harness.json"))
    if not h:
        return None
    frags = load(os.path.join(d, "stt-fragment.json")) or []
    raw_join, raw_shadow = toks(h.get("deliveredText")), toks(h.get("shadowFullText"))
    # Loop-mask per fragment (preserves boundaries) and the shadow globally.
    frag_comp, join_loops = [], 0
    for f in frags:
        c, n = compress_loops(toks(f.get("text")))
        frag_comp.append(c)
        join_loops += n
    join = [t for c in frag_comp for t in c]
    shadow, shadow_loops = compress_loops(raw_shadow)
    counts = [len(c) for c in frag_comp]
    extra, missing = attribute(join, shadow, counts) if frags else ([], [])
    ratio = (len(raw_join) / len(raw_shadow)) if raw_shadow else 0.0
    reliable = raw_shadow and RELIABLE_LO <= ratio <= RELIABLE_HI and len(raw_join) > 0

    per_frag = []
    for i, f in enumerate(frags):
        per_frag.append({
            "i": i,
            "words": len(toks(f.get("text"))),
            "forced": bool(f.get("seamForced")) if i > 0 else None,
            "echo": f.get("echoTokens") or 0,
            "extra": extra[i] if i < len(extra) else 0,
            "missing": missing[i] if i < len(missing) else 0,
        })

    # --- #129 per-seam quality evidence ---------------------------------
    prep_by_frag = prepare_index(d)
    per_seam = seam_records(frags, prep_by_frag) if frags else []
    qs = sorted(s["q"] for s in per_seam if s["q"] is not None)

    def med(vals):
        return statistics.median(vals) if vals else None

    qs_stitched = sorted(s["q"] for s in per_seam if s["q"] is not None and s["stitched"])
    qs_bare = sorted(s["q"] for s in per_seam if s["q"] is not None and s["stitched"] is False)
    qs_unclean = sorted(s["q"] for s in per_seam if s["q"] is not None and s["unclean"])
    qs_clean = sorted(s["q"] for s in per_seam if s["q"] is not None and not s["unclean"])
    # Trim false-positive heuristic (#129): the echo trim removed echoTokens
    # from a fragment's head, and the loop-masked diff against the shadow
    # says at least that many words are MISSING from that fragment's span —
    # the "echo" was real speech. Limit: the missing attribution is itself
    # approximate (seam-local, reference-dependent), so this is a FLAG for
    # manual review, not a verdict.
    trim_fp = sum(1 for f in per_frag if (f["echo"] or 0) > 0 and f["missing"] >= f["echo"])
    q_spear = spearman([s["q"] for s in per_seam if s["q"] is not None],
                       [1 if s["unclean"] else 0 for s in per_seam if s["q"] is not None])

    label = h.get("label", rid)
    parts = label.split("__")
    return {
        "id": rid,
        "class": parts[0],
        "duration": parts[1] if len(parts) > 1 else "?",
        "format": h.get("format"),
        "success": h.get("success"),
        # #129 knobs as the harness recorded them (sweep attribution)
        "axisK": h.get("axisK"),
        "axisC": h.get("axisC"),
        "qualityMin": h.get("qualityMin"),
        "marginKMs": h.get("marginKMs"),
        "searchSpan": h.get("searchSpan"),
        "policy": h.get("policy"),
        "fragmentSeconds": h.get("fragmentSeconds"),
        "fragments": len(frags),
        "join": len(raw_join),
        "shadow": len(raw_shadow),
        "join_loops": join_loops,
        "shadow_loops": shadow_loops,
        "ratio": round(ratio, 2),
        "reliable": bool(reliable),
        "extra": sum(extra),
        "missing": sum(missing),
        "forced_seams": sum(1 for f in frags if f.get("seamForced")),
        "silence_seams": sum(1 for f in frags if f["fragment"] > 0 and not f.get("seamForced")),
        "per_frag": per_frag,
        # --- #129 per-seam fields ---
        "seams": len(per_seam),
        "seams_unclean": sum(1 for s in per_seam if s["unclean"]),
        "seam_overlap_total": sum(s["overlap"] for s in per_seam),
        "word_splits": sum(1 for s in per_seam if s["split"]),
        "q_median": med(qs),
        "q_p25": pct(qs, 0.25),
        "q_p75": pct(qs, 0.75),
        "q_stitched_median": med(qs_stitched),
        "q_bare_median": med(qs_bare),
        "q_unclean_median": med(qs_unclean),
        "q_clean_median": med(qs_clean),
        "q_spearman": q_spear,
        "trim_fp": trim_fp,
        "prepare_captured": len(prep_by_frag),
        "prepare_expected": len(frags),
        "tokmirror_mismatch": sum(1 for s in per_seam if s["tokmirror_mismatch"]),
        "per_seam": per_seam,
    }


# --------------------------------------------------------------------------
# #129 sweep modes (--quality-csv / --stage-winners) + fixture selftest
# --------------------------------------------------------------------------

# Swept knob + provisional default per runner stage (run_quality.sh).
STAGE_KNOBS = {
    "1": ("axisK", 0.65),
    "2": ("qualityMin", 10.0),
    "3": ("axisC", 100.0),
    "4": ("marginKMs", 150),
    "5": ("searchSpan", "lateHalf"),
}

QUALITY_FIELDS = [
    "arm", "stage", "swept", "level", "sample", "class", "success", "error",
    "fragments", "seams", "axisK", "axisC", "qualityMin", "marginKMs", "searchSpan",
    "policy", "fragmentSeconds", "searchSeconds", "join", "shadow", "ratio",
    "reliable", "extra", "missing", "err100w", "seams_unclean", "seam_overlap_total",
    "word_splits", "q_median", "q_p25", "q_p75", "q_stitched_median", "q_bare_median",
    "q_unclean_median", "q_clean_median", "q_spearman", "trim_fp",
    "prepare_captured", "prepare_expected", "tokmirror_mismatch",
]


def quality_row(rid, d):
    """One CSV row per ARM (run): the runner's arm.json plan metadata plus
    everything analyze() computes — including arms whose harness.json never
    landed (success False: no data loss on failed arms)."""
    arm = load(os.path.join(d, "arm.json")) or {}
    h = load(os.path.join(d, "harness.json")) or {}
    r = analyze(rid, d) if h else None
    row = {
        "arm": rid,
        "stage": arm.get("stage", ""),
        "swept": arm.get("swept", ""),
        "level": arm.get("level", ""),
        "sample": arm.get("sample") or h.get("input") or "",
        "class": arm.get("class", ""),
    }
    for k in ("axisK", "axisC", "qualityMin", "marginKMs", "searchSpan",
              "policy", "fragmentSeconds", "searchSeconds"):
        row[k] = h.get(k, arm.get(k))
    if r:
        row.update({
            "success": r["success"], "error": h.get("error") or "",
            "fragments": r["fragments"], "seams": r["seams"],
            "join": r["join"], "shadow": r["shadow"], "ratio": r["ratio"],
            "reliable": r["reliable"], "extra": r["extra"], "missing": r["missing"],
            # the per-arm primary score: the #122 loop-masked err/100w
            "err100w": (round((r["extra"] + r["missing"]) / max(1, r["shadow"]) * 100, 2)
                        if r["reliable"] else None),
            "seams_unclean": r["seams_unclean"],
            "seam_overlap_total": r["seam_overlap_total"],
            "word_splits": r["word_splits"],
            "q_median": r["q_median"], "q_p25": r["q_p25"], "q_p75": r["q_p75"],
            "q_stitched_median": r["q_stitched_median"],
            "q_bare_median": r["q_bare_median"],
            "q_unclean_median": r["q_unclean_median"],
            "q_clean_median": r["q_clean_median"],
            "q_spearman": r["q_spearman"], "trim_fp": r["trim_fp"],
            "prepare_captured": r["prepare_captured"],
            "prepare_expected": r["prepare_expected"],
            "tokmirror_mismatch": r["tokmirror_mismatch"],
        })
    else:
        row.update({"success": False,
                    "error": h.get("error") or "no harness.json (incomplete/failed arm)"})
    return row


def _level_sort(lvl):
    try:
        return (0, float(lvl))
    except (TypeError, ValueError):
        return (1, str(lvl))


def _level_distance(lvl, default):
    try:
        return abs(float(lvl) - float(default))
    except (TypeError, ValueError):
        return 0.0 if lvl == default else 1.0


def quality_modes(base, csv_path, winners_path):
    """--quality-csv: one row per arm dir. --stage-winners: per-stage
    response curves (level -> mean err/100w, overlap, unclean seams — a FLAT
    curve is the stability-knob signature the #129 expectation wants made
    visible) and the coordinate-descent winner per stage: minimal mean
    err/100w over reliable runs, tie-break lower seam duplication, then
    closer to the provisional default."""
    import csv as _csv
    dirs = [x for x in sorted(os.listdir(base)) if os.path.isdir(os.path.join(base, x))]
    rows = [quality_row(x, os.path.join(base, x)) for x in dirs]

    if csv_path:
        with open(csv_path, "w", newline="", encoding="utf-8") as fh:
            w = _csv.DictWriter(fh, fieldnames=QUALITY_FIELDS, extrasaction="ignore")
            w.writeheader()
            for r in rows:
                w.writerow(r)
        print(f"wrote {len(rows)} arm rows -> {csv_path}")

    if not winners_path:
        return

    stages = {}
    for r in rows:
        s = str(r.get("stage") or "")
        if s in STAGE_KNOBS and r.get("swept"):
            stages.setdefault(s, []).append(r)

    winners = {}
    for s in sorted(stages):
        knob, default = STAGE_KNOBS[s]
        arms = stages[s]
        # held-knob context = every knob EXCEPT the swept one (levels
        # legitimately differ in the swept knob); more than one context
        # means the stage was re-run after a winner changed.
        held = [k for k in ("axisK", "axisC", "qualityMin", "marginKMs", "searchSpan")
                if k != knob]
        contexts = {tuple(r.get(k) for k in held) for r in arms if r.get("success")}
        warn = (f"  !! {len(contexts)} held-knob contexts — stage re-run after a "
                f"winner change? Split OUT dirs before trusting this summary."
                if len(contexts) > 1 else "")
        print(f"\nstage {s} — swept {knob} (provisional default {default}), "
              f"{len(arms)} arms{warn}")
        print(f"{'level':>10} {'arms':>5} {'rel':>4} {'err/100w':>9} {'ovl':>5} "
              f"{'unclean':>8} {'q_med':>7}")
        by_level = {}
        for r in arms:
            if r.get("success"):
                by_level.setdefault(r.get(knob), []).append(r)
        best = None
        for lvl in sorted(by_level, key=_level_sort):
            rs = by_level[lvl]
            rel = [r for r in rs if r.get("reliable") and r.get("err100w") is not None]
            mean_err = sum(r["err100w"] for r in rel) / len(rel) if rel else None
            ovl = sum(r.get("seam_overlap_total") or 0 for r in rs)
            unc = sum(r.get("seams_unclean") or 0 for r in rs)
            qmeds = [r.get("q_median") for r in rs if r.get("q_median") is not None]
            qmed = round(sum(qmeds) / len(qmeds), 2) if qmeds else None
            err_s = f"{mean_err:9.2f}" if mean_err is not None else "       -"
            print(f"{str(lvl):>10} {len(rs):>5} {len(rel):>4} {err_s} {ovl:>5} {unc:>8} "
                  f"{str(qmed):>7}")
            if rel:
                key = (mean_err, ovl, _level_distance(lvl, default))
                if best is None or key < best[0]:
                    best = (key, lvl)
        if best:
            winners[knob] = best[1]
            print(f"  -> winner: {knob}={best[1]}")
        else:
            print(f"  -> NO reliable runs; keeping the provisional default {knob}={default}")

    with open(winners_path, "w", encoding="utf-8") as fh:
        for knob, lvl in winners.items():
            fh.write(f"{knob}={lvl}\n")
    print(f"\nwrote {len(winners)} winners -> {winners_path}")


def selftest():
    """Unit-check the #129 per-seam extension against the synthetic fixture
    in testdata/quality-fixture (hand-written, expected values known). Also
    sanity-checks the #122 fields on the same fixture — they must not have
    changed."""
    base = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        "testdata", "quality-fixture")
    rid = "s1_fixture60__k0.65_c100.0_Q10.0_m150_lateHalf"
    row = quality_row(rid, os.path.join(base, rid))
    r = analyze(rid, os.path.join(base, rid))

    checks = [
        # #129 per-seam fields (expected values, see the fixture README)
        ("row.success", row["success"], True),
        ("row.stage", str(row["stage"]), "1"),
        ("row.swept", row["swept"], "axisK"),
        ("row.fragments", row["fragments"], 3),
        ("row.seams", row["seams"], 2),
        ("row.seam_overlap_total", row["seam_overlap_total"], 2),
        ("row.seams_unclean", row["seams_unclean"], 1),
        ("row.word_splits", row["word_splits"], 1),
        ("row.q_median", row["q_median"], 21.0),
        ("row.q_p25", row["q_p25"], 2.0),
        ("row.q_p75", row["q_p75"], 40.0),
        ("row.q_stitched_median", row["q_stitched_median"], 2.0),
        ("row.q_bare_median", row["q_bare_median"], 40.0),
        ("row.q_unclean_median", row["q_unclean_median"], 2.0),
        ("row.q_clean_median", row["q_clean_median"], 40.0),
        ("row.q_spearman", row["q_spearman"], -1.0),
        ("row.trim_fp", row["trim_fp"], 1),
        ("row.prepare_captured", row["prepare_captured"], 3),
        ("row.prepare_expected", row["prepare_expected"], 3),
        ("row.tokmirror_mismatch", row["tokmirror_mismatch"], 0),
        ("row.reliable", row["reliable"], True),
        ("row.extra", row["extra"], 0),
        ("row.missing", row["missing"], 2),
        ("row.err100w", row["err100w"], 10.0),
        ("row.axisK", row["axisK"], 0.65),
        ("row.qualityMin", row["qualityMin"], 10.0),
        # per-seam records themselves
        ("seam1.q", r["per_seam"][0]["q"], 2.0),
        ("seam1.stitched", r["per_seam"][0]["stitched"], True),
        ("seam1.overlap", r["per_seam"][0]["overlap"], 2),
        ("seam1.split", r["per_seam"][0]["split"], True),
        ("seam1.unclean", r["per_seam"][0]["unclean"], True),
        ("seam2.q", r["per_seam"][1]["q"], 40.0),
        ("seam2.stitched", r["per_seam"][1]["stitched"], False),
        ("seam2.overlap", r["per_seam"][1]["overlap"], 0),
        ("seam2.split", r["per_seam"][1]["split"], False),
        ("seam2.unclean", r["per_seam"][1]["unclean"], False),
        # #122 behavior on the fixture — unchanged
        ("class", r["class"], "fixture60"),
        ("duration", r["duration"], "60"),
        ("join", r["join"], 18),
        ("shadow", r["shadow"], 20),
        ("ratio", r["ratio"], 0.9),
        ("forced_seams", r["forced_seams"], 0),
        ("silence_seams", r["silence_seams"], 2),
        ("join_loops", r["join_loops"], 0),
        ("shadow_loops", r["shadow_loops"], 0),
    ]
    failed = 0
    for name, got, want in checks:
        ok = got == want and type(got) is type(want)
        print(f"  {'ok ' if ok else 'FAIL'} {name}: {got!r}" + ("" if ok else f" (want {want!r})"))
        failed += 0 if ok else 1

    # the CSV writer path must produce the full row set
    import csv as _csv
    import tempfile
    with tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False) as tf:
        tmp = tf.name
    quality_modes(base, tmp, None)
    with open(tmp, encoding="utf-8") as fh:
        written = list(_csv.DictReader(fh))
    ok = len(written) == 1 and written[0]["arm"] == rid and written[0]["q_median"] == "21.0"
    print(f"  {'ok ' if ok else 'FAIL'} quality-csv row round-trip")
    failed += 0 if ok else 1
    os.unlink(tmp)

    print(f"\nselftest: {len(checks) + 1 - failed}/{len(checks) + 1} checks passed")
    return 1 if failed else 0


def main():
    default = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results")
    args = sys.argv[1:]
    if "--selftest" in args:
        sys.exit(selftest())
    csv_path = args[args.index("--csv") + 1] if "--csv" in args else None
    detail = args[args.index("--detail") + 1] if "--detail" in args else None
    quality_csv = args[args.index("--quality-csv") + 1] if "--quality-csv" in args else None
    winners_path = args[args.index("--stage-winners") + 1] if "--stage-winners" in args else None
    consumed = set()
    for name in ("--csv", "--detail", "--quality-csv", "--stage-winners"):
        if name in args:
            consumed.update({args.index(name), args.index(name) + 1})
    if "--selftest" in args:
        consumed.add(args.index("--selftest"))
    positional = [a for i, a in enumerate(args) if i not in consumed and not a.startswith("-")]
    base = positional[0] if positional else default
    rows = [r for r in (analyze(x, os.path.join(base, x))
                        for x in sorted(os.listdir(base))
                        if os.path.isdir(os.path.join(base, x))) if r]

    if quality_csv or winners_path:
        quality_modes(base, quality_csv, winners_path)
        return

    if detail:
        r = next((x for x in rows if x["id"] == detail), None)
        if not r:
            print("no such run", detail)
            return
        print(f"{r['id']}  frags={r['fragments']} join={r['join']} shadow={r['shadow']} ratio={r['ratio']}")
        print(f"{'frag':>4} {'words':>5} {'seam':>7} {'echo':>4} {'extra':>5} {'miss':>5}")
        for f in r["per_frag"]:
            seam = "-" if f["forced"] is None else ("forced" if f["forced"] else "silence")
            print(f"{f['i']:4} {f['words']:5} {seam:>7} {f['echo']:4} {f['extra']:5} {f['missing']:5}")
        if r.get("per_seam"):
            print(f"\n{'seam':>4} {'Q':>8} {'stitch':>7} {'ovl':>4} {'split':>6} {'unclean':>8}")
            for s in r["per_seam"]:
                q = "  -" if s["q"] is None else f"{s['q']:8.2f}"
                st = "-" if s["stitched"] is None else str(s["stitched"])
                print(f"{s['seam']:4} {q} {st:>7} {s['overlap']:4} {str(s['split']):>6} {str(s['unclean']):>8}")
        return

    rel = [r for r in rows if r["reliable"]]
    unrel = [r for r in rows if not r["reliable"]]
    print(f"runs: {len(rows)} | reliable shadow reference: {len(rel)} | flagged unreliable: {len(unrel)}")
    if unrel:
        print("  unreliable:", ", ".join(f"{r['id']}({r['join']}/{r['shadow']})" for r in unrel))

    def arm_of(rid):
        import re as _re
        m = _re.match(r"W(\d+)_P(\d+)_", rid)
        if m:
            return f"W{m.group(1)}_P{m.group(2)}"
        for pre in ("P_all_", "P_forced_", "P_off_", "P_preroll_", "P_prompt_"):
            if rid.startswith(pre):
                return pre[:-1]
        for pre in ("B_", "S7_", "S21_", "R0.5_", "R2_"):
            if rid.startswith(pre):
                return pre[:-1]
        m = _re.match(r"Q(\d+)_", rid)
        if m:
            return "Q" + m.group(1)
        return "core"

    if "--arms" in sys.argv:
        groups = {}
        for r in rel:
            groups.setdefault(arm_of(r["id"]), []).append(r)
        print(f"\n{'arm':>10} {'runs':>4} {'frags':>5} {'shdw':>5} {'extra':>6} {'miss':>6} {'err/100w':>8} "
              f"{'joinLoop':>8} {'shdwLoop':>8}")
        order = ["core", "B_all", "S7", "S21", "P_all", "P_forced", "P_off", "P_preroll", "P_prompt",
                 "R0.5", "R2", "Q0", "Q32", "Q96", "Q320", "Q800",
                 "W21_P0", "W21_P96", "W21_P320", "W21_P800",
                 "W60_P0", "W60_P96", "W60_P320", "W60_P800"]
        for arm in order:
            g = groups.get(arm)
            if not g:
                continue
            shdw = sum(r["shadow"] for r in g)
            ex = sum(r["extra"] for r in g)
            mi = sum(r["missing"] for r in g)
            print(f"{arm:>10} {len(g):>4} {sum(r['fragments'] for r in g):>5} {shdw:>5} "
                  f"{ex:>6} {mi:>6} {(ex + mi) / max(1, shdw) * 100:8.1f} "
                  f"{sum(r['join_loops'] for r in g):>8} {sum(r['shadow_loops'] for r in g):>8}")
        return

    print(f"\n{'duration':>8} {'fmt':>4} {'runs':>4} {'frags':>5} {'shdw':>5} {'extra':>6} {'miss':>6} "
          f"{'err/100w':>8}")
    for dur in ["30", "60", "150", "300", "600", "10"]:
        for fmt in ["ogg", "wav"]:
            g = [r for r in rel if r["duration"] == dur and r["format"] == fmt]
            if not g:
                continue
            shdw = sum(r["shadow"] for r in g)
            ex = sum(r["extra"] for r in g)
            mi = sum(r["missing"] for r in g)
            fr = sum(r["fragments"] for r in g)
            print(f"{dur:>8} {fmt:>4} {len(g):>4} {fr:>5} {shdw:>5} {ex:>6} {mi:>6} "
                  f"{(ex + mi) / max(1, shdw) * 100:8.1f}")

    # per-fragment error rate by the cut type of the seam that STARTS the fragment
    def agg(forced):
        e = m = 0
        for r in rel:
            for f in r["per_frag"]:
                if f["forced"] is forced:
                    e += f["extra"]; m += f["missing"]
        return e, m
    fe, fm = agg(True)
    se, sm = agg(False)
    print(f"\nper-fragment errors by the cut type at the fragment's start:")
    print(f"  after a FORCED seam : extra {fe}, missing {fm}")
    print(f"  after a SILENCE seam: extra {se}, missing {sm}")
    print(f"  (echo-trimmed tokens total: {sum(f['echo'] for r in rel for f in r['per_frag'])}; "
          f"join loop tokens: {sum(r['join_loops'] for r in rel)}; "
          f"shadow loop tokens: {sum(r['shadow_loops'] for r in rel)})")

    print("\nworst reliable runs (extra+missing):")
    for r in sorted(rel, key=lambda r: -(r["extra"] + r["missing"]))[:8]:
        print(f"  {r['id']:34} frags={r['fragments']:3} join={r['join']:4} shdw={r['shadow']:4} "
              f"ratio={r['ratio']} extra={r['extra']:4} miss={r['missing']:4}")

    if csv_path:
        import csv
        path = csv_path
        fields = ["id", "class", "duration", "format", "success", "fragments", "join", "shadow",
                  "join_loops", "shadow_loops", "ratio", "reliable", "extra", "missing",
                  "forced_seams", "silence_seams"]
        with open(path, "w", newline="", encoding="utf-8") as fh:
            w = csv.DictWriter(fh, fieldnames=fields, extrasaction="ignore")
            w.writeheader()
            for r in rows:
                w.writerow(r)
        print("wrote", path)


if __name__ == "__main__":
    main()
