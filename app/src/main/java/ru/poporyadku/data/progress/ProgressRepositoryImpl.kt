package ru.poporyadku.data.progress

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.poporyadku.core.model.DayResult
import ru.poporyadku.core.model.PuzzleAttempt
import ru.poporyadku.core.time.ClockProvider
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.data.db.dao.AttemptDao
import ru.poporyadku.data.db.dao.DayResultDao
import ru.poporyadku.data.db.entity.DayResultEntity
import ru.poporyadku.data.db.mapper.toDomain
import ru.poporyadku.data.db.mapper.toEntity
import ru.poporyadku.domain.repository.AttemptAlreadyExistsException
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
        try {
            db.withTransaction {
                attempts.insert(attempt.copy(submittedAt = time.epochMillis).toEntity())
                recalculate(attempt.localDate, time.epochMillis)
            }
        } catch (e: SQLiteConstraintException) {
            // ITERATION_3_DESIGN.md, I3-D42. Транзакция уже откатилась целиком, поэтому
            // чтение ниже видит РЕАЛЬНОЕ состояние базы, а не состояние внутри неудавшейся
            // транзакции. Единственное принимаемое доказательство «уже отвечено» —
            // совпадение по (local_date, slot_index): любое другое нарушение ограничения
            // (NOT NULL, будущая миграция, вложенный запрос) остаётся инфраструктурным
            // сбоем и уходит наружу как есть, а не превращается в «вы уже отвечали».
            val existing = attempts.getByDateAndSlot(attempt.localDate.toString(), attempt.slotIndex)
            if (existing != null) {
                throw AttemptAlreadyExistsException(attempt.localDate, attempt.slotIndex)
            }
            throw e
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

    override suspend fun getAttempt(localDate: LocalDate, slotIndex: Int): PuzzleAttempt? =
        attempts.getByDateAndSlot(localDate.toString(), slotIndex)?.toDomain()

    override suspend fun getAttempts(localDate: LocalDate): List<PuzzleAttempt> =
        attempts.getByDate(localDate.toString()).map { it.toDomain() }

    override suspend fun getAllDayResults(): List<DayResult> =
        results.getAll().map { it.toDomain() }

    // ISO-строка → LocalDate выполняется здесь: TypeConverter'ов у базы нет (D-8),
    // а domain про формат хранения не знает.
    override suspend fun getCompletedDates(): List<LocalDate> =
        results.completedDates().map(LocalDate::parse)

    override fun observeDayResults(): Flow<List<DayResult>> =
        results.observeAll().map { rows -> rows.map { it.toDomain() } }

    companion object {
        // ITERATION_3_DESIGN.md, I3-D6 (PR 3A): верхняя граница счёта берётся из общего
        // контракта PairwiseScoreCalculator.MAX_PER_PUZZLE, а не из приватного литерала, —
        // связь с C(4,2) видна в коде, как и обещал D-17.
        private const val COMPLETED_SET_SIZE = 3
    }
}
