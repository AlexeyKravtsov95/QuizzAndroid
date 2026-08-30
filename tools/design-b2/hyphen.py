"""Russian hyphenation by the Liang/Knuth pattern algorithm over the ru_RU
orthographic pattern dictionary.

This models what `Hyphens.Auto` will do in the real app: AOSP ships Liang
pattern tables per locale and runs them through its own matcher, so break
points come from orthography, not from where a pixel-width search happened to
land. The previous generator cut words by binary search on width, which is what
produced «Располож-ите», «Кили-манд-» and «Монбл-ан» — none of which are legal
Russian break points.

No hyphen character is ever stored in the source strings. A break point is a
position; the hyphen is added by the layout engine at render time only when a
line actually breaks there.
"""
import os
import re

import assets

# Russian typographic minima: at least two letters stay on each line.
LEFT_MIN = 2
RIGHT_MIN = 2

_DIGIT = re.compile(r"\d")


class Hyphenator:
    def __init__(self, path=None):
        self.patterns = {}
        self.exceptions = {}
        self.max_len = 0
        with open(path or assets.hyphen_dictionary(), encoding="utf-8") as fh:
            lines = fh.read().splitlines()
        for raw in lines[1:]:            # line 0 is the encoding tag
            line = raw.strip()
            if not line or line[0] in "%#":
                continue
            if line.upper().startswith(("LEFTHYPHENMIN", "RIGHTHYPHENMIN",
                                        "COMPOUNDLEFTHYPHENMIN",
                                        "COMPOUNDRIGHTHYPHENMIN", "NOHYPHEN")):
                continue
            if "=" in line:              # explicit exception "по=на=чалу"
                word = line.replace("=", "")
                points = [0] * (len(word) + 1)
                index = 0
                for ch in line:
                    if ch == "=":
                        points[index] = 1
                    else:
                        index += 1
                self.exceptions[word.lower()] = points
                continue
            letters = _DIGIT.sub("", line)
            values, i = [], 0
            while i < len(line):
                if line[i].isdigit():
                    values.append(int(line[i]))
                    i += 2 if i + 1 < len(line) and not line[i + 1].isdigit() else 1
                else:
                    values.append(0)
                    i += 1
            values += [0] * (len(letters) + 1 - len(values))
            self.patterns[letters.lower()] = values[:len(letters) + 1]
            self.max_len = max(self.max_len, len(letters))

    def positions(self, word):
        """Legal break indices inside `word`; a break happens *before* index."""
        lower = word.lower()
        if lower in self.exceptions:
            flags = self.exceptions[lower]
            return [i for i in range(LEFT_MIN, len(word) - RIGHT_MIN + 1) if flags[i]]
        work = "." + lower + "."
        points = [0] * (len(work) + 1)
        for i in range(len(work)):
            for j in range(i + 1, min(i + self.max_len, len(work)) + 1):
                values = self.patterns.get(work[i:j])
                if values:
                    for k, value in enumerate(values):
                        if i + k < len(points) and value > points[i + k]:
                            points[i + k] = value
        return [i for i in range(LEFT_MIN, len(word) - RIGHT_MIN + 1)
                if points[i + 1] % 2 == 1]

    def split(self, word):
        """The word's syllable chunks. No hyphen is inserted into the text."""
        chunks, previous = [], 0
        for position in self.positions(word):
            chunks.append(word[previous:position])
            previous = position
        chunks.append(word[previous:])
        return chunks


_default = None


def default():
    global _default
    if _default is None:
        _default = Hyphenator()
    return _default
