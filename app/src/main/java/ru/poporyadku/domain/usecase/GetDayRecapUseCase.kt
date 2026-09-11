package ru.poporyadku.domain.usecase

import java.time.LocalDate
import javax.inject.Inject
import ru.poporyadku.core.model.PuzzleAttempt
import ru.poporyadku.core.model.SLOTS_PER_DAY
import ru.poporyadku.core.model.isPlayable
import ru.poporyadku.domain.repository.DayAssignmentRepository
import ru.poporyadku.domain.repository.ProgressRepository
import ru.poporyadku.domain.repository.PuzzleRepository
import ru.poporyadku.domain.scoring.StreakCalculator

/**
 * Итог дня (ITERATION_3_DESIGN.md, §10, I3-D37, I3-D46; ITERATION_5_DESIGN.md, §6.3,
 * I5-D9, I5-D31).
 *
 * **Только чтение Room.** Записей DataStore в пути загрузки итога нет: кэш серии
 * пишет только расчёт Home, а флаг первого завершённого дня ставит
 * `SubmitAnswerUseCase` в момент завершения. Поэтому отказ DataStore не может
 * превратить уже прочитанный день в `NotFound`.
 *
 * Ни установщик контента, ни репозиторий наборов не инжектируются намеренно:
 * `daily_sets` для итога не читается — `puzzleId` берётся из самой попытки, и замена
 * отозванной головоломки в наборе не подменяет то, что пользователь видел (I5-D10).
 */
class GetDayRecapUseCase @Inject constructor(
    private val assignments: DayAssignmentRepository,
    private val puzzles: PuzzleRepository,
    private val progress: ProgressRepository,
) {
    /**
     * @param localDate день, итог которого показывается; приходит из маршрута. «Сегодня»
     * здесь не нужно: серия — серия этого дня, а заголовок выбирает экран.
     */
    suspend operator fun invoke(localDate: LocalDate): DayRecapResult {
        // totalScore и isComplete читаются из day_results, а не суммируются заново:
        // таблица уже согласована с попытками в рамках одной транзакции (D-5).
        val dayResult = progress.getDayResult(localDate) ?: return DayRecapResult.NotFound

        // Номер дня даёт назначение. Без него его нечем заполнить, а выдумывать «День 0»
        // запрещено, поэтому такой день показывается как отсутствующий.
        val assignment = assignments.getAssignment(localDate) ?: return DayRecapResult.NotFound

        val attempts = progress.getAttempts(localDate).associateBy { it.slotIndex }
        val slots = (0 until SLOTS_PER_DAY).map { slotIndex -> outcomeOf(slotIndex, attempts[slotIndex]) }

        val completed = progress.getCompletedDates()
        // Якорь — localDate, а не today: серия, закончившаяся этим днём, — свойство дня.
        val streakAtDay = StreakCalculator.streaks(completed.filter { it <= localDate }, localDate).current
        val bestBeforeDay = StreakCalculator.bestStreak(completed.filter { it < localDate })

        return DayRecapResult.Content(
            localDate = localDate,
            dayNumber = assignment.setIndex + DAY_NUMBER_OFFSET,
            totalScore = dayResult.totalScore,
            isComplete = dayResult.isComplete,
            slots = slots,
            streakAtDay = if (dayResult.isComplete) streakAtDay else null,
            isRecordUpdated = isRecordUpdated(localDate, dayResult.isComplete, completed, streakAtDay, bestBeforeDay),
        )
    }

    /**
     * Пустой `submittedOrder` здесь — ФАКТ ХРАНЕНИЯ («порядок не отправлялся»), а не
     * управляющий сигнал: он влияет только на выбор представления.
     */
    private suspend fun outcomeOf(slotIndex: Int, attempt: PuzzleAttempt?): SlotOutcome {
        if (attempt == null) return SlotOutcome.NotPlayed(slotIndex)

        // Пропуск: показать нечего, головоломка не читается вовсе.
        if (attempt.submittedOrder.isEmpty()) return SlotOutcome.Unavailable(slotIndex, attempt.score)

        // puzzleId — ИЗ ПОПЫТКИ. Отозванная головоломка остаётся Played: строка не
        // удаляется, и её результат показывается целиком (I5-D11).
        val puzzle = puzzles.getPuzzle(attempt.puzzleId)
        return if (puzzle != null && puzzle.isPlayable()) {
            SlotOutcome.Played(slotIndex, attempt.score, puzzle.category)
        } else {
            // Не «ноль»: отвеченная головоломка, которую нечем показать, сохраняет свой
            // фактический счёт.
            SlotOutcome.Unavailable(slotIndex, attempt.score)
        }
    }

    /**
     * «Этот день установил рекорд», а не «сегодня повторён прежний» (I3-D46): серия,
     * закончившаяся этим днём, строго длиннее лучшей серии, существовавшей до него.
     */
    private fun isRecordUpdated(
        localDate: LocalDate,
        isComplete: Boolean,
        completed: List<LocalDate>,
        streakAtDay: Int,
        bestBeforeDay: Int,
    ): Boolean = isComplete && localDate in completed && streakAtDay > bestBeforeDay

    private companion object {
        const val DAY_NUMBER_OFFSET = 1
    }
}
