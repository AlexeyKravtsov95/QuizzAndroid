"""Design tokens, transcribed literally from docs/design/DESIGN_TOKENS.md.

Nothing in this file is invented. Every colour, size, radius and type role is a
copy of a row in that document; if a value is needed and missing there, the
rule in DESIGN_TOKENS.md section head applies — stop and add it to the document
first, do not improvise here.
"""

# --- 6.2 Colour schemes ----------------------------------------------------

LIGHT = {
    "primary": "#2C4A3E", "onPrimary": "#F6F0E2",
    "primaryContainer": "#E4E9DE", "onPrimaryContainer": "#2C4A3E",
    "secondary": "#55665C", "onSecondary": "#F6F0E2",
    "secondaryContainer": "#DCE3DC", "onSecondaryContainer": "#33413A",
    "tertiary": "#9C4A2E", "onTertiary": "#F6F0E2",
    "tertiaryContainer": "#F2DDD2", "onTertiaryContainer": "#6B2F1C",
    "error": "#A3342E", "onError": "#F6F0E2",
    "errorContainer": "#F5DAD5", "onErrorContainer": "#6B211B",
    "background": "#F6F0E2", "onBackground": "#23241F",
    "surface": "#F6F0E2", "onSurface": "#23241F",
    "surfaceVariant": "#EFE6D2", "onSurfaceVariant": "#5B584C",
    "surfaceTint": "#2C4A3E",
    "inverseSurface": "#33312A", "inverseOnSurface": "#F2ECDC",
    "inversePrimary": "#8FB5A2",
    "outline": "#8C8672", "outlineVariant": "#DCD3BC",
    "scrim": "#000000",
    "surfaceBright": "#FDFBF5", "surfaceDim": "#E4DCC5",
    "surfaceContainerLowest": "#FBF8F0", "surfaceContainerLow": "#FBF7EC",
    "surfaceContainer": "#F5EFE0", "surfaceContainerHigh": "#EFE6CE",
    "surfaceContainerHighest": "#E9DFC0",
}

DARK = {
    "primary": "#3E6350", "onPrimary": "#F5EFE0",
    "primaryContainer": "#24352C", "onPrimaryContainer": "#CFE0D2",
    "secondary": "#8FA398", "onSecondary": "#16261E",
    "secondaryContainer": "#2B3A32", "onSecondaryContainer": "#CDDCD2",
    "tertiary": "#D68A63", "onTertiary": "#3A2013",
    "tertiaryContainer": "#4A2A1E", "onTertiaryContainer": "#F0C9B3",
    "error": "#E8998C", "onError": "#3B0E09",
    "errorContainer": "#5C231C", "onErrorContainer": "#F5CCC4",
    "background": "#1C1A16", "onBackground": "#EDE7D8",
    "surface": "#1C1A16", "onSurface": "#EDE7D8",
    "surfaceVariant": "#3A3527", "onSurfaceVariant": "#B9B2A0",
    "surfaceTint": "#3E6350",
    "inverseSurface": "#EDE4CE", "inverseOnSurface": "#2C2A22",
    "inversePrimary": "#2C4A3E",
    "outline": "#8A8370", "outlineVariant": "#3C382C",
    "scrim": "#000000",
    "surfaceBright": "#3A3527", "surfaceDim": "#141210",
    "surfaceContainerLowest": "#111008", "surfaceContainerLow": "#262319",
    "surfaceContainer": "#2C2820", "surfaceContainerHigh": "#332E24",
    "surfaceContainerHighest": "#3A3428",
}

# --- 6.3 Font families -----------------------------------------------------
#
# The second name in each list is the real family; the first is a per-weight
# alias that exists only inside the render environment (see fonts.py) because
# cairo's toy text API cannot request weight 500 or 600 from CoreText.

SERIF = "Noto Serif"
SANS = "Golos Text"
MONO = "JetBrains Mono"

WEIGHT_STYLE = {400: "Regular", 500: "Medium", 600: "SemiBold", 700: "Bold"}


def family_attr(family, weight):
    return "%s %s, %s" % (family, WEIGHT_STYLE[weight], family)


# --- 6.4 Type scale --------------------------------------------------------
# role -> (family, weight, size sp, lineHeight sp, letterSpacing sp, uppercase)

TYPE = {
    "headlineMedium": (SERIF, 500, 28, 36, 0.0, False),
    "headlineSmall": (SERIF, 500, 24, 30, 0.0, False),
    "EditorialTitle": (SERIF, 500, 24, 30, 0.0, False),
    "titleLarge": (SERIF, 600, 20, 26, 0.0, False),
    "titleMedium": (SANS, 600, 16, 24, 0.15, False),
    "titleSmall": (SANS, 600, 14, 20, 0.1, False),
    "bodyLarge": (SANS, 400, 16, 24, 0.5, False),
    "bodyMedium": (SANS, 400, 14, 20, 0.25, False),
    "bodySmall": (SANS, 400, 12, 16, 0.4, False),
    "labelLarge": (SANS, 600, 15, 20, 0.1, False),
    "labelMedium": (SANS, 600, 13, 18, 0.2, False),
    "labelSmall": (SANS, 700, 11, 16, 0.3, False),
    # project roles
    "IssueNumberWord": (SERIF, 500, 28, 36, 0.0, False),
    "IssueNumberDigits": (MONO, 700, 40, 44, 0.0, False),
    "CardIndex": (MONO, 700, 14, 18, 0.0, False),
    "Metadata": (MONO, 500, 13, 18, 0.2, False),
    "Masthead": (SERIF, 600, 14, 20, 2.0, True),
    "IssueLabel": (MONO, 700, 11, 16, 1.5, True),
    "CapsLabel": (SANS, 700, 11, 16, 1.5, True),
}

# --- 6.5 Sizing ------------------------------------------------------------

SPACING = {100: 4, 200: 8, 300: 12, 400: 16, 500: 20,
           600: 24, 700: 32, 800: 40, 900: 48, 1000: 64}

MARGIN_DEFAULT = SPACING[600]      # 24 dp, width >= 360 dp
MARGIN_COMPACT = SPACING[400]      # 16 dp, width < 360 dp
SECTION = SPACING[600]             # 24 dp
LIST_GAP = SPACING[300]            # 12 dp
STAT_ROW_INNER = SPACING[100]      # 4 dp

SPINE_WIDTH = 3
SPINE_CONTENT_INDENT = SPACING[500]        # 20 dp
INDEX_ZONE_WIDTH = 56
DIVIDER_THICKNESS = 1
CUT_CORNER = 18
ORDERABLE_CARD_MIN_HEIGHT = 112
BUTTON_HEIGHT = 56
TOUCH_TARGET_MIN = 48
MOVE_BUTTON = 48
DRAG_HANDLE_TOUCH = 48
BACK_BUTTON = 48
HEADER_ICON = 48
ICON_DEFAULT = 24
ICON_SMALL = 16
ICON_STROKE_DEFAULT = 2.0
ICON_STROKE_SMALL = 1.6
CATEGORY_LABEL_MIN_HEIGHT = 26
DRAG_HANDLE_GLYPH = (16, 24)
PROGRESS_DOT = 8

# --- 6.6 Shapes ------------------------------------------------------------

SHAPE_EXTRA_SMALL = 4
SHAPE_SMALL = 8
SHAPE_MEDIUM = 12
SHAPE_LARGE = 16
SHAPE_EXTRA_LARGE = 28

# --- 6.7 Elevation ---------------------------------------------------------

ELEVATION_DRAGGED = 6
ELEVATION_DIALOG = 6

# --- 6.9 Opacity -----------------------------------------------------------

OPACITY_DISABLED_CONTENT = 0.38
OPACITY_DISABLED_CONTAINER = 0.12
OPACITY_PRESSED_STATE_LAYER = 0.08
OPACITY_FOCUS_STATE_LAYER = 0.12
OPACITY_DRAG_STATE_LAYER = 0.16
OPACITY_MOVE_BUTTON_ENABLED_BORDER = 0.50
OPACITY_SCRIM = 0.32

# --- 6.10 Adaptivity -------------------------------------------------------


def margin_for(width_dp):
    """spacing.margin.default at >= 360 dp, spacing.margin.compact below."""
    return MARGIN_DEFAULT if width_dp >= 360 else MARGIN_COMPACT


def scheme(theme):
    return LIGHT if theme == "light" else DARK
