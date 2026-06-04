#!/usr/bin/env python3
"""
Convert distribution/play-store-icon.svg to an Android VectorDrawable.

Reads the high-quality SVG source and writes a pure VectorDrawable XML
to app/src/main/res/drawable/ic_launcher_foreground.xml.

Unsupported SVG features dropped:
  - <filter> (drop shadows, glows)
  - <pattern> (mesh grille → flat dark fill)
  - <clipPath>
  - Background gradient rect (handled by adaptive icon's @color/primary)
  - Gradients flattened to solid colors (VectorDrawable gradients require
    complex aapt:attr syntax; flat colors are cleaner at launcher scale)
"""

import re
from xml.etree import ElementTree as ET
from pathlib import Path

SVG_PATH = "distribution/play-store-icon.svg"
OUT_PATH = "app/src/main/res/drawable/ic_launcher_foreground.xml"

# Flatten gradient references to representative solid colors
GRADIENT_FLATTEN = {
    "chrome-grad": "#B0BEC5",   # blue-grey 300 (midpoint of chrome)
    "gold-grad": "#FF8F00",     # amber 900 (midpoint of gold)
    "bg-grad": "#1B5E20",       # deep green (ignored, background handles this)
}


def flatten_color(ref):
    """Resolve a url(#gradient-id) reference to a flat hex color."""
    m = re.match(r"url\(#([\w-]+)\)", ref or "")
    if m and m.group(1) in GRADIENT_FLATTEN:
        return GRADIENT_FLATTEN[m.group(1)]
    return ref


def ellipse_to_path(cx, cy, rx, ry):
    return (
        f"M{cx - rx},{cy} "
        f"A{rx},{ry} 0 1,0 {cx + rx},{cy} "
        f"A{rx},{ry} 0 1,0 {cx - rx},{cy} Z"
    )


def rounded_rect_to_path(x, y, w, h, rx=0):
    rx = min(float(rx), w / 2, h / 2)
    return (
        f"M{x + rx},{y} "
        f"L{x + w - rx},{y} "
        f"A{rx},{rx} 0 0,1 {x + w},{y + rx} "
        f"L{x + w},{y + h - rx} "
        f"A{rx},{rx} 0 0,1 {x + w - rx},{y + h} "
        f"L{x + rx},{y + h} "
        f"A{rx},{rx} 0 0,1 {x},{y + h - rx} "
        f"L{x},{y + rx} "
        f"A{rx},{rx} 0 0,1 {x + rx},{y} Z"
    )


def convert_element(el):
    """Convert a single SVG element to a path dict or None (skip)."""
    tag = el.tag.split("}")[-1] if "}" in el.tag else el.tag

    # --- RECT ---
    if tag == "rect":
        x = float(el.get("x", 0))
        y = float(el.get("y", 0))
        w = float(el.get("width", 0))
        h = float(el.get("height", 0))
        rx = float(el.get("rx", 0))
        path_data = rounded_rect_to_path(x, y, w, h, rx)
        fill = flatten_color(el.get("fill"))
        opacity = el.get("opacity", None)
        result = {"d": path_data}
        if fill and fill != "none":
            result["fill"] = fill
        if opacity:
            result["fill-alpha"] = opacity
        return result

    # --- ELLIPSE ---
    if tag == "ellipse":
        cx = float(el.get("cx", 0))
        cy = float(el.get("cy", 0))
        rx = float(el.get("rx", 0))
        ry = float(el.get("ry", 0))
        path_data = ellipse_to_path(cx, cy, rx, ry)
        fill = flatten_color(el.get("fill"))
        opacity = el.get("opacity", None)
        result = {"d": path_data}
        if fill and fill != "none":
            result["fill"] = fill
        if opacity:
            result["fill-alpha"] = opacity
        return result

    # --- PATH ---
    if tag == "path":
        path_data = el.get("d", "")
        fill = flatten_color(el.get("fill"))
        opacity = el.get("opacity", None)
        stroke = flatten_color(el.get("stroke"))
        stroke_w = el.get("stroke-width", None)
        stroke_lc = el.get("stroke-linecap", None)
        clip = el.get("clip-path", None)

        # Drop if it references a clip-path or is purely a pattern/mask path
        if clip:
            return None

        # Replace pattern fill (mesh grille) with flat fill
        fill_raw = el.get("fill", "")
        if "grille-mesh" in fill_raw:
            fill = "#1C1C1C"

        result = {"d": path_data}
        if fill and fill != "none":
            result["fill"] = fill
        if stroke and stroke != "none":
            result["stroke"] = stroke
        if stroke_w:
            result["stroke-width"] = stroke_w
        if stroke_lc:
            result["stroke-linecap"] = stroke_lc
        if opacity:
            result["fill-alpha"] = opacity
        return result

    # --- GROUP (<g>) ---
    if tag == "g":
        results = []
        for child in el:
            child_result = convert_element(child)
            if child_result is not None:
                if isinstance(child_result, list):
                    results.extend(child_result)
                else:
                    results.append(child_result)
        return results

    return None


def main():
    tree = ET.parse(SVG_PATH)
    root = tree.getroot()

    # Parse elements, skipping <defs>, <svg> background rect
    all_paths = []
    for child in root:
        raw_tag = child.tag.split("}")[-1] if "}" in child.tag else child.tag
        if raw_tag == "defs":
            continue
        # Skip the full-canvas background rect (handled by adaptive icon bg)
        if raw_tag == "rect":
            w = float(child.get("width", 0))
            h = float(child.get("height", 0))
            if w >= 512 and h >= 512:
                continue

        result = convert_element(child)
        if result is not None:
            if isinstance(result, list):
                all_paths.extend(result)
            else:
                all_paths.append(result)

    # Also skip any path that's purely the background rect
    all_paths = [p for p in all_paths if p.get("d", "").strip() not in ("M0,0H512V512H0Z", "")]

    # Generate VectorDrawable XML
    lines = []
    lines.append('<?xml version="1.0" encoding="utf-8"?>')
    lines.append('<vector xmlns:android="http://schemas.android.com/apk/res/android"')
    lines.append('    android:width="108dp"')
    lines.append('    android:height="108dp"')
    lines.append('    android:viewportWidth="512"')
    lines.append('    android:viewportHeight="512">')

    for p in all_paths:
        lines.append('    <path')
        lines.append(f'        android:pathData="{p["d"]}"')
        if "fill" in p and p["fill"]:
            lines.append(f'        android:fillColor="{p["fill"]}"')
        if "fill-alpha" in p and p["fill-alpha"]:
            lines.append(f'        android:fillAlpha="{p["fill-alpha"]}"')
        if "stroke" in p and p["stroke"]:
            lines.append(f'        android:strokeColor="{p["stroke"]}"')
        if "stroke-width" in p and p["stroke-width"]:
            lines.append(f'        android:strokeWidth="{p["stroke-width"]}"')
        if "stroke-linecap" in p and p["stroke-linecap"]:
            lines.append(f'        android:strokeLineCap="{p["stroke-linecap"]}"')
        lines.append('        android:fillType="nonZero" />')

    lines.append('</vector>')

    out = "\n".join(lines)
    Path(OUT_PATH).write_text(out)
    print(f"Wrote {OUT_PATH} ({len(all_paths)} paths)")


if __name__ == "__main__":
    main()
