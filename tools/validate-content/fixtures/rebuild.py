#!/usr/bin/env python3
"""Пересборка каталога фикстур валидатора.

    python3 tools/validate-content/fixtures/rebuild.py            # пересобрать всё
    python3 tools/validate-content/fixtures/rebuild.py --check    # только сверить

**Фикстуры синтетические и не являются продуктовым контентом.** Тексты, значения,
источники и ссылки (домен ``example.invalid`` зарезервирован RFC 6761 и никогда не
резолвится) придуманы ради проверки правил и ничего не утверждают о мире. Настоящий
контент придёт батчами 4C-1…4C-5 по редакторскому протоколу.

**Что скрипт делает и чего не делает.** Он собирает пакеты из спецификации,
механически проставляя *производные* значения — ``files[].sha256``, ``setCount`` и
``puzzleCount`` — по фактическим байтам файлов. Он **не «чинит» фикстуры, намеренно
нарушающие манифест, хеш или счётчики**: их повреждение объявлено в спецификации
(``manifest_damage``) и применяется **после** автозаполнения, поэтому каждая пересборка
воспроизводит нарушение, а не устраняет его. Список таких фикстур — :data:`DELIBERATE`.

Нормализация всех файлов (кроме негативных фикстур ``M09``, которые её и нарушают):
UTF-8 без BOM, отступ 2 пробела, ``ensure_ascii=False``, ровно один завершающий ``\\n``.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import shutil
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

FIXTURES_DIR = Path(__file__).resolve().parent
TOOL_DIR = FIXTURES_DIR.parent
sys.path.insert(0, str(TOOL_DIR))

from contentval import diagnostics as diag  # noqa: E402
from contentval.shuffle import seed_of, shuffle, to_signed64  # noqa: E402

# --- Постоянные фикстур -----------------------------------------------------

PACK_ID = "core-ru"
PACK_TITLE = "Синтетическая фикстура валидатора"
SCHEMA_VERSION = 1
CONTENT_VERSION = 1
VERIFIED_AT = "2026-08-20"
VERIFIED_BY = "fixture-editor"

#: Дата, относительно которой считаются «будущие» даты во всех тестах (I4-A8).
#: Фиксирована в файле, а не берётся из календаря: иначе тест был бы таймером.
VALIDATION_DATE = "2026-09-03"

PUZZLES_FILE = "puzzles-001.json"
DAILY_SETS_FILE = "daily-sets-001.json"
MANIFEST_FILE = "manifest.json"

CARD_IDS = ["c1", "c2", "c3", "c4"]
CARD_TITLES = ["Образец А", "Образец Б", "Образец В", "Образец Г"]

CATEGORY_PREFIX = {
    "geography": "geo",
    "history": "hist",
    "science": "sci",
    "nature": "nat",
    "culture": "cult",
    "russia": "rus",
    "mixed": "mix",
}

#: Русское название признака и суффикс `displayValue` для разрешённых `sortKey`.
SORT_KEY_RU = {
    "year": ("год", "год"),
    "height": ("высота", "м"),
    "depth": ("глубина", "м"),
    "length": ("длина", "м"),
    "distance": ("расстояние", "м"),
    "area": ("площадь", "км²"),
    "mass": ("масса", "кг"),
    "speed": ("скорость", "км/ч"),
    "duration": ("длительность", "с"),
}

#: Значения с заведомо достаточными разрывами: год — ≥ 2, ратио-шкалы — ≫ 3 %.
SORT_KEY_VALUES = {
    "year": [1802, 1854, 1903, 1961],
    "height": [1200, 2400, 3600, 4800],
    "area": [5000, 12000, 30000, 75000],
    "duration": [60, 180, 600, 1800],
    "depth": [150, 400, 900, 2100],
    "mass": [12, 45, 130, 400],
    "speed": [30, 90, 240, 700],
    "length": [800, 1900, 4200, 9000],
    "distance": [2500, 6000, 15000, 40000],
}

SOURCE_ENCYCLOPEDIA = {
    "sourceId": "s1",
    "title": "Синтетический справочник фикстуры валидатора, издание первое",
    "kind": "encyclopedia",
    "url": "https://example.invalid/fixture/reference-1",
    "accessedAt": VERIFIED_AT,
}
SOURCE_OFFICIAL = {
    "sourceId": "s2",
    "title": "Синтетический официальный отчёт фикстуры валидатора",
    "kind": "official",
    "reference": "Фикстура валидатора, отчёт № 2, с. 12",
    "accessedAt": VERIFIED_AT,
}
SOURCE_OTHER = {
    "sourceId": "s3",
    "title": "Синтетический неавторитетный источник фикстуры",
    "kind": "other",
    "url": "https://example.invalid/fixture/other-3",
    "accessedAt": VERIFIED_AT,
}


# --- Построение головоломки -------------------------------------------------


def make_puzzle(
    puzzle_id: str,
    category: str,
    difficulty: int,
    sort_key: str = "year",
    direction: str = "ascending",
    volatility: str = "stable",
    disputed_card: int | None = None,
    retired_in: int | None = None,
    values: list[float] | None = None,
    correct_order: list[str] | None = None,
) -> dict:
    """Собрать синтетическую головоломку, удовлетворяющую всем 21 правилу.

    ``correctOrder`` вычисляется из значений и направления — ровно так, как его
    потом пересчитает правило ``R06``; передать его явно можно только для фикстур,
    которые это правило и нарушают.
    """
    numbers = list(values) if values is not None else list(SORT_KEY_VALUES[sort_key])
    label_ru, suffix = SORT_KEY_RU[sort_key]

    needs_pair = volatility == "slow"
    sources = [copy.deepcopy(SOURCE_ENCYCLOPEDIA)]
    if needs_pair or disputed_card is not None:
        sources.append(copy.deepcopy(SOURCE_OFFICIAL))

    cards = []
    for index, card_id in enumerate(CARD_IDS):
        source_ids = ["s1", "s2"] if (needs_pair or index == disputed_card) else ["s1"]
        card = {
            "cardId": card_id,
            "title": CARD_TITLES[index],
            "subtitle": f"Синтетическая карточка {index + 1}",
            "sortValue": numbers[index],
            "displayValue": f"{numbers[index]:g} {suffix}",
            "sourceIds": source_ids,
        }
        if index == disputed_card:
            card["disputed"] = True
            card["note"] = "Значение помечено спорным ради проверки правила R16"
        cards.append(card)

    if correct_order is None:
        order = sorted(range(4), key=lambda i: numbers[i], reverse=direction == "descending")
        correct_order = [CARD_IDS[i] for i in order]

    return {
        "puzzleId": puzzle_id,
        "category": category,
        "prompt": f"Расположите образцы по признаку «{label_ru}»",
        "sortKey": sort_key,
        "sortDirection": direction,
        "directionLabel": (
            "Сверху — наименьшее" if direction == "ascending" else "Сверху — наибольшее"
        ),
        "cards": cards,
        "correctOrder": correct_order,
        "explanation": (
            "Синтетическая головоломка фикстуры валидатора: порядок следует из значений "
            f"признака «{label_ru}», выписанных в поле sortValue каждой карточки."
        ),
        "sources": sources,
        "volatility": volatility,
        "difficulty": difficulty,
        "verifiedAt": VERIFIED_AT,
        "verifiedBy": VERIFIED_BY,
        "retiredIn": retired_in,
    }


def without_shuffle_identity(puzzle: dict) -> dict:
    """Развернуть направление сортировки, если стартовый порядок совпал с правильным.

    Позитивная фикстура обязана проходить и правило 10, а совпадение — редкая, но
    вполне возможная случайность: шаффлер её не «чинит» (I3-D8), поэтому чинить
    приходится контенту. Разворот направления меняет ``correctOrder`` на обратный,
    и совпасть с ним стартовый порядок уже не может — он равен прямому.
    """
    card_ids = [card["cardId"] for card in puzzle["cards"]]
    if shuffle(puzzle["puzzleId"], card_ids) != puzzle["correctOrder"]:
        return puzzle
    flipped = "descending" if puzzle["sortDirection"] == "ascending" else "ascending"
    puzzle["sortDirection"] = flipped
    puzzle["directionLabel"] = (
        "Сверху — наименьшее" if flipped == "ascending" else "Сверху — наибольшее"
    )
    puzzle["correctOrder"] = list(reversed(puzzle["correctOrder"]))
    assert shuffle(puzzle["puzzleId"], card_ids) != puzzle["correctOrder"]
    return puzzle


# --- Пакет ------------------------------------------------------------------


@dataclass
class Pack:
    """Собираемый пакет до записи на диск."""

    puzzles: list[dict]
    sets: list[dict]
    schema_version: int = SCHEMA_VERSION
    content_version: int = CONTENT_VERSION
    pack_id: str = PACK_ID
    puzzles_pack_id: str | None = None
    sets_pack_id: str | None = None
    puzzles_schema_version: int | None = None
    sets_schema_version: int | None = None
    declared_files: list[str] | None = None
    #: Файлы, объявленные манифестом, но намеренно не записанные на диск (M04).
    omit_files: set[str] = field(default_factory=set)
    #: Дополнительные физические файлы каталога (M08).
    extra_files: dict[str, str] = field(default_factory=dict)
    #: Порча байтов уже сериализованного файла — применяется ДО подсчёта sha256,
    #: поэтому M09/M05 проверяются без каскадного M06.
    raw_damage: dict[str, Callable[[bytes], bytes]] = field(default_factory=dict)
    #: Порча манифеста ПОСЛЕ автозаполнения производных значений (M06, R21).
    manifest_damage: Callable[[dict], None] | None = None

    def envelope_puzzles(self) -> dict:
        return {
            "schemaVersion": (
                self.puzzles_schema_version
                if self.puzzles_schema_version is not None
                else self.schema_version
            ),
            "packId": self.puzzles_pack_id or self.pack_id,
            "puzzles": self.puzzles,
        }

    def envelope_sets(self) -> dict:
        return {
            "schemaVersion": (
                self.sets_schema_version
                if self.sets_schema_version is not None
                else self.schema_version
            ),
            "packId": self.sets_pack_id or self.pack_id,
            "sets": self.sets,
        }


def _dump(document: object) -> bytes:
    """Нормализованная сериализация: 2 пробела, кириллица как есть, один ``\\n``."""
    return (json.dumps(document, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def write_pack(root: Path, pack: Pack) -> None:
    """Записать пакет в каталог, пересчитав производные значения манифеста."""
    if root.exists():
        shutil.rmtree(root)
    root.mkdir(parents=True)

    payload = {
        PUZZLES_FILE: _dump(pack.envelope_puzzles()),
        DAILY_SETS_FILE: _dump(pack.envelope_sets()),
    }
    for name, damage in pack.raw_damage.items():
        if name in payload:
            payload[name] = damage(payload[name])

    declared = pack.declared_files
    if declared is None:
        declared = [PUZZLES_FILE, DAILY_SETS_FILE]

    files = []
    for name in declared:
        raw = payload.get(name)
        digest = hashlib.sha256(raw).hexdigest() if raw is not None else "0" * 64
        files.append({"path": name, "sha256": digest})

    manifest = {
        "schemaVersion": pack.schema_version,
        "contentVersion": pack.content_version,
        "packId": pack.pack_id,
        "packTitle": PACK_TITLE,
        "setCount": len(pack.sets),
        "puzzleCount": len(pack.puzzles),
        "files": files,
    }
    if pack.manifest_damage is not None:
        pack.manifest_damage(manifest)

    manifest_raw = _dump(manifest)
    if MANIFEST_FILE in pack.raw_damage:
        manifest_raw = pack.raw_damage[MANIFEST_FILE](manifest_raw)
    (root / MANIFEST_FILE).write_bytes(manifest_raw)

    for name, raw in payload.items():
        if name in pack.omit_files:
            continue
        (root / name).write_bytes(raw)

    for name, text in pack.extra_files.items():
        (root / name).write_bytes(text.encode("utf-8"))


# --- Базовые позитивные пакеты ---------------------------------------------

#: Восемь наборов фикстуры `valid/`: категория и сложность каждого слота.
#: Открывающие категории geo → hist → sci → nat → cult → rus → mix → geo не
#: повторяются подряд (R20C), внутри набора категории попарно различны (R20A),
#: профиль [1,2,2] в наборах 0–6 и [1,2,3] в наборе 7 (R20B + R20D).
VALID_SET_PLAN: list[list[tuple[str, int]]] = [
    [("geography", 1), ("history", 2), ("science", 2)],
    [("history", 1), ("science", 2), ("nature", 2)],
    [("science", 1), ("nature", 2), ("culture", 2)],
    [("nature", 1), ("culture", 2), ("russia", 2)],
    [("culture", 1), ("russia", 2), ("mixed", 2)],
    [("russia", 1), ("mixed", 2), ("history", 2)],
    [("mixed", 1), ("history", 2), ("geography", 2)],
    [("geography", 1), ("science", 2), ("russia", 3)],
]

#: Четыре разрешённых `sortKey` фикстуры `valid/` — по кругу.
VALID_SORT_KEYS = ["year", "height", "area", "duration"]


def build_valid() -> Pack:
    """`valid/` — 8 наборов, 25 головоломок: 24 активные плюс одна отозванная вне наборов.

    Двадцать пять, а не двадцать четыре: ``R18B`` требует, чтобы головоломка набора
    встречалась не больше раза, а ``R18A`` запрещает ссылаться на отозванную — значит
    отозванная обязана быть лишней в пуле (ITERATION_4_DESIGN.md §6.1).
    """
    puzzles: list[dict] = []
    sets: list[dict] = []
    number = 0

    for set_index, slots in enumerate(VALID_SET_PLAN):
        ids = []
        for slot, (category, difficulty) in enumerate(slots):
            number += 1
            puzzle_id = f"{CATEGORY_PREFIX[category]}-obrazec-{number:03d}"
            sort_key = VALID_SORT_KEYS[number % len(VALID_SORT_KEYS)]
            direction = "ascending" if number % 2 else "descending"
            # Одна головоломка `slow` (каждая карточка с двумя источниками) и одна
            # со спорной карточкой — обе ветки правила R16 представлены в позитиве.
            volatility = "slow" if number == 2 else "stable"
            disputed = 1 if number == 6 else None
            puzzles.append(
                without_shuffle_identity(
                    make_puzzle(
                        puzzle_id,
                        category,
                        difficulty,
                        sort_key=sort_key,
                        direction=direction,
                        volatility=volatility,
                        disputed_card=disputed,
                    )
                )
            )
            ids.append(puzzle_id)
        sets.append({"setIndex": set_index, "puzzleIds": ids})

    # Двадцать пятая: отозвана текущей версией контента, ни одним набором не
    # используется. Ветка R18A/R18C проверяется на ней в позитиве.
    puzzles.append(
        without_shuffle_identity(
            make_puzzle(
                "cult-otozvannyy-025",
                "culture",
                2,
                sort_key="year",
                retired_in=CONTENT_VERSION,
            )
        )
    )
    return Pack(puzzles=puzzles, sets=sets)


def build_minimal() -> Pack:
    """`valid-minimal/` — один набор и три активные головоломки."""
    puzzles = [
        without_shuffle_identity(make_puzzle("geo-minimal-001", "geography", 1, sort_key="year")),
        without_shuffle_identity(
            make_puzzle("hist-minimal-002", "history", 2, sort_key="height", direction="descending")
        ),
        without_shuffle_identity(make_puzzle("sci-minimal-003", "science", 2, sort_key="area")),
    ]
    sets = [{"setIndex": 0, "puzzleIds": [p["puzzleId"] for p in puzzles]}]
    return Pack(puzzles=puzzles, sets=sets)


def build_pair() -> Pack:
    """Два набора — база для правил, которым нужна пара соседей (R18B, R19, R20C)."""
    puzzles = [
        without_shuffle_identity(make_puzzle("geo-para-001", "geography", 1, sort_key="year")),
        without_shuffle_identity(make_puzzle("hist-para-002", "history", 2, sort_key="height")),
        without_shuffle_identity(make_puzzle("sci-para-003", "science", 2, sort_key="area")),
        without_shuffle_identity(make_puzzle("hist-para-004", "history", 1, sort_key="duration")),
        without_shuffle_identity(
            make_puzzle("sci-para-005", "science", 2, sort_key="year", direction="descending")
        ),
        without_shuffle_identity(
            make_puzzle("nat-para-006", "nature", 2, sort_key="height", direction="descending")
        ),
    ]
    sets = [
        {"setIndex": 0, "puzzleIds": ["geo-para-001", "hist-para-002", "sci-para-003"]},
        {"setIndex": 1, "puzzleIds": ["hist-para-004", "sci-para-005", "nat-para-006"]},
    ]
    return Pack(puzzles=puzzles, sets=sets)


BASES: dict[str, Callable[[], Pack]] = {
    "valid": build_valid,
    "minimal": build_minimal,
    "pair": build_pair,
}


# --- Поиск идентификатора для фикстуры R10 ----------------------------------


#: Идентификатор фикстуры `R10`. Подбирать его перебором не нужно: годится любой,
#: потому что `correctOrder` фикстуры делается **равным** стартовому порядку, а
#: значения подбираются под него, чтобы правило 6 при этом молчало.
SHUFFLE_IDENTITY_ID = "geo-shaffler-001"


def shuffle_identity_order() -> list[str]:
    """Стартовый порядок фикстуры `R10` — он же её `correctOrder`.

    Фикстура существует именно потому, что шаффлер совпадение **не «чинит»**
    (I3-D8): правило `R10` обязано ловить такую головоломку в контенте, а не
    исправлять её в алгоритме.
    """
    return shuffle(SHUFFLE_IDENTITY_ID, CARD_IDS)


# --- Негативные фикстуры ----------------------------------------------------


@dataclass
class Case:
    """Одна негативная фикстура: как её испортить и что валидатор обязан сказать."""

    name: str
    base: str
    mutate: Callable[[Pack], None]
    codes: list[str]
    why: str
    runtime: list[str] | None = None


def _set_field(pack: Pack, index: int, field_name: str, value: object) -> None:
    pack.puzzles[index][field_name] = value


def _long_prompt() -> str:
    text = "Расположите образцы фикстуры по признаку, описанному в этом длинном тексте"
    return (text + " " + "и" * (91 - len(text) - 1))[:91]


def _r10_case(pack: Pack) -> None:
    puzzle_id, order = SHUFFLE_IDENTITY_ID, shuffle_identity_order()
    values = [0, 0, 0, 0]
    for rank, card_id in enumerate(order):
        values[CARD_IDS.index(card_id)] = SORT_KEY_VALUES["year"][rank]
    pack.puzzles[0] = make_puzzle(
        puzzle_id, "geography", 1, sort_key="year", values=values, correct_order=list(order)
    )
    pack.sets[0]["puzzleIds"][0] = puzzle_id


CASES: list[Case] = [
    # --- Схема и её специализированные коды ---------------------------------
    Case(
        "r01-schema-type",
        "minimal",
        lambda p: _set_field(p, 0, "difficulty", "1"),
        [diag.R01_SCHEMA],
        "difficulty строкой вместо целого — общий отказ схемы",
        runtime=[diag.R01_SCHEMA],
    ),
    Case(
        "r02-no-correct-order",
        "minimal",
        lambda p: p.puzzles[0].pop("correctOrder"),
        [diag.R02_CORRECT_ORDER_MISSING],
        "нет обязательного correctOrder — сверять вычисленный порядок не с чем",
        runtime=[diag.R01_SCHEMA],
    ),
    Case(
        "r03-prompt-too-long",
        "minimal",
        lambda p: _set_field(p, 0, "prompt", _long_prompt()),
        [diag.R03_TEXT_LENGTH],
        "prompt длиной 91 символ при пределе 90",
    ),
    Case(
        "r04-no-verified-by",
        "minimal",
        lambda p: p.puzzles[0].pop("verifiedBy"),
        [diag.R04_VERIFICATION_MISSING],
        "нет verifiedBy — редакторский протокол не зафиксирован",
    ),
    Case(
        "r04a-date-not-calendar",
        "minimal",
        lambda p: _set_field(p, 0, "verifiedAt", "2026-02-30"),
        [diag.R04A_DATE_NOT_CALENDAR],
        "30 февраля не существует; pattern такую дату пропускает",
    ),
    Case(
        "r04b-date-in-future",
        "minimal",
        lambda p: _set_field(p, 0, "verifiedAt", "2027-06-01"),
        [diag.R04B_DATE_IN_FUTURE],
        "дата проверки позже --validation-date фикстур",
    ),
    Case(
        "r05-duplicate-puzzle-id",
        "minimal",
        lambda p: p.puzzles.append(
            make_puzzle("geo-minimal-001", "geography", 2, sort_key="duration")
        ),
        [diag.R05_DUPLICATE_PUZZLE_ID],
        "второй объект с уже занятым puzzleId лежит в пуле вне наборов",
    ),
    Case(
        "r06-order-mismatch",
        "minimal",
        lambda p: _set_field(p, 0, "correctOrder", ["c2", "c1", "c3", "c4"]),
        [diag.R06_ORDER_MISMATCH],
        "записанный порядок не совпадает с вычисленным из sortValue",
    ),
    Case(
        "r07-duplicate-sort-value",
        "minimal",
        lambda p: p.puzzles[0]["cards"][1].__setitem__(
            "sortValue", p.puzzles[0]["cards"][0]["sortValue"]
        ),
        [diag.R07_DUPLICATE_SORT_VALUE],
        "две карточки с одинаковым значением — порядок перестаёт быть однозначным",
    ),
    Case(
        "r08-gap-year",
        "minimal",
        lambda p: _replace_values(p, 0, "year", [1900, 1901, 1950, 2000], "ascending"),
        [diag.R08_MIN_GAP],
        "разрыв в один год при пороге в два",
    ),
    Case(
        "r08-gap-relative",
        "minimal",
        lambda p: _replace_values(p, 1, "height", [1000, 1029, 2000, 3000], "descending"),
        [diag.R08_MIN_GAP],
        "разрыв 2,9 % при пороге 3 % от большего значения пары",
    ),
    Case(
        "r08c-sort-key-date",
        "minimal",
        lambda p: _set_field(p, 0, "sortKey", "date"),
        [diag.R08C_SORT_KEY_UNSUPPORTED],
        "sortKey date отложен: единица не зафиксирована ни одним документом",
    ),
    Case(
        "r08d-non-positive-ratio",
        "minimal",
        lambda p: _replace_values(p, 1, "height", [-1000, 500, 1500, 3000], "descending"),
        [diag.R08D_NON_POSITIVE_RATIO],
        "неположительное значение на ратио-шкале: относительный порог не определён",
    ),
    Case(
        "r08e-year-zero",
        "minimal",
        lambda p: _replace_values(p, 0, "year", [-100, 0, 100, 200], "ascending"),
        [diag.R08E_YEAR_ZERO],
        "нулевого года не существует",
    ),
    Case(
        "r09-volatile",
        "minimal",
        lambda p: _set_field(p, 0, "volatility", "volatile"),
        [diag.R09_VOLATILE_FORBIDDEN],
        "volatile остаётся в enum формата, но запрещён политикой MVP",
    ),
    Case(
        "r10-shuffle-identity",
        "minimal",
        _r10_case,
        [diag.R10_SHUFFLE_IDENTITY],
        "подобранный puzzleId: стартовый порядок совпал с правильным",
    ),
    Case(
        "r11-empty-source-ids",
        "minimal",
        lambda p: p.puzzles[0]["cards"][0].__setitem__("sourceIds", []),
        [diag.R11_SOURCE_IDS_EMPTY],
        "у карточки пустой массив источников",
        runtime=[diag.R01_SCHEMA],
    ),
    Case(
        "r12-unknown-source-id",
        "minimal",
        lambda p: p.puzzles[0]["cards"][0].__setitem__("sourceIds", ["s9"]),
        [diag.R12_SOURCE_ID_UNKNOWN],
        "карточка ссылается на источник, которого нет в sources",
    ),
    Case(
        "r13-duplicate-source-id",
        "minimal",
        lambda p: p.puzzles[0]["sources"].append(
            {
                "sourceId": "s1",
                "title": "Повторно объявленный источник фикстуры валидатора",
                "kind": "official",
                "reference": "Фикстура валидатора, дубликат идентификатора s1",
                "accessedAt": VERIFIED_AT,
            }
        ),
        [diag.R13_DUPLICATE_SOURCE_ID],
        "два источника с одним sourceId внутри головоломки",
    ),
    Case(
        "r14-unused-source",
        "minimal",
        lambda p: p.puzzles[0]["sources"].append(copy.deepcopy(SOURCE_OFFICIAL)),
        [diag.R14_UNUSED_SOURCE],
        "мёртвый источник: объявлен и не используется ни одной карточкой",
    ),
    Case(
        "r15-only-other",
        "minimal",
        lambda p: _only_other_source(p, 0),
        [diag.R15_NO_AUTHORITATIVE_SOURCE],
        "единственный источник карточки имеет kind other",
    ),
    Case(
        "r16-slow-single-source",
        "minimal",
        lambda p: _slow_with_single_source(p, 0),
        [diag.R16_SECOND_SOURCE_REQUIRED],
        "volatility slow при одном источнике у первой карточки",
    ),
    Case(
        "r16-disputed-single-source",
        "minimal",
        lambda p: _disputed_with_single_source(p, 0),
        [diag.R16_SECOND_SOURCE_REQUIRED],
        "disputed: true при одном источнике у карточки",
    ),
    Case(
        "r17-no-locator",
        "minimal",
        lambda p: p.puzzles[0]["sources"][0].pop("url"),
        [diag.R17_SOURCE_LOCATOR_MISSING],
        "у источника нет ни url, ни reference",
    ),
    # --- Ссылки и компоновка наборов ----------------------------------------
    Case(
        "r18-missing-reference",
        "minimal",
        lambda p: p.sets[0]["puzzleIds"].__setitem__(2, "sci-otsutstvuet-999"),
        [diag.R18_SET_REFERENCE_MISSING],
        "набор ссылается на головоломку, которой нет в пуле",
    ),
    Case(
        "r18a-retired-reference",
        "minimal",
        lambda p: _set_field(p, 2, "retiredIn", CONTENT_VERSION),
        [diag.R18A_SET_REFERENCE_RETIRED],
        "набор ссылается на отозванную головоломку",
    ),
    Case(
        "r18b-reused-puzzle",
        "pair",
        lambda p: p.sets[1]["puzzleIds"].__setitem__(1, "sci-para-003"),
        [diag.R18B_PUZZLE_REUSED],
        "одна головоломка использована в двух наборах пакета",
    ),
    Case(
        "r18c-retired-in-future",
        "minimal",
        lambda p: p.puzzles.append(
            make_puzzle(
                "cult-otozvannyy-004",
                "culture",
                2,
                sort_key="duration",
                retired_in=CONTENT_VERSION + 1,
            )
        ),
        [diag.R18C_RETIRED_IN_FUTURE],
        "retiredIn ссылается на версию контента, которой ещё не существует",
    ),
    Case(
        "r19-index-gap",
        "pair",
        lambda p: p.sets[1].__setitem__("setIndex", 2),
        [diag.R19_SET_INDEX_SEQUENCE],
        "дыра в последовательности setIndex: 0 и 2",
    ),
    Case(
        "r19-index-duplicate",
        "pair",
        lambda p: p.sets[1].__setitem__("setIndex", 0),
        [diag.R19_SET_INDEX_SEQUENCE],
        "два набора с одним setIndex",
    ),
    Case(
        "r20a-same-category",
        "minimal",
        lambda p: _set_field(p, 1, "category", "geography"),
        [diag.R20A_SET_CATEGORY_REPEAT],
        "две одинаковые категории в наборе",
    ),
    Case(
        "r20a-two-mixed",
        "minimal",
        lambda p: (_set_field(p, 0, "category", "mixed"), _set_field(p, 1, "category", "mixed")),
        [diag.R20A_SET_CATEGORY_REPEAT],
        "mixed + mixed + science: mixed не является исключением из R20A",
    ),
    Case(
        "r20a-mixed-plus-repeat",
        "minimal",
        lambda p: (_set_field(p, 0, "category", "mixed"), _set_field(p, 2, "category", "history")),
        [diag.R20A_SET_CATEGORY_REPEAT],
        "mixed + history + history: соседство с mixed не разрешает повтор категории",
    ),
    Case(
        "r20b-profile-112",
        "minimal",
        lambda p: _set_field(p, 1, "difficulty", 1),
        [diag.R20B_SET_DIFFICULTY_PROFILE],
        "профиль [1,1,2] не входит в перечень {[1,2,2],[1,2,3]}",
    ),
    Case(
        "r20b-profile-223",
        "valid",
        lambda p: _profile_223(p),
        [diag.R20B_SET_DIFFICULTY_PROFILE],
        "профиль [2,2,3] в наборе 7, где difficulty 3 разрешена: падает только R20B",
    ),
    Case(
        "r20c-opening-category",
        "pair",
        lambda p: _set_field(p, 3, "category", "geography"),
        [diag.R20C_SET_OPENING_CATEGORY],
        "одна категория открывает наборы 0 и 1",
    ),
    Case(
        "r20c-opening-mixed",
        "pair",
        lambda p: (_set_field(p, 0, "category", "mixed"), _set_field(p, 3, "category", "mixed")),
        [diag.R20C_SET_OPENING_CATEGORY],
        "mixed открывает два соседних набора: правило сравнивает значения",
    ),
    Case(
        "r20d-early-difficulty",
        "minimal",
        lambda p: _set_field(p, 2, "difficulty", 3),
        [diag.R20D_EARLY_DIFFICULTY],
        "difficulty 3 в наборе 0: профиль [1,2,3] допустим, но не в первых семи",
    ),
    Case(
        "r21-wrong-counts",
        "minimal",
        lambda p: _damage_counts(p),
        [diag.R21_MANIFEST_COUNTS],
        "puzzleCount манифеста не совпадает с фактическим",
    ),
    # --- Правила уровня пакета ----------------------------------------------
    Case(
        "m01-schema-version",
        "minimal",
        lambda p: _bump_schema_version(p, 2),
        [diag.M01_SCHEMA_VERSION_UNSUPPORTED],
        "schemaVersion 2 при поддерживаемой 1 — во всех трёх файлах, чтобы не задеть M07",
    ),
    Case(
        "m02-pack-mismatch",
        "minimal",
        lambda p: setattr(p, "puzzles_pack_id", "drugoy-pak"),
        [diag.M02_PACK_ID_MISMATCH],
        "packId файла головоломок расходится с манифестом",
    ),
    Case(
        "m03-traversal",
        "minimal",
        lambda p: setattr(p, "declared_files", ["../secret.json", DAILY_SETS_FILE]),
        [diag.M03_FILE_LIST_INVALID, diag.M03_FILE_LIST_INVALID],
        "обход каталога в files[].path; вторая находка — нет файла с префиксом puzzles-",
    ),
    Case(
        "m03-absolute-path",
        "minimal",
        lambda p: setattr(p, "declared_files", ["/etc/passwd", DAILY_SETS_FILE]),
        [diag.M03_FILE_LIST_INVALID, diag.M03_FILE_LIST_INVALID],
        "абсолютный путь в files[].path",
    ),
    Case(
        "m03-subdirectory",
        "minimal",
        lambda p: setattr(p, "declared_files", ["sub/puzzles-001.json", DAILY_SETS_FILE]),
        [diag.M03_FILE_LIST_INVALID, diag.M03_FILE_LIST_INVALID],
        "подкаталог в files[].path: формат допускает ровно три файла без вложенности",
    ),
    Case(
        "m03-uppercase-extension",
        "minimal",
        lambda p: setattr(p, "declared_files", ["puzzles-001.JSON", DAILY_SETS_FILE]),
        [diag.M03_FILE_LIST_INVALID, diag.M03_FILE_LIST_INVALID],
        "другой регистр расширения не проходит закрытый шаблон",
    ),
    Case(
        "m03-duplicate-path",
        "minimal",
        lambda p: setattr(p, "declared_files", [PUZZLES_FILE, PUZZLES_FILE]),
        [diag.M03_FILE_LIST_INVALID, diag.M03_FILE_LIST_INVALID],
        "дубликат пути; вторая находка — нет файла с префиксом daily-sets-",
    ),
    Case(
        "m03-three-files",
        "minimal",
        lambda p: setattr(
            p, "declared_files", [PUZZLES_FILE, DAILY_SETS_FILE, "puzzles-002.json"]
        ),
        [diag.M03_FILE_LIST_INVALID],
        "три объявления вместо двух: шарды не поддерживаются. Обе находки (лишний "
        "элемент и два файла с префиксом puzzles-) указывают на /files и схлопываются "
        "в одну — это одна ошибка списка файлов",
    ),
    Case(
        "m04-missing-file",
        "minimal",
        lambda p: p.omit_files.add(DAILY_SETS_FILE),
        [diag.M04_FILE_MISSING],
        "объявленный манифестом файл отсутствует в каталоге",
    ),
    Case(
        "m05-malformed",
        "minimal",
        lambda p: p.raw_damage.__setitem__(PUZZLES_FILE, _break_json),
        [diag.M05_MALFORMED_JSON],
        "лишняя запятая делает файл неразбираемым; sha256 считается уже от порченых байтов",
    ),
    Case(
        "m06-hash-mismatch",
        "minimal",
        lambda p: setattr(p, "manifest_damage", _zero_first_hash),
        [diag.M06_HASH_MISMATCH],
        "sha256 в манифесте не соответствует байтам файла",
    ),
    Case(
        "m06-hash-uppercase",
        "minimal",
        lambda p: setattr(p, "manifest_damage", _uppercase_first_hash),
        [diag.M06_HASH_MISMATCH],
        "верхний регистр хеша: сравнение точное, требуются 64 строчных hex-символа",
    ),
    Case(
        "m07-schema-mismatch",
        "minimal",
        lambda p: setattr(p, "puzzles_schema_version", 2),
        [diag.M07_SCHEMA_VERSION_MISMATCH],
        "schemaVersion файла головоломок расходится с манифестом",
    ),
    Case(
        "m08-extra-file",
        "minimal",
        lambda p: p.extra_files.__setitem__(
            "puzzles-002.json",
            '{\n  "schemaVersion": 1,\n  "packId": "core-ru",\n  "puzzles": []\n}\n',
        ),
        [diag.M08_UNEXPECTED_FILE],
        "необъявленный файл в каталоге: шарда быть не может",
    ),
    Case(
        "m09-bom",
        "minimal",
        lambda p: p.raw_damage.__setitem__(PUZZLES_FILE, lambda raw: b"\xef\xbb\xbf" + raw),
        [diag.M09_ENCODING],
        "BOM в начале файла; хеш посчитан от байтов с BOM, поэтому M06 не срабатывает",
    ),
    Case(
        "m09-no-trailing-newline",
        "minimal",
        lambda p: p.raw_damage.__setitem__(DAILY_SETS_FILE, lambda raw: raw.rstrip(b"\n")),
        [diag.M09_ENCODING],
        "нет завершающего перевода строки: единственная фикстура вне нормализации",
    ),
]

#: Фикстуры, у которых манифест, хеш или счётчики испорчены **намеренно**.
#: Пересборка обязана воспроизводить порчу, а не устранять её.
DELIBERATE = {
    "r21-wrong-counts",
    "m01-schema-version",
    "m02-pack-mismatch",
    "m03-traversal",
    "m03-absolute-path",
    "m03-subdirectory",
    "m03-uppercase-extension",
    "m03-duplicate-path",
    "m03-three-files",
    "m04-missing-file",
    "m05-malformed",
    "m06-hash-mismatch",
    "m06-hash-uppercase",
    "m07-schema-mismatch",
    "m08-extra-file",
    "m09-bom",
    "m09-no-trailing-newline",
}


def _replace_values(pack: Pack, index: int, sort_key: str, values: list[float], direction: str) -> None:
    puzzle = pack.puzzles[index]
    replacement = make_puzzle(
        puzzle["puzzleId"],
        puzzle["category"],
        puzzle["difficulty"],
        sort_key=sort_key,
        direction=direction,
        values=values,
    )
    pack.puzzles[index] = replacement


def _only_other_source(pack: Pack, index: int) -> None:
    puzzle = pack.puzzles[index]
    puzzle["sources"] = [copy.deepcopy(SOURCE_ENCYCLOPEDIA), copy.deepcopy(SOURCE_OTHER)]
    puzzle["cards"][0]["sourceIds"] = ["s3"]


def _slow_with_single_source(pack: Pack, index: int) -> None:
    puzzle = pack.puzzles[index]
    puzzle["volatility"] = "slow"
    puzzle["sources"] = [copy.deepcopy(SOURCE_ENCYCLOPEDIA), copy.deepcopy(SOURCE_OFFICIAL)]
    for card in puzzle["cards"]:
        card["sourceIds"] = ["s1", "s2"]
    puzzle["cards"][0]["sourceIds"] = ["s1"]


def _disputed_with_single_source(pack: Pack, index: int) -> None:
    puzzle = pack.puzzles[index]
    puzzle["cards"][0]["disputed"] = True
    puzzle["cards"][0]["sourceIds"] = ["s1"]


def _profile_223(pack: Pack) -> None:
    """Профиль [2,2,3] в наборе 7 фикстуры `valid/`: R20D там не применяется."""
    ids = pack.sets[7]["puzzleIds"]
    by_id = {p["puzzleId"]: p for p in pack.puzzles}
    by_id[ids[0]]["difficulty"] = 2


def _damage_counts(pack: Pack) -> None:
    def damage(manifest: dict) -> None:
        manifest["puzzleCount"] = 99

    pack.manifest_damage = damage


def _bump_schema_version(pack: Pack, version: int) -> None:
    pack.schema_version = version
    pack.puzzles_schema_version = version
    pack.sets_schema_version = version


def _break_json(raw: bytes) -> bytes:
    return raw.replace(b'{\n  "schemaVersion"', b'{,\n  "schemaVersion"', 1)


def _zero_first_hash(manifest: dict) -> None:
    manifest["files"][0]["sha256"] = "0" * 64


def _uppercase_first_hash(manifest: dict) -> None:
    manifest["files"][0]["sha256"] = manifest["files"][0]["sha256"].upper()


# --- Сборка каталога --------------------------------------------------------


def build_all(root: Path) -> dict[str, dict]:
    """Записать все фикстуры и вернуть содержимое ``expectations.json``."""
    entries: dict[str, dict] = {}

    write_pack(root / "valid", build_valid())
    entries["valid"] = {"cli": []}
    write_pack(root / "valid-minimal", build_minimal())
    entries["valid-minimal"] = {"cli": []}

    invalid_root = root / "invalid"
    if invalid_root.exists():
        shutil.rmtree(invalid_root)
    invalid_root.mkdir(parents=True)

    for case in CASES:
        pack = BASES[case.base]()
        case.mutate(pack)
        write_pack(invalid_root / case.name, pack)
        entry: dict[str, object] = {"cli": sorted(case.codes), "why": case.why}
        if case.runtime is not None:
            entry["runtime"] = sorted(case.runtime)
        entries[f"invalid/{case.name}"] = entry

    return entries


def build_shuffle_vectors() -> dict:
    """Общие векторы правила 10 — литералы, снятые с настоящего Kotlin-кода.

    Значения получены прогоном **неизменённого**
    ``app/src/main/java/ru/poporyadku/domain/shuffle/DeterministicShuffler.kt``
    на JVM; три первых совпадают с литералами теста ``I3-H2``, зафиксированными
    итерацией 3. Файл читают и Python-порт (``I4-P4`` в этом PR), и JVM-harness
    (``I4-P4`` в PR 4B).
    """
    vectors = [
        ("tmp-geo-vysota-001", "контракт I3-H2: временная головоломка «география»"),
        ("tmp-hist-izobreteniya-002", "контракт I3-H2: временная головоломка «история»"),
        ("tmp-sci-otkrytiya-003", "контракт I3-H2: временная головоломка «наука»"),
        ("головоломка-кириллица-001", "кириллица: seed считается по UTF-8-байтам, не по символам"),
        (
            "geo-predelnaya-dlina-identifikatorov-obrazec-001",
            "идентификатор предельной длины 48 символов",
        ),
        ("geo-obrazec-baytovoy-pary-001", "пара, различающаяся одним байтом: первый"),
        ("geo-obrazec-baytovoy-pary-002", "пара, различающаяся одним байтом: второй"),
    ]
    return {
        "source": (
            "app/src/main/java/ru/poporyadku/domain/shuffle/DeterministicShuffler.kt "
            "(ADR-010, I3-D7 — I3-D9); значения сняты с настоящего Kotlin-кода"
        ),
        "cardIds": CARD_IDS,
        "vectors": [
            {
                "puzzleId": puzzle_id,
                "seed": to_signed64(seed_of(puzzle_id)),
                "startOrder": shuffle(puzzle_id, CARD_IDS),
                "why": why,
            }
            for puzzle_id, why in vectors
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Пересборка фикстур валидатора контента")
    parser.add_argument(
        "--check",
        action="store_true",
        help="не писать файлы, только проверить, что пересборка ничего не изменила бы",
    )
    args = parser.parse_args()

    if args.check:
        # Проверка выполняется тестом test_expectations.py; отдельный режим нужен для
        # ручного запуска перед коммитом.
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            expected = build_all(Path(tmp))
        actual = json.loads((FIXTURES_DIR / "expectations.json").read_text(encoding="utf-8"))
        drift = sorted(set(expected) ^ set(actual["fixtures"]))
        if drift:
            print("расхождение состава фикстур:", drift)
            return 1
        print("состав фикстур совпадает с expectations.json")
        return 0

    entries = build_all(FIXTURES_DIR)

    expectations = {
        "_comment": (
            "Единственный источник ожидаемых кодов для фикстур валидатора. Колонка cli "
            "проверяется тестами PR 4A; колонка runtime — контракт полного пути "
            "ContentPackReader → ContentValidator и заполняется в PR 4B (там, где она "
            "совпала бы с cli, её опускают). Порядок кодов — отсортированный, тексты "
            "сообщений контрактом не являются. Поле why — пояснение для человека."
        ),
        "validationDate": VALIDATION_DATE,
        "fixtures": dict(sorted(entries.items())),
    }
    (FIXTURES_DIR / "expectations.json").write_bytes(_dump(expectations))
    (FIXTURES_DIR / "shuffle-vectors.json").write_bytes(_dump(build_shuffle_vectors()))

    print(f"собрано фикстур: 2 позитивные + {len(CASES)} негативных")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
