package ru.poporyadku.domain.usecase

import ru.poporyadku.core.model.Category

/**
 * Строка итога дня (ITERATION_3_DESIGN.md, I3-D37).
 *
 * Строка существует и тогда, когда головоломку показать нечем: пропуск, ненайденная
 * головоломка или головоломка, не прошедшая проверку формы. Фактический `score`
 * сохраняется в обоих вариантах — [Unavailable] не равно «ноль».
 */
sealed interface SlotOutcome {
    val slotIndex: Int

    /** 0..6. */
    val score: Int

    /** Обычный случай: головоломка загрузилась, категория известна. */
    data class Played(
        override val slotIndex: Int,
        override val score: Int,
        val category: Category,
    ) : SlotOutcome

    /** Пропуск (`submittedOrder` пуст) либо головоломка недоступна. Категории нет. */
    data class Unavailable(
        override val slotIndex: Int,
        override val score: Int,
    ) : SlotOutcome
}
