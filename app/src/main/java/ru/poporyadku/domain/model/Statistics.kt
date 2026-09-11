package ru.poporyadku.domain.model

import ru.poporyadku.domain.scoring.Streaks

/**
 * Статистика истории игрока (ITERATION_5_DESIGN.md, §3.5, §4.1, I5-D5).
 *
 * Строится единственной функцией `StatisticsCalculator.of` — её вызывают и архив
 * (`GetStatisticsUseCase`), и Home (`GetTodayStateUseCase`), поэтому поля, которые есть
 * в [TodayStats], совпадают с ним по построению.
 */
data class Statistics(
    /** Строк `day_results` — дней хотя бы с одной попыткой, включая незавершённые. */
    val playedDays: Int,
    /** Дней с `is_complete = 1`. */
    val completedDays: Int,
    /**
     * Средний балл завершённых дней в десятых, half-up: `137` ⇒ «13,7».
     * `null`, если завершённых дней нет (O5-3: незавершённые в среднее не входят).
     */
    val averageTenths: Int?,
    /** Максимум `total_score` по всем сыгранным дням, 0..18; 0 при пустой истории. */
    val bestDayScore: Int,
    /** `StreakCalculator` по датам завершённых дней. */
    val streaks: Streaks,
)
