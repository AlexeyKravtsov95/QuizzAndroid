package ru.poporyadku.domain.repository

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import ru.poporyadku.core.model.DayResult
import ru.poporyadku.core.model.PuzzleAttempt

// ITERATION_2_DESIGN.md, D-5 / D-17: запись попытки и пересчёт day_results — одна
// транзакция; доменные диапазоны валидируются реализацией до записи.
// ITERATION_3_DESIGN.md, I3-D20: чтения, которых требуют use cases итерации 3.
interface ProgressRepository {

    /**
     * Записывает попытку и пересчитывает `day_results` в одной транзакции.
     *
     * `submittedAt` входного [attempt] ИГНОРИРУЕТСЯ: фактическую метку времени ставит
     * реализация из `ClockProvider` — тем же снимком, которым пересчитывается день
     * (I3-D45). Вызывающий передаёт любое значение-заглушку.
     *
     * @throws AttemptAlreadyExistsException если по `(localDate, slotIndex)` запись уже
     * есть — повтор или проигранная гонка. Прочие сбои хранилища пробрасываются как есть.
     */
    suspend fun recordAttempt(attempt: PuzzleAttempt)

    suspend fun getDayResult(localDate: LocalDate): DayResult?

    suspend fun getDayResults(from: LocalDate, to: LocalDate): List<DayResult>

    /** Попытка по слоту: null — слот ещё не закрыт. */
    suspend fun getAttempt(localDate: LocalDate, slotIndex: Int): PuzzleAttempt?

    /** Попытки за дату по возрастанию `slotIndex`; только реально записанные. */
    suspend fun getAttempts(localDate: LocalDate): List<PuzzleAttempt>

    /** Вся история дней: статистика Home считается из одной выборки (I3-D14). */
    suspend fun getAllDayResults(): List<DayResult>

    /** Даты с `is_complete = 1` — вход [ru.poporyadku.domain.scoring.StreakCalculator] (I3-D10). */
    suspend fun getCompletedDates(): List<LocalDate>

    /** Room-Flow: запись попытки на любом экране пересчитывает Home (I3-D14). */
    fun observeDayResults(): Flow<List<DayResult>>
}
