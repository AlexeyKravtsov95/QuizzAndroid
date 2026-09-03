"""Находки валидатора: стабильные коды, детерминированный порядок, два формата вывода.

Контракт с тестами (ITERATION_4_DESIGN.md §7.6, I4-A33, I4-A38): стабильны **код,
файл, JSON pointer и порядок**. Текст ``message`` контрактом не является и ни в одном
тесте не сравнивается — он пишется для человека и может меняться свободно.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Iterable, Sequence

# --- Стабильные коды -------------------------------------------------------
#
# Строки совпадают с ContentViolation в приложении (PR 4B): один и тот же контент,
# нарушивший одно и то же правило, называется одинаково в CI и на устройстве.

# Правила уровня пакета (ITERATION_4_DESIGN.md §6.3).
M01_SCHEMA_VERSION_UNSUPPORTED = "M01_SCHEMA_VERSION_UNSUPPORTED"
M02_PACK_ID_MISMATCH = "M02_PACK_ID_MISMATCH"
M03_FILE_LIST_INVALID = "M03_FILE_LIST_INVALID"
M04_FILE_MISSING = "M04_FILE_MISSING"
M05_MALFORMED_JSON = "M05_MALFORMED_JSON"
M06_HASH_MISMATCH = "M06_HASH_MISMATCH"
M07_SCHEMA_VERSION_MISMATCH = "M07_SCHEMA_VERSION_MISMATCH"
M08_UNEXPECTED_FILE = "M08_UNEXPECTED_FILE"
M09_ENCODING = "M09_ENCODING"
M10_EXPECTED_VOLUME = "M10_EXPECTED_VOLUME"

# Двадцать одно правило CONTENT_MODEL.md §8 и буквенные подкоды (§6.2).
R01_SCHEMA = "R01_SCHEMA"
R02_CORRECT_ORDER_MISSING = "R02_CORRECT_ORDER_MISSING"
R03_TEXT_LENGTH = "R03_TEXT_LENGTH"
R04_VERIFICATION_MISSING = "R04_VERIFICATION_MISSING"
R04A_DATE_NOT_CALENDAR = "R04A_DATE_NOT_CALENDAR"
R04B_DATE_IN_FUTURE = "R04B_DATE_IN_FUTURE"
R05_DUPLICATE_PUZZLE_ID = "R05_DUPLICATE_PUZZLE_ID"
R06_ORDER_MISMATCH = "R06_ORDER_MISMATCH"
R07_DUPLICATE_SORT_VALUE = "R07_DUPLICATE_SORT_VALUE"
R08_MIN_GAP = "R08_MIN_GAP"
R08C_SORT_KEY_UNSUPPORTED = "R08C_SORT_KEY_UNSUPPORTED"
R08D_NON_POSITIVE_RATIO = "R08D_NON_POSITIVE_RATIO"
R08E_YEAR_ZERO = "R08E_YEAR_ZERO"
R09_VOLATILE_FORBIDDEN = "R09_VOLATILE_FORBIDDEN"
R10_SHUFFLE_IDENTITY = "R10_SHUFFLE_IDENTITY"
R11_SOURCE_IDS_EMPTY = "R11_SOURCE_IDS_EMPTY"
R12_SOURCE_ID_UNKNOWN = "R12_SOURCE_ID_UNKNOWN"
R13_DUPLICATE_SOURCE_ID = "R13_DUPLICATE_SOURCE_ID"
R14_UNUSED_SOURCE = "R14_UNUSED_SOURCE"
R15_NO_AUTHORITATIVE_SOURCE = "R15_NO_AUTHORITATIVE_SOURCE"
R16_SECOND_SOURCE_REQUIRED = "R16_SECOND_SOURCE_REQUIRED"
R17_SOURCE_LOCATOR_MISSING = "R17_SOURCE_LOCATOR_MISSING"
R18_SET_REFERENCE_MISSING = "R18_SET_REFERENCE_MISSING"
R18A_SET_REFERENCE_RETIRED = "R18A_SET_REFERENCE_RETIRED"
R18B_PUZZLE_REUSED = "R18B_PUZZLE_REUSED"
R18C_RETIRED_IN_FUTURE = "R18C_RETIRED_IN_FUTURE"
R19_SET_INDEX_SEQUENCE = "R19_SET_INDEX_SEQUENCE"
R20A_SET_CATEGORY_REPEAT = "R20A_SET_CATEGORY_REPEAT"
R20B_SET_DIFFICULTY_PROFILE = "R20B_SET_DIFFICULTY_PROFILE"
R20C_SET_OPENING_CATEGORY = "R20C_SET_OPENING_CATEGORY"
R20D_EARLY_DIFFICULTY = "R20D_EARLY_DIFFICULTY"
R21_MANIFEST_COUNTS = "R21_MANIFEST_COUNTS"

ALL_CODES: tuple[str, ...] = (
    M01_SCHEMA_VERSION_UNSUPPORTED,
    M02_PACK_ID_MISMATCH,
    M03_FILE_LIST_INVALID,
    M04_FILE_MISSING,
    M05_MALFORMED_JSON,
    M06_HASH_MISMATCH,
    M07_SCHEMA_VERSION_MISMATCH,
    M08_UNEXPECTED_FILE,
    M09_ENCODING,
    M10_EXPECTED_VOLUME,
    R01_SCHEMA,
    R02_CORRECT_ORDER_MISSING,
    R03_TEXT_LENGTH,
    R04_VERIFICATION_MISSING,
    R04A_DATE_NOT_CALENDAR,
    R04B_DATE_IN_FUTURE,
    R05_DUPLICATE_PUZZLE_ID,
    R06_ORDER_MISMATCH,
    R07_DUPLICATE_SORT_VALUE,
    R08_MIN_GAP,
    R08C_SORT_KEY_UNSUPPORTED,
    R08D_NON_POSITIVE_RATIO,
    R08E_YEAR_ZERO,
    R09_VOLATILE_FORBIDDEN,
    R10_SHUFFLE_IDENTITY,
    R11_SOURCE_IDS_EMPTY,
    R12_SOURCE_ID_UNKNOWN,
    R13_DUPLICATE_SOURCE_ID,
    R14_UNUSED_SOURCE,
    R15_NO_AUTHORITATIVE_SOURCE,
    R16_SECOND_SOURCE_REQUIRED,
    R17_SOURCE_LOCATOR_MISSING,
    R18_SET_REFERENCE_MISSING,
    R18A_SET_REFERENCE_RETIRED,
    R18B_PUZZLE_REUSED,
    R18C_RETIRED_IN_FUTURE,
    R19_SET_INDEX_SEQUENCE,
    R20A_SET_CATEGORY_REPEAT,
    R20B_SET_DIFFICULTY_PROFILE,
    R20C_SET_OPENING_CATEGORY,
    R20D_EARLY_DIFFICULTY,
    R21_MANIFEST_COUNTS,
)

# --- Порядок отображения файлов (ITERATION_4_DESIGN.md §4.7) ---------------
#
# Он совпадает с направлением ссылок (наборы ссылаются на головоломки) и НЕ обязан
# совпадать с порядком ввода-вывода: рантайм читает manifest → daily-sets, а
# отображает диагностики в этом порядке.

MANIFEST_NAME = "manifest.json"
_PREFIX_ORDER = ("puzzles-", "daily-sets-")


def file_rank(file_name: str) -> tuple[int, str]:
    """Ключ сортировки файла в порядке отображения.

    ``manifest.json`` первым, затем файл головоломок, затем файл наборов; всё
    остальное (лишние файлы для M08) — после них, по имени.
    """
    if file_name == MANIFEST_NAME:
        return (0, "")
    for index, prefix in enumerate(_PREFIX_ORDER):
        if file_name.startswith(prefix):
            return (1 + index, file_name)
    return (1 + len(_PREFIX_ORDER), file_name)


def pointer_rank(pointer: str) -> tuple[tuple[int, int, str], ...]:
    """Ключ сортировки JSON pointer: посегментно, индексы массивов — численно.

    ``/puzzles/2`` обязан сортироваться раньше ``/puzzles/10`` (I4-A33), поэтому
    сегмент, состоящий из цифр, сравнивается как число, а не как строка. Первый
    элемент кортежа сегмента различает числовые и именованные сегменты, чтобы
    сравнение int со str не выбрасывало ``TypeError``.
    """
    segments = [segment for segment in pointer.split("/") if segment != ""]
    key: list[tuple[int, int, str]] = []
    for segment in segments:
        if segment.isdigit():
            key.append((0, int(segment), ""))
        else:
            key.append((1, 0, segment))
    return tuple(key)


@dataclass(frozen=True)
class Finding:
    """Одна находка валидатора.

    :param code: стабильный код правила — часть контракта;
    :param file: имя файла пакета (без каталога) — часть контракта;
    :param pointer: JSON pointer внутри файла (``""`` — файл целиком) — часть контракта;
    :param message: человекочитаемое пояснение — **не** часть контракта.
    """

    code: str
    file: str
    pointer: str
    message: str

    def sort_key(self) -> tuple[object, ...]:
        return (file_rank(self.file), pointer_rank(self.pointer), self.code)

    def as_dict(self) -> dict[str, str]:
        return {
            "code": self.code,
            "file": self.file,
            "pointer": self.pointer,
            "message": self.message,
        }


def sort_findings(findings: Iterable[Finding]) -> list[Finding]:
    """Детерминированный порядок находок (ITERATION_4_DESIGN.md §7.6).

    1. файл — в порядке отображения;
    2. JSON pointer — посегментно, индексы массивов численно;
    3. код правила — лексикографически.

    Совпадающие по всем трём ключам находки схлопываются в одну: одно и то же
    нарушение, увиденное двумя слоями (например, календарно невозможная дата —
    ``format: date`` в схеме и семантическая проверка §5.2), — это одна ошибка
    контента, а не две. Остаётся первая по порядку обхода правил; порядок обхода
    детерминирован сам, поэтому и выбор сообщения детерминирован.

    Сортировка устойчива, поэтому две находки с одним ключом, но разными
    сообщениями (их быть не должно) сохранили бы порядок появления.
    """
    unique: dict[tuple[str, str, str], Finding] = {}
    for finding in findings:
        key = (finding.file, finding.pointer, finding.code)
        if key not in unique:
            unique[key] = finding
    return sorted(unique.values(), key=Finding.sort_key)


def counts_of(findings: Sequence[Finding]) -> dict[str, int]:
    """Счётчики по кодам в порядке ALL_CODES — стабильный порядок ключей JSON."""
    tally: dict[str, int] = {}
    for code in ALL_CODES:
        total = sum(1 for finding in findings if finding.code == code)
        if total:
            tally[code] = total
    # Код, не попавший в ALL_CODES, — ошибка программиста, но молча терять находку
    # хуже, чем показать её в конце.
    for finding in findings:
        if finding.code not in tally and finding.code not in ALL_CODES:
            tally[finding.code] = sum(1 for other in findings if other.code == finding.code)
    return tally


def format_human(findings: Sequence[Finding]) -> str:
    """Человекочитаемый вывод: одна строка на находку, тот же порядок, что у JSON."""
    lines = []
    for finding in findings:
        location = f"{finding.file}#{finding.pointer}" if finding.pointer else finding.file
        lines.append(f"{finding.code} {location} — {finding.message}")
    return "\n".join(lines)


def format_json(findings: Sequence[Finding]) -> str:
    """Машиночитаемый вывод: тот же список находок в том же порядке."""
    payload = {
        "findings": [finding.as_dict() for finding in findings],
        "counts": counts_of(findings),
    }
    return json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=False)
