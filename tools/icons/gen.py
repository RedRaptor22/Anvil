#!/usr/bin/env python3
"""Turn Plume's SVG sprite into Android VectorDrawables.

Run from the repo root:   python3 tools/icons/gen.py

Why a generator rather than 51 hand-written XML files: the icons are part of
the visual language, and the only way to keep them honest is to be able to
re-derive them from the web build's own sprite whenever that sprite changes.
Every path here is Plume's `d` attribute copied through untouched — the script
only translates the *presentation* into VectorDrawable's vocabulary:

  * `.ico svg` in the stylesheet supplies fill:none, stroke:currentColor,
    stroke-width:1.5 and round caps/joins; VectorDrawable has no cascade, so
    those become explicit attributes on every path.
  * `<circle>` and `<ellipse>` have no VectorDrawable equivalent, so they are
    rewritten as two half-arcs. Android's pathData parser does support `A`.
  * an SVG `transform="translate(..)"` becomes a <group>.
  * `opacity` covers fill and stroke together, so it maps to both alphas.

Colour is left as opaque black with a default tint of @color/ink, and the
button re-tints at runtime — which is exactly what `currentColor` does in the
browser: the button decides, not the glyph.

The `_on` variants exist because of one CSS rule:

    button.on svg{fill:currentColor;stroke:currentColor;}

That rule outranks `.ico svg{fill:none}` (two type selectors to one), so an
active icon button in Plume shows a *filled* glyph — the stylesheet's own note
calls this out: "ACTIVE = a filled glyph on a soft tint, not a colour accent".
It does NOT outrank the id-scoped `#brushGrid svg` or `#railTab svg` rules, so
those glyphs stay hollow when active and get no `_on` variant here.
"""

import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "..", "app", "src", "main", "res", "drawable")

SYMBOL = re.compile(r'<symbol\s+id="([^"]+)"\s+viewBox="([^"]+)"\s*>(.*?)</symbol>', re.S)
ELEMENT = re.compile(r'<(path|circle|ellipse)\b([^>]*?)/?>')
ATTR = re.compile(r'([a-zA-Z-]+)="([^"]*)"')


def num(x):
    """Format a float the way the sprite writes them: no trailing zeros."""
    s = ("%.4f" % x).rstrip("0").rstrip(".")
    return s if s not in ("", "-0") else "0"


def circle_path(cx, cy, r):
    """A full circle as two half-arcs, which is the only closed-curve form
    VectorDrawable's parser accepts."""
    return "M%s,%sA%s,%s 0 1,0 %s,%sA%s,%s 0 1,0 %s,%sZ" % (
        num(cx - r), num(cy), num(r), num(r),
        num(cx + r), num(cy), num(r), num(r), num(cx - r), num(cy))


def ellipse_path(cx, cy, rx, ry):
    return "M%s,%sA%s,%s 0 1,0 %s,%sA%s,%s 0 1,0 %s,%sZ" % (
        num(cx - rx), num(cy), num(rx), num(ry),
        num(cx + rx), num(cy), num(rx), num(ry), num(cx - rx), num(cy))


def build(symbol_body, defaults, force_fill):
    """Return the <path>/<group> lines for one symbol.

    `defaults` carries the stroke presentation the stylesheet would have
    supplied; `force_fill` is the button.on rule.
    """
    lines = []
    for tag, raw in ELEMENT.findall(symbol_body):
        a = dict(ATTR.findall(raw))

        if tag == "path":
            data = a["d"]
        elif tag == "circle":
            data = circle_path(float(a["cx"]), float(a["cy"]), float(a["r"]))
        elif tag == "ellipse":
            data = ellipse_path(float(a["cx"]), float(a["cy"]),
                                float(a["rx"]), float(a["ry"]))
        else:
            raise SystemExit("unhandled element <%s>; teach gen.py about it "
                             "rather than hand-editing the output" % tag)

        stroked = a.get("stroke", "currentColor") != "none"
        filled = a.get("fill", "none") != "none" or force_fill

        attrs = ['android:pathData="%s"' % data.replace('"', "&quot;")]
        attrs.append('android:fillColor="%s"' % ("#000000" if filled else "#00000000"))
        if stroked:
            attrs.append('android:strokeColor="#000000"')
            attrs.append('android:strokeWidth="%s"' % a.get("stroke-width", defaults["width"]))
            cap = a.get("stroke-linecap", defaults["cap"])
            join = a.get("stroke-linejoin", defaults["join"])
            if cap:
                attrs.append('android:strokeLineCap="%s"' % cap)
            if join:
                attrs.append('android:strokeLineJoin="%s"' % join)
        if "opacity" in a:
            # the sprite writes ".5"; aapt wants a leading digit
            o = num(float(a["opacity"]))
            attrs.append('android:fillAlpha="%s"' % o)
            attrs.append('android:strokeAlpha="%s"' % o)

        path = "    <path\n" + "".join("        %s\n" % x for x in attrs)
        path = path.rstrip("\n") + " />"

        tr = a.get("transform")
        if tr:
            m = re.match(r'translate\(\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*\)$', tr.strip())
            if not m:
                raise SystemExit("unhandled transform %r" % tr)
            path = ('    <group android:translateX="%s" android:translateY="%s">\n'
                    % (m.group(1), m.group(2))
                    + "\n".join("    " + ln for ln in path.split("\n"))
                    + "\n    </group>")
        lines.append(path)
    return lines


def emit(name, view_w, view_h, body):
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           "<!-- Generated by tools/icons/gen.py from tools/icons/*.svg. "
           "Do not edit by hand. -->\n"
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           '    android:width="%sdp"\n'
           '    android:height="%sdp"\n'
           '    android:viewportWidth="%s"\n'
           '    android:viewportHeight="%s"\n'
           '    android:tint="@color/ink">\n'
           % (num(view_w), num(view_h), num(view_w), num(view_h)))
    xml += "\n".join(body) + "\n</vector>\n"
    with open(os.path.join(OUT, name + ".xml"), "w") as f:
        f.write(xml)


def run(path, prefix, defaults, on_variants):
    written = 0
    with open(path) as f:
        src = f.read()
    for sid, view, body in SYMBOL.findall(src):
        vb = [float(x) for x in view.split()]
        name = prefix + sid.split("-", 1)[1].replace("-", "_")
        emit(name, vb[2], vb[3], build(body, defaults, False))
        written += 1
        if on_variants:
            emit(name + "_on", vb[2], vb[3], build(body, defaults, True))
            written += 1
    return written


if __name__ == "__main__":
    if not os.path.isdir(OUT):
        os.makedirs(OUT)
    n = run(os.path.join(HERE, "sprite.svg"), "ic_",
            {"width": "1.5", "cap": "round", "join": "round"}, True)
    # `#brushGrid svg{stroke-width:1.4}` and no cap/join rule, so the glyphs
    # that want round ends say so themselves.
    n += run(os.path.join(HERE, "brushes.svg"), "ic_brush_",
             {"width": "1.4", "cap": None, "join": None}, False)
    sys.stdout.write("%d drawables written to %s\n" % (n, os.path.normpath(OUT)))
