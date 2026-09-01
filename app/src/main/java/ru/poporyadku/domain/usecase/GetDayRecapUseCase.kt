package ru.poporyadku.domain.usecase

import java.time.LocalDate
import javax.inject.Inject
import ru.poporyadku.core.model.PuzzleAttempt
import ru.poporyadku.core.model.isPlayable
import ru.poporyadku.domain.repository.DayAssignmentRepository
import ru.poporyadku.domain.repository.ProgressRepository
import ru.poporyadku.domain.repository.PuzzleRepository
import ru.poporyadku.domain.scoring.StreakCalculator

/**
 * Итог дня (ITERATION_3_DESIGN.md, §10, I3-D37, I3-D46).
 *
 * Ни установщик контента, ни репозиторий наборов не инжектируются намеренно:
 * `daily_sets` для итога не читается — `puzzleId` берётся из самой попытки, а установка
 * контента к просмотру уже сыгранного дня отношения не имеет.
 */
class GetDayRecapUseCase @Inject constructor(
    private val assignments: DayAssignmentRepository,
    private val puzzles: PuzzleRepository,
    private val progress: ProgressRepository,
    private val streaks: GetStreaksUseCase,
) {
    /**
     * @param localDate день, итог которого показывается; приходит из маршрута.
     * @param today «сегодня» — только для ОТОБРАЖАЕМЫХ серий: экран может быть открыт
     * для архивной даты, а серия «сейчас» всегда считается на сегодня.
     */
    suspend operator fun invoke(localDate: LocalDate, today: LocalDate): DayRecapResult {
        // totalScore и isComplete читаются из day_results, а не суммируются заново:
        // таблица уже согласована с попытками в рамках одной транзакции (D-5).
        val dayResult = progress.getDayResult(localDate) ?: return DayRecapResult.NotFound

        // Номер дня даёт назначение. Без него его нечем заполнить, а выдумывать «День 0»
        // запрещено, поэтому такой день показывается как отсутствующий.
        val assignment = assignments.getAssignment(localDate) ?: return DayRecapResult.NotFound

        val slots = progress.getAttempts(localDate)
            .sortedBy { it.slotIndex }
            .map { outcomeOf(it) }

        val displayed = streaks(today)
        val completed = progress.getCompletedDates()

        return DayRecapResult.Content(
            localDate = localDate,
            dayNumber = assignment.setIndex + DAY_NUMBER_OFFSET,
            totalScore = dayResult.totalScore,
            isComplete = dayResult.isComplete,
            slots = slots,
            currentStreak = displayed.current,
            bestStreak = displayed.best,
            isRecordUpdated = isRecordUpdated(localDate, dayResult.isComplete, completed),
        )
    }

    /**
     * Строк ровно столько, сколько записанных попыток: незаписанные слоты не
     * синтезируются. Пустой `submittedOrder` здесь — ФАКТ ХРАНЕНИЯ («порядок не
     * отправлялся»), а не управляющий сигнал: он влияет только на выбор представления.
     */
    private suspend fun outcomeOf(attempt: PuzzleAttempt): SlotOutcome {
        val puzzle = puzzles.getPuzzle(attempt.puzzleId)
        val playable = puzzle != null && puzzle.isPlayable() && attempt.submittedOrder.isNotEmpty()
        return if (playable) {
            SlotOutcome.Played(attempt.slotIndex, attempt.score, puzzle.category)
        } else {
            // Не «ноль»: тот же вариант получает отвеченная головоломка, которую нечем
            // показать, и её фактический score обязан быть виден.
            SlotOutcome.Unavailable(attempt.slotIndex, attempt.score)
        }
    }

    /**
     * «Этот день установил рекорд», а не «сегодня повторён прежний» (I3-D46).
     *
     * `today` в расчёте НЕ участвует: серия, закончившаяся этим днём, — свойство самого
     * дня, и от момента просмотра и от более поздних результатов не зависит.
     */
    private fun isRecordUpdated(
        localDate: LocalDate,
        isComplete: Boolean,
        completed: List<LocalDate>,
    ): Boolean {
        val completedThroughDay = completed.filter { it <= localDate }
        val completedBeforeDay = completed.filter { it < localDate }

        // Якорь — localDate, а не today.
        val streakAtDay = StreakCalculator.streaks(completedThroughDay, localDate).current
        val bestBeforeDay = StreakCalculator.bestStreak(completedBeforeDay)

        return isComplete && localDate in completed && streakAtDay > bestBeforeDay
    }

    private companion object {
        const val DAY_NUMBER_OFFSET = 1
    }
}
