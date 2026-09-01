package ru.poporyadku.domain.usecase

import java.time.LocalDate
import javax.inject.Inject
import ru.poporyadku.core.model.PuzzleAttempt
import ru.poporyadku.core.model.SLOTS_PER_DAY
import ru.poporyadku.core.model.isPlayable
import ru.poporyadku.core.model.puzzleIdAt
import ru.poporyadku.domain.repository.AttemptAlreadyExistsException
import ru.poporyadku.domain.repository.DailySetRepository
import ru.poporyadku.domain.repository.DayAssignmentRepository
import ru.poporyadku.domain.repository.ProgressRepository
import ru.poporyadku.domain.repository.PuzzleRepository
import ru.poporyadku.domain.scoring.PairwiseScoreCalculator

/**
 * Значение-заглушка поля `submittedAt` (I3-D45).
 *
 * Фактическую метку времени ставит `ProgressRepositoryImpl` из своего `ClockProvider` —
 * тем же снимком, которым пересчитывается `day_results`. Use case часов не читает вовсе,
 * и переданное здесь значение до базы не доезжает.
 */
private const val SUBMITTED_AT_SET_BY_REPOSITORY = 0L

/**
 * Приём ответа или пропуска (ITERATION_3_DESIGN.md, §9, I3-D36, I3-D42, I3-D45).
 *
 * Один вход — одна защита от повторной записи. Отдельный `SkipPuzzleUseCase` пришлось бы
 * снабдить теми же пятью проверками, то есть завести вторую реализацию защиты, и
 * расхождение двух копий рано или поздно дало бы путь, по которому попытку можно
 * перезаписать. Ветвление касается ровно двух шагов: нужен ли `Puzzle` и как считается счёт.
 *
 * Навигации здесь нет: use case возвращает исход, экран выбирает эффект.
 */
class SubmitAnswerUseCase @Inject constructor(
    private val assignments: DayAssignmentRepository,
    private val sets: DailySetRepository,
    private val puzzles: PuzzleRepository,
    private val progress: ProgressRepository,
) {
    suspend operator fun invoke(
        localDate: LocalDate,
        slotIndex: Int,
        submission: Submission,
    ): SubmitResult {
        if (slotIndex !in 0 until SLOTS_PER_DAY) {
            return SubmitResult.Failure(PuzzleErrorKind.SlotOutOfRange)
        }

        // Без назначения попытка записалась бы на дату, по которой набор не выдавался,
        // и день попал бы в архив с чужим номером.
        val assignment = assignments.getAssignment(localDate)
            ?: return SubmitResult.Failure(PuzzleErrorKind.NoAssignment)

        // Ранний рубеж: закрывает повтор через другой вход — возврат в маршрут,
        // восстановление процесса, редирект. Настоящую гонку двух корутин он не ловит:
        // её ловит UNIQUE(local_date, slot_index) ниже.
        val existing = progress.getAttempt(localDate, slotIndex)
        if (existing != null) {
            return SubmitResult.AlreadyClosed(slotIndex, AttemptKind.of(existing))
        }

        // ДО любой ветки: без набора неизвестен puzzleId, а попытка без корректного
        // puzzle_id — фиктивная запись, которую нечем показать ни в результате, ни в
        // архиве. Поэтому пропуск при отсутствующем наборе не записывается тоже.
        val set = sets.getSet(assignment.packId, assignment.setIndex)
            ?: return SubmitResult.Failure(PuzzleErrorKind.SetNotFound)

        // puzzleId известен ВСЕГДА, ещё до загрузки Puzzle: пропуск не зависит от того,
        // читается ли головоломка.
        val puzzleId = set.puzzleIdAt(slotIndex)

        val score = when (submission) {
            // К PuzzleRepository ветка пропуска не обращается вовсе.
            Submission.Skip -> 0

            is Submission.Answer -> {
                val puzzle = puzzles.getPuzzle(puzzleId)
                    ?: return SubmitResult.Failure(PuzzleErrorKind.PuzzleNotFound)
                if (!puzzle.isPlayable()) {
                    return SubmitResult.Failure(PuzzleErrorKind.InvalidPuzzle)
                }
                PairwiseScoreCalculator.evaluate(submission.order, puzzle.correctOrder).score
            }
        }

        val order = when (submission) {
            Submission.Skip -> emptyList()
            is Submission.Answer -> submission.order
        }

        try {
            progress.recordAttempt(
                PuzzleAttempt(
                    id = 0,
                    localDate = localDate,
                    slotIndex = slotIndex,
                    puzzleId = puzzleId,
                    submittedOrder = order,
                    score = score,
                    submittedAt = SUBMITTED_AT_SET_BY_REPOSITORY,
                )
            )
        } catch (e: AttemptAlreadyExistsException) {
            // Исход определяет ПОБЕДИВШАЯ запись, а не намерение проигравшей корутины
            // (I3-D45): гонка Answer против Skip разрешается по тому, что лежит в базе.
            // Неизвестные инфраструктурные исключения сюда не попадают — реализация
            // репозитория переводит в этот тип только доказанный повтор (I3-D42).
            val winner = progress.getAttempt(localDate, slotIndex)
                ?: return SubmitResult.Failure(PuzzleErrorKind.Storage)
            return SubmitResult.AlreadyClosed(slotIndex, AttemptKind.of(winner))
        }

        return SubmitResult.Recorded(
            slotIndex = slotIndex,
            score = score,
            kind = if (submission is Submission.Skip) AttemptKind.Skipped else AttemptKind.Answered,
        )
    }
}
