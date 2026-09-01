package ru.poporyadku.domain.usecase

import java.time.LocalDate
import javax.inject.Inject
import ru.poporyadku.core.model.SLOTS_PER_DAY
import ru.poporyadku.core.model.isPlayable
import ru.poporyadku.core.model.puzzleIdAt
import ru.poporyadku.domain.content.ContentInstaller
import ru.poporyadku.domain.repository.DailySetRepository
import ru.poporyadku.domain.repository.DayAssignmentRepository
import ru.poporyadku.domain.repository.ProgressRepository
import ru.poporyadku.domain.repository.PuzzleRepository
import ru.poporyadku.domain.shuffle.DeterministicShuffler

/**
 * Головоломка слота (ITERATION_3_DESIGN.md, §8).
 *
 * Оба входных параметра приходят из аргументов маршрута. Дату use case НЕ вычисляет:
 * сессия играется на ту дату, для которой была начата, а «сегодня» посреди сессии
 * может смениться.
 */
class GetPuzzleUseCase @Inject constructor(
    private val content: ContentInstaller,
    private val assignments: DayAssignmentRepository,
    private val sets: DailySetRepository,
    private val puzzles: PuzzleRepository,
    private val progress: ProgressRepository,
) {
    suspend operator fun invoke(localDate: LocalDate, slotIndex: Int): GetPuzzleResult {
        content.ensureInstalled()

        // Диапазон — первым: puzzleIdAt на неверном индексе бросает исключение,
        // а это доменный случай, а не дефект вызывающего на этом входе.
        if (slotIndex !in 0 until SLOTS_PER_DAY) {
            return GetPuzzleResult.Failure(PuzzleErrorKind.SlotOutOfRange)
        }

        val assignment = assignments.getAssignment(localDate)
            ?: return GetPuzzleResult.Failure(PuzzleErrorKind.NoAssignment)

        // ДО загрузки набора и головоломки: закрытый слот обязан перенаправлять даже
        // тогда, когда контент под ним сломан, иначе игрок получил бы ошибку вместо
        // своего же результата (I3-D45).
        val existing = progress.getAttempt(localDate, slotIndex)
        if (existing != null) {
            return GetPuzzleResult.AlreadyClosed(slotIndex, AttemptKind.of(existing))
        }

        val set = sets.getSet(assignment.packId, assignment.setIndex)
            ?: return GetPuzzleResult.Failure(PuzzleErrorKind.SetNotFound)

        val puzzleId = set.puzzleIdAt(slotIndex)
        val puzzle = puzzles.getPuzzle(puzzleId)
            ?: return GetPuzzleResult.Failure(PuzzleErrorKind.PuzzleNotFound)

        if (!puzzle.isPlayable()) {
            return GetPuzzleResult.Failure(PuzzleErrorKind.InvalidPuzzle)
        }

        return GetPuzzleResult.Playable(
            puzzle = puzzle,
            slotIndex = slotIndex,
            setIndex = assignment.setIndex,
            startOrder = DeterministicShuffler.shuffle(puzzle.puzzleId, puzzle.cards.map { it.cardId }),
        )
    }
}
