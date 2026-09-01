package ru.poporyadku.domain.scoring

import java.time.LocalDate

/**
 * Серия завершённых дней (ARCHITECTURE.md, §4, ADR-005; ITERATION_3_DESIGN.md, I3-D10, I3-D11).
 *
 * Источник — ПОЛНАЯ история завершённых дат (`day_results` с `is_complete = 1`),
 * а не окно: лучшая серия может целиком лежать за границей окна и была бы занижена молча.
 *
 * Чистые функции без состояния. `StreakCache` здесь не читается и не пишется —
 * его единственный писатель появляется в PR 3B (`GetStreaksUseCase`).
 */
object StreakCalculator {

    /** Серия, заканчивающаяся сегодня или вчера; иначе 0. */
    fun currentStreak(completedDates: Collection<LocalDate>, today: LocalDate): Int =
        current(normalize(completedDates), today)

    /** Самая длинная серия за всю историю, включая даты в будущем. */
    fun bestStreak(completedDates: Collection<LocalDate>): Int =
        best(normalize(completedDates))

    /** Единственная точка вызова из приложения: одна нормализация на оба значения. */
    fun streaks(completedDates: Collection<LocalDate>, today: LocalDate): Streaks {
        val dates = normalize(completedDates)
        return Streaks(current = current(dates, today), best = best(dates))
    }

    /** Снимает дубликаты и несортированность за один проход (I3-D11). */
    private fun normalize(completedDates: Collection<LocalDate>): Set<LocalDate> =
        completedDates.toSortedSet()

    private fun current(dates: Set<LocalDate>, today: LocalDate): Int {
        // Серия не может заканчиваться в будущем: такие записи появляются после
        // перевода часов вперёд и обратно и в текущей серии не участвуют.
        val past = dates.filterTo(HashSet()) { !it.isAfter(today) }
        if (past.isEmpty()) return 0

        val yesterday = today.minusDays(1)
        val anchor = when {
            today in past -> today
            // Сегодняшний день ещё можно завершить — до конца дня серия не обнуляется.
            yesterday in past -> yesterday
            else -> return 0
        }

        var count = 1
        var day = anchor
        while (day.minusDays(1) in past) {
            count++
            day = day.minusDays(1)
        }
        return count
    }

    private fun best(dates: Set<LocalDate>): Int {
        var best = 0
        var run = 0
        var previous: LocalDate? = null
        // dates отсортированы: переходы месяца и года считает сам LocalDate.
        for (date in dates) {
            run = if (previous != null && previous.plusDays(1) == date) run + 1 else 1
            if (run > best) best = run
            previous = date
        }
        return best
    }
}
