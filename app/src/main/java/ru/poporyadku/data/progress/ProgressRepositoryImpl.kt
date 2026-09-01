package ru.poporyadku.data.progress

import androidx.room.withTransaction
import java.time.LocalDate
import javax.inject.Inject
import ru.poporyadku.core.model.DayResult
import ru.poporyadku.core.model.PuzzleAttempt
import ru.poporyadku.core.time.ClockProvider
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.data.db.dao.AttemptDao
import ru.poporyadku.data.db.dao.DayResultDao
import ru.poporyadku.data.db.entity.DayResultEntity
import ru.poporyadku.data.db.mapper.toDomain
import ru.poporyadku.data.db.mapper.toEntity
import ru.poporyadku.domain.repository.ProgressRepository
import ru.poporyadku.domain.scoring.PairwiseScoreCalculator

// ITERATION_2_DESIGN.md, D-5 / D-17. Room не поддерживает CHECK-ограничения — домен
// обязан держать диапазоны сам, до открытия транзакции: некорректный вызов — дефект
// кода, а не состояние базы, откатывать нечего.
class ProgressRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val attempts: AttemptDao,
    private val results: DayResultDao,
    private val clock: ClockProvider,
) : ProgressRepository {

    override suspend fun recordAttempt(attempt: PuzzleAttempt) {
        require(attempt.slotIndex in 0..2) { "slotIndex вне 0..2: ${attempt.slotIndex}" }
        require(attempt.score in 0..PairwiseScoreCalculator.MAX_PER_PUZZLE) {
            "score вне 0..${PairwiseScoreCalculator.MAX_PER_PUZZLE}: ${attempt.score}"
        }

        val time = clock.now()
        db.withTransaction {
            attempts.insert(attempt.copy(submittedAt = time.epochMillis).toEntity())
            recalculate(attempt.localDate, time.epochMillis)
        }
    }

    /** total_score/completed_count/is_complete пересчитываются из puzzle_attempts, а не
     *  инкрементируются (D-5); вызывается только внутри транзакции recordAttempt. */
    private suspend fun recalculate(localDate: LocalDate, now: Long) {
        val dayAttempts = attempts.getByDate(localDate.toString())
        val completedCount = dayAttempts.size
        val isComplete = completedCount == COMPLETED_SET_SIZE
        results.upsert(
            DayResultEntity(
                localDate = localDate.toString(),
                totalScore = dayAttempts.sumOf { it.score },
                completedCount = completedCount,
                isComplete = isComplete,
                completedAt = if (isComplete) now else null,
            )
        )
    }

    override suspend fun getDayResult(localDate: LocalDate): DayResult? =
        results.getByDate(localDate.toString())?.toDomain()

    override suspend fun getDayResults(from: LocalDate, to: LocalDate): List<DayResult> =
        results.getRange(from.toString(), to.toString()).map { it.toDomain() }

    companion object {
        // ITERATION_3_DESIGN.md, I3-D6 (PR 3A): верхняя граница счёта берётся из общего
        // контракта PairwiseScoreCalculator.MAX_PER_PUZZLE, а не из приватного литерала, —
        // связь с C(4,2) видна в коде, как и обещал D-17.
        private const val COMPLETED_SET_SIZE = 3
    }
}
