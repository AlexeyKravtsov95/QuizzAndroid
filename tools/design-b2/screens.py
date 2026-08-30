"""The four base states of Design Gate B2, laid out from real measured blocks.

Each screen returns a `Rendered`: the canvas holding one device-sized surface
plus, for scrolling screens, the full document height and the offsets at which
the review board should take its slices.

Content data:
  * the mountains puzzle is the real example from CONTENT_MODEL.md section 5
    (`geo-vysota-gor-007`);
  * day totals, streak, issue number and the category mix on DayRecap are
    illustrative values inside the product's own ranges (0-6 per puzzle, 0-18
    per day, day 1-35, three different categories per set) — not claims about
    shipped content.
"""
import components
import svgkit
import tokens
from components import Ctx
from svgkit import fmt

# --- content ---------------------------------------------------------------

PUZZLE = {
    "id": "geo-vysota-gor-007",
    "category": "geography",
    "prompt": "Расположите вершины от самой низкой к самой высокой",
    "directionLabel": "Сверху — самая низкая",
    "explanation": ("Монблан — высшая точка Альп, но уступает Эльбрусу. "
                    "Килиманджаро выше обоих, а рекорд среди четырёх держит "
                    "Аконкагуа: это высочайшая вершина за пределами Азии."),
    "shuffled": [("Килиманджаро", "Танзания"), ("Аконкагуа", "Анды, Аргентина"),
                 ("Эльбрус", "Кавказ, Россия"), ("Монблан", "Альпы, Франция и Италия")],
    "correct": [("Монблан", "Альпы, Франция и Италия", "4808 м"),
                ("Эльбрус", "Кавказ, Россия", "5642 м"),
                ("Килиманджаро", "Танзания", "5895 м"),
                ("Аконкагуа", "Анды, Аргентина", "6961 м")],
    "inverted": ["Карточка «Эльбрус» должна располагаться после карточки «Монблан»"],
    "score": "5 из 6",
}

HOME = {
    "date": "Суббота, 29 августа 2026",
    "day": 24,
    "stats": [("Серия", "6 дней"), ("Лучший день", "17 из 18"), ("Сыграно дней", "23")],
}

RECAP = {
    "score": "15 из 18",
    "rows": [("geography", "5 из 6"), ("history", "6 из 6"), ("science", "4 из 6")],
    "streak": ("Серия", "6 дней"),
}

VIEWPORT_HEIGHT = 844
TOP_INSET = 24            # typical WindowInsets.statusBars, not a token
BOTTOM_INSET = 24         # typical WindowInsets.navigationBars, gesture nav


class Rendered:
    def __init__(self, canvas, width, document_height, slices, scroll_top=None):
        self.canvas = canvas
        self.width = width
        self.document_height = document_height
        self.slices = slices          # list of (offset, caption)
        self.scroll_top = scroll_top  # y where the scroll container starts


def _new(width, height):
    return svgkit.Canvas(width, height)


def _fixed_bottom_button_zone(ctx):
    """Height reserved under the scroll container so a pinned PrimaryButton
    never covers the last element of the content."""
    return tokens.SECTION + tokens.BUTTON_HEIGHT + tokens.SECTION + BOTTOM_INSET


# --- Home ------------------------------------------------------------------


def home(theme, width=390, scale=1.0):
    canvas = _new(width, VIEWPORT_HEIGHT)
    ctx = Ctx(canvas, theme, width, scale)
    canvas.rect(0, 0, width, VIEWPORT_HEIGHT, fill=ctx.c("surface"))

    y = TOP_INSET
    y += components.home_header(ctx, y, HOME["date"], archive_visible=True)
    y += tokens.SECTION
    components.daily_issue_panel_ready(ctx, y, HOME["day"], HOME["stats"])

    button_y = VIEWPORT_HEIGHT - BOTTOM_INSET - tokens.SECTION - tokens.BUTTON_HEIGHT
    height = components.primary_button(ctx, button_y, "Играть")
    if button_y + height > VIEWPORT_HEIGHT - BOTTOM_INSET:
        button_y = VIEWPORT_HEIGHT - BOTTOM_INSET - height
        components.primary_button(ctx, button_y, "Играть")
    return Rendered(canvas, width, VIEWPORT_HEIGHT, [(0, None)])


# --- Puzzle ----------------------------------------------------------------


def _puzzle_document(theme, width, scale, dragging_index, pad_bottom):
    """Prompt, direction label and the four cards as one content container.

    At 100% on the reference phone this fits without scrolling — that is the
    vertical budget in DESIGN_TOKENS.md section 6.5. At 200% it does not, and
    UX_FLOW.md section 4 then puts the whole thing into a scroll container: the
    formulation scrolls together with the list rather than pushing it off the
    screen.
    """
    doc = _new(width, VIEWPORT_HEIGHT * 4)
    ctx = Ctx(doc, theme, width, scale)
    anchors, cursor = {}, 0

    cursor += doc.paragraph(ctx.margin, cursor, ctx.content_width, PUZZLE["prompt"],
                            ctx.style("EditorialTitle"), ctx.c("onSurface"))
    cursor += doc.paragraph(ctx.margin, cursor, ctx.content_width,
                            PUZZLE["directionLabel"], ctx.style("bodyMedium"),
                            ctx.c("onSurfaceVariant"))
    cursor += tokens.SPACING[300]
    anchors["list"] = cursor
    for index, (title, subtitle) in enumerate(PUZZLE["shuffled"]):
        cursor += components.orderable_card(
            ctx, cursor, index + 1, title, subtitle,
            first=index == 0, last=index == len(PUZZLE["shuffled"]) - 1,
            dragging=(dragging_index == index))
        if index != len(PUZZLE["shuffled"]) - 1:
            cursor += tokens.LIST_GAP
    if pad_bottom:
        cursor += _fixed_bottom_button_zone(ctx)
    return doc, cursor, anchors


def puzzle(theme, width=390, scale=1.0, dragging_index=None, offset=0):
    canvas = _new(width, VIEWPORT_HEIGHT)
    ctx = Ctx(canvas, theme, width, scale)
    canvas.rect(0, 0, width, VIEWPORT_HEIGHT, fill=ctx.c("surface"))

    y = TOP_INSET
    y += components.app_top_bar(ctx, y, "Задание 1 из 3", back=True,
                                category=PUZZLE["category"])
    scroll_top = y + tokens.SECTION
    scroll_bottom = (VIEWPORT_HEIGHT - BOTTOM_INSET - tokens.SECTION
                     - tokens.BUTTON_HEIGHT - tokens.SECTION)
    visible = scroll_bottom - scroll_top

    _, bare, _ = _puzzle_document(theme, width, scale, dragging_index, pad_bottom=False)
    scrolls = bare > visible
    doc, document, anchors = _puzzle_document(theme, width, scale, dragging_index,
                                              pad_bottom=scrolls)

    offset = max(0, min(offset, max(0, document - visible)))
    clip = canvas.clip_rect(0, scroll_top, width, visible)
    canvas.embed(doc, transform="translate(0,%s)" % fmt(scroll_top - offset), clip=clip,
                 name="PuzzleContent")
    components.primary_button(ctx, scroll_bottom + tokens.SECTION, "Проверить")

    slices = [(0, None)]
    if scrolls:
        slices = [(0, "Основной viewport"),
                  (min(anchors["list"], document - visible),
                   "Тот же экран, прокручено к списку карточек")]
    return Rendered(canvas, width, document, slices,
                    scroll_top=scroll_top if scrolls else None)


# --- PuzzleResult ----------------------------------------------------------


def _result_document(theme, width, scale):
    """Lay out the whole PuzzleResult document once.

    Order is fixed by DESIGN_PRINCIPLES.md section 3: correct order ->
    explanation -> ScoreBadge -> ScoringHint -> InvertedPairRow -> SourcesBlock
    -> ReportInaccuracyAction. Returns (canvas, height, anchors).
    """
    doc = _new(width, VIEWPORT_HEIGHT * 4)
    ctx = Ctx(doc, theme, width, scale)
    anchors = {}
    cursor = 0

    heading = ctx.style("EditorialTitle")
    cursor += doc.paragraph(ctx.margin, cursor, ctx.content_width,
                            "Правильный порядок", heading, ctx.c("onSurface"))
    cursor += tokens.SPACING[300]
    for index, (title, subtitle, value) in enumerate(PUZZLE["correct"]):
        cursor += components.orderable_card(ctx, cursor, index + 1, title, subtitle,
                                            value=value, interactive=False)
        cursor += tokens.LIST_GAP
    cursor += tokens.SECTION - tokens.LIST_GAP

    body = ctx.style("bodyLarge")
    cursor += doc.paragraph(ctx.margin, cursor, ctx.content_width,
                            PUZZLE["explanation"], body, ctx.c("onSurface"))
    cursor += tokens.SECTION

    anchors["score"] = cursor
    cursor += components.score_badge(ctx, cursor, PUZZLE["score"])
    cursor += tokens.SPACING[200]
    cursor += components.scoring_hint(ctx, cursor)
    cursor += tokens.SECTION
    for text in PUZZLE["inverted"]:
        cursor += components.inverted_pair_row(ctx, cursor, text)
        cursor += tokens.SPACING[200]
    cursor += tokens.SECTION - tokens.SPACING[200]
    cursor += components.sources_block_collapsed(ctx, cursor)
    cursor += components.report_inaccuracy_action(ctx, cursor)
    anchors["last_element_bottom"] = cursor
    # Bottom padding of the scroll container. Without it the pinned
    # PrimaryButton would sit on top of ReportInaccuracyAction at the end of
    # the scroll — the overlap defect this pass had to remove.
    cursor += _fixed_bottom_button_zone(ctx)
    return doc, cursor, anchors


def puzzle_result(theme, width=390, scale=1.0, offset=0):
    """One slice of PuzzleResult. `offset` scrolls the content container."""
    canvas = _new(width, VIEWPORT_HEIGHT)
    ctx = Ctx(canvas, theme, width, scale)
    canvas.rect(0, 0, width, VIEWPORT_HEIGHT, fill=ctx.c("surface"))

    y = TOP_INSET
    y += components.app_top_bar(ctx, y, "Задание 1 из 3", back=True)
    scroll_top = y + tokens.SPACING[400]
    scroll_bottom = (VIEWPORT_HEIGHT - BOTTOM_INSET - tokens.SECTION
                     - tokens.BUTTON_HEIGHT - tokens.SECTION)
    visible = scroll_bottom - scroll_top

    doc, document, anchors = _result_document(theme, width, scale)
    offset = max(0, min(offset, document - visible))
    clip = canvas.clip_rect(0, scroll_top, width, visible)
    canvas.embed(doc, transform="translate(0,%s)" % fmt(scroll_top - offset), clip=clip,
                 name="PuzzleResultScroll")
    components.primary_button(ctx, scroll_bottom + tokens.SECTION, "Дальше")

    continuation = max(0, min(anchors["score"] - tokens.SPACING[400], document - visible))
    return Rendered(canvas, width, document,
                    [(0, "Основной viewport"),
                     (continuation, "Тот же экран, прокручено вниз")],
                    scroll_top=scroll_top)


# --- DayRecap --------------------------------------------------------------


def day_recap(theme, width=390, scale=1.0):
    canvas = _new(width, VIEWPORT_HEIGHT)
    ctx = Ctx(canvas, theme, width, scale)
    canvas.rect(0, 0, width, VIEWPORT_HEIGHT, fill=ctx.c("surface"))

    y = TOP_INSET
    # today's recap: title «Сегодня», no leading back icon (COMPONENTS.md)
    y += components.app_top_bar(ctx, y, "Сегодня", back=False)
    scroll_top = y + tokens.SECTION

    style = ctx.style("labelLarge")
    button_h = max(tokens.BUTTON_HEIGHT, style.line_height + 2 * tokens.SPACING[300])
    bottom = VIEWPORT_HEIGHT - BOTTOM_INSET - tokens.SECTION
    done_y = bottom - button_h
    share_y = done_y - tokens.SPACING[300] - button_h
    scroll_bottom = share_y - tokens.SECTION

    # the two pinned buttons own the bottom band; the content above them lives
    # in its own container and can never be painted underneath them
    doc = _new(width, VIEWPORT_HEIGHT * 2)
    dctx = Ctx(doc, theme, width, scale)
    cursor = 0
    cursor += components.score_badge(dctx, cursor, RECAP["score"])
    cursor += tokens.SECTION
    cursor += components.day_result_rows(dctx, cursor, RECAP["rows"])
    cursor += components.streak_row(dctx, cursor, *RECAP["streak"])

    clip = canvas.clip_rect(0, scroll_top, width, scroll_bottom - scroll_top)
    canvas.embed(doc, transform="translate(0,%s)" % fmt(scroll_top), clip=clip,
                 name="DayRecapContent")
    components.secondary_button(ctx, share_y, "Поделиться")
    components.primary_button(ctx, done_y, "Готово")
    return Rendered(canvas, width, VIEWPORT_HEIGHT, [(0, None)],
                    scroll_top=scroll_top if cursor > scroll_bottom - scroll_top else None)
