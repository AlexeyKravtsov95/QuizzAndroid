package ru.poporyadku.ui.puzzle

/**
 * Куда поставить карточку (ITERATION_6_DESIGN.md, §4.1, I6-D3).
 *
 * Намерение описывает **цель**, а не «откуда»: индекс «откуда» устаревает между кадрами
 * жеста, а `cardId` — нет. Поэтому у кнопки, custom action и перетаскивания одно и то же
 * намерение и один и тот же алгоритм.
 */
sealed interface MoveTarget {

    /** Текущий индекс − 1. */
    data object Up : MoveTarget

    /** Текущий индекс + 1. */
    data object Down : MoveTarget

    /** Индекс 0. */
    data object First : MoveTarget

    /** Последний индекс. */
    data object Last : MoveTarget

    /**
     * Абсолютный индекс — цель перетаскивания.
     *
     * Абсолютная, а не относительная: она **идемпотентна**. Если UI дважды пришлёт одну и
     * ту же цель до перекомпозиции, вторая команда найдёт карточку уже на месте и ничего
     * не сделает; относительный «шаг вниз» в той же гонке переставил бы карточку дважды.
     */
    data class Index(val index: Int) : MoveTarget
}

/**
 * Единственный алгоритм перестановки карточек (ITERATION_6_DESIGN.md, §4.2, I6-D4).
 *
 * Чистый Kotlin: ни одного импорта платформы и ни одного импорта Compose — поэтому все
 * инварианты проверяются JVM-тестом перечислением (`I6-R1`, `I6-R2`), а не через экран.
 *
 * Вызывается ровно из одного места продуктового кода — `PuzzleViewModel.reorder`
 * (проверка `I6-K6`): второй реализации перестановки «для жеста» не существует, поэтому
 * кнопка, custom action и перетаскивание не могут разойтись в поведении.
 */
object CardOrder {

    /**
     * @return новый порядок, если перестановка меняет позицию карточки; `null`, если
     * действие неприменимо: неизвестный [cardId], цель вне `0..lastIndex`, цель равна
     * текущему индексу. Входной [order] не изменяется.
     *
     * Результат — всегда перестановка входа: то же количество и то же множество
     * идентификаторов, без потерь и дублей; перемещённая карточка стоит на цели,
     * относительный порядок остальных сохранён.
     */
    fun move(
        order: List<String>,
        cardId: String,
        target: MoveTarget,
    ): List<String>? {
        val index = order.indexOf(cardId)
        if (index < 0) return null

        val to = when (target) {
            MoveTarget.Up -> index - 1
            MoveTarget.Down -> index + 1
            MoveTarget.First -> 0
            MoveTarget.Last -> order.lastIndex
            is MoveTarget.Index -> target.index
        }
        if (to == index || to !in order.indices) return null

        // `removeAt` + `add` на копии: остальные карточки сохраняют относительный
        // порядок по построению, а не по отдельной проверке.
        return order.toMutableList().apply { add(to, removeAt(index)) }
    }
}
