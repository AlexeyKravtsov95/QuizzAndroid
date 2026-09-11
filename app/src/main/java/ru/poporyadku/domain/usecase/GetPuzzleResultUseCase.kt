package ru.poporyadku.domain.usecase

import java.time.LocalDate
import javax.inject.Inject
import ru.poporyadku.core.model.isPlayable
import ru.poporyadku.domain.repository.DayAssignmentRepository
import ru.poporyadku.domain.repository.ProgressRepository
import ru.poporyadku.domain.repository.PuzzleRepository
import ru.poporyadku.domain.scoring.PairwiseScoreCalculator
import ru.poporyadku.domain.scoring.PuzzleRetirement

/**
 * Восстановление экрана результата из базы (ITERATION_3_DESIGN.md, I3-D21, I3-D49;
 * ITERATION_5_DESIGN.md, §6.4, I5-D10, I5-D11).
 *
 * Всё, что показывает экран, выводится из пары `(localDate, slotIndex)`: ничего не живёт
 * только в памяти и ничего не передаётся через `Bundle`. Про экранную модель результата,
 * ViewModel, строки, подсказки и навигацию use case не знает.
 *
 * `puzzleId` берётся ИЗ ПОПЫТКИ, а не из набора: попытка хранит именно ту головоломку,
 * на которую отвечал игрок, а состав набора после отзыва может измениться (I4-D4).
 * `daily_sets` здесь не читается вовсе — поэтому исторический результат не подменяется
 * текущей ротацией по построению.
 */
class GetPuzzleResultUseCase @Inject constructor(
    private val assignments: DayAssignmentRepository,
    private val puzzles: PuzzleRepository,
    private val progress: ProgressRepository,
    private val getInstalledContentVersion: GetInstalledContentVersionUseCase,
) {
    suspend operator fun invoke(localDate: LocalDate, slotIndex: Int): PuzzleResultLoad {
        val attempt = progress.getAttempt(localDate, slotIndex)
            ?: return PuzzleResultLoad.NoAttempt(slotIndex)

        // Вторым шагом, ДО калькулятора: PairwiseScoreCalculator на пустом submittedOrder
        // упал бы на require, и обходить его пустым входом не требуется — калькулятор
        // сохраняет строгий контракт (I3-D6, I3-D45).
        if (AttemptKind.of(attempt) == AttemptKind.Skipped) {
            return PuzzleResultLoad.Skipped(slotIndex)
        }

        assignments.getAssignment(localDate)
            ?: return PuzzleResultLoad.Failure(PuzzleErrorKind.NoAssignment)

        val puzzle = puzzles.getPuzzle(attempt.puzzleId)
            ?: return PuzzleResultLoad.Failure(PuzzleErrorKind.PuzzleNotFound)

        if (!puzzle.isPlayable()) {
            return PuzzleResultLoad.Failure(PuzzleErrorKind.InvalidPuzzle)
        }

        // Установленная версия читается только для показываемой головоломки: на
        // отсутствующей, неверной форме и пропуске отзыв неприменим (§3.8).
        val installed = getInstalledContentVersion()

        return PuzzleResultLoad.Content(
            slotIndex = slotIndex,
            puzzle = puzzle,
            attempt = attempt,
            scored = PairwiseScoreCalculator.evaluate(attempt.submittedOrder, puzzle.correctOrder),
            isRetired = PuzzleRetirement.isRetired(puzzle.retiredIn, puzzle.contentVersion, installed),
        )
    }
}
