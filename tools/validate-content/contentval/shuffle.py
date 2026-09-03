"""Порт ``domain/shuffle/DeterministicShuffler.kt`` на Python (ADR-010, I4-D26).

Единственное место дублирования алгоритма во всём проекте. Оно закрыто общими
векторами ``fixtures/shuffle-vectors.json``, полученными из **настоящего** Kotlin-кода
и сверяемыми обеими сторонами (I4-P4).

Три вещи, на которых расходятся неаккуратные порты, и как они закрыты здесь:

* **переполнение.** В Kotlin ``Long`` переполняется штатно, в Python целые
  неограничены — поэтому каждая 64-битная операция маскируется ``& _MASK64``;
* **знак.** ``seedOf`` возвращает знаковый ``Long``; здесь состояние хранится
  беззнаковым, а сравнение с Kotlin выполняется через :func:`to_signed64`;
* **логический сдвиг вправо.** Kotlin ``ushr`` не размножает знаковый бит; на
  беззнаковом представлении обычный ``>>`` делает ровно это.

Никакого ``hash()``, ``random``, даты, ``packId`` или ``setIndex``: результат зависит
только от UTF-8-байтов ``puzzleId`` и от длины списка.
"""

from __future__ import annotations

from typing import Sequence

_MASK64 = 0xFFFFFFFFFFFFFFFF

# 0xCBF29CE484222325 / 0x100000001B3 — те же константы, что в Kotlin-файле.
_FNV_OFFSET_BASIS = 0xCBF29CE484222325
_FNV_PRIME = 0x100000001B3

# 0x9E3779B97F4A7C15 / 0xBF58476D1CE4E5B9 / 0x94D049BB133111EB.
_SPLITMIX_GAMMA = 0x9E3779B97F4A7C15
_SPLITMIX_MIX_1 = 0xBF58476D1CE4E5B9
_SPLITMIX_MIX_2 = 0x94D049BB133111EB


def to_signed64(value: int) -> int:
    """Беззнаковое 64-битное значение → знаковый Kotlin ``Long``."""
    value &= _MASK64
    return value - (1 << 64) if value >= (1 << 63) else value


def seed_of(puzzle_id: str) -> int:
    """FNV-1a 64 по UTF-8-байтам идентификатора. Возвращает беззнаковое значение.

    Соответствует ``DeterministicShuffler.seedOf``; для сравнения с литералами
    Kotlin-теста результат пропускается через :func:`to_signed64`.
    """
    digest = _FNV_OFFSET_BASIS
    for byte in puzzle_id.encode("utf-8"):
        digest = ((digest ^ byte) * _FNV_PRIME) & _MASK64
    return digest


def _next_state(state: int) -> int:
    return (state + _SPLITMIX_GAMMA) & _MASK64


def _mix(state: int) -> int:
    z = state & _MASK64
    z = ((z ^ (z >> 30)) * _SPLITMIX_MIX_1) & _MASK64
    z = ((z ^ (z >> 27)) * _SPLITMIX_MIX_2) & _MASK64
    return (z ^ (z >> 31)) & _MASK64


def shuffle(puzzle_id: str, card_ids: Sequence[str]) -> list[str]:
    """Стартовый порядок карточек. Перестановка входа, та же длина и мультимножество.

    Пустой список и список из одного элемента возвращаются как есть — как в Kotlin.
    """
    result = list(card_ids)
    if len(result) <= 1:
        return result

    state = seed_of(puzzle_id)
    # Fisher–Yates сверху вниз, в точности как в Kotlin.
    for i in range(len(result) - 1, 0, -1):
        state = _next_state(state)
        # `ushr 1` на беззнаковом представлении — обычный сдвиг; остаток от
        # неотрицательного числа неотрицателен, поэтому floorMod и % совпадают.
        j = (_mix(state) >> 1) % (i + 1)
        result[i], result[j] = result[j], result[i]
    return result
