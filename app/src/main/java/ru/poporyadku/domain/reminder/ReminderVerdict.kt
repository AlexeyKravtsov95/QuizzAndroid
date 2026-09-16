package ru.poporyadku.domain.reminder

/** Почему срабатывание не показывает уведомление (ITERATION_6_DESIGN.md, §8.5, §9.6). */
sealed interface SkipReason {

    /** Напоминание выключено — работа не продолжается, следующей цели нет. */
    data object Disabled : SkipReason

    /** Работа поставлена на другое время: настройку изменили после планирования. */
    data object StaleTime : SkipReason

    /** Работа поставлена на другую дату: сработала после смены даты. */
    data object StaleDate : SkipReason

    /** Момент ещё не наступил (часы переведены вперёд — WorkManager считает по настенным). */
    data object Early : SkipReason

    /** Уведомления недоступны; причина сохраняется для диагностики, а не для показа. */
    data class NoAccess(val availability: NotificationAvailability) : SkipReason

    /** Состояние дня не позволяет обещать «новые задания» (O6-1, I6-D33). */
    data class DayNotReadyYet(val reason: DayNotReady) : SkipReason
}

/**
 * Решение перед показом (ITERATION_6_DESIGN.md, §9.6).
 *
 * [nextTrigger] — цель следующей работы: она есть у **всех** исходов, кроме выключенного
 * напоминания. Именно поэтому пропуск показа не обрывает цепочку: не показали сегодня —
 * напомним завтра.
 */
sealed interface ReminderVerdict {

    val nextTrigger: ReminderTrigger?

    data class Show(override val nextTrigger: ReminderTrigger) : ReminderVerdict

    data class Skip(
        val reason: SkipReason,
        override val nextTrigger: ReminderTrigger?,
    ) : ReminderVerdict
}
