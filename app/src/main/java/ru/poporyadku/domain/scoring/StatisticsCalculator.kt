package ru.poporyadku.domain.scoring

import java.time.LocalDate
import ru.poporyadku.core.model.DayResult
import ru.poporyadku.domain.model.Statistics

/**
 * Единственный расчёт статистики истории (ITERATION_5_DESIGN.md, §3.5, §6.2, I5-D5).
 *
 * Вызывают его и архив (`GetStatisticsUseCase`), и Home (`GetTodayStateUseCase`):
 * согласованность двух экранов держится построением, а не дисциплиной (§3.5, п. 8).
 * Чистая функция: `today` приходит параметром, часы здесь не читаются.
 *
 * Вход — строки `day_results` как есть, в любом порядке: сохранённые агрегаты
 * (`total_score`, `completed_count`, `is_complete`) не пересчитываются из попыток (§5.2).
 */
object StatisticsCalculator {

    fun of(days: List<DayResult>, today: LocalDate): Statistics {
        val complete = days.filter { it.isComplete }
        val completedDays = complete.size
        return Statistics(
            // Незавершённый день сыгран: он и в архиве, и в «Сыграно дней» Home (§3.5, п. 1).
            playedDays = days.size,
            completedDays = completedDays,
            // O5-3: частичный балл незавершённого дня не входит ни в числитель, ни в знаменатель.
            averageTenths = averageTenths(complete.sumOf { it.totalScore.toLong() }, completedDays),
            // Максимум по ВСЕМ дням, включая незавершённые, — ровно как TodayStats (§3.5, п. 4).
            bestDayScore = days.maxOfOrNull { it.totalScore } ?: 0,
            // Только завершённые даты; будущие StreakCalculator сам исключает из текущей
            // серии и учитывает в лучшей (I3-D11).
            streaks = StreakCalculator.streaks(complete.map { it.localDate }, today),
        )
    }

    /**
     * Среднее `sum / count` в десятых с округлением half-up от точного рационального
     * значения — целочисленно, без `Double` (§3.5, п. 3):
     * `floor(10·sum/count + 1/2) = (20·sum + count) div (2·count)`.
     *
     * Промежуточные значения — `Long`; результат ≤ 180 для корректной истории, а на
     * испорченной `toIntExact` бросает вместо тихого переполнения.
     */
    private fun averageTenths(sum: Long, count: Int): Int? =
        if (count == 0) null else Math.toIntExact((20L * sum + count) / (2L * count))
}
