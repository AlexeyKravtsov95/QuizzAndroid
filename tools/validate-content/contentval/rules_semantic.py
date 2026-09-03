"""Правила одной головоломки и одного файла головоломок.

Коды ``R04A``, ``R04B``, ``R05``–``R10``, ``R12``–``R16``, ``R18C``.

Ни одно правило здесь не обращается к системному календарю: «сегодня» приходит
аргументом ``validation_date`` с границы CLI (ITERATION_4_DESIGN.md §5.2). Тест,
сравнивающий дату фикстуры с часами машины, зелен сегодня и красен через год — это
таймер, а не тест.
"""

from __future__ import annotations

from datetime import date
from typing import Iterable

from . import diagnostics as diag
from . import units
from .shuffle import shuffle


def _iso_date(value: str) -> date | None:
    """Разобрать ISO-дату календарно. ``None`` — такой даты не существует.

    ``pattern`` в схеме пропускает ``2026-13-45``, а полагаться на то, что реализация
    включила ``format``, — значит зависеть от настройки, которую легко потерять.
    """
    try:
        parsed = date.fromisoformat(value)
    except ValueError:
        return None
    # `fromisoformat` в 3.11+ понимает и расширенные формы; шаблон схемы уже сузил
    # вход до YYYY-MM-DD, но проверка обратной сборкой делает это независимо от схемы.
    return parsed if parsed.isoformat() == value else None


def _check_date(
    value: object, file_name: str, pointer: str, label: str, validation_date: date
) -> list[diag.Finding]:
    """R04A (календарно невозможная дата) и R04B (дата в будущем)."""
    if not isinstance(value, str):
        return []
    parsed = _iso_date(value)
    if parsed is None:
        return [
            diag.Finding(
                diag.R04A_DATE_NOT_CALENDAR,
                file_name,
                pointer,
                f"{label} {value!r} не является календарной датой",
            )
        ]
    if parsed > validation_date:
        return [
            diag.Finding(
                diag.R04B_DATE_IN_FUTURE,
                file_name,
                pointer,
                f"{label} {value} позже даты проверки {validation_date.isoformat()} "
                "(--validation-date)",
            )
        ]
    return []


def _sorted_card_ids(cards: list[dict], direction: str) -> list[str]:
    """Порядок карточек, вычисленный из ``sortValue`` и ``sortDirection``.

    Ключ сортировки дополнен позицией карточки, чтобы порядок был полным и
    детерминированным даже при равных значениях: сравнивать вычисленный порядок с
    записанным имеет смысл и тогда, а сами равные значения ловит R07.
    """
    indexed = list(enumerate(cards))
    indexed.sort(key=lambda pair: (pair[1]["sortValue"], pair[0]), reverse=direction == "descending")
    return [card["cardId"] for _, card in indexed]


def check_puzzle_file(
    file_name: str, document: dict, content_version: int | None, validation_date: date
) -> list[diag.Finding]:
    """Все семантические правила файла головоломок."""
    findings: list[diag.Finding] = []
    puzzles = document.get("puzzles")
    if not isinstance(puzzles, list):
        return findings

    findings.extend(_check_duplicate_ids(file_name, puzzles))

    for index, puzzle in enumerate(puzzles):
        if not isinstance(puzzle, dict):
            continue
        findings.extend(
            _check_puzzle(file_name, f"/puzzles/{index}", puzzle, content_version, validation_date)
        )
    return findings


def _check_duplicate_ids(file_name: str, puzzles: list) -> list[diag.Finding]:
    """R05: дубликат ``puzzleId`` внутри файла."""
    findings: list[diag.Finding] = []
    seen: dict[str, int] = {}
    for index, puzzle in enumerate(puzzles):
        if not isinstance(puzzle, dict):
            continue
        puzzle_id = puzzle.get("puzzleId")
        if not isinstance(puzzle_id, str):
            continue
        if puzzle_id in seen:
            findings.append(
                diag.Finding(
                    diag.R05_DUPLICATE_PUZZLE_ID,
                    file_name,
                    f"/puzzles/{index}/puzzleId",
                    f"puzzleId {puzzle_id!r} уже объявлен в /puzzles/{seen[puzzle_id]}",
                )
            )
        else:
            seen[puzzle_id] = index
    return findings


def _check_puzzle(
    file_name: str,
    base: str,
    puzzle: dict,
    content_version: int | None,
    validation_date: date,
) -> list[diag.Finding]:
    findings: list[diag.Finding] = []
    puzzle_id = puzzle.get("puzzleId")
    cards = puzzle.get("cards")
    sources = puzzle.get("sources")
    sort_key = puzzle.get("sortKey")
    direction = puzzle.get("sortDirection")

    # --- R04A / R04B: даты редакторского протокола --------------------------
    findings.extend(
        _check_date(puzzle.get("verifiedAt"), file_name, f"{base}/verifiedAt", "verifiedAt", validation_date)
    )

    # --- R09: volatile запрещён политикой, а не форматом --------------------
    if puzzle.get("volatility") == "volatile":
        findings.append(
            diag.Finding(
                diag.R09_VOLATILE_FORBIDDEN,
                file_name,
                f"{base}/volatility",
                "volatility \"volatile\" запрещена в MVP: значение устареет быстрее релиза "
                "(CONTENT_MODEL.md §4, §9)",
            )
        )

    # --- R18C: retiredIn не может быть из будущего --------------------------
    retired_in = puzzle.get("retiredIn")
    if (
        isinstance(retired_in, int)
        and not isinstance(retired_in, bool)
        and content_version is not None
        and retired_in > content_version
    ):
        findings.append(
            diag.Finding(
                diag.R18C_RETIRED_IN_FUTURE,
                file_name,
                f"{base}/retiredIn",
                f"retiredIn {retired_in} больше contentVersion пакета {content_version}: "
                "головоломка отозвана версией, которой ещё не существует",
            )
        )

    if isinstance(cards, list) and len(cards) == 4 and all(isinstance(c, dict) for c in cards):
        findings.extend(_check_values(file_name, base, puzzle, cards, sort_key, direction))
        findings.extend(_check_sources(file_name, base, puzzle, cards, sources, validation_date))

    # --- R10: стартовый порядок не должен совпадать с правильным ------------
    correct_order = puzzle.get("correctOrder")
    if (
        isinstance(puzzle_id, str)
        and isinstance(correct_order, list)
        and isinstance(cards, list)
        and all(isinstance(c, dict) and isinstance(c.get("cardId"), str) for c in cards)
    ):
        card_ids = [c["cardId"] for c in cards]
        if shuffle(puzzle_id, card_ids) == correct_order:
            findings.append(
                diag.Finding(
                    diag.R10_SHUFFLE_IDENTITY,
                    file_name,
                    f"{base}/correctOrder",
                    "стартовый порядок DeterministicShuffler совпадает с correctOrder — "
                    "головоломка открывается уже решённой; шаффлер совпадение не «чинит» "
                    "(I3-D8), поэтому меняется контент, а не алгоритм",
                )
            )

    return findings


def _check_values(
    file_name: str,
    base: str,
    puzzle: dict,
    cards: list[dict],
    sort_key: object,
    direction: object,
) -> list[diag.Finding]:
    """R06, R07, R08, R08C, R08D, R08E."""
    findings: list[diag.Finding] = []

    values = [card.get("sortValue") for card in cards]
    if not all(isinstance(v, (int, float)) and not isinstance(v, bool) for v in values):
        return findings
    numbers: list[float] = [float(v) for v in values]  # type: ignore[arg-type]

    # --- R07: равные значения внутри головоломки ----------------------------
    has_duplicates = False
    for i in range(len(numbers)):
        for j in range(i + 1, len(numbers)):
            if numbers[i] == numbers[j]:
                has_duplicates = True
                findings.append(
                    diag.Finding(
                        diag.R07_DUPLICATE_SORT_VALUE,
                        file_name,
                        f"{base}/cards/{j}/sortValue",
                        f"sortValue {numbers[j]!r} повторяет значение карточки "
                        f"{base}/cards/{i}: порядок перестаёт быть однозначным",
                    )
                )

    # --- R06: записанный порядок против вычисленного ------------------------
    correct_order = puzzle.get("correctOrder")
    if direction in ("ascending", "descending") and isinstance(correct_order, list):
        computed = _sorted_card_ids(cards, str(direction))
        if computed != correct_order:
            findings.append(
                diag.Finding(
                    diag.R06_ORDER_MISMATCH,
                    file_name,
                    f"{base}/correctOrder",
                    f"записан {correct_order}, вычислен из sortValue и "
                    f"sortDirection={direction!r} — {computed}",
                )
            )

    if not isinstance(sort_key, str) or sort_key not in units.SORT_KEYS:
        return findings

    spec = units.SORT_KEYS[sort_key]

    # --- R08C: ключ отложен до отдельного решения ---------------------------
    if spec.forbidden_reason is not None:
        findings.append(
            diag.Finding(
                diag.R08C_SORT_KEY_UNSUPPORTED,
                file_name,
                f"{base}/sortKey",
                f"sortKey {sort_key!r} запрещён в паке v1: {spec.forbidden_reason}. "
                "Единица и порог разрыва для него не определены ни одним документом",
            )
        )
        # Порог для запрещённого ключа не определён — считать R08 было бы выдумкой.
        return findings

    # --- R08D / R08E: допустимость самих значений ---------------------------
    for index, value in enumerate(numbers):
        if spec.ratio and value <= 0:
            findings.append(
                diag.Finding(
                    diag.R08D_NON_POSITIVE_RATIO,
                    file_name,
                    f"{base}/cards/{index}/sortValue",
                    f"sortValue {value!r} на ратио-шкале {sort_key!r} (единица — {spec.unit}) "
                    "обязан быть положительным: относительный порог разрыва иначе не определён",
                )
            )
        if sort_key == "year" and value == 0:
            findings.append(
                diag.Finding(
                    diag.R08E_YEAR_ZERO,
                    file_name,
                    f"{base}/cards/{index}/sortValue",
                    "нулевого года не существует: до н. э. записывается отрицательным числом",
                )
            )

    if any(spec.ratio and value <= 0 for value in numbers) or (
        sort_key == "year" and any(value == 0 for value in numbers)
    ):
        # Значения уже названы недопустимыми; разрыв между ними ничего не добавит.
        return findings

    if has_duplicates:
        # Нулевой разрыв между равными значениями — следствие R07, а не отдельное
        # нарушение правила 8: R08 здесь дублировал бы уже названную ошибку.
        return findings

    # --- R08: минимальный разрыв между соседними значениями -----------------
    ordered = sorted(numbers)
    for index in range(len(ordered) - 1):
        first, second = ordered[index], ordered[index + 1]
        if not units.min_gap_satisfied(sort_key, first, second):
            findings.append(
                diag.Finding(
                    diag.R08_MIN_GAP,
                    file_name,
                    f"{base}/cards",
                    f"соседние значения {first!r} и {second!r} нарушают правило разрыва "
                    f"для sortKey {sort_key!r}: {units.min_gap_description(sort_key)}",
                )
            )
    return findings


def _check_sources(
    file_name: str,
    base: str,
    puzzle: dict,
    cards: list[dict],
    sources: object,
    validation_date: date,
) -> list[diag.Finding]:
    """R12–R16 и даты источников (R04A/R04B)."""
    findings: list[diag.Finding] = []
    if not isinstance(sources, list) or not all(isinstance(s, dict) for s in sources):
        return findings

    declared: dict[str, dict] = {}
    for index, source in enumerate(sources):
        source_id = source.get("sourceId")
        findings.extend(
            _check_date(
                source.get("accessedAt"),
                file_name,
                f"{base}/sources/{index}/accessedAt",
                "accessedAt",
                validation_date,
            )
        )
        if not isinstance(source_id, str):
            continue
        # --- R13: дубликат sourceId внутри головоломки ----------------------
        if source_id in declared:
            findings.append(
                diag.Finding(
                    diag.R13_DUPLICATE_SOURCE_ID,
                    file_name,
                    f"{base}/sources/{index}/sourceId",
                    f"sourceId {source_id!r} объявлен в головоломке повторно",
                )
            )
            continue
        declared[source_id] = source

    used: set[str] = set()
    volatility = puzzle.get("volatility")

    for card_index, card in enumerate(cards):
        card_base = f"{base}/cards/{card_index}"
        source_ids = card.get("sourceIds")
        if not isinstance(source_ids, list):
            continue

        known: list[str] = []
        for id_index, source_id in enumerate(source_ids):
            if not isinstance(source_id, str):
                continue
            used.add(source_id)
            if source_id not in declared:
                # --- R12: карточка ссылается на необъявленный источник ------
                findings.append(
                    diag.Finding(
                        diag.R12_SOURCE_ID_UNKNOWN,
                        file_name,
                        f"{card_base}/sourceIds/{id_index}",
                        f"sourceId {source_id!r} не объявлен в sources головоломки",
                    )
                )
            else:
                known.append(source_id)

        # --- R15: хотя бы один авторитетный источник у карточки -------------
        kinds = {declared[source_id].get("kind") for source_id in known}
        if known and not (kinds & units.AUTHORITATIVE_SOURCE_KINDS):
            findings.append(
                diag.Finding(
                    diag.R15_NO_AUTHORITATIVE_SOURCE,
                    file_name,
                    f"{card_base}/sourceIds",
                    "у карточки нет источника с kind official, encyclopedia или academic: "
                    "«other» (Википедия, СМИ, блоги) годится только как дополнение",
                )
            )

        # --- R16: два источника при slow или disputed -----------------------
        needs_second = volatility == "slow" or card.get("disputed") is True
        if needs_second and len(source_ids) < 2:
            reason = "volatility \"slow\"" if volatility == "slow" else "disputed: true"
            findings.append(
                diag.Finding(
                    diag.R16_SECOND_SOURCE_REQUIRED,
                    file_name,
                    f"{card_base}/sourceIds",
                    f"источников у карточки {len(source_ids)}; при {reason} требуется "
                    "не меньше двух, и второй обязан быть независимым",
                )
            )

    # --- R14: объявленный, но никем не использованный источник --------------
    for index, source in enumerate(sources):
        source_id = source.get("sourceId")
        if isinstance(source_id, str) and source_id not in used:
            findings.append(
                diag.Finding(
                    diag.R14_UNUSED_SOURCE,
                    file_name,
                    f"{base}/sources/{index}/sourceId",
                    f"источник {source_id!r} не используется ни одной карточкой: "
                    "мёртвый источник — признак небрежно продуманного покрытия",
                )
            )
    return findings


def collect_puzzles(document: dict) -> list[dict]:
    """Список объектов головоломок файла (пустой, если конверт не той формы)."""
    puzzles = document.get("puzzles")
    if not isinstance(puzzles, list):
        return []
    return [puzzle for puzzle in puzzles if isinstance(puzzle, dict)]


def index_by_id(puzzles: Iterable[dict]) -> dict[str, dict]:
    """Индекс головоломок по ``puzzleId``; при дубликате остаётся первая (R05 уже объявлен)."""
    index: dict[str, dict] = {}
    for puzzle in puzzles:
        puzzle_id = puzzle.get("puzzleId")
        if isinstance(puzzle_id, str) and puzzle_id not in index:
            index[puzzle_id] = puzzle
    return index
