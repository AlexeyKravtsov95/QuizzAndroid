"""Порт `DeterministicShuffler` против общих векторов — Python-половина `I4-P4`.

JVM-половина (тот же файл векторов, прогнанный настоящим Kotlin-кодом) относится к
PR 4B; здесь проверяется, что порт воспроизводит зафиксированные литералы и свойства
алгоритма, на которых расходятся неаккуратные реализации: знаковая арифметика,
логический сдвиг вправо и переполнение 64 бит.

Литералы векторов сняты с **неизменённого**
``app/src/main/java/ru/poporyadku/domain/shuffle/DeterministicShuffler.kt``; три
первых совпадают с контрактом `I3-H2`, зафиксированным итерацией 3.
"""

from __future__ import annotations

import json

import pytest

from conftest import FIXTURES_DIR, PUZZLES

from contentval.shuffle import seed_of, shuffle, to_signed64

VECTORS = json.loads((FIXTURES_DIR / "shuffle-vectors.json").read_text(encoding="utf-8"))
CARD_IDS = VECTORS["cardIds"]


@pytest.mark.parametrize(
    "vector", VECTORS["vectors"], ids=[v["puzzleId"] for v in VECTORS["vectors"]]
)
def test_i4_p4_python_port_matches_shared_vectors(vector):
    """`I4-P4`: порт воспроизводит каждый общий вектор — и порядок, и seed."""
    assert shuffle(vector["puzzleId"], CARD_IDS) == vector["startOrder"]
    assert to_signed64(seed_of(vector["puzzleId"])) == vector["seed"]


def test_i4_p4_vectors_cover_the_required_cases():
    """`I4-P4`: набор векторов содержит все случаи, ради которых он существует.

    Три `tmp-*` из `I3-H2`, кириллический идентификатор, идентификатор предельной
    длины и пара, различающаяся одним байтом.
    """
    ids = [vector["puzzleId"] for vector in VECTORS["vectors"]]

    assert "tmp-geo-vysota-001" in ids
    assert "tmp-hist-izobreteniya-002" in ids
    assert "tmp-sci-otkrytiya-003" in ids
    assert any(any(ord(ch) > 127 for ch in value) for value in ids), "кириллический id"
    assert any(len(value) == 48 for value in ids), "id предельной длины"

    pairs = [
        (first, second)
        for first in ids
        for second in ids
        if first != second
        and len(first) == len(second)
        and sum(a != b for a, b in zip(first, second)) == 1
    ]
    assert pairs, "пара идентификаторов, различающихся одним байтом"


def test_i4_p4_contract_literals_from_iteration_three():
    """`I4-P4`: три вектора `I3-H2` выписаны литералами и здесь.

    Это не дублирование, а вторая точка отказа: если кто-нибудь перегенерирует
    `shuffle-vectors.json` испорченным портом, тест упадёт на литералах, а не
    примет новый файл за истину.
    """
    assert shuffle("tmp-geo-vysota-001", CARD_IDS) == ["c1", "c2", "c4", "c3"]
    assert shuffle("tmp-hist-izobreteniya-002", CARD_IDS) == ["c1", "c3", "c2", "c4"]
    assert shuffle("tmp-sci-otkrytiya-003", CARD_IDS) == ["c3", "c4", "c2", "c1"]

    assert to_signed64(seed_of("tmp-geo-vysota-001")) == 8056761665564835395
    assert to_signed64(seed_of("tmp-hist-izobreteniya-002")) == -1313988808359401040
    assert to_signed64(seed_of("tmp-sci-otkrytiya-003")) == -5719328115881941861


def test_shuffle_result_is_a_permutation():
    """Результат — перестановка входа: та же длина и то же мультимножество."""
    for vector in VECTORS["vectors"]:
        result = shuffle(vector["puzzleId"], CARD_IDS)
        assert sorted(result) == sorted(CARD_IDS)


def test_shuffle_depends_only_on_puzzle_id_bytes():
    """Ни `packId`, ни дата, ни регистр в seed не входят — иначе это другой вектор."""
    base = seed_of("tmp-geo-vysota-001")

    assert seed_of("core-ru:tmp-geo-vysota-001") != base
    assert seed_of("tmp-geo-vysota-001:2026-09-01") != base
    assert seed_of("TMP-GEO-VYSOTA-001") != base


def test_shuffle_masks_64_bit_arithmetic():
    """Seed всегда помещается в 64 бита: маскирование не потеряно.

    В Kotlin переполнение `Long` штатно, в Python целые неограничены — без явной
    маски порт разошёлся бы с оригиналом на длинных идентификаторах, и именно
    поэтому в векторах есть строка предельной длины.
    """
    for vector in VECTORS["vectors"]:
        assert 0 <= seed_of(vector["puzzleId"]) <= 0xFFFFFFFFFFFFFFFF
        assert -(2**63) <= to_signed64(seed_of(vector["puzzleId"])) < 2**63


def test_shuffle_short_inputs_are_returned_as_is():
    """Пустой список и список из одного элемента возвращаются без изменений."""
    assert shuffle("tmp-geo-vysota-001", []) == []
    assert shuffle("tmp-geo-vysota-001", ["c1"]) == ["c1"]


def test_r10_uses_the_port_on_real_fixtures():
    """Правило 10 действительно опирается на порт, а не на заглушку.

    Негативная фикстура подобрана так, что её стартовый порядок равен `correctOrder`;
    если порт разойдётся с Kotlin, совпадение исчезнет и фикстура перестанет падать.
    """
    document = json.loads(
        (FIXTURES_DIR / "invalid" / "r10-shuffle-identity" / PUZZLES).read_text(encoding="utf-8")
    )
    puzzle = document["puzzles"][0]
    card_ids = [card["cardId"] for card in puzzle["cards"]]

    assert shuffle(puzzle["puzzleId"], card_ids) == puzzle["correctOrder"]


def test_no_valid_fixture_puzzle_starts_solved():
    """Ни одна головоломка позитивных фикстур не открывается уже решённой."""
    for name in ("valid", "valid-minimal"):
        document = json.loads((FIXTURES_DIR / name / PUZZLES).read_text(encoding="utf-8"))
        for puzzle in document["puzzles"]:
            card_ids = [card["cardId"] for card in puzzle["cards"]]
            assert shuffle(puzzle["puzzleId"], card_ids) != puzzle["correctOrder"], puzzle["puzzleId"]
