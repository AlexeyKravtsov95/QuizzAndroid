#!/usr/bin/env python3
"""Build every Design Gate B2 asset: 12 full-screen artboards, 4 visual state
sheets, and the two contact sheets — then validate the result.

Usage:  python3 tools/design-b2/generate.py [--only 05,11] [--skip-png]
"""
import argparse
import os
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import assets  # noqa: E402

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(REPO, "docs", "design", "b2")
ARTBOARDS = os.path.join(OUT, "artboards")
STATE_SHEETS = os.path.join(OUT, "state-sheets")
PNG_SCALE = 2


def write_svg(path, canvas):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(canvas.to_svg())
    ET.parse(path)          # fail loudly if the markup is not well-formed XML
    return path


def render_png(svg_path, png_path=None, scale=PNG_SCALE):
    import cairosvg
    png_path = png_path or svg_path[:-4] + ".png"
    cairosvg.svg2png(url=svg_path, write_to=png_path, scale=scale)
    return png_path


def contact_sheet(paths, out_path, columns, label_height=34, gap=24, target_width=520):
    """Lay the rendered PNGs out on one sheet with their file names."""
    from PIL import Image, ImageDraw, ImageFont

    thumbs = []
    for path in paths:
        image = Image.open(path).convert("RGB")
        ratio = target_width / image.width
        thumbs.append((os.path.basename(path),
                       image.resize((target_width, max(1, int(image.height * ratio))),
                                    Image.LANCZOS)))
    rows = (len(thumbs) + columns - 1) // columns
    row_heights = []
    for row in range(rows):
        chunk = thumbs[row * columns:(row + 1) * columns]
        row_heights.append(max(t.height for _, t in chunk) + label_height)

    width = gap + columns * (target_width + gap)
    height = gap + sum(h + gap for h in row_heights)
    sheet = Image.new("RGB", (width, height), "#EDE6D4")
    draw = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype(assets.static_path("Golos Text", 500), 18)
    except Exception:
        font = ImageFont.load_default()

    y = gap
    for row in range(rows):
        x = gap
        for name, thumb in thumbs[row * columns:(row + 1) * columns]:
            sheet.paste(thumb, (x, y))
            draw.text((x, y + thumb.height + 8), name, fill="#23241F", font=font)
            x += target_width + gap
        y += row_heights[row] + gap
    sheet.save(out_path)
    return out_path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--only", default="")
    parser.add_argument("--skip-png", action="store_true")
    args = parser.parse_args()

    print("assets:")
    assets.ensure()
    registered = assets.register_with_renderer()
    print("  %d font faces registered with the renderer" % len(registered))

    import boards
    import statesheets

    wanted = {s.strip() for s in args.only.split(",") if s.strip()}
    written = []

    print("artboards:")
    for number, slug, factory in sorted(boards.ARTBOARDS):
        if wanted and slug[:2] not in wanted and slug not in wanted:
            continue
        canvas = factory()
        svg = write_svg(os.path.join(ARTBOARDS, slug + ".svg"), canvas)
        line = "  %-34s %4d x %4d" % (slug + ".svg", canvas.width, canvas.height)
        if not args.skip_png:
            png = render_png(svg)
            from PIL import Image
            with Image.open(png) as image:
                line += "   png %d x %d" % image.size
        print(line)
        written.append(svg)

    print("state sheets:")
    for slug, factory in statesheets.SHEETS:
        if wanted and slug not in wanted:
            continue
        canvas = factory()
        svg = write_svg(os.path.join(STATE_SHEETS, slug + ".svg"), canvas)
        line = "  %-34s %4d x %4d" % (slug + ".svg", canvas.width, canvas.height)
        if not args.skip_png:
            png = render_png(svg)
            from PIL import Image
            with Image.open(png) as image:
                line += "   png %d x %d" % image.size
        print(line)
        written.append(svg)

    if not args.skip_png and not wanted:
        print("contact sheets:")
        artboard_pngs = [os.path.join(ARTBOARDS, slug + ".png")
                         for _, slug, _ in sorted(boards.ARTBOARDS)]
        path = contact_sheet(artboard_pngs, os.path.join(OUT, "b2-contact-sheet.png"),
                             columns=4)
        print("  %s" % os.path.relpath(path, REPO))
        sheet_pngs = [os.path.join(STATE_SHEETS, slug + ".png")
                      for slug, _ in statesheets.SHEETS]
        # state sheets carry body text, so they get a much larger thumbnail than
        # the artboard contact sheet — the point is to read them, not to count them
        path = contact_sheet(sheet_pngs,
                             os.path.join(OUT, "state-sheets-contact-sheet.png"),
                             columns=2, target_width=1180, label_height=48)
        print("  %s" % os.path.relpath(path, REPO))

    return written


if __name__ == "__main__":
    main()
