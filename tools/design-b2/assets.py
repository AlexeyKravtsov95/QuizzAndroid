"""Third-party build inputs: the three approved OFL fonts and the ru_RU
hyphenation pattern dictionary.

Nothing here is committed to the repository — DESIGN_TOKENS.md section 6.3
says font files are not added to the repo at this stage. Everything is fetched
into .cache/ on first run and rebuilt deterministically from upstream sources.

  Noto Serif / Golos Text / JetBrains Mono
      github.com/google/fonts, ofl/<family>/, SIL Open Font License 1.1.
      Downloaded as variable fonts and instanced to the exact static weights
      declared in DESIGN_TOKENS.md section 6.3.

  hyph_ru_RU.dic
      github.com/LibreOffice/dictionaries, ru_RU/, Liang hyphenation patterns
      (c) 1997-2008 Alexander I. Lebedev, BSD 3-clause style licence.

  Material Symbols Outlined
      NOT fetched here — the seven category pictograms are extracted once and
      committed as plain SVG paths in category_icons.py, so the 10 MB icon font
      is not a build dependency.
"""
import os
import urllib.request

BASE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(BASE, ".cache")
SRC = os.path.join(CACHE, "fonts-src")
STATIC = os.path.join(CACHE, "fonts-static")
ALIAS = os.path.join(CACHE, "fonts-alias")
HYPH = os.path.join(CACHE, "hyph")

GF = "https://github.com/google/fonts/raw/main/ofl/%s"
LO = "https://raw.githubusercontent.com/LibreOffice/dictionaries/master/ru_RU/%s"

DOWNLOADS = [
    (GF % "notoserif/NotoSerif%5Bwdth%2Cwght%5D.ttf", SRC, "NotoSerif-VF.ttf"),
    (GF % "notoserif/OFL.txt", SRC, "NotoSerif-OFL.txt"),
    (GF % "golostext/GolosText%5Bwght%5D.ttf", SRC, "GolosText-VF.ttf"),
    (GF % "golostext/OFL.txt", SRC, "GolosText-OFL.txt"),
    (GF % "jetbrainsmono/JetBrainsMono%5Bwght%5D.ttf", SRC, "JetBrainsMono-VF.ttf"),
    (GF % "jetbrainsmono/OFL.txt", SRC, "JetBrainsMono-OFL.txt"),
    (LO % "hyph_ru_RU.dic", HYPH, "hyph_ru_RU.dic"),
    (LO % "README_ru_RU.txt", HYPH, "LICENCE_hyph_ru_RU.txt"),
]

# family -> (variable file, {weight: static style name})
FONT_SPEC = {
    "Noto Serif": ("NotoSerif-VF.ttf", {400: "Regular", 500: "Medium", 600: "SemiBold"}),
    "Golos Text": ("GolosText-VF.ttf", {400: "Regular", 500: "Medium",
                                        600: "SemiBold", 700: "Bold"}),
    "JetBrains Mono": ("JetBrainsMono-VF.ttf", {500: "Medium", 700: "Bold"}),
}

# "ё" plus the longest words that actually occur in the boards' content.
COVERAGE_PROBE = ("ёЁРасположитеКилиманджароМонбланАконкагуаЭльбрусТанзания"
                  "ПОПОРЯДКУВЫПУСКСЕРИЯ№·—«»")


def _download():
    for url, directory, name in DOWNLOADS:
        os.makedirs(directory, exist_ok=True)
        path = os.path.join(directory, name)
        if os.path.exists(path) and os.path.getsize(path) > 0:
            continue
        print("  fetch %s" % name)
        with urllib.request.urlopen(url, timeout=120) as response:
            data = response.read()
        with open(path, "wb") as fh:
            fh.write(data)


def _set_names(font, family, style, weight):
    from fontTools.ttLib import TTFont  # noqa: F401  (documents the dependency)
    name = font["name"]
    postscript = "%s-%s" % (family.replace(" ", ""), style)
    full = family if style == "Regular" else "%s %s" % (family, style)
    for nid, value in ((1, family), (2, style), (3, full), (4, full),
                       (6, postscript), (16, family), (17, style)):
        name.setName(value, nid, 3, 1, 0x409)
        name.setName(value, nid, 1, 0, 0)
    font["OS/2"].usWeightClass = weight
    font["OS/2"].fsSelection = ((font["OS/2"].fsSelection & ~0x61)
                                | (0x20 if weight >= 700 else 0x40))
    font["head"].macStyle = 1 if weight >= 700 else 0


def _instance():
    """Build the static weights, plus a per-weight alias family.

    The alias set exists purely because cairo's toy text API — the one cairosvg
    drives — only distinguishes normal from bold and therefore cannot ask
    CoreText for weight 500 or 600. Giving each weight its own family name is
    what makes the rasteriser use the genuine Medium/SemiBold instance instead
    of silently snapping to Regular or Bold. Every SVG carries
    font-family="Noto Serif Medium, Noto Serif" with the real font-weight, so a
    browser or Compose resolves the real family and the real weight.
    """
    from fontTools.ttLib import TTFont
    from fontTools.varLib import instancer

    for directory in (STATIC, ALIAS):
        os.makedirs(directory, exist_ok=True)

    report = []
    for family, (src, weights) in FONT_SPEC.items():
        for weight, style in sorted(weights.items()):
            real = os.path.join(STATIC, "%s-%s.ttf" % (family.replace(" ", ""), style))
            alias = os.path.join(ALIAS, "%s%s.ttf" % (family.replace(" ", ""), style))
            if os.path.exists(real) and os.path.exists(alias):
                report.append((family, weight, style, real, None))
                continue
            font = TTFont(os.path.join(SRC, src))
            axes = {"wght": weight}
            if "wdth" in {a.axisTag for a in font["fvar"].axes}:
                axes["wdth"] = 100
            instancer.instantiateVariableFont(font, axes, inplace=True,
                                              updateFontNames=False)
            cmap = font.getBestCmap()
            missing = sorted({c for c in COVERAGE_PROBE if ord(c) not in cmap})
            if missing:
                raise SystemExit("%s %d is missing glyphs: %s"
                                 % (family, weight, "".join(missing)))
            _set_names(font, family, style, weight)
            font.save(real)
            _set_names(font, "%s %s" % (family, style), "Regular", 400)
            font.save(alias)
            report.append((family, weight, style, real, missing))
    return report


def ensure():
    """Fetch and build everything the generator needs. Idempotent."""
    _download()
    return _instance()


def register_with_renderer():
    """Make the alias faces visible to CoreText for this process only.

    Homebrew cairo on macOS resolves families through the quartz font backend,
    not through fontconfig, so a fontconfig directory is invisible to it.
    kCTFontManagerScopeProcess registration reaches CoreText without installing
    anything into the user's font library or touching the system. On non-macOS
    hosts the fontconfig path is used instead.
    """
    import ctypes
    import ctypes.util
    import glob

    files = sorted(glob.glob(os.path.join(ALIAS, "*.ttf")))
    if not files:
        raise SystemExit("no alias fonts built; run assets.ensure() first")

    coretext = ctypes.util.find_library("CoreText")
    if coretext is None:                                    # not macOS
        os.environ.setdefault("XDG_DATA_HOME", CACHE)
        return files

    cf = ctypes.cdll.LoadLibrary(ctypes.util.find_library("CoreFoundation"))
    ct = ctypes.cdll.LoadLibrary(coretext)
    cf.CFURLCreateFromFileSystemRepresentation.restype = ctypes.c_void_p
    cf.CFURLCreateFromFileSystemRepresentation.argtypes = [
        ctypes.c_void_p, ctypes.c_char_p, ctypes.c_long, ctypes.c_bool]
    cf.CFRelease.argtypes = [ctypes.c_void_p]
    ct.CTFontManagerRegisterFontsForURL.restype = ctypes.c_bool
    ct.CTFontManagerRegisterFontsForURL.argtypes = [
        ctypes.c_void_p, ctypes.c_uint32, ctypes.c_void_p]

    for path in files:
        raw = path.encode("utf-8")
        url = cf.CFURLCreateFromFileSystemRepresentation(None, raw, len(raw), False)
        if not url:
            raise SystemExit("cannot build a CFURL for %s" % path)
        ok = ct.CTFontManagerRegisterFontsForURL(url, 1, None)  # scope = process
        cf.CFRelease(url)
        if not ok:
            raise SystemExit("CoreText refused to register %s" % path)
    return files


def static_path(family, weight):
    from tokens import WEIGHT_STYLE
    return os.path.join(STATIC, "%s-%s.ttf"
                        % (family.replace(" ", ""), WEIGHT_STYLE[weight]))


def hyphen_dictionary():
    return os.path.join(HYPH, "hyph_ru_RU.dic")


if __name__ == "__main__":
    for family, weight, style, path, missing in ensure():
        print("%-15s %3d %-9s %s" % (family, weight, style, os.path.basename(path)))
    print("registered:", len(register_with_renderer()))
