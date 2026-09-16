package ru.poporyadku.domain.reminder

import ru.poporyadku.core.model.DayResult
import ru.poporyadku.domain.assignment.Decision

/**
 * Состояние дня, из-за которого напоминание не показывается
 * (ITERATION_6_DESIGN.md, §9.6, I6-D33, O6-1).
 */
enum class DayNotReady {
    /** День завершён: все три слота закрыты. */
    DayCompleted,

    /** День начат, закрыт 1–2 слота — **O6-1**: не показывать. */
    InProgress,

    /** Новый набор ещё не положен: «новое задание» было бы неправдой. */
    AwaitingNextDay,

    /** Наборы исчерпаны: новых заданий нет. */
    ContentExhausted,
}

/**
 * Разрешает ли состояние дня показать напоминание (ITERATION_6_DESIGN.md, §9.6,
 * таблица `ReminderEligibility.decide`).
 *
 * Чистая функция от решения политики выдачи и сегодняшнего результата — ни чтений, ни
 * записей. Напоминание обещает «новые задания готовы», поэтому показ разрешён только
 * там, где это правда: набор либо будет создан ([Decision.NewSet]), либо перенесён
 * ([Decision.CarryOver]), либо уже назначен и к нему ещё не притрагивались.
 *
 * @param decision решение политики выдачи, полученное **чтением** (`peek()`).
 * @param todayResult строка `day_results` на сегодня; `null` — ни одной попытки нет.
 */
object ReminderEligibility {

    fun decide(decision: Decision, todayResult: DayResult?): DayNotReady? = when (decision) {
        // Набор для продолжения существует — показ честен.
        is Decision.NewSet, is Decision.CarryOver -> null

        is Decision.Assigned -> when {
            todayResult == null || todayResult.completedCount == 0 -> null
            todayResult.isComplete || todayResult.completedCount >= SLOTS_PER_DAY -> DayNotReady.DayCompleted
            // O6-1: пользователь уже вернулся сам — напоминание читалось бы как давление.
            else -> DayNotReady.InProgress
        }

        Decision.AwaitingNextDay -> DayNotReady.AwaitingNextDay
        Decision.ContentExhausted -> DayNotReady.ContentExhausted
    }

    private const val SLOTS_PER_DAY = 3
}
