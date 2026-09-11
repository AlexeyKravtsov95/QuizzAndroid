package ru.poporyadku.domain.usecase

import java.time.LocalDate

/** Итог дня (ITERATION_3_DESIGN.md, §10; ITERATION_5_DESIGN.md, §4.1, §6.3). */
sealed interface DayRecapResult {

    data class Content(
        val localDate: LocalDate,
        /** `setIndex + 1` назначения этой даты. */
        val dayNumber: Int,
        /** Читается из `day_results`, а не суммируется заново; 0..18. */
        val totalScore: Int,
        val isComplete: Boolean,
        /** РОВНО три строки, по `slotIndex` 0..2; слот без попытки — [SlotOutcome.NotPlayed]. */
        val slots: List<SlotOutcome>,
        /**
         * Серия, закончившаяся ЭТИМ днём (I5-D9, O5-5); `null` у незавершённого дня —
         * он серию не продолжал. От `today` не зависит.
         */
        val streakAtDay: Int?,
        /** Свойство ЭТОГО дня; от момента просмотра не зависит (I3-D46). */
        val isRecordUpdated: Boolean,
    ) : DayRecapResult

    /** Данных за этот день нет. */
    data object NotFound : DayRecapResult
}
