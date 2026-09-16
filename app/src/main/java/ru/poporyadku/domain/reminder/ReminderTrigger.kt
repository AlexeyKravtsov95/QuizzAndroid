package ru.poporyadku.domain.reminder

import java.time.Instant
import java.time.LocalDate

/**
 * Момент следующего напоминания (ITERATION_6_DESIGN.md, §9.1, §9.2, I6-D27, I6-D28).
 *
 * Три поля — ровно то, что уходит в `WorkRequest` ([targetDate], [minuteOfDay]) плюс
 * момент [at], по которому считается задержка. Настроек, счёта и `puzzleId` в работе
 * нет: всё, что нужно для решения о показе, worker перечитывает сам при срабатывании
 * (I6-D32).
 *
 * @param targetDate локальная дата, на которую поставлено напоминание.
 * @param minuteOfDay минута суток выбранного времени, `0..1439`.
 * @param at момент срабатывания — [targetDate] и выбранное время в зоне, действовавшей
 *  на момент расчёта.
 */
data class ReminderTrigger(
    val targetDate: LocalDate,
    val minuteOfDay: Int,
    val at: Instant,
) {
    init {
        require(minuteOfDay in MIN_MINUTE_OF_DAY..MAX_MINUTE_OF_DAY) {
            "минута суток вне $MIN_MINUTE_OF_DAY..$MAX_MINUTE_OF_DAY: $minuteOfDay"
        }
    }

    companion object {
        const val MIN_MINUTE_OF_DAY = 0
        const val MAX_MINUTE_OF_DAY = 1439
    }
}
