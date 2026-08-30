"""Text measurement and line breaking against the real approved TTFs.

Widths come from the font's own hmtx table, so a line that fits here fits at the
same width in the app. Line breaking is: break at spaces first; only if a single
word still does not fit, break it at an orthographic hyphenation point from the
ru_RU dictionary. Nothing is ever shrunk to fit and nothing is ever truncated
with an ellipsis.
"""
import functools

import assets
import hyphen
import tokens


@functools.lru_cache(maxsize=None)
def _metrics(family, weight):
    """Advance widths and vertical metrics, in em, straight from the TTF."""
    from fontTools.ttLib import TTFont
    font = TTFont(assets.static_path(family, weight))
    upm = font["head"].unitsPerEm
    hmtx = font["hmtx"]
    advances = {cp: hmtx[glyph][0] / upm for cp, glyph in font.getBestCmap().items()}
    hhea = font["hhea"]
    return advances, hhea.ascender / upm, -hhea.descender / upm


def _font(family, weight):
    return _metrics(family, weight)[0]


class Style:
    """A resolved type role at a concrete font scale."""

    def __init__(self, role, scale=1.0):
        family, weight, size, line_height, tracking, upper = tokens.TYPE[role]
        self.role = role
        self.family = family
        self.weight = weight
        self.size = size * scale
        self.line_height = line_height * scale
        self.tracking = tracking * scale
        self.uppercase = upper
        self.font_family = tokens.family_attr(family, weight)

    def text(self, value):
        return value.upper() if self.uppercase else value

    def width(self, value):
        value = self.text(value)
        advances = _font(self.family, self.weight)
        total = 0.0
        for ch in value:
            if ord(ch) not in advances:
                raise KeyError("%s %d has no glyph for %r (%s)"
                               % (self.family, self.weight, ch, self.role))
            total += advances[ord(ch)]
        return total * self.size + self.tracking * len(value)

    def baseline(self):
        """Distance from the top of the line box to the baseline.

        Computed from the font's own ascent and descent, the way Compose
        centres a line inside `lineHeight`, rather than from a fudge factor.
        """
        _, ascent, descent = _metrics(self.family, self.weight)
        leading = self.line_height - (ascent + descent) * self.size
        return leading / 2 + ascent * self.size


HYPHEN = "‐"          # the character drawn at an automatic break

# Every string that went through the line breaker, before wrapping. The
# hyphenation validator uses this as the corpus of real source strings, so it
# can prove that a hyphen visible in an SVG sits at a dictionary break point of
# an actual word rather than wherever the available width happened to end.
SOURCE_STRINGS = set()

# Characters a line may break *after* without any hyphen being added, because
# the character is already part of the string: an orthographic hyphen
# («Санкт-Петербург»), or URL punctuation.
BREAK_AFTER = "-‑/\\:.,=&?_"


def _break_points(word):
    """(index, adds_hyphen) pairs where `word` may be split.

    Orthographic points come from the ru_RU pattern dictionary and take a
    hyphen. Points after a character that is already in the string take none —
    which is how a long URL wraps without acquiring an invented hyphen.
    """
    points = {}
    for index in range(1, len(word)):
        if word[index - 1] in BREAK_AFTER and index < len(word):
            points[index] = False
    for index in hyphen.default().positions(word):
        points.setdefault(index, True)
    return sorted(points.items())


def _break_word(word, style, first_width, next_width):
    """Split one over-long word. Returns fragments; a fragment that ends at an
    orthographic point is returned with its hyphen already attached."""
    points = _break_points(word)
    fragments, start, available = [], 0, first_width
    while start < len(word):
        chosen = None
        for point, adds_hyphen in points:
            if point <= start:
                continue
            piece = word[start:point] + (HYPHEN if adds_hyphen else "")
            if style.width(piece) <= available:
                chosen = (point, adds_hyphen)
            else:
                break
        rest = word[start:]
        if chosen is None or style.width(rest) <= available:
            if style.width(rest) <= available or not fragments:
                fragments.append(rest)
                return fragments
            fragments.append(("", True))          # push the word to a new line
            available = next_width
            continue
        point, adds_hyphen = chosen
        fragments.append(word[start:point] + (HYPHEN if adds_hyphen else ""))
        start = point
        available = next_width
        if style.width(word[start:]) <= available:
            fragments.append(word[start:])
            return fragments
    return fragments


def wrap(text, style, width, first_width=None):
    """Break `text` into lines that fit `width`.

    Returns a list of strings; a line that ends in an automatic break carries a
    trailing HYPHEN character, added here and never present in the source text.
    """
    SOURCE_STRINGS.add(text)
    first_width = width if first_width is None else first_width
    lines, current, available = [], "", first_width

    def flush():
        nonlocal current, available
        if current:
            lines.append(current)
            current = ""
            available = width

    for word in text.split(" "):
        if not word:
            continue
        candidate = (current + " " + word) if current else word
        if style.width(candidate) <= available:
            current = candidate
            continue
        if current and style.width(word) <= width:
            flush()
            current = word
            continue
        # the word alone does not fit on a full line -> orthographic break
        room = available - (style.width(current + " ") if current else 0)
        pieces = _break_word(word, style, room if current else available, width)
        if pieces and pieces[0] == ("", True):
            flush()
            pieces = _break_word(word, style, width, width)
        for index, chunk in enumerate(pieces):
            last = index == len(pieces) - 1
            candidate = (current + " " + chunk) if current else chunk
            if style.width(candidate) <= (available if not lines or current else width):
                current = candidate
            else:
                flush()
                current = chunk
            if not last:
                flush()
    flush()
    return lines or [""]


def block_height(lines, style):
    return len(lines) * style.line_height
