"""Единицы ``sortValue`` и правило минимального разрыва (I4-D24).

`CONTENT_MODEL.md` §8 задаёт разрыв «≥ 2 года» и «≥ 3 %», не определяя единицу для
каждого ``sortKey``. Без такой таблицы правило 8 неисполнимо, поэтому она зафиксирована
здесь — в одном месте — и используется правилами ``R08``, ``R08C``, ``R08D``, ``R08E``.

Пять ключей запрещены в паке v1 и **не имеют** ни единицы, ни порога: придумывать их —
значит принимать архитектурное решение мимо документа (ITERATION_4_DESIGN.md §5.4).
"""

from __future__ import annotations

from dataclasses import dataclass

#: Абсолютный порог шкалы «год»: соседние значения должны различаться не менее чем на 2.
YEAR_MIN_GAP_ABSOLUTE = 2.0

#: Относительный порог ратио-шкал: |a − b| ≥ 0,03 · max(|a|, |b|).
RATIO_MIN_GAP_FRACTION = 0.03

#: Допуск сравнения с порогом. Значения контента приходят из десятичного JSON, и
#: `0.03 * 100.0` в двоичной плавающей арифметике даёт 3.0000000000000004, из-за чего
#: ровно граничная пара (100.0 и 103.0) считалась бы нарушением. Допуск относительный,
#: масштабируется вместе со значениями и на 2,99 % не срабатывает: разница между
#: 2,99 % и 3 % на порядки больше 1e-9.
GAP_RELATIVE_TOLERANCE = 1e-9


@dataclass(frozen=True)
class SortKeySpec:
    """Строка таблицы «sortKey → единица → правило разрыва»."""

    key: str
    #: Единица ``sortValue``, как её обязан понимать редактор.
    unit: str
    #: ``True`` — ратио-шкала: относительный порог и требование ``sortValue > 0`` (R08D).
    ratio: bool
    #: ``None`` — ключ разрешён в паке v1; строка — причина запрета (текст R08C).
    forbidden_reason: str | None = None


#: Таблица целиком. Порядок строк повторяет таблицу ITERATION_4_DESIGN.md §5.4.
SORT_KEYS: dict[str, SortKeySpec] = {
    spec.key: spec
    for spec in (
        SortKeySpec("year", "год; до н. э. — отрицательное число, нуля не существует", ratio=False),
        SortKeySpec("height", "метры", ratio=True),
        SortKeySpec("depth", "метры", ratio=True),
        SortKeySpec("length", "метры", ratio=True),
        SortKeySpec("distance", "метры", ratio=True),
        SortKeySpec("area", "квадратные километры", ratio=True),
        SortKeySpec("mass", "килограммы", ratio=True),
        SortKeySpec("speed", "километры в час", ratio=True),
        SortKeySpec("duration", "секунды", ratio=True),
        SortKeySpec(
            "population",
            unit="—",
            ratio=False,
            forbidden_reason=(
                "по природе volatile: население меняется быстрее, чем выходит "
                "релиз (правило 9 и CONTENT_MODEL.md §9)"
            ),
        ),
        SortKeySpec(
            "temperature",
            unit="—",
            ratio=False,
            forbidden_reason=(
                "интервальная шкала: относительный порог неприменим, "
                "абсолютный не задан ни одним документом"
            ),
        ),
        SortKeySpec(
            "date",
            unit="—",
            ratio=False,
            forbidden_reason="единица не зафиксирована: день эпохи или год — неизвестно",
        ),
        SortKeySpec(
            "latitude",
            unit="—",
            ratio=False,
            forbidden_reason="относительный порог вырождается около экватора",
        ),
        SortKeySpec(
            "longitude",
            unit="—",
            ratio=False,
            forbidden_reason="относительный порог вырождается около нулевого меридиана",
        ),
    )
}

#: Ключи, разрешённые в паке v1 (девять).
ALLOWED_SORT_KEYS: frozenset[str] = frozenset(
    key for key, spec in SORT_KEYS.items() if spec.forbidden_reason is None
)

#: Ключи, отложенные до отдельного решения (пять).
FORBIDDEN_SORT_KEYS: frozenset[str] = frozenset(
    key for key, spec in SORT_KEYS.items() if spec.forbidden_reason is not None
)

#: Виды источников, годные как единственный источник карточки (CONTENT_MODEL.md §4).
AUTHORITATIVE_SOURCE_KINDS: frozenset[str] = frozenset({"official", "encyclopedia", "academic"})

#: Ровно два допустимых профиля сложности набора (I4-D25). Перечень, а не неравенства:
#: неравенства пропустили бы [1,1,2] и [2,2,3], то есть расширили бы утверждённое правило.
ALLOWED_DIFFICULTY_PROFILES: tuple[tuple[int, int, int], ...] = ((1, 2, 2), (1, 2, 3))

#: Наборы 0…6 проходит каждый пользователь: difficulty 3 в них запрещена (R20D).
EARLY_SET_INDEX_LIMIT = 6

#: Максимальная поддерживаемая версия формата (ContentSchema.SUPPORTED_SCHEMA_VERSION).
SUPPORTED_SCHEMA_VERSION = 1


def min_gap_satisfied(sort_key: str, first: float, second: float) -> bool:
    """Проверить пару соседних значений на правило минимального разрыва.

    Вызывается только для разрешённых ключей: запрет отложенных — это R08C, и он
    проверяется раньше, чтобы для запрещённого ключа не появлялся ещё и R08.
    """
    spec = SORT_KEYS[sort_key]
    gap = abs(first - second)
    if spec.ratio:
        threshold = RATIO_MIN_GAP_FRACTION * max(abs(first), abs(second))
    else:
        threshold = YEAR_MIN_GAP_ABSOLUTE
    return gap >= threshold - abs(threshold) * GAP_RELATIVE_TOLERANCE


def min_gap_description(sort_key: str) -> str:
    """Текст порога для сообщения находки (в контракт тестов не входит)."""
    spec = SORT_KEYS[sort_key]
    if spec.ratio:
        return f"|a − b| ≥ {RATIO_MIN_GAP_FRACTION:.2f} · max(|a|, |b|), единица — {spec.unit}"
    return f"|a − b| ≥ {YEAR_MIN_GAP_ABSOLUTE:.0f}, единица — {spec.unit}"
