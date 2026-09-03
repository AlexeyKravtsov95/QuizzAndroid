"""Ссылочная целостность наборов и правила их компоновки.

Коды ``R18``, ``R18A``, ``R18B``, ``R19``, ``R20A``–``R20D``.

Правила компоновки реализованы **буквально** по `CONTENT_MODEL.md` §6 в формализации
I4-D25: перечень из двух профилей сравнивается напрямую, а не выводится через
неравенства (``d₀ ≤ d₁ ≤ d₂ ∧ d₀ < d₂`` пропустило бы ``[1,1,2]`` и ``[2,2,3]``, то есть
расширило бы утверждённое правило). ``mixed`` не является исключением ни в ``R20A``,
ни в ``R20C``: это такое же значение поля ``category``, как ``history``.
"""

from __future__ import annotations

from . import diagnostics as diag
from . import units


def check_sets(
    file_name: str,
    document: dict,
    puzzles_by_id: dict[str, dict],
    puzzles_file: str,
) -> list[diag.Finding]:
    """Все кросс-файловые правила файла наборов."""
    findings: list[diag.Finding] = []
    sets = document.get("sets")
    if not isinstance(sets, list):
        return findings

    entries = [(index, item) for index, item in enumerate(sets) if isinstance(item, dict)]

    findings.extend(_check_index_sequence(file_name, entries))
    findings.extend(_check_references(file_name, entries, puzzles_by_id, puzzles_file))
    findings.extend(_check_reuse(file_name, entries))
    findings.extend(_check_composition(file_name, entries, puzzles_by_id))
    return findings


def _check_index_sequence(file_name: str, entries: list[tuple[int, dict]]) -> list[diag.Finding]:
    """R19: множество ``setIndex`` обязано быть ровно ``0 .. len-1``, без дыр и дубликатов.

    Непрерывность — не эстетика: на ней стоит диапазонный предикат импортёра
    (``set_index < 0 OR set_index >= setCount``), которым обнаруживается назначение
    вне пакета (ITERATION_4_DESIGN.md §3.3, свидетельство A).
    """
    findings: list[diag.Finding] = []
    seen: dict[int, int] = {}
    duplicated = False
    for position, item in entries:
        set_index = item.get("setIndex")
        if not isinstance(set_index, int) or isinstance(set_index, bool):
            continue
        if set_index in seen:
            duplicated = True
            findings.append(
                diag.Finding(
                    diag.R19_SET_INDEX_SEQUENCE,
                    file_name,
                    f"/sets/{position}/setIndex",
                    f"setIndex {set_index} уже объявлен в /sets/{seen[set_index]}",
                )
            )
        else:
            seen[set_index] = position

    if duplicated:
        # Дубликат неизбежно оставляет дыру в последовательности; вторая находка про
        # ту же ошибку ничего не добавила бы.
        return findings

    expected = set(range(len(entries)))
    actual = set(seen)
    if actual != expected:
        missing = sorted(expected - actual)
        extra = sorted(actual - expected)
        findings.append(
            diag.Finding(
                diag.R19_SET_INDEX_SEQUENCE,
                file_name,
                "/sets",
                f"последовательность setIndex обязана покрывать 0..{len(entries) - 1} "
                f"без дыр; отсутствуют {missing}, лишние {extra}",
            )
        )
    return findings


def _check_references(
    file_name: str,
    entries: list[tuple[int, dict]],
    puzzles_by_id: dict[str, dict],
    puzzles_file: str,
) -> list[diag.Finding]:
    """R18 (ссылка в никуда) и R18A (ссылка на отозванную головоломку)."""
    findings: list[diag.Finding] = []
    for position, item in entries:
        puzzle_ids = item.get("puzzleIds")
        if not isinstance(puzzle_ids, list):
            continue
        for slot, puzzle_id in enumerate(puzzle_ids):
            if not isinstance(puzzle_id, str):
                continue
            pointer = f"/sets/{position}/puzzleIds/{slot}"
            puzzle = puzzles_by_id.get(puzzle_id)
            if puzzle is None:
                findings.append(
                    diag.Finding(
                        diag.R18_SET_REFERENCE_MISSING,
                        file_name,
                        pointer,
                        f"головоломки {puzzle_id!r} нет в {puzzles_file}",
                    )
                )
            elif puzzle.get("retiredIn") is not None:
                findings.append(
                    diag.Finding(
                        diag.R18A_SET_REFERENCE_RETIRED,
                        file_name,
                        pointer,
                        f"головоломка {puzzle_id!r} отозвана (retiredIn "
                        f"{puzzle.get('retiredIn')!r}) и не может использоваться набором",
                    )
                )
    return findings


def _check_reuse(file_name: str, entries: list[tuple[int, dict]]) -> list[diag.Finding]:
    """R18B: головоломка используется в пакете не более одного раза."""
    findings: list[diag.Finding] = []
    first_use: dict[str, tuple[int, int]] = {}
    for position, item in entries:
        puzzle_ids = item.get("puzzleIds")
        if not isinstance(puzzle_ids, list):
            continue
        for slot, puzzle_id in enumerate(puzzle_ids):
            if not isinstance(puzzle_id, str):
                continue
            if puzzle_id in first_use:
                previous_position, previous_slot = first_use[puzzle_id]
                findings.append(
                    diag.Finding(
                        diag.R18B_PUZZLE_REUSED,
                        file_name,
                        f"/sets/{position}/puzzleIds/{slot}",
                        f"головоломка {puzzle_id!r} уже использована в "
                        f"/sets/{previous_position}/puzzleIds/{previous_slot}",
                    )
                )
            else:
                first_use[puzzle_id] = (position, slot)
    return findings


def _check_composition(
    file_name: str, entries: list[tuple[int, dict]], puzzles_by_id: dict[str, dict]
) -> list[diag.Finding]:
    """R20A, R20B, R20C, R20D — предикаты формализации I4-D25.

    Наборы сравниваются в порядке ``setIndex``, а не в порядке записи в файле:
    ``R20C`` говорит о соседстве в последовательности, которую видит пользователь.
    """
    findings: list[diag.Finding] = []

    ordered: list[tuple[int, int, dict]] = []
    for position, item in entries:
        set_index = item.get("setIndex")
        if isinstance(set_index, int) and not isinstance(set_index, bool):
            ordered.append((set_index, position, item))
    ordered.sort(key=lambda triple: (triple[0], triple[1]))

    previous_opening: str | None = None
    previous_set_index: int | None = None

    for set_index, position, item in ordered:
        base = f"/sets/{position}"
        puzzle_ids = item.get("puzzleIds")
        if not isinstance(puzzle_ids, list) or len(puzzle_ids) != 3:
            continue

        puzzles = [puzzles_by_id.get(pid) if isinstance(pid, str) else None for pid in puzzle_ids]
        if any(puzzle is None for puzzle in puzzles):
            # Ссылка уже названа кодом R18; компоновку неполного набора не считаем.
            previous_opening = None
            previous_set_index = set_index
            continue

        categories = [puzzle.get("category") for puzzle in puzzles]  # type: ignore[union-attr]
        difficulties = [puzzle.get("difficulty") for puzzle in puzzles]  # type: ignore[union-attr]

        # --- R20A: три попарно различные категории, mixed исключением не является
        if all(isinstance(category, str) for category in categories):
            if len(set(categories)) != 3:
                findings.append(
                    diag.Finding(
                        diag.R20A_SET_CATEGORY_REPEAT,
                        file_name,
                        f"{base}/puzzleIds",
                        f"категории набора {categories} не попарно различны; «mixed» — "
                        "такое же значение category, как «history», и исключением не является",
                    )
                )

        # --- R20B: профиль сложности принадлежит перечню из двух ------------
        if all(isinstance(d, int) and not isinstance(d, bool) for d in difficulties):
            profile = (difficulties[0], difficulties[1], difficulties[2])
            if profile not in units.ALLOWED_DIFFICULTY_PROFILES:
                allowed = ", ".join(str(list(p)) for p in units.ALLOWED_DIFFICULTY_PROFILES)
                findings.append(
                    diag.Finding(
                        diag.R20B_SET_DIFFICULTY_PROFILE,
                        file_name,
                        f"{base}/puzzleIds",
                        f"профиль сложности {list(profile)} не входит в перечень допустимых "
                        f"({allowed})",
                    )
                )

            # --- R20D: в наборах 0…6 нет difficulty 3 ----------------------
            if set_index <= units.EARLY_SET_INDEX_LIMIT and max(difficulties) > 2:  # type: ignore[type-var]
                findings.append(
                    diag.Finding(
                        diag.R20D_EARLY_DIFFICULTY,
                        file_name,
                        f"{base}/puzzleIds",
                        f"difficulty {max(difficulties)} в наборе {set_index}: через первые "
                        f"{units.EARLY_SET_INDEX_LIMIT + 1} наборов проходит каждый пользователь, "
                        "и в них допустимы только 1 и 2",
                    )
                )

        # --- R20C: категория не открывает два набора подряд -----------------
        opening = categories[0] if isinstance(categories[0], str) else None
        if (
            opening is not None
            and previous_opening is not None
            and previous_set_index is not None
            and set_index == previous_set_index + 1
            and opening == previous_opening
        ):
            findings.append(
                diag.Finding(
                    diag.R20C_SET_OPENING_CATEGORY,
                    file_name,
                    f"{base}/puzzleIds/0",
                    f"категория {opening!r} открывает и набор {previous_set_index}, и набор "
                    f"{set_index}; правило сравнивает значения, поэтому «mixed» исключением "
                    "не является",
                )
            )

        previous_opening = opening
        previous_set_index = set_index

    return findings
