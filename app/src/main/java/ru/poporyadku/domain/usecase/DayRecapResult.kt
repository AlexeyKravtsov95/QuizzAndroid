package ru.poporyadku.domain.usecase

import java.time.LocalDate

/** Итог дня (ITERATION_3_DESIGN.md, §10). */
sealed interface DayRecapResult {

    data class Content(
        val localDate: LocalDate,
        /** `setIndex + 1` назначения этой даты. */
        val dayNumber: Int,
        /** Читается из `day_results`, а не суммируется заново; 0..18. */
        val totalScore: Int,
        val isComplete: Boolean,
        /** Только слоты с записанной попыткой, по возрастанию `slotIndex`. */
        val slots: List<SlotOutcome>,
        /** «Сейчас»: считается на `today`, а не на дату дня. */
        val currentStreak: Int,
        /** «Сейчас»: считается на `today`, а не на дату дня. */
        val bestStreak: Int,
        /** Свойство ЭТОГО дня; от момента просмотра не зависит (I3-D46). */
        val isRecordUpdated: Boolean,
    ) : DayRecapResult

    /** Данных за этот день нет. */
    data object NotFound : DayRecapResult
}
