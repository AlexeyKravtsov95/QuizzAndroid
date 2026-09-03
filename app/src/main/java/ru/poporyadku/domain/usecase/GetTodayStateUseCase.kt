package ru.poporyadku.domain.usecase

import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import ru.poporyadku.core.model.DayResult
import ru.poporyadku.core.model.SLOTS_PER_DAY
import ru.poporyadku.domain.assignment.Decision
import ru.poporyadku.domain.assignment.DecisionContext
import ru.poporyadku.domain.content.ContentInstallException
import ru.poporyadku.domain.content.ContentInstaller
import ru.poporyadku.domain.model.CompletedDaySummary
import ru.poporyadku.domain.model.TodayFailureKind
import ru.poporyadku.domain.model.TodayState
import ru.poporyadku.domain.model.TodayStats
import ru.poporyadku.domain.repository.DayAssignmentRepository
import ru.poporyadku.domain.repository.ProgressRepository
import ru.poporyadku.domain.scoring.Streaks

/**
 * Живое состояние сегодняшнего дня (ITERATION_3_DESIGN.md, I3-D13, I3-D14, I3-D38, I3-D43).
 *
 * Репозиторий наборов сюда не инжектируется: состав набора для Home не нужен — номер
 * дня даёт `setIndex` из решения политики, а головоломки читаются при старте сессии.
 */
class GetTodayStateUseCase @Inject constructor(
    private val content: ContentInstaller,
    private val assignments: DayAssignmentRepository,
    private val progress: ProgressRepository,
    private val streaks: GetStreaksUseCase,
) {

    /**
     * @param refreshSignals «пересчитай сейчас»: возврат на экран, ретрай, смена даты
     * по тикеру. За ПЕРВУЮ эмиссию ни один из них не отвечает — она гарантирована
     * конструкцией потока (I3-D38).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(refreshSignals: Flow<Unit>): Flow<TodayState> =
        combine(
            // Room-Flow выдаёт текущее содержимое таблицы сразу при подписке.
            progress.observeDayResults(),
            // onStart ЗДЕСЬ, а не в ViewModel: иначе гарантия первой эмиссии зависела бы
            // от того, что каждый вызывающий не забыл её обеспечить.
            refreshSignals.onStart { emit(Unit) },
        ) { _, _ -> Unit }
            .mapLatest {
                // Отказ обрабатывается ВНУТРИ эмиссии и превращается в ЗНАЧЕНИЕ.
                // Терминального оператора catch в цепочке нет вовсе: он завершил бы
                // поток, и кнопка «Повторить» рисовалась бы, но не работала (I3-D43).
                val partial = PartialToday()
                try {
                    compute(partial)
                } catch (e: CancellationException) {
                    // Первым блоком: CancellationException наследует IllegalStateException
                    // и была бы проглочена общим catch, окажись он выше.
                    throw e
                } catch (e: Exception) {
                    // Ловится Exception, а не Throwable: OutOfMemoryError и подобные
                    // означают невосстановимое состояние процесса, и прятать их за
                    // работающей кнопкой «Повторить» — значит скрыть отказ.
                    failureState(e, partial)
                }
            }

    /** То, что успело прочитаться до отказа. Ничего не выдумывается (I3-D34). */
    private class PartialToday {
        var today: LocalDate? = null
        var stats: TodayStats? = null
    }

    private suspend fun compute(partial: PartialToday): TodayState {
        content.ensureInstalled()

        val context = assignments.peek()
        val today = context.localDate
        partial.today = today

        // Одна выборка на всю статистику, hasAnyAttemptEver и последний завершённый день.
        val dayResults = progress.getAllDayResults()
        val stats = statsOf(dayResults, streaks(today))
        partial.stats = stats

        return when (val decision = context.decision) {
            is Decision.NewSet -> beforeStart(today, decision.setIndex, dayResults, stats)
            is Decision.CarryOver -> beforeStart(today, decision.setIndex, dayResults, stats)
            is Decision.Assigned -> assigned(context, decision.setIndex, dayResults, stats)
            Decision.AwaitingNextDay -> TodayState.AwaitingNextDay(
                today = today,
                lastCompleted = lastCompleted(dayResults),
                stats = stats,
            )

            Decision.ContentExhausted -> TodayState.ContentExhausted(today, stats)
        }
    }

    /**
     * Набор на сегодня ещё не выдан. FirstRun отличается от Ready ИСТОРИЕЙ, а не
     * сегодняшним днём: любая записанная попытка создаёт строку `day_results`, поэтому
     * пустая таблица и есть «не сыграно ни одной головоломки» (I3-D13).
     */
    private fun beforeStart(
        today: LocalDate,
        setIndex: Int,
        dayResults: List<DayResult>,
        stats: TodayStats,
    ): TodayState {
        val dayNumber = setIndex + DAY_NUMBER_OFFSET
        return if (dayResults.isEmpty()) {
            TodayState.FirstRun(today = today, dayNumber = dayNumber)
        } else {
            TodayState.Ready(today = today, dayNumber = dayNumber, stats = stats)
        }
    }

    /**
     * Назначение на сегодня уже есть. Ноль попыток даёт InProgress и «Продолжить»:
     * показать «Играть» человеку, который день уже начал, было бы неправдой (I3-D13).
     */
    private fun assigned(
        context: DecisionContext,
        setIndex: Int,
        dayResults: List<DayResult>,
        stats: TodayStats,
    ): TodayState {
        val today = context.localDate
        // Дата назначения по определению Decision.Assigned равна сегодняшней.
        val result = dayResults.firstOrNull { it.localDate == today }
        val completedCount = result?.completedCount ?: 0
        val dayNumber = setIndex + DAY_NUMBER_OFFSET

        return if (completedCount >= SLOTS_PER_DAY) {
            TodayState.Completed(
                today = today,
                sessionDate = today,
                dayNumber = dayNumber,
                totalScore = result?.totalScore ?: 0,
                streaks = stats.streaks,
                // Из DecisionContext: зона и момент — из того же Clock, что дата (I3-D40).
                nextLocalDateStartsAt = context.nextLocalDateStartsAt,
            )
        } else {
            TodayState.InProgress(
                today = today,
                sessionDate = today,
                dayNumber = dayNumber,
                completedCount = completedCount,
            )
        }
    }

    /**
     * Сводка последнего завершённого дня — только из реальных данных. Если назначения
     * на эту дату нет (история из другого пакета, ручная правка базы), сводки нет
     * вовсе: фиктивные номер дня и счёт не подставляются (I3-D35).
     */
    private suspend fun lastCompleted(dayResults: List<DayResult>): CompletedDaySummary? {
        val last = dayResults.filter { it.isComplete }.maxByOrNull { it.localDate } ?: return null
        val assignment = assignments.getAssignment(last.localDate) ?: return null
        return CompletedDaySummary(
            localDate = last.localDate,
            dayNumber = assignment.setIndex + DAY_NUMBER_OFFSET,
            totalScore = last.totalScore,
        )
    }

    private fun statsOf(dayResults: List<DayResult>, streaks: Streaks): TodayStats = TodayStats(
        streaks = streaks,
        bestDayScore = dayResults.maxOfOrNull { it.totalScore } ?: 0,
        playedDayCount = dayResults.size,
        completedDayCount = dayResults.count { it.isComplete },
    )

    private fun failureState(e: Exception, partial: PartialToday): TodayState = TodayState.Failure(
        today = partial.today,
        stats = partial.stats,
        kind = kindOf(e),
    )

    /**
     * Классификация отказа (I3-D47; ITERATION_4_DESIGN.md, **I4-D19**, `I4-V1`).
     *
     * `when` по закрытой taxonomy ИСЧЕРПЫВАЮЩИЙ и без `else` внутри неё: появление
     * пятого варианта `ContentInstallException` обязано сломать компиляцию здесь,
     * а не молча уехать в `Generic`.
     *
     * Отказы базы среди вариантов отсутствуют намеренно: `SQLiteException` и прочие
     * исключения Room не оборачиваются и попадают в общую ветку — повторить имеет
     * смысл, разрушительных действий не предлагается.
     */
    private fun kindOf(e: Exception): TodayFailureKind = when (e) {
        is ContentInstallException -> when (e) {
            is ContentInstallException.Conflict -> TodayFailureKind.ContentConflict
            is ContentInstallException.BundleInvalid -> TodayFailureKind.ContentUnusable
            is ContentInstallException.UnsupportedSchema -> TodayFailureKind.ContentUnusable
            // Ввод-вывод, а не содержимое: повтор имеет смысл.
            is ContentInstallException.AssetUnreadable -> TodayFailureKind.Generic
        }

        else -> TodayFailureKind.Generic
    }

    private companion object {
        /** Номер дня, который видит игрок, — `setIndex + 1`. */
        const val DAY_NUMBER_OFFSET = 1
    }
}
