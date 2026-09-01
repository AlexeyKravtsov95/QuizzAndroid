package ru.poporyadku.domain.model

import ru.poporyadku.domain.scoring.Streaks

/**
 * Статистика, известная про историю игрока (ITERATION_3_DESIGN.md, I3-D34).
 *
 * Считается из ОДНОЙ выборки `day_results` (I3-D14): отдельных запросов «сыграно дней»,
 * «лучший день» и «завершено дней» не существует.
 */
data class TodayStats(
    /** Текущая и лучшая серия (раздел 5, I3-D10). */
    val streaks: Streaks,
    /** Максимум `total_score` за всю историю, 0..18. */
    val bestDayScore: Int,
    /** Строк в `day_results` — дней, где есть хотя бы одна попытка. */
    val playedDayCount: Int,
    /** Дней с `is_complete = 1` — от него зависит видимость иконки «Архив». */
    val completedDayCount: Int,
)
