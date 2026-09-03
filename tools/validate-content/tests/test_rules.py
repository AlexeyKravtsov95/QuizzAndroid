"""Двадцать одно правило контента и буквенные подкоды.

`I4-A3`–`I4-A12a`. Тесты сравнивают **коды**; текст сообщения в утверждениях не
участвует нигде.
"""

from __future__ import annotations

import pytest

from conftest import PUZZLES, SETS, VALIDATION_DATE, diag, fixture, run_cli, set_values


def codes(path: str, *extra: str, validation_date: str = VALIDATION_DATE) -> list[str]:
    run = run_cli(path, *extra, validation_date=validation_date)
    return run.codes


# --- I4-A3: по фикстуре на каждое правило и каждый подкод -------------------

RULE_FIXTURES = [
    ("r01-schema-type", diag.R01_SCHEMA),
    ("r02-no-correct-order", diag.R02_CORRECT_ORDER_MISSING),
    ("r03-prompt-too-long", diag.R03_TEXT_LENGTH),
    ("r04-no-verified-by", diag.R04_VERIFICATION_MISSING),
    ("r04a-date-not-calendar", diag.R04A_DATE_NOT_CALENDAR),
    ("r04b-date-in-future", diag.R04B_DATE_IN_FUTURE),
    ("r05-duplicate-puzzle-id", diag.R05_DUPLICATE_PUZZLE_ID),
    ("r06-order-mismatch", diag.R06_ORDER_MISMATCH),
    ("r07-duplicate-sort-value", diag.R07_DUPLICATE_SORT_VALUE),
    ("r08-gap-year", diag.R08_MIN_GAP),
    ("r08-gap-relative", diag.R08_MIN_GAP),
    ("r08c-sort-key-date", diag.R08C_SORT_KEY_UNSUPPORTED),
    ("r08d-non-positive-ratio", diag.R08D_NON_POSITIVE_RATIO),
    ("r08e-year-zero", diag.R08E_YEAR_ZERO),
    ("r09-volatile", diag.R09_VOLATILE_FORBIDDEN),
    ("r10-shuffle-identity", diag.R10_SHUFFLE_IDENTITY),
    ("r11-empty-source-ids", diag.R11_SOURCE_IDS_EMPTY),
    ("r12-unknown-source-id", diag.R12_SOURCE_ID_UNKNOWN),
    ("r13-duplicate-source-id", diag.R13_DUPLICATE_SOURCE_ID),
    ("r14-unused-source", diag.R14_UNUSED_SOURCE),
    ("r15-only-other", diag.R15_NO_AUTHORITATIVE_SOURCE),
    ("r16-slow-single-source", diag.R16_SECOND_SOURCE_REQUIRED),
    ("r16-disputed-single-source", diag.R16_SECOND_SOURCE_REQUIRED),
    ("r17-no-locator", diag.R17_SOURCE_LOCATOR_MISSING),
    ("r18-missing-reference", diag.R18_SET_REFERENCE_MISSING),
    ("r18a-retired-reference", diag.R18A_SET_REFERENCE_RETIRED),
    ("r18b-reused-puzzle", diag.R18B_PUZZLE_REUSED),
    ("r18c-retired-in-future", diag.R18C_RETIRED_IN_FUTURE),
    ("r19-index-gap", diag.R19_SET_INDEX_SEQUENCE),
    ("r19-index-duplicate", diag.R19_SET_INDEX_SEQUENCE),
    ("r20a-same-category", diag.R20A_SET_CATEGORY_REPEAT),
    ("r20a-two-mixed", diag.R20A_SET_CATEGORY_REPEAT),
    ("r20a-mixed-plus-repeat", diag.R20A_SET_CATEGORY_REPEAT),
    ("r20b-profile-112", diag.R20B_SET_DIFFICULTY_PROFILE),
    ("r20b-profile-223", diag.R20B_SET_DIFFICULTY_PROFILE),
    ("r20c-opening-category", diag.R20C_SET_OPENING_CATEGORY),
    ("r20c-opening-mixed", diag.R20C_SET_OPENING_CATEGORY),
    ("r20d-early-difficulty", diag.R20D_EARLY_DIFFICULTY),
    ("r21-wrong-counts", diag.R21_MANIFEST_COUNTS),
]


@pytest.mark.parametrize("name,expected", RULE_FIXTURES, ids=[n for n, _ in RULE_FIXTURES])
def test_i4_a3_each_rule_has_a_failing_fixture(name, expected):
    """`I4-A3`: у каждого правила и каждого подкода — своя падающая фикстура."""
    run = run_cli(fixture(f"invalid/{name}"))

    assert run.code == 1
    assert expected in run.codes


def test_i4_a3_every_letter_subcode_is_covered():
    """`I4-A3`: буквенные подкоды перечислены поимённо, а не «где-то есть»."""
    covered = {code for _, code in RULE_FIXTURES}
    subcodes = {
        diag.R04A_DATE_NOT_CALENDAR,
        diag.R04B_DATE_IN_FUTURE,
        diag.R08C_SORT_KEY_UNSUPPORTED,
        diag.R08D_NON_POSITIVE_RATIO,
        diag.R08E_YEAR_ZERO,
        diag.R18A_SET_REFERENCE_RETIRED,
        diag.R18B_PUZZLE_REUSED,
        diag.R18C_RETIRED_IN_FUTURE,
        diag.R20A_SET_CATEGORY_REPEAT,
        diag.R20B_SET_DIFFICULTY_PROFILE,
        diag.R20C_SET_OPENING_CATEGORY,
        diag.R20D_EARLY_DIFFICULTY,
    }
    assert subcodes <= covered


# --- I4-A4: правило 6 на обеих сортировках ----------------------------------


@pytest.mark.parametrize("direction", ["ascending", "descending"])
def test_i4_a4_order_mismatch_on_both_directions(pack, direction):
    """`I4-A4`: `R06` ловит расхождение и при `ascending`, и при `descending`."""

    def correct(documents):
        set_values(documents, 0, "year", [1802, 1854, 1903, 1961], direction)

    assert codes(pack(correct)) == []

    def broken(documents):
        correct(documents)
        order = documents[PUZZLES]["puzzles"][0]["correctOrder"]
        order[0], order[1] = order[1], order[0]

    assert codes(pack(broken)) == [diag.R06_ORDER_MISMATCH]


# --- I4-A5: границы правила 8 ------------------------------------------------


@pytest.mark.parametrize(
    "values,expected",
    [
        ([1900, 1902, 1950, 2000], []),
        ([1900, 1901, 1950, 2000], [diag.R08_MIN_GAP]),
    ],
    ids=["ровно 2 года — проходит", "1 год — падает"],
)
def test_i4_a5_year_gap_boundary(pack, values, expected):
    """`I4-A5`: ровно два года проходят, один год — нет."""
    assert codes(pack(lambda d: set_values(d, 0, "year", values))) == expected


@pytest.mark.parametrize(
    "values,expected",
    [
        ([9700, 10000, 20000, 40000], []),
        ([9701, 10000, 20000, 40000], [diag.R08_MIN_GAP]),
    ],
    ids=["ровно 3,00 % — проходит", "2,99 % — падает"],
)
def test_i4_a5_relative_gap_boundary(pack, values, expected):
    """`I4-A5`: ровно 3,00 % от большего значения пары проходят, 2,99 % — нет."""
    assert codes(pack(lambda d: set_values(d, 0, "height", values))) == expected


# --- I4-A6: правило 16 -------------------------------------------------------


def _two_sources(puzzle: dict) -> None:
    puzzle["sources"] = [
        {
            "sourceId": "s1",
            "title": "Синтетический справочник фикстуры валидатора, издание первое",
            "kind": "encyclopedia",
            "url": "https://example.invalid/fixture/reference-1",
            "accessedAt": "2026-08-20",
        },
        {
            "sourceId": "s2",
            "title": "Синтетический официальный отчёт фикстуры валидатора",
            "kind": "official",
            "reference": "Фикстура валидатора, отчёт № 2, с. 12",
            "accessedAt": "2026-08-20",
        },
    ]


def test_i4_a6_slow_with_two_sources_passes(pack):
    """`I4-A6`: `slow` с двумя источниками на каждой карточке проходит."""

    def mutate(documents):
        puzzle = documents[PUZZLES]["puzzles"][0]
        puzzle["volatility"] = "slow"
        _two_sources(puzzle)
        for card in puzzle["cards"]:
            card["sourceIds"] = ["s1", "s2"]

    assert codes(pack(mutate)) == []


def test_i4_a6_disputed_with_two_sources_passes(pack):
    """`I4-A6`: спорная карточка с двумя источниками проходит."""

    def mutate(documents):
        puzzle = documents[PUZZLES]["puzzles"][0]
        _two_sources(puzzle)
        puzzle["cards"][0]["disputed"] = True
        puzzle["cards"][0]["sourceIds"] = ["s1", "s2"]
        for card in puzzle["cards"][1:]:
            card["sourceIds"] = ["s1", "s2"]

    assert codes(pack(mutate)) == []


@pytest.mark.parametrize(
    "name", ["r16-slow-single-source", "r16-disputed-single-source"]
)
def test_i4_a6_single_source_fails(name):
    """`I4-A6`: каждый из двух случаев с одним источником падает."""
    assert codes(fixture(f"invalid/{name}")) == [diag.R16_SECOND_SOURCE_REQUIRED]


# --- I4-A7: профили сложности ------------------------------------------------


def _profile(documents, values: tuple[int, int, int]) -> None:
    by_id = {p["puzzleId"]: p for p in documents[PUZZLES]["puzzles"]}
    for puzzle_id, difficulty in zip(documents[SETS]["sets"][0]["puzzleIds"], values):
        by_id[puzzle_id]["difficulty"] = difficulty


@pytest.mark.parametrize("profile", [(1, 2, 2), (1, 2, 3)])
def test_i4_a7_allowed_profiles_pass(pack, profile):
    """`I4-A7`: перечень допустимых профилей — ровно `{[1,2,2], [1,2,3]}`.

    Профиль `[1,2,3]` проверяется в наборе 7 фикстуры `valid/`: в наборе 0 его
    запретила бы `R20D`, и тест перестал бы проверять `R20B`.
    """
    if profile == (1, 2, 2):
        assert codes(pack(lambda d: _profile(d, profile))) == []
    else:
        def mutate(documents):
            by_id = {p["puzzleId"]: p for p in documents[PUZZLES]["puzzles"]}
            for puzzle_id, difficulty in zip(documents[SETS]["sets"][7]["puzzleIds"], profile):
                by_id[puzzle_id]["difficulty"] = difficulty

        assert codes(pack(mutate, base="valid")) == []


@pytest.mark.parametrize(
    "profile", [(1, 1, 2), (2, 2, 3), (2, 2, 2), (1, 3, 3), (2, 1, 3), (3, 1, 2)]
)
def test_i4_a7_forbidden_profiles_fail(pack, profile):
    """`I4-A7`: перечень сравнивается напрямую, поэтому `[1,1,2]` и `[2,2,3]` падают.

    Вывод через неравенства (`d₀ ≤ d₁ ≤ d₂ ∧ d₀ < d₂`) пропустил бы оба и тем самым
    расширил бы утверждённое правило `CONTENT_MODEL.md` §6 (I4-D25).
    """
    found = codes(pack(lambda d: _profile(d, profile)))
    assert diag.R20B_SET_DIFFICULTY_PROFILE in found


# --- I4-A7a: категории внутри набора ----------------------------------------


def _categories(documents, values: tuple[str, str, str]) -> None:
    by_id = {p["puzzleId"]: p for p in documents[PUZZLES]["puzzles"]}
    for puzzle_id, category in zip(documents[SETS]["sets"][0]["puzzleIds"], values):
        by_id[puzzle_id]["category"] = category


@pytest.mark.parametrize(
    "categories,expected",
    [
        (("geography", "history", "science"), []),
        (("geography", "geography", "science"), [diag.R20A_SET_CATEGORY_REPEAT]),
        (("mixed", "mixed", "science"), [diag.R20A_SET_CATEGORY_REPEAT]),
        (("mixed", "history", "history"), [diag.R20A_SET_CATEGORY_REPEAT]),
        (("mixed", "history", "science"), []),
    ],
    ids=["три различные", "две одинаковые", "mixed+mixed", "mixed+повтор", "mixed допустим"],
)
def test_i4_a7a_set_categories(pack, categories, expected):
    """`I4-A7a`: `mixed` — такое же значение `category`, как `history`.

    Он совместим с любой парой **различных** категорий, но не разрешает ни второй
    `mixed`, ни повтор другой категории рядом с собой.
    """
    assert codes(pack(lambda d: _categories(d, categories))) == expected


# --- I4-A7b: открывающая категория ------------------------------------------


def _openings(documents, first: str, second: str) -> None:
    by_id = {p["puzzleId"]: p for p in documents[PUZZLES]["puzzles"]}
    by_id[documents[SETS]["sets"][0]["puzzleIds"][0]]["category"] = first
    by_id[documents[SETS]["sets"][1]["puzzleIds"][0]]["category"] = second


@pytest.mark.parametrize(
    "first,second,expected",
    [
        ("geography", "history", []),
        ("geography", "geography", [diag.R20C_SET_OPENING_CATEGORY]),
        ("mixed", "mixed", [diag.R20C_SET_OPENING_CATEGORY]),
    ],
    ids=["разные открывающие", "одинаковые открывающие", "mixed дважды подряд"],
)
def test_i4_a7b_opening_category(tmp_path, first, second, expected):
    """`I4-A7b`: правило сравнивает значения, поэтому `mixed` исключением не является."""
    from conftest import build_pack

    path = str(
        build_pack(
            tmp_path,
            base="invalid/r18b-reused-puzzle",
            mutate=lambda d: (_openings(d, first, second), _fix_reuse(d)),
        )
    )
    assert codes(path) == expected


def _fix_reuse(documents) -> None:
    """Вернуть базе `pair` уникальные ссылки: тест проверяет `R20C`, а не `R18B`."""
    documents[SETS]["sets"][1]["puzzleIds"][1] = "sci-para-005"


# --- I4-A7c: первые семь наборов --------------------------------------------


def test_i4_a7c_profile_123_is_rejected_in_early_sets(pack):
    """`I4-A7c`: `[1,2,3]` допустим по `R20B`, но в наборе 6 запрещён `R20D`."""

    def mutate(documents):
        by_id = {p["puzzleId"]: p for p in documents[PUZZLES]["puzzles"]}
        for puzzle_id, difficulty in zip(documents[SETS]["sets"][6]["puzzleIds"], (1, 2, 3)):
            by_id[puzzle_id]["difficulty"] = difficulty

    assert codes(pack(mutate, base="valid")) == [diag.R20D_EARLY_DIFFICULTY]


def test_i4_a7c_profile_123_is_allowed_from_set_seven():
    """`I4-A7c`: в наборе 7 фикстуры `valid/` профиль `[1,2,3]` уже допустим."""
    assert codes(fixture("valid")) == []


# --- I4-A8: даты при фиксированной --validation-date ------------------------


@pytest.mark.parametrize("value", ["2026-02-30", "2026-13-01"])
def test_i4_a8_non_calendar_dates(pack, value):
    """`I4-A8`: `pattern` такие даты пропускает, `R04A` — нет."""
    found = codes(pack(lambda d: d[PUZZLES]["puzzles"][0].__setitem__("verifiedAt", value)))
    assert found == [diag.R04A_DATE_NOT_CALENDAR]


def test_i4_a8_date_after_validation_date(pack):
    """`I4-A8`: дата позже `--validation-date` — `R04B`."""
    path = pack(lambda d: d[PUZZLES]["puzzles"][0].__setitem__("verifiedAt", "2026-09-04"))
    assert codes(path, validation_date="2026-09-03") == [diag.R04B_DATE_IN_FUTURE]


def test_i4_a8_date_equal_to_validation_date_passes(pack):
    """`I4-A8`: дата, равная `--validation-date`, — не будущее."""
    path = pack(lambda d: d[PUZZLES]["puzzles"][0].__setitem__("verifiedAt", "2026-09-03"))
    assert codes(path, validation_date="2026-09-03") == []


def test_i4_a8_source_dates_are_checked_too(pack):
    """`I4-A8`: `accessedAt` источников проверяется тем же правилом, что `verifiedAt`."""
    path = pack(
        lambda d: d[PUZZLES]["puzzles"][0]["sources"][0].__setitem__("accessedAt", "2027-01-01")
    )
    assert codes(path, validation_date="2026-09-03") == [diag.R04B_DATE_IN_FUTURE]


def test_i4_a8_rules_never_read_the_system_clock():
    """`I4-A8`: правила и тесты не обращаются к системному календарю.

    Проверяется формой кода, а не наблюдением: единственное допустимое обращение —
    значение по умолчанию для `--validation-date` на границе CLI (§5.2).
    """
    from pathlib import Path

    from conftest import TOOL_DIR

    # Маркеры собираются по частям намеренно: иначе этот тест нашёл бы сам себя и
    # проверял бы собственный исходник вместо правил.
    forbidden = ("date." + "today", "datetime." + "now", "datetime." + "today")
    offenders = []
    for path in sorted((TOOL_DIR / "contentval").glob("*.py")) + sorted(
        (TOOL_DIR / "tests").glob("*.py")
    ):
        text = Path(path).read_text(encoding="utf-8")
        for marker in forbidden:
            if marker in text and path.name != "cli.py":
                offenders.append(f"{path.name}: {marker}")
    assert offenders == []

    cli_text = (TOOL_DIR / "contentval" / "cli.py").read_text(encoding="utf-8")
    assert cli_text.count(forbidden[0] + "()") == 1, "ровно одно обращение — умолчание аргумента"


# --- I4-A9: R20C сквозь границу батчей --------------------------------------


def test_i4_a9_opening_category_across_batch_boundary(pack):
    """`I4-A9`: набор 7 сверяется с набором 6, даже если они пришли разными батчами.

    Валидатор всегда видит файл целиком, поэтому граница батча для него не
    существует — это и проверяется.
    """

    def mutate(documents):
        by_id = {p["puzzleId"]: p for p in documents[PUZZLES]["puzzles"]}
        opening_six = documents[SETS]["sets"][6]["puzzleIds"][0]
        opening_seven = documents[SETS]["sets"][7]["puzzleIds"][0]
        by_id[opening_seven]["category"] = by_id[opening_six]["category"]

    found = codes(pack(mutate, base="valid"))
    assert diag.R20C_SET_OPENING_CATEGORY in found


# --- I4-A10: пять отложенных sortKey ----------------------------------------


@pytest.mark.parametrize(
    "sort_key", ["date", "population", "temperature", "latitude", "longitude"]
)
def test_i4_a10_deferred_sort_keys_are_rejected(pack, sort_key):
    """`I4-A10`: все пять отложенных ключей отвергаются `R08C` с указанием причины.

    Ключи остаются в `enum` схемы: запрет — политика пака v1, а не изменение формата.
    """
    run = run_cli(pack(lambda d: d[PUZZLES]["puzzles"][0].__setitem__("sortKey", sort_key)))
    assert run.codes == [diag.R08C_SORT_KEY_UNSUPPORTED]
    assert run.findings[0]["pointer"] == "/puzzles/0/sortKey"
    assert run.findings[0]["message"], "причина запрета обязана быть названа"


@pytest.mark.parametrize(
    "sort_key,values",
    [
        ("year", [1802, 1854, 1903, 1961]),
        ("height", [1200, 2400, 3600, 4800]),
        ("depth", [150, 400, 900, 2100]),
        ("length", [800, 1900, 4200, 9000]),
        ("distance", [2500, 6000, 15000, 40000]),
        ("area", [5000, 12000, 30000, 75000]),
        ("mass", [12, 45, 130, 400]),
        ("speed", [30, 90, 240, 700]),
        ("duration", [60, 180, 600, 1800]),
    ],
)
def test_i4_a10_allowed_sort_keys_pass(pack, sort_key, values):
    """`I4-A10`: девять разрешённых ключей проходят."""
    assert codes(pack(lambda d: set_values(d, 0, sort_key, values))) == []


# --- I4-A11: значения ратио-шкал и нулевой год ------------------------------


def test_i4_a11_non_positive_ratio_value(pack):
    """`I4-A11`: неположительное значение на ратио-шкале — `R08D`."""
    found = codes(pack(lambda d: set_values(d, 0, "height", [-1000, 500, 1500, 3000])))
    assert found == [diag.R08D_NON_POSITIVE_RATIO]


def test_i4_a11_zero_ratio_value(pack):
    """`I4-A11`: ноль на ратио-шкале — тоже `R08D`: разрыв от нуля не определён."""
    found = codes(pack(lambda d: set_values(d, 0, "area", [0, 500, 1500, 3000])))
    assert found == [diag.R08D_NON_POSITIVE_RATIO]


def test_i4_a11_year_zero(pack):
    """`I4-A11`: нулевого года не существует — `R08E`."""
    found = codes(pack(lambda d: set_values(d, 0, "year", [-100, 0, 100, 200])))
    assert found == [diag.R08E_YEAR_ZERO]


def test_i4_a11_negative_year_is_allowed(pack):
    """`I4-A11`: до н. э. — отрицательное число, и это не нарушение."""
    assert codes(pack(lambda d: set_values(d, 0, "year", [-776, -500, -100, 100]))) == []


# --- I4-A12 / I4-A12a --------------------------------------------------------


def test_i4_a12_puzzle_used_twice(pack):
    """`I4-A12`: одна головоломка в двух наборах — `R18B`."""
    assert codes(fixture("invalid/r18b-reused-puzzle")) == [diag.R18B_PUZZLE_REUSED]


def test_i4_a12a_retired_in_future_fails(pack):
    """`I4-A12a`: `retiredIn` больше `contentVersion` — `R18C`."""
    assert codes(fixture("invalid/r18c-retired-in-future")) == [diag.R18C_RETIRED_IN_FUTURE]


def test_i4_a12a_retired_in_current_version_passes():
    """`I4-A12a`: `retiredIn`, равный текущей версии, — законный отзыв.

    Фикстура `valid/` содержит ровно такую головоломку и даёт ноль находок.
    """
    assert codes(fixture("valid")) == []


# --- R08 не прячется за R07, R08D и R08E ------------------------------------


def test_r08_is_reported_for_pairs_without_diagnosed_values(pack):
    """Разрыв — свойство пары, поэтому один дубликат не отменяет проверку остальных.

    В `[100, 100, 200, 202]` дубликат даёт `R07`, но пара 200/202 нарушает порог
    3 % независимо: 0,03 · 202 = 6,06 > 2. Ранний выход по всей головоломке прятал
    бы её за уже названной ошибкой.
    """
    found = codes(pack(lambda d: set_values(d, 0, "height", [100, 100, 200, 202])))

    # Порядок задан сортировкой указателей: R08 указывает на массив /cards целиком,
    # R07 — на конкретную карточку, и `/cards` короче, поэтому идёт раньше.
    assert found == [diag.R08_MIN_GAP, diag.R07_DUPLICATE_SORT_VALUE]


def test_r08_is_reported_alongside_r08d(pack):
    """То же для `R08D`: пара из двух допустимых значений проверяется как обычно."""
    found = codes(pack(lambda d: set_values(d, 0, "height", [-50, 200, 202, 5000])))

    assert found == [diag.R08_MIN_GAP, diag.R08D_NON_POSITIVE_RATIO]


def test_r08_is_reported_alongside_r08e(pack):
    """То же для `R08E`: нулевой год не отменяет проверку разрыва между годами."""
    found = codes(pack(lambda d: set_values(d, 0, "year", [0, 100, 101, 500])))

    assert found == [diag.R08_MIN_GAP, diag.R08E_YEAR_ZERO]


def test_pairs_touching_a_diagnosed_value_stay_suppressed(pack):
    """Пара, содержащая уже названное значение, второй находки не порождает.

    Нулевой разрыв между двумя вхождениями дубликата — следствие `R07`, а не
    отдельное нарушение правила 8.
    """
    assert codes(pack(lambda d: set_values(d, 0, "year", [1802, 1802, 1903, 1961]))) == [
        diag.R07_DUPLICATE_SORT_VALUE
    ]
    assert codes(pack(lambda d: set_values(d, 0, "height", [-1000, 500, 1500, 3000]))) == [
        diag.R08D_NON_POSITIVE_RATIO
    ]


# --- Верхние границы принадлежат правилам, а не схеме ------------------------


def test_retired_in_upper_bound_belongs_to_r18c(pack):
    """Огромный `retiredIn` даёт `R18C`, а не `R01`.

    Выдуманная верхняя граница в схеме не просто дублировала бы правило, а
    **подменяла** его: находка схемы блокирует семантические правила файла, и точный
    `R18C` заменился бы общим `R01`.
    """
    # Головоломка берётся из пула вне наборов: у используемой набором сработал бы ещё
    # и R18A, и тест перестал бы проверять то, ради чего написан.
    found = codes(
        pack(
            lambda d: d[PUZZLES]["puzzles"][-1].__setitem__("retiredIn", 100001),
            base="invalid/r18c-retired-in-future",
        )
    )

    assert found == [diag.R18C_RETIRED_IN_FUTURE]


def test_set_index_upper_bound_belongs_to_r19(pack):
    """Огромный `setIndex` даёт `R19`, а не `R01`, по той же причине."""
    found = codes(pack(lambda d: d[SETS]["sets"][0].__setitem__("setIndex", 100001)))

    assert found == [diag.R19_SET_INDEX_SEQUENCE]


def test_schema_keeps_bounds_it_actually_owns():
    """Границы, у которых нет другого владельца, из схемы не убраны.

    Проверка нужна, чтобы «убрать выдуманное maximum» не превратилось в «ослабить
    схему»: `difficulty` ограничена 1..3 по `CONTENT_MODEL.md` §4, а размеры массивов
    и длины строк не проверяет ни одно правило.
    """
    import json
    from pathlib import Path

    from conftest import TOOL_DIR

    puzzles = json.loads(
        (Path(TOOL_DIR) / "schema" / "puzzles.schema.json").read_text(encoding="utf-8")
    )
    puzzle = puzzles["$defs"]["puzzle"]["properties"]

    assert puzzle["difficulty"] == {"type": "integer", "minimum": 1, "maximum": 3}
    assert puzzle["cards"]["minItems"] == 4 and puzzle["cards"]["maxItems"] == 4
    assert puzzle["sources"]["minItems"] == 1 and puzzle["sources"]["maxItems"] == 12
    assert puzzle["prompt"]["minLength"] == 10 and puzzle["prompt"]["maxLength"] == 90
