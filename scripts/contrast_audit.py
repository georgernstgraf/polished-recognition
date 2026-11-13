#!/usr/bin/env python3
"""Device contrast audit — WCAG 2.1 ratios for every app text element.

Input: a `uiautomator` XML dump plus a `screencap -p` PNG. Every node that
carries text, belongs to our package and is visible (at least partly) in the
screenshot is measured from its **pixels**, not from the theme:

  * crop the node's `bounds` out of the screenshot,
  * the **background** is the dominant exact colour of that crop,
  * the **ink** is the colour cluster farthest from the background in WCAG
    relative luminance. Clusters are luminance buckets (64 of them), and a
    bucket only competes when it holds at least `--min-ink-share` of the crop
    (default 0.5 %): a flat glyph core lands in one bucket, antialiasing
    fringes spread over the neighbours, and a single stray pixel owns a
    bucket far below the floor. The bucket's representative is its most
    common exact colour, so the ratio is computed from real UI colours,
  * the ratio is the WCAG 2.1 contrast ratio of both colours.

Why buckets and not "the farthest pixel": a rounded view's `bounds` leave the
window background in the corners, a text field carries an accent underline,
and a button's fill differs from the window — all of them are colour clusters
that sit closer to the background than the glyph core, so the farthest
*bucket* is the text. Measured on the A03: a button whose text holds 0.96 %
of the crop would otherwise report the 2.1 % corner as ink and fail at 1.14:1.

Thresholds (WCAG 2.1): **4.5:1** normal text, **3.0:1** large text and
**3.0:1** disabled UI (`enabled="false"` — disabled controls are exempt from
the text minimum, but a 3.0 floor keeps them legible).

Large-text heuristic (the one thing uiautomator does NOT tell us): a node
counts as large when its `bounds` height is at least 18 sp in pixels —

    18 sp == round(18 * density_dpi / 160 * font_scale) px

i.e. 47 px at 420 dpi, 36 px at 320 dpi — **and** the measured glyph extent
(the median ink line band) reaches the same 18 sp. The second condition is
what keeps a 12-sp hint inside a 60-px input field from being called large:
the view bounds are the input box, not the text. The heuristic is a proxy,
not a font-size query, and it errs strict — a node it cannot prove large
keeps the 4.5 threshold. On the A03 nothing qualifies (16-sp field hints
measure 33 px of ink, 18-sp headings 34 px), so the device gate runs at
4.5:1 for every enabled text element.

Ratios are gated on their 2-decimal value — the same value the table prints —
so a printed row can never contradict its own verdict. Antialiased text
measures a hair below the CSS colour on a 1x screenshot; a 5.05:1 palette
entry lands at 5.00–5.05:1 here.

Exit codes: 0 = every element passes, 1 = at least one fails, 2 = usage or
environment error (unreadable dump/screenshot, no measurable node).

Usage:
    contrast_audit.py --dump dump.xml --shot shot.png [--mode light]
                      [--json out.json] [--density-dpi 420] [--font-scale 1.0]
"""

from __future__ import annotations

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

try:
    from PIL import Image
except ImportError:  # pragma: no cover - environment guard
    sys.exit("contrast_audit: Pillow is required (python3 -m pip install pillow)")

PACKAGE = "com.georgernstgraf.polishedrecognition"

NORMAL_MIN = 4.5
LARGE_MIN = 3.0
DISABLED_MIN = 3.0

MIN_INK_SHARE = 0.005
MIN_BG_SHARE = 0.35
BINS = 64
MIN_CROP_W = 6
MIN_CROP_H = 8
MIN_VISIBLE = 0.5


# --- colour math -----------------------------------------------------------


def _channel(value: int) -> float:
    v = value / 255.0
    return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4


def luminance(rgb: tuple[int, int, int]) -> float:
    r, g, b = rgb
    return 0.2126 * _channel(r) + 0.7152 * _channel(g) + 0.0722 * _channel(b)


def contrast(a: tuple[int, int, int], b: tuple[int, int, int]) -> float:
    la, lb = luminance(a), luminance(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def hexcolor(rgb: tuple[int, int, int]) -> str:
    return "#{:02X}{:02X}{:02X}".format(*rgb)


# --- dump parsing ----------------------------------------------------------


def _parse_bounds(raw: str) -> tuple[int, int, int, int] | None:
    """"[x1,y1][x2,y2]" -> (l, t, r, b)."""
    try:
        left, top, right, bottom = (
            int(part)
            for part in raw.replace("[", ",").replace("]", ",").split(",")
            if part.strip()
        )
    except (TypeError, ValueError):
        return None
    return left, top, right, bottom


def read_hierarchy(path: Path) -> ET.Element:
    """Parse a raw `uiautomator dump /dev/tty` blob.

    The raw stream may carry the "UI hierchary dumped to: /dev/tty" banner
    (AOSP's typo) before or after the XML and stray NUL bytes; slice the
    document out instead of trusting the file to be pure XML.
    """
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError as exc:
        raise SystemExit(f"contrast_audit: cannot read dump {path}: {exc}")
    start = text.find("<hierarchy")
    end = text.rfind("</hierarchy>")
    if start < 0 or end < 0:
        raise SystemExit(f"contrast_audit: {path} holds no <hierarchy> document")
    blob = text[start : end + len("</hierarchy>")].replace("\x00", "")
    try:
        return ET.fromstring(blob)
    except ET.ParseError as exc:
        raise SystemExit(f"contrast_audit: {path} is not parseable XML: {exc}")


def _own_texts(node: ET.Element) -> set[str]:
    return {
        (child.get("text") or "").strip()
        for child in node.iter("node")
        if child is not node
    }


def collect_nodes(root: ET.Element, package: str):
    """Text-bearing app nodes, without wrapper duplicates.

    A node that repeats the text of one of its own descendants is a container
    wrapping a leaf (its crop would include more than the glyphs) — skipped.
    """
    nodes = []
    seen: set[tuple[str, str, str]] = set()
    for node in root.iter("node"):
        text = (node.get("text") or "").strip()
        if not text or (node.get("package") or "") != package:
            continue
        if text in _own_texts(node):
            continue  # container duplicate of a leaf
        bounds = _parse_bounds(node.get("bounds") or "")
        if bounds is None:
            continue
        key = (text, node.get("bounds") or "", node.get("enabled") or "")
        if key in seen:
            continue
        seen.add(key)
        nodes.append(
            {
                "text": text,
                "class": (node.get("class") or "").rsplit(".", 1)[-1],
                "bounds": bounds,
                "enabled": (node.get("enabled") or "true") == "true",
            }
        )
    return nodes


# --- measurement -----------------------------------------------------------


def measure(img: Image.Image, bounds, min_ink_share: float):
    """Dominant colour vs. opposing colour cluster inside `bounds`.

    Colours are clustered by relative luminance into BINS buckets (a flat
    glyph core lands in one bucket; antialiasing fringes spread over the
    neighbouring ones). The **background** is the dominant exact colour, the
    **ink** is the bucket farthest from it that holds at least
    `min_ink_share` of the crop — its representative is the bucket's most
    common exact colour. A rounded view whose bounds leave the window
    background in the corners therefore cannot steal the ink slot (that
    corner is close in luminance), and a single stray pixel owns a bucket of
    its own, far below the share floor.
    """
    left, top, right, bottom = bounds
    width, height = img.size
    cl, ct = max(0, left), max(0, top)
    cr, cb = min(width, right), min(height, bottom)
    node_area = max(1, (right - left) * (bottom - top))
    cw, ch = cr - cl, cb - ct
    if cw < MIN_CROP_W or ch < MIN_CROP_H:
        return None, "offscreen"
    visible = (cw * ch) / node_area
    if visible < MIN_VISIBLE:
        return None, "clipped"

    crop = img.crop((cl, ct, cr, cb)).convert("RGB")
    raw = crop.tobytes()
    counts = Counter(zip(raw[0::3], raw[1::3], raw[2::3]))
    total = cw * ch

    bg, bg_count = counts.most_common(1)[0]
    bg_share = bg_count / total
    if bg_share < MIN_BG_SHARE:
        return None, "ambiguous"
    bg_lum = luminance(bg)

    bin_pixels: dict[int, int] = {}
    bin_rep: dict[int, tuple[tuple[int, int, int], int]] = {}
    for colour, count in counts.items():
        bucket = int(luminance(colour) * BINS)
        bin_pixels[bucket] = bin_pixels.get(bucket, 0) + count
        rep, rep_count = bin_rep.get(bucket, (colour, 0))
        if count > rep_count:
            bin_rep[bucket] = (colour, count)

    best_dist, best_bucket, ink, ink_count = -1.0, -1, bg, 0
    for bucket, count in bin_pixels.items():
        if count / total < min_ink_share:
            continue
        colour = bin_rep[bucket][0]
        if colour == bg:
            continue  # the background's own bucket is not ink
        dist = abs(luminance(colour) - bg_lum)
        if dist > best_dist:
            best_dist, best_bucket, ink, ink_count = dist, bucket, colour, count

    if best_bucket < 0:
        # Nothing but the background colour reaches the share floor: the glyphs
        # are too thin for this crop to measure (a short label in a wide
        # button). Reporting a ratio here would be a fabricated 1.00:1.
        return None, "ink-below-share"

    # Glyph extent: the view bounds include padding and (for fields) the whole
    # input box, so the rendered text height is the only honest "how large is
    # this text" signal. Full-width rows are structural (a field's underline,
    # a slider track) and are dropped; a multi-line block is reduced to its
    # median line band so three lines of 12-sp text do not read as "large".
    row_hits = [0] * ch
    for index, colour in enumerate(zip(raw[0::3], raw[1::3], raw[2::3])):
        if int(luminance(colour) * BINS) != best_bucket:
            continue
        row_hits[index // cw] += 1
    rule_limit = 0.8 * cw
    bands: list[int] = []
    run = 0
    for hits in row_hits:
        if 0 < hits < rule_limit:
            run += 1
        elif run:
            bands.append(run)
            run = 0
    if run:
        bands.append(run)
    bands.sort()
    ink_height = bands[len(bands) // 2] if bands else 0

    return (
        {
            "bg": bg,
            "ink": ink,
            "bgShare": round(bg_share, 4),
            "inkShare": round(ink_count / total, 4),
            "inkHeight": ink_height,
            "visible": round(visible, 4),
        },
        None,
    )


def threshold_for(bounds_h: int, ink_h: int, enabled: bool, large_px: int) -> tuple[float, str]:
    """4.5 normal, 3.0 large, 3.0 disabled.

    Large needs BOTH the spec's bounds height (>= 18 sp) and a measured glyph
    extent of at least the same 18 sp: a 12-sp hint inside a 60-px input field
    satisfies the bounds rule alone, and calling that large would silently
    lower its threshold to 3.0. Measured on the A03, a 16-sp field hint (33 px
    of ink) and an 18-sp heading (34 px) are one pixel apart, so the cut sits
    at the full em — the strict direction, and no text in this app is large.
    """
    if bounds_h >= large_px and ink_h >= large_px:
        return LARGE_MIN, "large"
    if not enabled:
        return DISABLED_MIN, "disabled"
    return NORMAL_MIN, "normal"


# --- reporting -------------------------------------------------------------


def _clip(text: str, limit: int = 40) -> str:
    flat = " ".join(text.split())
    return flat if len(flat) <= limit else flat[: limit - 1] + "…"


def render_table(mode: str, rows: list[dict], skipped: list[dict]) -> str:
    lines = []
    head = (
        f"{'RATIO':>7}  {'THR':>3}  {'STATE':<8}  {'H':>3} {'IH':>3}  "
        f"{'BG':<8} {'INK':<8}  TEXT"
    )
    lines.append(f"mode={mode}")
    lines.append(head)
    lines.append("-" * 102)
    for row in rows:
        flag = "" if row["pass"] else "  <-- FAIL"
        lines.append(
            f"{row['ratio']:>6.2f}:1  {row['threshold']:>3.1f}  "
            f"{row['kind']:<8}  {row['height']:>3} {row['inkHeight']:>3}  "
            f"{row['bg']:<8} {row['ink']:<8}  {_clip(row['text'])}{flag}"
        )
    failed = [row for row in rows if not row["pass"]]
    worst = rows[0] if rows else None
    lines.append("-" * 102)
    summary = (
        f"elements={len(rows)}  fail={len(failed)}  "
        f"skipped={len(skipped)}"
    )
    if worst is not None:
        summary += (
            f"  worst={worst['ratio']:.2f}:1 "
            f"({worst['kind']}, {_clip(worst['text'], 24)!r})"
        )
    lines.append(summary)
    if skipped:
        reasons: Counter[str] = Counter(entry["reason"] for entry in skipped)
        lines.append(
            "skipped: " + ", ".join(f"{reason}={count}" for reason, count in sorted(reasons.items()))
        )
        for entry in skipped:
            if entry["reason"] == "ink-below-share":
                lines.append(f"  not measurable (too little ink): {_clip(entry['text'])}")
    if failed:
        lines.append(
            "failed: "
            + ", ".join(f"{row['ratio']:.2f}<{row['threshold']:.1f}" for row in failed)
        )
    return "\n".join(lines)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        prog="contrast_audit.py",
        description="WCAG 2.1 contrast of every app text element in a device screenshot.",
    )
    parser.add_argument("--dump", required=True, type=Path, help="uiautomator XML dump")
    parser.add_argument("--shot", required=True, type=Path, help="PNG screencap")
    parser.add_argument("--mode", default="unknown", help="label for the run (light/dark)")
    parser.add_argument("--package", default=PACKAGE, help="app package to audit")
    parser.add_argument(
        "--density-dpi",
        type=int,
        default=420,
        help="effective screen density (wm density); drives the large-text cut",
    )
    parser.add_argument(
        "--font-scale",
        type=float,
        default=1.0,
        help="system font_scale (settings get system font_scale), default 1.0",
    )
    parser.add_argument(
        "--large-px",
        type=int,
        default=None,
        help="override the large-text height in px (default 18sp at --density-dpi)",
    )
    parser.add_argument(
        "--min-ink-share",
        type=float,
        default=MIN_INK_SHARE,
        help="minimum share of the crop the ink cluster must hold (default 0.005)",
    )
    parser.add_argument("--json", type=Path, default=None, help="write the data as JSON")
    args = parser.parse_args(argv)

    if not args.shot.is_file():
        print(f"contrast_audit: no screenshot at {args.shot}", file=sys.stderr)
        return 2
    large_px = (
        args.large_px
        if args.large_px is not None
        else round(18 * args.density_dpi / 160 * args.font_scale)
    )

    root = read_hierarchy(args.dump)
    try:
        img = Image.open(args.shot)
    except OSError as exc:
        print(f"contrast_audit: cannot open screenshot: {exc}", file=sys.stderr)
        return 2
    img.load()

    nodes = collect_nodes(root, args.package)
    rows: list[dict] = []
    skipped: list[dict] = []
    for node in nodes:
        left, top, right, bottom = node["bounds"]
        measured, reason = measure(img, node["bounds"], args.min_ink_share)
        if measured is None:
            skipped.append({"text": node["text"], "reason": reason})
            continue
        height = bottom - top
        threshold, kind = threshold_for(
            height, measured["inkHeight"], node["enabled"], large_px
        )
        ratio = round(contrast(measured["bg"], measured["ink"]), 2)
        rows.append(
            {
                "text": node["text"],
                "class": node["class"],
                "bounds": list(node["bounds"]),
                "height": height,
                "enabled": node["enabled"],
                "kind": kind,
                "threshold": threshold,
                "ratio": ratio,
                "pass": ratio >= threshold,
                "bg": hexcolor(measured["bg"]),
                "ink": hexcolor(measured["ink"]),
                "bgShare": measured["bgShare"],
                "inkShare": measured["inkShare"],
                "inkHeight": measured["inkHeight"],
                "visible": measured["visible"],
            }
        )

    rows.sort(key=lambda row: (row["ratio"], row["text"]))
    print(render_table(args.mode, rows, skipped))

    if args.json is not None:
        payload = {
            "mode": args.mode,
            "package": args.package,
            "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "densityDpi": args.density_dpi,
            "fontScale": args.font_scale,
            "largePx": large_px,
            "minInkShare": args.min_ink_share,
            "thresholds": {"normal": NORMAL_MIN, "large": LARGE_MIN, "disabled": DISABLED_MIN},
            "elements": rows,
            "skipped": skipped,
            "summary": {
                "elements": len(rows),
                "failed": sum(1 for row in rows if not row["pass"]),
                "skipped": len(skipped),
                "worst": rows[0]["ratio"] if rows else None,
            },
        }
        try:
            args.json.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n")
        except OSError as exc:
            print(f"contrast_audit: cannot write {args.json}: {exc}", file=sys.stderr)
            return 2

    if not rows:
        print(
            "contrast_audit: no measurable text element — wrong package, "
            "empty screen, or the app is not in the foreground",
            file=sys.stderr,
        )
        return 2
    return 0 if all(row["pass"] for row in rows) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
