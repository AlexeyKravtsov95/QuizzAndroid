"""The COMPONENTS.md inventory, drawn.

Every function draws exactly one named component and returns the height it
consumed, so screens are laid out by stacking real measured blocks rather than
by hand-placed coordinates. Font-scale-dependent values come from `Style`
(sp scales), dp values never scale.
"""
import category_icons
import svgkit
import tokens
from svgkit import fmt, rgba
from typeset import Style


class Ctx:
    """Everything a component needs to know about the screen it sits on."""

    def __init__(self, canvas, theme, width, scale=1.0):
        self.canvas = canvas
        self.theme = theme
        self.colour = tokens.scheme(theme)
        self.width = width
        self.scale = scale
        self.margin = tokens.margin_for(width)

    @property
    def content_width(self):
        return self.width - 2 * self.margin

    def style(self, role):
        return Style(role, self.scale)

    def c(self, role):
        return self.colour[role]


# --- AppTopBar -------------------------------------------------------------


def app_top_bar(ctx, y, title, back=True, category=None):
    """AppTopBar. Returns its height.

    Adaptive rule (COMPONENTS.md, "Перенос CategoryLabel"): the label lives in
    the right slot while it fits; when title and label cannot share the row it
    moves to a second line, left-aligned under the title. The back button keeps
    48 x 48 dp and the label never shrinks.
    """
    cv, colour = ctx.canvas, ctx.colour
    title_style = ctx.style("titleMedium")
    x = ctx.margin
    bar_height = max(tokens.BUTTON_HEIGHT, title_style.line_height + 2 * tokens.SPACING[200])

    with cv.group("AppTopBar"):
        text_x = x
        if back:
            svgkit.chevron(cv, x + tokens.BACK_BUTTON / 2, y + bar_height / 2,
                           tokens.ICON_DEFAULT, colour["onSurface"], "left")
            cv.rect(x, y + (bar_height - tokens.BACK_BUTTON) / 2,
                    tokens.BACK_BUTTON, tokens.BACK_BUTTON, fill="none")
            text_x = x + tokens.BACK_BUTTON + tokens.SPACING[200]

        label_w = label_h = 0
        if category:
            label_w, label_h = category_label_size(ctx, category)

        title_w = title_style.width(title)
        room = ctx.width - ctx.margin - text_x
        inline = (not category) or (title_w + tokens.SPACING[300] + label_w <= room)

        cv.text(text_x, y + bar_height / 2 - title_style.line_height / 2 + title_style.baseline(),
                title, title_style, colour["onSurface"])

        if category and inline:
            category_label(ctx, ctx.width - ctx.margin - label_w,
                           y + (bar_height - label_h) / 2, category)
            return bar_height
        if category:
            second = y + bar_height + tokens.SPACING[200]
            category_label(ctx, text_x, second, category)
            return bar_height + tokens.SPACING[200] + label_h + tokens.SPACING[200]
    return bar_height


# --- CategoryLabel ---------------------------------------------------------


def category_label_size(ctx, category):
    style = ctx.style("labelMedium")
    text = category_icons.CATEGORY_LABELS[category]
    width = (tokens.SPACING[200] + tokens.ICON_SMALL + tokens.SPACING[200]
             + style.width(text) + tokens.SPACING[200])
    height = max(tokens.CATEGORY_LABEL_MIN_HEIGHT,
                 style.line_height + 2 * tokens.SPACING[100])
    return width, height


def category_label(ctx, x, y, category):
    """CategoryLabel: pictogram + name, always together, never icon-only."""
    cv, colour = ctx.canvas, ctx.colour
    style = ctx.style("labelMedium")
    text = category_icons.CATEGORY_LABELS[category]
    width, height = category_label_size(ctx, category)
    with cv.group("CategoryLabel"):
        # shape.categoryLabel.corner = shape.extraSmall (4 dp) — a dp radius,
        # so it does not grow with the font scale.
        cv.rect(x, y, width, height, fill=colour["primaryContainer"],
                rx=tokens.SHAPE_EXTRA_SMALL)
        svgkit.category_icon(cv, x + tokens.SPACING[200],
                             y + (height - tokens.ICON_SMALL) / 2,
                             tokens.ICON_SMALL, colour["onPrimaryContainer"], category)
        cv.text(x + tokens.SPACING[200] + tokens.ICON_SMALL + tokens.SPACING[200],
                y + height / 2 - style.line_height / 2 + style.baseline(),
                text, style, colour["onPrimaryContainer"])
    return width, height


# --- Buttons ---------------------------------------------------------------


def _button_height(ctx, label, style, inner_width):
    from typeset import wrap
    lines = wrap(label, style, inner_width)
    return max(tokens.BUTTON_HEIGHT, len(lines) * style.line_height + 2 * tokens.SPACING[300]), lines


def primary_button(ctx, y, label, x=None, width=None, disabled=False):
    cv, colour = ctx.canvas, ctx.colour
    style = ctx.style("labelLarge")
    x = ctx.margin if x is None else x
    width = ctx.content_width if width is None else width
    height, lines = _button_height(ctx, label, style, width - 2 * tokens.SPACING[400])
    with cv.group("PrimaryButton"):
        if disabled:
            cv.rect(x, y, width, height, fill="none", rx=tokens.SHAPE_SMALL,
                    stroke=rgba(colour["outline"], tokens.OPACITY_DISABLED_CONTENT),
                    stroke_width=1)
            fill = rgba(colour["onSurface"], tokens.OPACITY_DISABLED_CONTENT)
        else:
            cv.rect(x, y, width, height, fill=colour["primary"], rx=tokens.SHAPE_SMALL)
            fill = colour["onPrimary"]
        top = y + (height - len(lines) * style.line_height) / 2
        for index, line in enumerate(lines):
            cv.text(x + width / 2, top + index * style.line_height + style.baseline(),
                    line, style, fill, anchor="middle")
    return height


def secondary_button(ctx, y, label, x=None, width=None):
    cv, colour = ctx.canvas, ctx.colour
    style = ctx.style("labelLarge")
    x = ctx.margin if x is None else x
    width = ctx.content_width if width is None else width
    height, lines = _button_height(ctx, label, style, width - 2 * tokens.SPACING[400])
    with cv.group("SecondaryButton"):
        cv.rect(x, y, width, height, fill="none", rx=tokens.SHAPE_SMALL,
                stroke=colour["outline"], stroke_width=1)
        top = y + (height - len(lines) * style.line_height) / 2
        for index, line in enumerate(lines):
            cv.text(x + width / 2, top + index * style.line_height + style.baseline(),
                    line, style, colour["onSurface"], anchor="middle")
    return height


# --- HomeHeader ------------------------------------------------------------


def home_header(ctx, y, date_text, archive_visible=True):
    cv, colour = ctx.canvas, ctx.colour
    masthead = ctx.style("Masthead")
    meta = ctx.style("Metadata")
    x = ctx.margin
    title = "По порядку!"
    with cv.group("HomeHeader"):
        icons_width = 2 * tokens.HEADER_ICON + tokens.SPACING[200]
        masthead_w = masthead.width(title)
        row_height = max(tokens.HEADER_ICON, masthead.line_height)
        inline = masthead_w + tokens.SPACING[600] + icons_width <= ctx.content_width

        cv.text(x, y + row_height / 2 - masthead.line_height / 2 + masthead.baseline(),
                title, masthead, colour["onSurface"])
        icon_y = y + (row_height - tokens.HEADER_ICON) / 2
        if not inline:
            icon_y = y + row_height + tokens.SPACING[200]
        icon_right = ctx.width - ctx.margin
        svgkit.settings_icon(cv, icon_right - tokens.HEADER_ICON / 2,
                             icon_y + tokens.HEADER_ICON / 2, colour["onSurface"])
        if archive_visible:
            svgkit.archive_icon(cv,
                                icon_right - tokens.HEADER_ICON - tokens.SPACING[200]
                                - tokens.HEADER_ICON / 2,
                                icon_y + tokens.HEADER_ICON / 2, colour["onSurface"])
        used = row_height if inline else row_height + tokens.SPACING[200] + tokens.HEADER_ICON

        line_y = y + used + tokens.SPACING[300]
        cv.line(x, line_y, ctx.width - ctx.margin, line_y,
                colour["outlineVariant"], tokens.DIVIDER_THICKNESS)
        date_top = line_y + tokens.SPACING[300]
        date_h = cv.paragraph(x, date_top, ctx.content_width, date_text, meta,
                              colour["onSurfaceVariant"])
    return date_top + date_h - y


# --- DailyIssuePanel -------------------------------------------------------


def daily_issue_panel_ready(ctx, y, day_number, stats):
    """DailyIssuePanel in state `Ready`: issue number plus three statistics
    rows, one composite editorial unit behind a single spine."""
    cv, colour = ctx.canvas, ctx.colour
    word = ctx.style("IssueNumberWord")
    digits = ctx.style("IssueNumberDigits")
    issue_label = ctx.style("IssueLabel")
    caps = ctx.style("CapsLabel")
    value_style = ctx.style("labelMedium")

    x = ctx.margin
    content_x = x + tokens.SPINE_WIDTH + tokens.SPINE_CONTENT_INDENT
    content_w = ctx.width - ctx.margin - content_x
    cursor = y

    with cv.group("DailyIssuePanel"):
        spine = len(cv.body)          # reserve the slot; height is known at the end
        cv.raw("")
        cv.text(content_x, cursor + issue_label.baseline(), "Выпуск", issue_label,
                colour["tertiary"])
        cursor += issue_label.line_height
        row_h = max(word.line_height, digits.line_height)
        baseline = cursor + row_h / 2 + word.size * 0.36
        cv.text(content_x, baseline, "День", word, colour["onSurface"])
        cv.text(content_x + word.width("День") + tokens.SPACING[200], baseline,
                str(day_number), digits, colour["onSurface"])
        cursor += row_h

        for label, value in stats:
            cursor += tokens.SPACING[400]
            cv.line(content_x, cursor, ctx.width - ctx.margin, cursor,
                    colour["outlineVariant"], tokens.DIVIDER_THICKNESS)
            cursor += tokens.DIVIDER_THICKNESS + tokens.SPACING[400]
            cv.text(content_x, cursor + caps.baseline(), label, caps,
                    colour["onSurfaceVariant"])
            cursor += caps.line_height + tokens.STAT_ROW_INNER
            cv.text(content_x, cursor + value_style.baseline(), value, value_style,
                    colour["onSurface"])
            cursor += value_style.line_height
        cursor += tokens.SPACING[400]
        cv.line(content_x, cursor, ctx.width - ctx.margin, cursor,
                colour["outlineVariant"], tokens.DIVIDER_THICKNESS)
        cursor += tokens.DIVIDER_THICKNESS
        # the spine runs the full height of the block it marks, no shorter
        probe = svgkit.Canvas(1, 1)
        probe.rect(x, y, tokens.SPINE_WIDTH, cursor - y, fill=colour["primary"])
        cv.body[spine] = probe.body[0]
    return cursor - y


# --- OrderableCard ---------------------------------------------------------


def _card_text_zone(ctx, card_width, interactive):
    left = tokens.INDEX_ZONE_WIDTH + tokens.SPACING[400]
    if interactive:
        # spacing.300 keeps the MoveButton column clear of the 18 dp cut corner
        right = tokens.SPACING[300] + tokens.MOVE_BUTTON + tokens.SPACING[300]
    else:
        right = tokens.SPACING[400]
    return left, card_width - left - right


def orderable_card_height(ctx, title, subtitle, value, card_width, interactive):
    from typeset import wrap
    title_style, sub_style = ctx.style("titleLarge"), ctx.style("bodyMedium")
    _, text_w = _card_text_zone(ctx, card_width, interactive)
    height = len(wrap(title, title_style, text_w)) * title_style.line_height
    if subtitle:
        height += len(wrap(subtitle, sub_style, text_w)) * sub_style.line_height
    if value:
        height += sub_style.line_height
    height += 2 * tokens.SPACING[400]
    return max(tokens.ORDERABLE_CARD_MIN_HEIGHT, height)


def orderable_card(ctx, y, position, title, subtitle=None, value=None,
                   interactive=True, first=False, last=False, dragging=False):
    """OrderableCard. `interactive` False is the read-only PuzzleResult form:
    DragHandle and both MoveButtons are absent from the tree, not disabled."""
    from typeset import wrap
    cv, colour = ctx.canvas, ctx.colour
    x, width = ctx.margin, ctx.content_width
    height = orderable_card_height(ctx, title, subtitle, value, width, interactive)
    title_style, sub_style = ctx.style("titleLarge"), ctx.style("bodyMedium")
    index_style = ctx.style("CardIndex")

    def shape(fill):
        cv.path(svgkit.cut_corner_path(x, y, width, height,
                                       tokens.SHAPE_MEDIUM, tokens.CUT_CORNER), fill=fill)

    with cv.group("OrderableCard", extra='data-state="%s"'
                  % ("dragging" if dragging else ("readonly" if not interactive else "default"))):
        if dragging:
            svgkit.drop_shadow(cv, shape, tokens.ELEVATION_DRAGGED)
        shape(colour["surfaceContainerLow"])
        cv.path(svgkit.cut_corner_path(x, y, width, height,
                                       tokens.SHAPE_MEDIUM, tokens.CUT_CORNER),
                stroke=colour["outlineVariant"], stroke_width=tokens.DIVIDER_THICKNESS)
        if dragging:
            shape(rgba(colour["primary"], tokens.OPACITY_DRAG_STATE_LAYER))

        # index zone
        cv.line(x + tokens.INDEX_ZONE_WIDTH, y + tokens.SPACING[300],
                x + tokens.INDEX_ZONE_WIDTH, y + height - tokens.SPACING[300],
                colour["outlineVariant"], tokens.DIVIDER_THICKNESS)
        index_text = "№%d" % position if interactive else str(position)
        index_w = index_style.width(index_text)
        if interactive:
            stack = index_style.line_height + tokens.SPACING[200] + tokens.DRAG_HANDLE_GLYPH[1]
            top = y + (height - stack) / 2
            cv.text(x + tokens.INDEX_ZONE_WIDTH / 2 - index_w / 2,
                    top + index_style.baseline(), index_text, index_style,
                    colour["onSurfaceVariant"])
            with cv.group("DragHandle"):
                svgkit.drag_handle(cv, x + tokens.INDEX_ZONE_WIDTH / 2,
                                   top + index_style.line_height + tokens.SPACING[200]
                                   + tokens.DRAG_HANDLE_GLYPH[1] / 2,
                                   colour["onSurfaceVariant"])
        else:
            cv.text(x + tokens.INDEX_ZONE_WIDTH / 2 - index_w / 2,
                    y + height / 2 - index_style.line_height / 2 + index_style.baseline(),
                    index_text, index_style, colour["onSurfaceVariant"])

        # text zone
        text_left, text_w = _card_text_zone(ctx, width, interactive)
        title_lines = wrap(title, title_style, text_w)
        sub_lines = wrap(subtitle, sub_style, text_w) if subtitle else []
        block = (len(title_lines) * title_style.line_height
                 + len(sub_lines) * sub_style.line_height
                 + (sub_style.line_height if value else 0))
        cursor = y + (height - block) / 2
        for line in title_lines:
            cv.text(x + text_left, cursor + title_style.baseline(), line,
                    title_style, colour["onSurface"])
            cursor += title_style.line_height
        for line in sub_lines:
            cv.text(x + text_left, cursor + sub_style.baseline(), line,
                    sub_style, colour["onSurfaceVariant"])
            cursor += sub_style.line_height
        if value:
            cv.text(x + text_left, cursor + sub_style.baseline(), value,
                    sub_style, colour["onSurface"])

        # control zone
        if interactive:
            col_x = x + width - tokens.SPACING[300] - tokens.MOVE_BUTTON
            pair = 2 * tokens.MOVE_BUTTON + tokens.SPACING[200]
            top = y + (height - pair) / 2
            move_button(ctx, col_x, top, "up", enabled=not first)
            move_button(ctx, col_x, top + tokens.MOVE_BUTTON + tokens.SPACING[200],
                        "down", enabled=not last)
    return height


def move_button(ctx, x, y, direction, enabled=True):
    cv, colour = ctx.canvas, ctx.colour
    size = tokens.MOVE_BUTTON
    with cv.group("MoveButton", extra='data-state="%s"' % ("enabled" if enabled else "disabled")):
        if enabled:
            cv.rect(x, y, size, size, fill=colour["primaryContainer"], rx=tokens.SHAPE_SMALL,
                    stroke=rgba(colour["primary"], tokens.OPACITY_MOVE_BUTTON_ENABLED_BORDER),
                    stroke_width=1)
            ink = colour["onPrimaryContainer"]
        else:
            cv.rect(x, y, size, size, fill="none", rx=tokens.SHAPE_SMALL,
                    stroke=rgba(colour["outline"], tokens.OPACITY_DISABLED_CONTENT),
                    stroke_width=1)
            ink = rgba(colour["onSurface"], tokens.OPACITY_DISABLED_CONTENT)
        svgkit.chevron(cv, x + size / 2, y + size / 2, tokens.ICON_DEFAULT, ink, direction)
    return size


# --- PuzzleResult parts ----------------------------------------------------


def score_badge(ctx, y, text):
    cv = ctx.canvas
    style = ctx.style("headlineSmall")
    with cv.group("ScoreBadge"):
        cv.text(ctx.margin, y + style.baseline(), text, style, ctx.c("onSurface"))
    return style.line_height


def scoring_hint(ctx, y):
    cv = ctx.canvas
    style = ctx.style("bodyMedium")
    text = ("Баллы даются за каждую пару карточек в правильном порядке. "
            "У четырёх карточек шесть пар")
    with cv.group("ScoringHint"):
        return cv.paragraph(ctx.margin, y, ctx.content_width, text, style,
                            ctx.c("onSurfaceVariant"))


def inverted_pair_row(ctx, y, text):
    cv = ctx.canvas
    style = ctx.style("bodyMedium")
    with cv.group("InvertedPairRow"):
        return cv.paragraph(ctx.margin, y, ctx.content_width, text, style, ctx.c("tertiary"))


def sources_block_collapsed(ctx, y):
    cv, colour = ctx.canvas, ctx.colour
    style = ctx.style("labelLarge")
    height = max(tokens.TOUCH_TARGET_MIN, style.line_height + 2 * tokens.SPACING[300])
    with cv.group("SourcesBlock", extra='data-state="collapsed"'):
        cv.text(ctx.margin, y + height / 2 - style.line_height / 2 + style.baseline(),
                "Источники", style, colour["onSurface"])
        svgkit.chevron(cv, ctx.width - ctx.margin - tokens.ICON_DEFAULT / 2,
                       y + height / 2, tokens.ICON_DEFAULT, colour["onSurface"], "down")
        cv.line(ctx.margin, y + height, ctx.width - ctx.margin, y + height,
                colour["outlineVariant"], tokens.DIVIDER_THICKNESS)
    return height + tokens.DIVIDER_THICKNESS


def report_inaccuracy_action(ctx, y):
    cv = ctx.canvas
    style = ctx.style("labelSmall")
    height = max(tokens.TOUCH_TARGET_MIN, style.line_height + 2 * tokens.SPACING[300])
    with cv.group("ReportInaccuracyAction"):
        cv.text(ctx.margin, y + height / 2 - style.line_height / 2 + style.baseline(),
                "Сообщить о неточности", style, ctx.c("onSurfaceVariant"))
    return height


# --- DayRecap parts --------------------------------------------------------


def day_result_rows(ctx, y, rows):
    """Three DayResultRow entries under one adaptive rule.

    COMPONENTS.md: if any one row cannot hold CategoryLabel and result on a
    single line at the current width and font scale, the whole list of three
    switches to the stacked scheme. A list where some rows are inline and some
    are stacked is a defect, not an adaptation.
    """
    cv, colour = ctx.canvas, ctx.colour
    value_style = ctx.style("bodyLarge")
    measured = [(category_label_size(ctx, category), value_style.width(value))
                for category, value in rows]
    inline = all(label_w + tokens.SPACING[400] + value_w <= ctx.content_width
                 for (label_w, _), value_w in measured)

    cursor = y
    for index, (category, value) in enumerate(rows):
        (label_w, label_h), value_w = measured[index]
        with cv.group("DayResultRow",
                      extra='data-layout="%s"' % ("inline" if inline else "stacked")):
            if inline:
                row_h = max(label_h, value_style.line_height) + 2 * tokens.SPACING[300]
                category_label(ctx, ctx.margin, cursor + (row_h - label_h) / 2, category)
                cv.text(ctx.width - ctx.margin,
                        cursor + row_h / 2 - value_style.line_height / 2 + value_style.baseline(),
                        value, value_style, colour["onSurface"], anchor="end")
            else:
                row_h = (tokens.SPACING[200] + label_h + tokens.SPACING[300]
                         + value_style.line_height + tokens.SPACING[200])
                category_label(ctx, ctx.margin, cursor + tokens.SPACING[200], category)
                cv.text(ctx.margin,
                        cursor + tokens.SPACING[200] + label_h + tokens.SPACING[300]
                        + value_style.baseline(),
                        value, value_style, colour["onSurface"])
            cursor += row_h
            cv.line(ctx.margin, cursor, ctx.width - ctx.margin, cursor,
                    colour["outlineVariant"], tokens.DIVIDER_THICKNESS)
            cursor += tokens.DIVIDER_THICKNESS
    return cursor - y


def streak_row(ctx, y, label, value):
    cv, colour = ctx.canvas, ctx.colour
    caps, value_style = ctx.style("CapsLabel"), ctx.style("labelMedium")
    cursor = y + tokens.SPACING[300]
    with cv.group("StreakRow"):
        cv.text(ctx.margin, cursor + caps.baseline(), label, caps, colour["onSurfaceVariant"])
        cursor += caps.line_height + tokens.STAT_ROW_INNER
        cv.text(ctx.margin, cursor + value_style.baseline(), value, value_style,
                colour["onSurface"])
        cursor += value_style.line_height
    return cursor - y


# --- DragEducationHint -----------------------------------------------------


def drag_education_hint(ctx, y):
    """A solid tonal plaque that takes its own band in the content flow — it is
    not an overlay and never covers the card's DragHandle or MoveButton."""
    cv, colour = ctx.canvas, ctx.colour
    style = ctx.style("bodyMedium")
    text = "Перетащите за ручку или используйте стрелки"
    from typeset import wrap
    inner = ctx.content_width - 2 * tokens.SPACING[300]
    lines = wrap(text, style, inner)
    height = len(lines) * style.line_height + 2 * tokens.SPACING[200]
    with cv.group("DragEducationHint"):
        cv.rect(ctx.margin, y, ctx.content_width, height,
                fill=colour["surfaceContainer"], rx=tokens.SHAPE_EXTRA_SMALL)
        for index, line in enumerate(lines):
            cv.text(ctx.margin + tokens.SPACING[300],
                    y + tokens.SPACING[200] + index * style.line_height + style.baseline(),
                    line, style, colour["onSurfaceVariant"])
    return height


# --- SourcesBlock expanded / SourceRow -------------------------------------


SOURCE_KIND_LABELS = {
    "official": "Официальный источник",
    "encyclopedia": "Энциклопедия",
    "academic": "Научный источник",
    "other": "Дополнительный источник",
}


def source_row(ctx, y, source, state):
    """One SourceRow. Name, kind and accessedAt are shown in all three states;
    `link` adds the external-transition icon and a 48 dp target."""
    cv, colour = ctx.canvas, ctx.colour
    name_style = ctx.style("bodySmall")
    meta_style = ctx.style("bodySmall")
    text_w = ctx.content_width - tokens.SPACING[400]
    if state == "link":
        text_w -= tokens.ICON_SMALL + tokens.SPACING[300]

    cursor = y + tokens.SPACING[300]
    with cv.group("SourceRow", extra='data-state="%s"' % state):
        cursor += cv.paragraph(ctx.margin, cursor, text_w, source["title"],
                               name_style, colour["onSurface"])
        meta = "%s · обращение %s" % (SOURCE_KIND_LABELS[source["kind"]],
                                      source["accessedAt"])
        cursor += cv.paragraph(ctx.margin, cursor, text_w, meta, meta_style,
                               colour["onSurfaceVariant"])
        if state == "urlPlainText":
            cursor += cv.paragraph(ctx.margin, cursor, text_w, source["url"],
                                   meta_style, colour["onSurfaceVariant"])
        if source.get("reference"):
            cursor += cv.paragraph(ctx.margin, cursor, text_w, source["reference"],
                                   meta_style, colour["onSurfaceVariant"])
        cursor += tokens.SPACING[300]
        height = cursor - y
        if state == "link":
            height = max(height, tokens.TOUCH_TARGET_MIN)
            _external_link_icon(cv, ctx.width - ctx.margin - tokens.ICON_SMALL,
                                y + tokens.SPACING[300], colour["onSurface"])
        cv.line(ctx.margin, y + height, ctx.width - ctx.margin, y + height,
                colour["outlineVariant"], tokens.DIVIDER_THICKNESS)
    return height + tokens.DIVIDER_THICKNESS


def _external_link_icon(canvas, x, y, colour):
    s = tokens.ICON_SMALL
    w = tokens.ICON_STROKE_SMALL
    canvas.path("M%s,%s H%s V%s" % (fmt(x + s * 0.62), fmt(y + s * 0.10),
                                    fmt(x + s * 0.92), fmt(y + s * 0.40)),
                stroke=colour, stroke_width=w)
    canvas.line(x + s * 0.40, y + s * 0.62, x + s * 0.92, y + s * 0.10, colour, w)
    canvas.path("M%s,%s H%s V%s H%s V%s" %
                (fmt(x + s * 0.55), fmt(y + s * 0.12), fmt(x + s * 0.08),
                 fmt(y + s * 0.92), fmt(x + s * 0.88), fmt(y + s * 0.48)),
                stroke=colour, stroke_width=w)


def sources_block_expanded(ctx, y, sources):
    cv, colour = ctx.canvas, ctx.colour
    style = ctx.style("labelLarge")
    header = max(tokens.TOUCH_TARGET_MIN, style.line_height + 2 * tokens.SPACING[300])
    cursor = y
    with cv.group("SourcesBlock", extra='data-state="expanded"'):
        cv.text(ctx.margin, cursor + header / 2 - style.line_height / 2 + style.baseline(),
                "Источники", style, colour["onSurface"])
        svgkit.chevron(cv, ctx.width - ctx.margin - tokens.ICON_DEFAULT / 2,
                       cursor + header / 2, tokens.ICON_DEFAULT, colour["onSurface"], "up")
        cursor += header
        cv.line(ctx.margin, cursor, ctx.width - ctx.margin, cursor,
                colour["outlineVariant"], tokens.DIVIDER_THICKNESS)
        cursor += tokens.DIVIDER_THICKNESS
        for source, state in sources:
            cursor += source_row(ctx, cursor, source, state)
    return cursor - y


# --- NotificationOptInDialog ----------------------------------------------


def notification_dialog(ctx, x, y, width, stacked=None):
    """The single dialog of the MVP: one question node plus two actions.
    Actions stack vertically when they cannot share a row."""
    cv, colour = ctx.canvas, ctx.colour
    title = ctx.style("titleMedium")
    label = ctx.style("labelLarge")
    pad = tokens.SPACING[600]
    inner = width - 2 * pad
    from typeset import wrap
    lines = wrap("Напоминать о новом задании?", title, inner)

    yes, no = "Да, в 9:00", "Не нужно"
    button_h = max(tokens.BUTTON_HEIGHT, label.line_height + 2 * tokens.SPACING[300])
    pair_w = (inner - tokens.SPACING[300]) / 2
    fits = (label.width(yes) + 2 * tokens.SPACING[400] <= pair_w
            and label.width(no) + 2 * tokens.SPACING[400] <= pair_w)
    stacked = (not fits) if stacked is None else stacked

    body_h = len(lines) * title.line_height + tokens.SPACING[600]
    actions_h = (2 * button_h + tokens.SPACING[300]) if stacked else button_h
    height = pad + body_h + actions_h + pad

    def shape(fill):
        cv.rect(x, y, width, height, fill=fill, rx=tokens.SHAPE_LARGE)

    with cv.group("NotificationOptInDialog",
                  extra='data-actions="%s"' % ("stacked" if stacked else "row")):
        svgkit.drop_shadow(cv, shape, tokens.ELEVATION_DIALOG)
        shape(colour["surfaceContainerHigh"])
        cursor = y + pad
        for line in lines:
            cv.text(x + pad, cursor + title.baseline(), line, title, colour["onSurface"])
            cursor += title.line_height
        cursor += tokens.SPACING[600]
        if stacked:
            primary_button(ctx, cursor, yes, x=x + pad, width=inner)
            secondary_button(ctx, cursor + button_h + tokens.SPACING[300], no,
                             x=x + pad, width=inner)
        else:
            primary_button(ctx, cursor, yes, x=x + pad, width=pair_w)
            secondary_button(ctx, cursor, no, x=x + pad + pair_w + tokens.SPACING[300],
                             width=pair_w)
    return height


# --- ThreeStepProgress -----------------------------------------------------


def three_step_progress(ctx, x, y, mode, completed):
    cv, colour = ctx.canvas, ctx.colour
    d, gap = tokens.PROGRESS_DOT, tokens.SPACING[100]
    with cv.group("ThreeStepProgress",
                  extra='data-mode="%s" data-completed="%d"' % (mode, completed)):
        for index in range(3):
            cx = x + d / 2 + index * (d + gap)
            cy = y + d / 2
            if index < completed:
                cv.circle(cx, cy, d / 2, fill=colour["primary"])
            elif index == completed and mode == "ActiveDay":
                cv.circle(cx, cy, d / 2 - 0.5, fill=colour["primaryContainer"],
                          stroke=colour["primary"], stroke_width=1)
            else:
                cv.circle(cx, cy, d / 2, fill=colour["outlineVariant"])
    return 3 * d + 2 * gap, d
