package ru.poporyadku.domain.usecase

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import ru.poporyadku.core.time.DateProvider
import ru.poporyadku.domain.model.Statistics
import ru.poporyadku.domain.repository.ProgressRepository
import ru.poporyadku.domain.scoring.StatisticsCalculator

/**
 * Поток статистики архива (ITERATION_5_DESIGN.md, §3.5, §5.2, I5-D5).
 *
 * Тот же источник, что у Home, — `ProgressRepository.observeDayResults()`, и та же
 * функция — [StatisticsCalculator]. Собственного SQL-агрегата нет: одна выборка
 * `day_results`, один расчёт.
 *
 * `GetStreaksUseCase` здесь не вызывается: архив — читатель, а не писатель
 * `StreakCache`, единственный писатель остаётся у расчёта Home (I3-D12).
 */
class GetStatisticsUseCase @Inject constructor(
    private val progress: ProgressRepository,
    private val dateProvider: DateProvider,
) {

    /**
     * @param refresh «пересчитай с новым `today`» без новой записи в базу: `ON_START`
     * и «Повторить». За первую эмиссию он не отвечает — её гарантирует `onStart`.
     * `today` читается заново на каждую эмиссию, ровно один раз.
     */
    operator fun invoke(refresh: Flow<Unit>): Flow<Statistics> =
        combine(progress.observeDayResults(), refresh.onStart { emit(Unit) }) { days, _ -> days }
            .map { days -> StatisticsCalculator.of(days, dateProvider.today()) }
}
