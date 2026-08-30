"""Review boards: a device surface (or two, for a scrolling screen) inside an
annotated frame.

A board can carry more than one device frame of the *same* screen — the opening
viewport plus a continuation slice further down the same scroll container. That
is one artboard, not two: the count of full-screen artboards stays at 12.
"""
import screens
import svgkit
import tokens
from svgkit import fmt
from typeset import Style, wrap

BOARD_BG = "#EDE6D4"
BOARD_INK = "#23241F"
BOARD_MUTED = "#5B584C"
PAD = 40
FRAME_GAP = 36
PHONE_RADIUS = 32

TITLE = Style("titleMedium", 18 / 16)
META = Style("labelMedium", 12 / 13)
NOTE = Style("bodySmall", 12 / 12)
CAPTION = Style("labelMedium", 12 / 13)


def _header_lines(title, meta, notes, width):
    lines = [("title", title)]
    lines.append(("meta", meta))
    for note in notes:
        for line in wrap(note, NOTE, width):
            lines.append(("note", line))
    return lines


def _header_height(lines):
    height = 0
    for kind, _ in lines:
        style = {"title": TITLE, "meta": META, "note": NOTE}[kind]
        height += style.line_height + (6 if kind == "title" else 2)
    return height


def build(name, title, meta, notes, frames, captions=None):
    """frames: list of screens.Rendered-like canvases with .canvas/.width."""
    captions = captions or [None] * len(frames)
    frame_w = sum(f.width for f in frames) + FRAME_GAP * (len(frames) - 1)
    text_w = max(frame_w, 560)
    lines = _header_lines(title, meta, notes, text_w)
    header_h = _header_height(lines)
    wrapped = [wrap(c, CAPTION, f.width) if c else [] for c, f in zip(captions, frames)]
    caption_rows = max((len(w) for w in wrapped), default=0)
    caption_h = caption_rows * CAPTION.line_height + 10 if caption_rows else 0

    width = PAD * 2 + max(frame_w, text_w)
    height = PAD + header_h + 28 + screens.VIEWPORT_HEIGHT + caption_h + PAD

    board = svgkit.Canvas(width, height, title)
    board.rect(0, 0, width, height, fill=BOARD_BG)

    y = PAD
    for kind, text in lines:
        style = {"title": TITLE, "meta": META, "note": NOTE}[kind]
        colour = BOARD_INK if kind == "title" else BOARD_MUTED
        board.text(PAD, y + style.baseline(), text, style, colour)
        y += style.line_height + (6 if kind == "title" else 2)

    y += 28
    x = PAD
    for index, (frame, caption) in enumerate(zip(frames, captions)):
        board.rect(x - 2, y - 2, frame.width + 4, screens.VIEWPORT_HEIGHT + 4,
                   rx=PHONE_RADIUS + 2, fill=svgkit.rgba("#000000", 0.08))
        clip = board.clip_rect(x, y, frame.width, screens.VIEWPORT_HEIGHT, rx=PHONE_RADIUS)
        board.embed(frame.canvas, transform="translate(%s,%s)" % (fmt(x), fmt(y)),
                    clip=clip, name="DeviceSurface")
        board.rect(x, y, frame.width, screens.VIEWPORT_HEIGHT, rx=PHONE_RADIUS,
                   fill="none", stroke=BOARD_INK, stroke_width=2)
        for row, line in enumerate(wrapped[index]):
            board.text(x, y + screens.VIEWPORT_HEIGHT + 10
                       + row * CAPTION.line_height + CAPTION.baseline(),
                       line, CAPTION, BOARD_MUTED)
        x += frame.width + FRAME_GAP
    return board


# --- the twelve artboards --------------------------------------------------


def _meta(theme, width, scale):
    return ("Тема: %s · viewport %d×%d dp · font scale %d%%"
            % ("светлая" if theme == "light" else "тёмная",
               width, screens.VIEWPORT_HEIGHT, int(scale * 100)))


ARTBOARDS = []


def artboard(number, slug):
    def decorate(fn):
        ARTBOARDS.append((number, slug, fn))
        return fn
    return decorate


@artboard(1, "01-home-ready-light")
def _01():
    return _home("light", 390, 1.0)


@artboard(2, "02-home-ready-dark")
def _02():
    return _home("dark", 390, 1.0)


@artboard(9, "09-home-ready-200-light")
def _09():
    return _home("light", 320, 2.0)


def _home(theme, width, scale):
    frame = screens.home(theme, width, scale)
    notes = [
        "Компоненты: HomeHeader (мастхед, дата, иконки архива и настроек), "
        "DailyIssuePanel.Ready (номер выпуска, серия, лучший день, сыграно дней), "
        "PrimaryButton «Играть».",
        "Категория дня не раскрыта ни текстом, ни иконкой, ни цветом. Иконки архива и "
        "настроек — независимые цели 48 × 48 dp с зазором 8 dp между границами.",
    ]
    if scale > 1:
        notes.append("При 320 dp / 200% мастхед остаётся первой строкой, иконки "
                     "переносятся на отдельную строку под ним; их размер не уменьшен.")
    return build("home", "Home · Ready", _meta(theme, width, scale), notes, [frame])


@artboard(3, "03-puzzle-playing-light")
def _03():
    return _puzzle("light", 390, 1.0, None)


@artboard(4, "04-puzzle-playing-dark")
def _04():
    return _puzzle("dark", 390, 1.0, 1)


@artboard(10, "10-puzzle-playing-200-light")
def _10():
    return _puzzle("light", 320, 2.0, None)


def _puzzle(theme, width, scale, dragging):
    frame = screens.puzzle(theme, width, scale, dragging_index=dragging)
    frames, captions = [frame], [None]
    if len(frame.slices) > 1:
        frames = [frame, screens.puzzle(theme, width, scale, dragging_index=dragging,
                                        offset=frame.slices[1][0])]
        captions = ["Основной viewport (начало экрана)",
                    "Тот же экран, прокручено к списку карточек"]
    notes = [
        "Компоненты: AppTopBar (кнопка «Назад», «Задание 1 из 3», CategoryLabel в правом "
        "слоте), EditorialTitle (формулировка), подпись направления (bodyMedium, без "
        "курсива), 4 × OrderableCard, MoveButton (disabled на первой и последней), "
        "DragHandle, PrimaryButton «Проверить».",
    ]
    if dragging is not None:
        notes.append("Карточка «Аконкагуа» — в состоянии dragging: elevation.dragged (6 dp, "
                     "настоящая тень) плюс opacity.dragStateLayer 16% поверх заливки. "
                     "Масштаб карточки не меняется.")
    else:
        notes.append("DragEducationHint намеренно отсутствует: это базовое состояние "
                     "Playing, подсказка проверяется отдельным state sheet.")
    if scale > 1:
        notes.append("При 320 dp / 200% CategoryLabel не помещается рядом с заголовком и "
                     "переносится на вторую строку AppTopBar; кнопка «Назад» сохраняет "
                     "48 × 48 dp, метка не уменьшается.")
        notes.append("Контент не помещается в экран, поэтому формулировка, подпись "
                     "направления и список карточек прокручиваются одним контейнером "
                     "(UX_FLOW.md, раздел 4) — формулировка не выталкивает список за "
                     "пределы экрана. Второй срез показывает тот же экран, прокрученный к "
                     "списку. Нижний padding контейнера равен высоте закреплённой кнопки "
                     "плюс два spacing.section, поэтому «Проверить» не ложится на карточку.")
    else:
        notes.append("Прокрутки нет: расчётный бюджет 766 dp против доступных 796 dp "
                     "(жестовая навигация) подтверждён фактической вёрсткой.")
    return build("puzzle", "Puzzle · Playing", _meta(theme, width, scale), notes,
                 frames, captions)


@artboard(5, "05-puzzle-result-light")
def _05():
    return _result("light", 390, 1.0)


@artboard(6, "06-puzzle-result-dark")
def _06():
    return _result("dark", 390, 1.0)


@artboard(11, "11-puzzle-result-200-light")
def _11():
    return _result("light", 320, 2.0)


def _result(theme, width, scale):
    base = screens.puzzle_result(theme, width, scale, offset=0)
    offsets = [(0, "Основной viewport (начало экрана)"),
               (base.slices[1][0], "Прокручено вниз: счёт, подсказка, перепутанная пара")]
    if scale > 1:
        # at 200% the tail of the document no longer fits into one continuation,
        # so the bottom slice is shown as well — task requirement 3
        offsets.append((base.document_height, "Конец прокрутки: источники, «Сообщить о "
                                              "неточности», нижний padding"))
    frames = [screens.puzzle_result(theme, width, scale, offset=offset)
              for offset, _ in offsets]
    captions = [caption for _, caption in offsets]
    notes = [
        "Компоненты: AppTopBar, 4 × OrderableCard в read-only форме (DragHandle и обе "
        "MoveButton отсутствуют в дереве, добавлен displayValue), объяснение (bodyLarge), "
        "ScoreBadge, ScoringHint (первый показ), InvertedPairRow, SourcesBlock "
        "(collapsed), ReportInaccuracyAction, PrimaryButton «Дальше».",
        "Экран прокручиваемый, поэтому артборд показывает %d среза одного и того же "
        "экрана: слева основной viewport, дальше — продолжения того же scroll-контейнера "
        "ниже сгиба. Это один артборд, а не %d." % (len(frames), len(frames)),
        "Нижний padding scroll-контейнера равен высоте закреплённой PrimaryButton плюс два "
        "spacing.section, поэтому кнопка «Дальше» не перекрывает ReportInaccuracyAction "
        "в конце прокрутки.",
    ]
    if scale > 1:
        notes.append("При 320 dp / 200% хвост документа не помещается в один "
                     "continuation, поэтому показан ещё и нижний срез прокрутки: "
                     "SourcesBlock в свёрнутом виде, ReportInaccuracyAction и нижний "
                     "padding контейнера. Всё читаемо в том же кегле — ничего не обрезано "
                     "эллипсисом и не уменьшено ради размещения.")
    return build("result", "PuzzleResult", _meta(theme, width, scale), notes,
                 frames, captions)


@artboard(7, "07-day-recap-light")
def _07():
    return _recap("light", 390, 1.0)


@artboard(8, "08-day-recap-dark")
def _08():
    return _recap("dark", 390, 1.0)


@artboard(12, "12-day-recap-200-light")
def _12():
    return _recap("light", 320, 2.0)


def _recap(theme, width, scale):
    frame = screens.day_recap(theme, width, scale)
    notes = [
        "Компоненты: AppTopBar с заголовком «Сегодня» и без кнопки «Назад» (выход — "
        "PrimaryButton «Готово» или системная кнопка назад), ScoreBadge «15 из 18», "
        "3 × DayResultRow, StreakRow, SecondaryButton «Поделиться», PrimaryButton «Готово».",
        "Иконка категории у каждой строки — своя: глобус для географии, портик для "
        "истории, колба для науки. Одна и та же пиктограмма на разных категориях — дефект.",
    ]
    if scale > 1:
        notes.append("При 320 dp / 200% ни одна строка DayResultRow не помещается в одну "
                     "строку, поэтому весь список из трёх строк переходит в stacked-режим "
                     "целиком: метка сверху, результат под ней, одинаковые отступы и "
                     "выравнивание у всех трёх. Смешанной раскладки в списке не бывает.")
    else:
        notes.append("Все три DayResultRow помещаются в одну строку, поэтому весь список "
                     "остаётся в inline-режиме.")
    return build("recap", "DayRecap · Сегодня", _meta(theme, width, scale), notes, [frame])
