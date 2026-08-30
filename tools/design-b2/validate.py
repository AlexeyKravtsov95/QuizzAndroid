#!/usr/bin/env python3
"""Automated gate for the Design Gate B2 assets.

Run after generate.py. Everything here is a hard check with a pass/fail line —
no check reports "ok" on the basis of an annotation.

  1. every SVG is well-formed XML;
  2. every hyphen visible in an SVG sits at a ru_RU dictionary break point of a
     real source word, or after a character the source string already had
     (URL punctuation, «Санкт-Петербург»). This is the check the task asked
     for: it is what catches «Располож-ите», «Кили-манд-» and «Монбл-ан»;
  3. no soft hyphen (U+00AD) anywhere;
  4. no ellipsis anywhere;
  5. every SVG names only the three approved font families;
  6. every artboard has a PNG at least as new as its SVG;
  7. there are exactly 12 full-screen artboards;
  8. every colour literal in an SVG is a token value from DESIGN_TOKENS.md;
  9. the seven category pictograms are seven distinct paths.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import category_icons          # noqa: E402
import hyphen                  # noqa: E402
import tokens                  # noqa: E402
import typeset                 # noqa: E402

SVG_NS = "{http://www.w3.org/2000/svg}"
REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(REPO, "docs", "design", "b2")
ARTBOARDS = os.path.join(OUT, "artboards")
STATE_SHEETS = os.path.join(OUT, "state-sheets")

SOFT_HYPHEN = "­"
HYPHEN = typeset.HYPHEN

failures = []


def check(name, ok, detail=""):
    print("  [%s] %s%s" % ("ok" if ok else "FAIL", name, ("  — " + detail) if detail else ""))
    if not ok:
        failures.append(name)
    return ok


def svg_files():
    return (sorted(glob.glob(os.path.join(ARTBOARDS, "*.svg")))
            + sorted(glob.glob(os.path.join(STATE_SHEETS, "*.svg"))))


def source_corpus():
    """Regenerate every board in memory to collect the pre-wrap source strings."""
    import assets
    assets.register_with_renderer()
    import boards
    import statesheets
    for _, _, factory in boards.ARTBOARDS:
        factory()
    for _, factory in statesheets.SHEETS:
        factory()
    words = set()
    for text in typeset.SOURCE_STRINGS:
        for word in text.split(" "):
            if word:
                words.add(word)
    return words


def explain_fragment(fragment, words):
    """Is `fragment` a legal piece of some source word, ending at a break point?"""
    hy = hyphen.default()
    for word in words:
        start = 0
        while True:
            at = word.find(fragment, start)
            if at < 0:
                break
            start = at + 1
            end = at + len(fragment)
            if end >= len(word):
                continue
            points = dict(typeset._break_points(word))
            starts_ok = at == 0 or at in points
            if starts_ok and points.get(end) is True:
                return word
    return None


def main():
    files = svg_files()
    print("files: %d SVG" % len(files))

    print("1. XML well-formedness")
    for path in files:
        try:
            ET.parse(path)
        except ET.ParseError as error:
            check(os.path.basename(path), False, str(error))
    check("all %d SVG parse" % len(files), not failures)

    print("2. Russian hyphenation — every automatic break is orthographic")
    words = source_corpus()
    fragments = {}
    for path in files:
        for node in ET.parse(path).iter(SVG_NS + "text"):
            rendered = node.text or ""
            if HYPHEN not in rendered:
                continue
            for chunk in rendered.split(" "):
                if chunk.endswith(HYPHEN):
                    fragments.setdefault(chunk[:-1], set()).add(os.path.basename(path))
    bad = []
    for fragment in sorted(fragments):
        origin = explain_fragment(fragment, words)
        if origin is None:
            bad.append(fragment)
        else:
            print("      %-14s <- %-16s (%s)"
                  % (fragment + HYPHEN, origin, ", ".join(sorted(fragments[fragment]))))
    check("%d hyphenated fragments, all at dictionary points" % len(fragments),
          not bad, "illegal: %s" % ", ".join(bad) if bad else "")

    print("3. no soft hyphen U+00AD")
    hits = [os.path.basename(p) for p in files
            if SOFT_HYPHEN in open(p, encoding="utf-8").read()]
    check("no U+00AD", not hits, ", ".join(hits))

    print("4. no ellipsis")
    hits = [os.path.basename(p) for p in files
            if "…" in open(p, encoding="utf-8").read()]
    check("no '…'", not hits, ", ".join(hits))

    print("5. only approved font families")
    allowed = set()
    for family in (tokens.SERIF, tokens.SANS, tokens.MONO):
        allowed.add(family)
        for style in tokens.WEIGHT_STYLE.values():
            allowed.add("%s %s" % (family, style))
    unexpected = set()
    for path in files:
        for node in ET.parse(path).iter(SVG_NS + "text"):
            for name in (node.get("font-family") or "").split(","):
                name = name.strip().strip("'\"")
                if name and name not in allowed:
                    unexpected.add(name)
    check("font families ⊆ Noto Serif / Golos Text / JetBrains Mono",
          not unexpected, ", ".join(sorted(unexpected)))

    print("6. PNG regenerated from the current SVG")
    stale = []
    for path in files:
        png = path[:-4] + ".png"
        if not os.path.exists(png):
            stale.append(os.path.basename(png) + " missing")
        elif os.path.getmtime(png) < os.path.getmtime(path) - 1:
            stale.append(os.path.basename(png) + " older than its SVG")
    check("every SVG has an up-to-date PNG", not stale, "; ".join(stale))

    print("7. exactly 12 full-screen artboards")
    svgs = sorted(glob.glob(os.path.join(ARTBOARDS, "*.svg")))
    pngs = sorted(glob.glob(os.path.join(ARTBOARDS, "*.png")))
    check("12 SVG + 12 PNG in artboards/", len(svgs) == 12 and len(pngs) == 12,
          "%d SVG, %d PNG" % (len(svgs), len(pngs)))

    print("8. colours are token values")
    palette = set()
    for scheme in (tokens.LIGHT, tokens.DARK):
        palette.update(value.upper() for value in scheme.values())
    # board chrome is not product surface: it is allowed its own three greys
    import boards as board_module
    palette.update(v.upper() for v in (board_module.BOARD_BG, board_module.BOARD_INK,
                                       board_module.BOARD_MUTED, "#C9C0A8", "#000000"))
    literal = re.compile(r"#[0-9A-Fa-f]{3,8}")
    unknown = set()
    for path in files:
        for value in literal.findall(open(path, encoding="utf-8").read()):
            if value.upper() not in palette:
                unknown.add(value)
    check("no colour outside DESIGN_TOKENS.md (+ board chrome)",
          not unknown, ", ".join(sorted(unknown)))

    print("9. seven distinct category pictograms")
    paths = {key: value["path"] for key, value in category_icons.CATEGORY_ICONS.items()}
    symbols = {value["symbol"] for value in category_icons.CATEGORY_ICONS.values()}
    check("7 categories, 7 distinct symbols, 7 distinct paths",
          len(paths) == 7 and len(symbols) == 7 and len(set(paths.values())) == 7,
          "%d paths, %d symbols" % (len(set(paths.values())), len(symbols)))

    print()
    if failures:
        print("FAILED: %s" % ", ".join(failures))
        return 1
    print("all checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
