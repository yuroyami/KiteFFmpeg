#!/usr/bin/env python3
"""Writes the four fonts test_glyph_fallback.c renders with, into fonts/ beside this script.

Each glyph is a plain shape whose area the test can tell apart from every other font's version of
the same character, so the test learns which font drew a line from how much ink it left. They are
generated rather than taken from a real font so that no third-party font is committed. Needs
fontTools (pip install fonttools); the output is committed, so the test itself never runs this.

  Kite Test Latin   Latin letters, 500 x 700 boxes; a hollow .notdef box.
  Kite Test CJK     Regular: 日本語の字幕 and ★ as 800 x 800 squares with a 400 x 400 hole, and
                    Latin letters as thin 100 x 700 bars.
                    Bold: the same characters as solid 600 x 600 squares, and 200 x 700 bars.
  Kite Test Star    only ★, as a solid 300 x 300 square.
"""

import os

from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen

HERE = os.path.dirname(os.path.abspath(__file__))
CJK = "日本語の字幕★"
LATIN = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"


def rect(pen, x0, y0, x1, y1, clockwise=True):
    points = [(x0, y0), (x0, y1), (x1, y1), (x1, y0)]
    if not clockwise:
        points.reverse()
    pen.moveTo(points[0])
    for p in points[1:]:
        pen.lineTo(p)
    pen.closePath()


def glyph(*rects):
    pen = TTGlyphPen(None)
    for r in rects:
        rect(pen, *r)
    return pen.glyph()


def build(path, family, style, weight, glyphs, advances, cmap):
    order = [".notdef"] + [g for g in glyphs if g != ".notdef"]
    fb = FontBuilder(1000, isTTF=True)
    fb.setupGlyphOrder(order)
    fb.setupCharacterMap(cmap)
    fb.setupGlyf(glyphs)
    fb.setupHorizontalMetrics({g: (advances[g], 0) for g in order})
    fb.setupHorizontalHeader(ascent=800, descent=-200)
    full = f"{family} {style}" if style != "Regular" else family
    fb.setupNameTable({
        "familyName": family,
        "styleName": style,
        "uniqueFontIdentifier": f"{full};kite-test",
        "fullName": full,
        "psName": full.replace(" ", ""),
        "version": "Version 1.0",
    })
    bold = weight >= 700
    fb.setupOS2(
        usWeightClass=weight,
        fsSelection=0x20 if bold else 0x40,
        sTypoAscender=800, sTypoDescender=-200, sTypoLineGap=0,
        usWinAscent=800, usWinDescent=200,
    )
    fb.setupPost()
    fb.setupHead(unitsPerEm=1000, macStyle=1 if bold else 0)
    fb.save(path)


def name(ch):
    return f"uni{ord(ch):04X}"


def latin():
    glyphs = {".notdef": glyph((100, 0, 600, 700), (150, 50, 550, 650, False)), "space": glyph()}
    advances = {".notdef": 700, "space": 300}
    cmap = {0x20: "space"}
    for ch in LATIN:
        glyphs[name(ch)] = glyph((50, 0, 550, 700))
        advances[name(ch)] = 600
        cmap[ord(ch)] = name(ch)
    build(os.path.join(HERE, "fonts/kite-test-latin.ttf"), "Kite Test Latin", "Regular", 400,
          glyphs, advances, cmap)


def cjk(style, weight, square, bar):
    glyphs = {".notdef": glyph(), "space": glyph()}
    advances = {".notdef": 1000, "space": 500}
    cmap = {0x20: "space"}
    for ch in CJK:
        glyphs[name(ch)] = glyph(*square)
        advances[name(ch)] = 1000
        cmap[ord(ch)] = name(ch)
    for ch in LATIN:
        glyphs[name(ch)] = glyph(bar)
        advances[name(ch)] = 600
        cmap[ord(ch)] = name(ch)
    file = f"fonts/kite-test-cjk-{style.lower()}.ttf"
    build(os.path.join(HERE, file), "Kite Test CJK", style, weight, glyphs, advances, cmap)


def star():
    glyphs = {".notdef": glyph(), name("★"): glyph((350, 0, 650, 300))}
    advances = {".notdef": 1000, name("★"): 1000}
    build(os.path.join(HERE, "fonts/kite-test-star.ttf"), "Kite Test Star", "Regular", 400,
          glyphs, advances, {ord("★"): name("★")})


if __name__ == "__main__":
    os.makedirs(os.path.join(HERE, "fonts"), exist_ok=True)
    latin()
    cjk("Regular", 400, [(100, -100, 900, 700), (300, 100, 700, 500, False)], (250, 0, 350, 700))
    cjk("Bold", 700, [(200, 0, 800, 600)], (200, 0, 400, 700))
    star()
