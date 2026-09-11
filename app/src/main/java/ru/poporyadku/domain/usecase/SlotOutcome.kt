package ru.poporyadku.domain.usecase

import ru.poporyadku.core.model.Category

/**
 * Строка итога дня (ITERATION_3_DESIGN.md, I3-D37; ITERATION_5_DESIGN.md, §4.1, I5-D9).
 *
 * Итог дня всегда содержит три строки — по одной на слот. `score` живёт в вариантах,
 * а не в общем интерфейсе: у [NotPlayed] счёта нет, и «0» там был бы выдумкой.
 * [Unavailable] при этом не равно «ноль»: его фактический счёт — из попытки.
 */
sealed interface SlotOutcome {
    val slotIndex: Int

    /**
     * Головоломка загрузилась и прошла проверку формы, порядок отправлялся.
     * Отозванная головоломка — тоже [Played] (I5-D11): её результат показывается целиком.
     */
    data class Played(
        override val slotIndex: Int,
        /** 0..6 — из попытки. */
        val score: Int,
        val category: Category,
    ) : SlotOutcome

    /** Пропуск (`submittedOrder` пуст) либо головоломка отсутствует/неверной формы. */
    data class Unavailable(
        override val slotIndex: Int,
        /** 0..6 — фактический счёт попытки; у пропуска 0. */
        val score: Int,
    ) : SlotOutcome

    /** Попытки нет: слот не сыгран (закрывает O-3 итерации 3). */
    data class NotPlayed(
        override val slotIndex: Int,
    ) : SlotOutcome
}
