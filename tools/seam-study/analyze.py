#!/usr/bin/env python3
"""#122 seam-study analyzer v2.

Two robustness upgrades over v1:
  * RELIABILITY filter — the shadow full-context pass is known to collapse or
    hallucinate on long/noisy audio, so a run whose shadow word count is far
    from the join's is flagged and excluded from the error totals.
  * PER-FRAGMENT attribution — the join/shadow token diff is mapped back onto
    the fragment boundaries, so "missing/extra" is localized to a fragment
    (whose STARTING seam is either forced or silence-aligned).

Usage: python3 analyze.py [results_dir] [--csv out.csv] [--detail RUNID]
"""
import json
import os
import re
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

    label = h.get("label", rid)
    parts = label.split("__")
    return {
        "id": rid,
        "class": parts[0],
        "duration": parts[1] if len(parts) > 1 else "?",
        "format": h.get("format"),
        "success": h.get("success"),
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
    }


def main():
    default = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results")
    args = sys.argv[1:]
    csv_path = args[args.index("--csv") + 1] if "--csv" in args else None
    detail = args[args.index("--detail") + 1] if "--detail" in args else None
    consumed = set()
    for name in ("--csv", "--detail"):
        if name in args:
            consumed.update({args.index(name), args.index(name) + 1})
    positional = [a for i, a in enumerate(args) if i not in consumed and not a.startswith("-")]
    base = positional[0] if positional else default
    rows = [r for r in (analyze(x, os.path.join(base, x))
                        for x in sorted(os.listdir(base))
                        if os.path.isdir(os.path.join(base, x))) if r]

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
