"""Visual state sheets — components whose real place of appearance falls
outside the four base states of B2, checked with the same strictness as an
artboard but without adding a thirteenth full-screen artboard.
"""
import category_icons
import components
import screens
import svgkit
import tokens
from boards import BOARD_BG, BOARD_INK, BOARD_MUTED, CAPTION, META, NOTE, PAD, TITLE
from components import Ctx
from svgkit import fmt
from typeset import wrap


PANEL_GAP = 28
TEXT_WIDTH = 1180


def sheet_width(panel_widths):
    """Wide enough for the panel row, never narrower than the prose column."""
    row = 2 * PAD + sum(panel_widths) + PANEL_GAP * (len(panel_widths) - 1)
    return max(TEXT_WIDTH, row)


def measure(draw, theme, width, scale):
    """Run a panel's drawing routine on a throwaway canvas to learn its height,
    so no panel is ever sized by a hand-guessed number and clipped."""
    probe = svgkit.Canvas(width, 8000)
    return draw(Ctx(probe, theme, width, scale), probe)


class Sheet:
    """A sheet is a light board with stacked, titled sections."""

    def __init__(self, title, subtitle, width):
        self.width = width
        self.canvas = svgkit.Canvas(width, 10, title)
        self.cursor = PAD
        self.canvas.text(PAD, self.cursor + TITLE.baseline(), title, TITLE, BOARD_INK)
        self.cursor += TITLE.line_height + 6
        self.canvas.text(PAD, self.cursor + META.baseline(), subtitle, META, BOARD_MUTED)
        self.cursor += META.line_height + 18

    def note(self, text):
        for line in wrap(text, NOTE, self.width - 2 * PAD):
            self.canvas.text(PAD, self.cursor + NOTE.baseline(), line, NOTE, BOARD_MUTED)
            self.cursor += NOTE.line_height
        self.cursor += 8

    def heading(self, text):
        self.cursor += 10
        self.canvas.line(PAD, self.cursor, self.width - PAD, self.cursor, "#C9C0A8", 1)
        self.cursor += 12
        self.canvas.text(PAD, self.cursor + CAPTION.baseline(), text, CAPTION, BOARD_INK)
        self.cursor += CAPTION.line_height + 10

    def caption(self, x, text):
        self.canvas.text(x, self.cursor + CAPTION.baseline(), text, CAPTION, BOARD_MUTED)

    def panel(self, x, width, height, theme, scale=1.0, label=None):
        """A surface-coloured panel to draw product components on."""
        if label:
            self.canvas.text(x, self.cursor + CAPTION.baseline(), label, CAPTION, BOARD_MUTED)
        top = self.cursor + (CAPTION.line_height + 8 if label else 0)
        colour = tokens.scheme(theme)
        sub = svgkit.Canvas(width, height)
        ctx = Ctx(sub, theme, width, scale)
        sub.rect(0, 0, width, height, fill=colour["surface"])
        return ctx, sub, x, top

    def place(self, sub, x, top, stroke="#C9C0A8"):
        clip = self.canvas.clip_rect(x, top, sub.width, sub.height)
        self.canvas.embed(sub, transform="translate(%s,%s)" % (fmt(x), fmt(top)), clip=clip)
        self.canvas.rect(x, top, sub.width, sub.height, fill="none",
                         stroke=stroke, stroke_width=1)

    def finish(self):
        self.cursor += PAD
        self.canvas.height = self.cursor
        body = self.canvas.body
        self.canvas.body = []
        self.canvas.rect(0, 0, self.width, self.cursor, fill=BOARD_BG)
        self.canvas.body.extend(body)
        return self.canvas


# --- ThreeStepProgress -----------------------------------------------------


def three_step_progress():
    sheet = Sheet("ThreeStepProgress — все семь валидных комбинаций",
                  "mode × completedCount · светлая и тёмная тема · font scale 100%", 1180)
    sheet.note("Модель — два независимых поля. mode = ActiveDay живёт только на "
               "Home.InProgress и допускает completedCount 0–2, где точка сразу после "
               "последней пройденной находится в состоянии current. mode = ClosedDay живёт "
               "только в Archive row, допускает 0–3 и состояния current не имеет ни при "
               "каком значении. Одно и то же completedCount = 2 рендерится по-разному в "
               "двух режимах — сравнение вынесено отдельной секцией ниже.")
    sheet.note("Точки никогда не единственный носитель состояния: рядом всегда стоит текст, "
               "и именно он озвучивается TalkBack (точки — clearAndSetSemantics).")

    combos = [("ActiveDay", 0, "Задание 1 из 3"), ("ActiveDay", 1, "Задание 2 из 3"),
              ("ActiveDay", 2, "Задание 3 из 3"),
              ("ClosedDay", 0, "29 августа · День 24 · не завершён · 0 из 18"),
              ("ClosedDay", 1, "28 августа · День 23 · не завершён · 4 из 18"),
              ("ClosedDay", 2, "27 августа · День 22 · не завершён · 9 из 18"),
              ("ClosedDay", 3, "26 августа · День 21 · 15 из 18")]

    for theme in ("light", "dark"):
        sheet.heading("Тема: %s" % ("светлая" if theme == "light" else "тёмная"))
        row_h = 46
        ctx, sub, x, top = sheet.panel(PAD, sheet.width - 2 * PAD,
                                       row_h * len(combos) + 24, theme)
        style = ctx.style("bodyMedium")
        mono = ctx.style("Metadata")
        y = 12
        for mode, completed, text in combos:
            sub.text(16, y + row_h / 2 - mono.line_height / 2 + mono.baseline(),
                     "%s · %d" % (mode, completed), mono, ctx.c("onSurfaceVariant"))
            components.three_step_progress(ctx, 230, y + (row_h - tokens.PROGRESS_DOT) / 2,
                                           mode, completed)
            sub.text(300, y + row_h / 2 - style.line_height / 2 + style.baseline(),
                     text, style, ctx.c("onSurface"))
            y += row_h
        sheet.place(sub, x, top)
        sheet.cursor = top + sub.height + 16

    sheet.heading("Одно и то же completedCount = 2 в двух режимах")
    ctx, sub, x, top = sheet.panel(PAD, sheet.width - 2 * PAD, 92, "light")
    style = ctx.style("bodyMedium")
    for index, (mode, note) in enumerate([
            ("ActiveDay", "третья точка — current: обводка primary, заливка primaryContainer"),
            ("ClosedDay", "третья точка — outlineVariant: «текущего» задания в закрытом дне нет")]):
        y = 16 + index * 40
        components.three_step_progress(ctx, 16, y + 4, mode, 2)
        sub.text(70, y + style.baseline(), "%s — %s" % (mode, note), style,
                 ctx.c("onSurface"))
    sheet.place(sub, x, top)
    sheet.cursor = top + sub.height
    return sheet.finish()


# --- DragEducationHint -----------------------------------------------------


def drag_education_hint():
    sheet = Sheet("DragEducationHint — одноразовая подсказка на первой головоломке",
                  "в потоке контента · 100% и 200% · светлая и тёмная тема",
                  sheet_width([390, 390, 320]))
    sheet.note("Плашка занимает собственную полосу между подписью направления и первой "
               "карточкой: она раздвигает соседние элементы, а не накладывается на них, и "
               "не перекрывает ни DragHandle, ни MoveButton. Заливка сплошная "
               "(surfaceContainer), не полупрозрачная — контраст текста не зависит от того, "
               "что под плашкой.")
    sheet.note("Точный текст плашки — «Перетащите за ручку или используйте стрелки», без "
               "вариаций. TalkBack при этом озвучивает другую формулировку: «Для изменения "
               "порядка используйте действия карточки или кнопки перемещения».")
    sheet.note("PrimaryButton «Проверить» — disabled первые dragHint.submitLockDuration "
               "(600 мс): прозрачный фон, обводка outline при opacity.disabledContent 38%, "
               "текст onSurface при той же непрозрачности. Покачивание карточки — один раз "
               "за dragHint.wobbleDuration (300 мс) с амплитудой 4°; при reduced motion оно "
               "обнуляется полностью, а dragHint.autoHideDelay (4 с) и submitLockDuration "
               "не сокращаются.")

    def draw(ctx, canvas):
        y = tokens.SPACING[400]
        y += canvas.paragraph(ctx.margin, y, ctx.content_width,
                              screens.PUZZLE["directionLabel"], ctx.style("bodyMedium"),
                              ctx.c("onSurfaceVariant"))
        y += tokens.SPACING[300]
        y += components.drag_education_hint(ctx, y)
        y += tokens.SPACING[300]
        y += components.orderable_card(ctx, y, 1, "Килиманджаро", "Танзания", first=True)
        y += tokens.SPACING[600]
        y += components.primary_button(ctx, y, "Проверить", disabled=True)
        return y + tokens.SPACING[400]

    panels = [("light", 390, 1.0, "Светлая · 390 dp · 100%"),
              ("dark", 390, 1.0, "Тёмная · 390 dp · 100%"),
              ("light", 320, 2.0, "Светлая · 320 dp · 200%")]
    sheet.heading("Подсказка в потоке контента")
    heights = [measure(draw, theme, width, scale) for theme, width, scale, _ in panels]
    tallest = max(heights)
    x, tops = PAD, []
    for (theme, width, scale, label), _ in zip(panels, heights):
        ctx, sub, px, top = sheet.panel(x, width, tallest, theme, scale, label)
        draw(ctx, sub)
        sheet.place(sub, px, top)
        tops.append(top + sub.height)
        x += width + PANEL_GAP
    sheet.cursor = max(tops)
    return sheet.finish()


# --- SourcesBlock / SourceRow ---------------------------------------------


SOURCES = [
    ({"title": "Encyclopaedia Britannica: Mount Elbrus", "kind": "encyclopedia",
      "url": "https://www.britannica.com/place/Mount-Elbrus",
      "accessedAt": "2026-08-20"}, "link"),
    ({"title": "Encyclopaedia Britannica: Mont Blanc", "kind": "encyclopedia",
      "url": "https://www.britannica.com/place/Mont-Blanc",
      "accessedAt": "2026-08-20"}, "urlPlainText"),
    ({"title": "Institut géographique national: результаты измерений высоты Монблана",
      "kind": "official",
      "reference": "IGN, отчёт о геодезическом измерении вершины Монблан",
      "accessedAt": "2026-08-20"}, "referenceOnly"),
]


def sources_block_expanded():
    sheet = Sheet("SourcesBlock.expanded — три состояния SourceRow",
                  "источники s1 / s3 / s4 головоломки geo-vysota-gor-007 · "
                  "светлая и тёмная тема · 100% и 200%", sheet_width([390, 390, 320]))
    sheet.note("Состояний ровно три, не два. link — есть url и на устройстве найден "
               "обработчик ACTION_VIEW: строка кликабельна целиком, справа иконка внешнего "
               "перехода, высота не меньше 48 dp. urlPlainText — url есть, обработчика нет: "
               "строка некликабельна, без иконки, сам url показан как обычный читаемый "
               "текст. referenceOnly — url отсутствует, показывается обязательный reference.")
    sheet.note("Название, вид источника и дата обращения показаны во всех трёх состояниях "
               "без исключений. Состояние s3 здесь — иллюстративное допущение «обработчика "
               "нет»: оно зависит от устройства, а не от данных источника.")
    sheet.note("Русские подписи вида источника зафиксированы в COMPONENTS.md → SourceRow "
               "как строковые ресурсы: «Официальный источник», «Энциклопедия», «Научный "
               "источник», «Дополнительный источник». Raw-значения enum (official / "
               "encyclopedia / academic / other) нигде не показываются пользователю; "
               "«Дополнительный источник» — только подпись, признаком авторитетности не "
               "является (правила авторитетности не меняются, см. CONTENT_MODEL.md).")

    panels = [("light", 390, 1.0, "Светлая · 390 dp · 100%"),
              ("dark", 390, 1.0, "Тёмная · 390 dp · 100%"),
              ("light", 320, 2.0, "Светлая · 320 dp · 200%")]
    sheet.heading("Раскрытый блок")
    x, tops = PAD, []
    for theme, width, scale, label in panels:
        probe = svgkit.Canvas(width, 4000)
        pctx = Ctx(probe, theme, width, scale)
        needed = components.sources_block_expanded(pctx, tokens.SPACING[400], SOURCES) \
            + 2 * tokens.SPACING[400]
        ctx, sub, px, top = sheet.panel(x, width, needed, theme, scale, label)
        components.sources_block_expanded(ctx, tokens.SPACING[400], SOURCES)
        sheet.place(sub, px, top)
        tops.append(top + sub.height)
        x += width + PANEL_GAP
    sheet.cursor = max(tops)

    sheet.heading("Легенда: все четыре подписи Source.kind (справочно, вне экранного "
                  "viewport)")
    sheet.note("Не четвёртая SourceRow и не пятое состояние — компактный список вне "
               "экранного viewport, для сверки enum → строковый ресурс. Три состояния "
               "SourceRow выше остаются link/urlPlainText/referenceOnly без изменений; "
               "среди трёх иллюстративных источников нет kind = other, поэтому его подпись "
               "проверяется только здесь.")
    legend_ctx = Ctx(sheet.canvas, "light", sheet.width, 1.0)
    raw_style = legend_ctx.style("Metadata")
    label_style = legend_ctx.style("bodyMedium")
    legend_colour = tokens.scheme("light")
    row_h = max(raw_style.line_height, label_style.line_height) + tokens.SPACING[200]
    legend_x = PAD
    legend_y = sheet.cursor
    for kind in ("official", "encyclopedia", "academic", "other"):
        sheet.canvas.text(legend_x, legend_y + raw_style.baseline(), kind, raw_style,
                          legend_colour["onSurfaceVariant"])
        sheet.canvas.text(legend_x + 220, legend_y + label_style.baseline(),
                          "→ «%s»" % components.SOURCE_KIND_LABELS[kind], label_style,
                          legend_colour["onSurface"])
        legend_y += row_h
    sheet.cursor = legend_y
    return sheet.finish()


# --- NotificationOptInDialog ----------------------------------------------


def notification_opt_in_dialog():
    sheet = Sheet("NotificationOptInDialog — единственный диалог MVP",
                  "светлая и тёмная тема · 100% и 200% с вертикальными действиями · scrim",
                  sheet_width([390, 390, 320]))
    sheet.note("Один текстовый узел вопроса (titleMedium), два явных действия: «Да, в 9:00» "
               "(PrimaryButton) и «Не нужно» (SecondaryButton). Касание вне диалога не "
               "закрывает его — dismissOnClickOutside = false.")
    sheet.note("Единственное место продукта, где одновременно применяются shape.large, "
               "surfaceContainerHigh, elevation.dialog и opacity.scrim (32%). Ни форма, ни "
               "тень диалога не распространяются на обычные контейнеры.")
    sheet.note("При 200% действия складываются вертикально, обе кнопки сохраняют "
               "size.button.height и size.touchTarget.min — они не сжимаются ради ширины.")

    panels = [("light", 390, 1.0, "Светлая · 390 dp · 100% · действия в ряд"),
              ("dark", 390, 1.0, "Тёмная · 390 dp · 100% · действия в ряд"),
              ("light", 320, 2.0, "Светлая · 320 dp · 200% · действия вертикально")]
    sheet.heading("Диалог поверх DayRecap")
    def backdrop(ctx, canvas):
        y = tokens.SPACING[400]
        y += components.app_top_bar(ctx, y, "Сегодня", back=False)
        y += tokens.SECTION
        y += components.score_badge(ctx, y, screens.RECAP["score"])
        y += tokens.SECTION
        y += components.day_result_rows(ctx, y, screens.RECAP["rows"])
        return y + tokens.SPACING[400]

    def dialog_height(theme, width, scale):
        probe = svgkit.Canvas(width, 4000)
        return components.notification_dialog(
            Ctx(probe, theme, width, scale), tokens.SPACING[600], 0,
            width - 2 * tokens.SPACING[600])

    heights = [max(measure(backdrop, t, w, s),
                   dialog_height(t, w, s) + 2 * tokens.SECTION)
               for t, w, s, _ in panels]
    tallest = max(heights)
    x, tops = PAD, []
    for theme, width, scale, label in panels:
        ctx, sub, px, top = sheet.panel(x, width, tallest, theme, scale, label)
        backdrop(ctx, sub)
        sub.rect(0, 0, width, tallest,
                 fill=svgkit.rgba(tokens.scheme(theme)["scrim"], tokens.OPACITY_SCRIM))
        dialog_w = width - 2 * tokens.SPACING[600]
        components.notification_dialog(
            ctx, tokens.SPACING[600],
            (tallest - dialog_height(theme, width, scale)) / 2, dialog_w)
        sheet.place(sub, px, top)
        tops.append(top + sub.height)
        x += width + PANEL_GAP
    sheet.cursor = max(tops)
    return sheet.finish()


# --- CategoryLabel ---------------------------------------------------------


def category_label():
    sheet = Sheet("CategoryLabel — иконки всех семи категорий",
                  "Material Symbols Outlined · одно семейство, opsz 20, wght 600 · "
                  "icon.size.small (16 dp)", sheet_width([366, 366, 366]))
    sheet.note("Семь значений enum `category` из CONTENT_MODEL.md, каждое со своей "
               "пиктограммой. Одна иконка на несколько категорий — дефект: в предыдущей "
               "ревизии одна и та же гора стояла у географии, истории и науки.")
    sheet.note("Все семь взяты из одной системы, одного оптического размера и одного веса, "
               "поэтому визуальный вес набора одинаков по построению. В SVG это vector "
               "path, а не иконочный шрифт: ни один глиф не адресуется кодом символа.")
    sheet.note("Иконка нигде не появляется без названия категории. Эмодзи не используются.")

    sheet.heading("Семь иконок рядом: 16 dp (рабочий размер) и ×4 для разбора")
    order = ["geography", "history", "science", "nature", "culture", "russia", "mixed"]
    ctx, sub, x, top = sheet.panel(PAD, sheet.width - 2 * PAD, 190, "light")
    colour = tokens.scheme("light")
    caption = ctx.style("labelMedium")
    mono = ctx.style("Metadata")
    column = (sub.width - 32) / 7
    for index, category in enumerate(order):
        cx = 16 + index * column
        svgkit.category_icon(sub, cx + column / 2 - 32, 16, 64,
                             colour["onSurface"], category)
        svgkit.category_icon(sub, cx + column / 2 - tokens.ICON_SMALL / 2, 96,
                             tokens.ICON_SMALL, colour["onSurface"], category)
        label = category_icons.CATEGORY_LABELS[category]
        sub.text(cx + column / 2, 132 + caption.baseline(), label, caption,
                 colour["onSurface"], anchor="middle")
        sub.text(cx + column / 2, 154 + mono.baseline(),
                 category_icons.CATEGORY_ICONS[category]["symbol"], mono,
                 colour["onSurfaceVariant"], anchor="middle")
    sheet.place(sub, x, top)
    sheet.cursor = top + sub.height + 8

    sheet.heading("Компонент целиком: обе темы и 200%")
    x, tops = PAD, []
    for theme, scale, label in [("light", 1.0, "Светлая · 100%"),
                                ("dark", 1.0, "Тёмная · 100%"),
                                ("light", 2.0, "Светлая · 200%")]:
        width = 366
        probe = svgkit.Canvas(width, 2000)
        pctx = Ctx(probe, theme, width, scale)
        heights = [components.category_label_size(pctx, c)[1] for c in order]
        needed = sum(heights) + tokens.SPACING[300] * (len(order) - 1) + 2 * tokens.SPACING[400]
        ctx, sub, px, top = sheet.panel(x, width, needed, theme, scale, label)
        y = tokens.SPACING[400]
        for category in order:
            _, height = components.category_label(ctx, tokens.SPACING[400], y, category)
            y += height + tokens.SPACING[300]
        sheet.place(sub, px, top)
        tops.append(top + sub.height)
        x += width + PANEL_GAP
    sheet.cursor = max(tops)
    return sheet.finish()


SHEETS = [
    ("three-step-progress", three_step_progress),
    ("drag-education-hint", drag_education_hint),
    ("sources-block-expanded", sources_block_expanded),
    ("notification-opt-in-dialog", notification_opt_in_dialog),
    ("category-label", category_label),
]
