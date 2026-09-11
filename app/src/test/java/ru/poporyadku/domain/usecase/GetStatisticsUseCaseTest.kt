package ru.poporyadku.domain.usecase

import app.cash.turbine.test
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.poporyadku.core.model.DayResult
import ru.poporyadku.core.model.PuzzleAttempt
import ru.poporyadku.core.time.DateProvider
import ru.poporyadku.domain.model.Statistics
import ru.poporyadku.domain.repository.ProgressRepository
import ru.poporyadku.domain.scoring.Streaks

/**
 * `GetStatisticsUseCase` — ITERATION_5_DESIGN.md, §5.2, I5-D5: поток статистики архива
 * на подготовленном наборе дней (пример 1 раздела 3.5, `I5-S1`).
 *
 * Чистый JVM на фейках: предмет проверки — топология потока (первая эмиссия без
 * сигнала, пересчёт по записи и по `refresh`, одно чтение `today` на эмиссию), а не SQL.
 * Фейк прогресса отвечает только на `observeDayResults()`: любое другое чтение — провал,
 * собственного источника данных у статистики нет.
 */
class GetStatisticsUseCaseTest {

    private val day7 = LocalDate.of(2026, 8, 7)

    private fun date(day: Int): LocalDate = LocalDate.of(2026, 8, day)

    private fun complete(date: LocalDate, score: Int) = DayResult(date, score, 3, true, 1L)

    private fun partial(date: LocalDate, completedCount: Int, score: Int) =
        DayResult(date, score, completedCount, false, null)

    private val example1 = listOf(
        complete(date(1), 15),
        complete(date(2), 12),
        partial(date(3), completedCount = 2, score = 8),
        complete(date(5), 18),
        complete(date(6), 11),
        partial(date(7), completedCount = 1, score = 5),
    )

    /** Отвечает только на observeDayResults(): статистика обязана читать тот же поток, что Home. */
    private class ObservedOnlyProgress(initial: List<DayResult>) : ProgressRepository {
        val days = MutableStateFlow(initial)

        override fun observeDayResults(): Flow<List<DayResult>> = days

        override suspend fun recordAttempt(attempt: PuzzleAttempt) = unexpected()
        override suspend fun getDayResult(localDate: LocalDate): DayResult? = unexpected()
        override suspend fun getDayResults(from: LocalDate, to: LocalDate): List<DayResult> = unexpected()
        override suspend fun getAttempt(localDate: LocalDate, slotIndex: Int): PuzzleAttempt? = unexpected()
        override suspend fun getAttempts(localDate: LocalDate): List<PuzzleAttempt> = unexpected()
        override suspend fun getAllDayResults(): List<DayResult> = unexpected()
        override suspend fun getCompletedDates(): List<LocalDate> = unexpected()

        private fun unexpected(): Nothing = throw AssertionError("статистика читает только observeDayResults()")
    }

    /** Считает обращения: `today` читается ровно один раз на эмиссию. */
    private class CountingDateProvider(var date: LocalDate) : DateProvider {
        var calls = 0
            private set

        override fun today(): LocalDate {
            calls++
            return date
        }
    }

    @Test
    fun `first emission needs no refresh signal and matches example 1`() = runTest {
        val useCase = GetStatisticsUseCase(ObservedOnlyProgress(example1), CountingDateProvider(day7))

        val statistics = useCase(emptyFlow()).first()

        assertEquals(
            Statistics(
                playedDays = 6,
                completedDays = 4,
                averageTenths = 140,
                bestDayScore = 18,
                streaks = Streaks(current = 2, best = 2),
            ),
            statistics,
        )
    }

    @Test
    fun `refresh recomputes with a new today without a database write`() = runTest {
        val dates = CountingDateProvider(day7)
        val refresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val useCase = GetStatisticsUseCase(ObservedOnlyProgress(example1), dates)

        useCase(refresh).test {
            assertEquals(Streaks(current = 2, best = 2), awaitItem().streaks)

            // 08-10: вчерашнего 08-09 нет — текущая серия оборвалась, лучшая осталась.
            dates.date = date(10)
            refresh.emit(Unit)
            val later = awaitItem()
            assertEquals(Streaks(current = 0, best = 2), later.streaks)
            assertEquals(6, later.playedDays)
            assertEquals(140, later.averageTenths)
            assertEquals(18, later.bestDayScore)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a new day in the database recomputes the statistics`() = runTest {
        val progress = ObservedOnlyProgress(example1)
        val useCase = GetStatisticsUseCase(progress, CountingDateProvider(day7))

        useCase(emptyFlow()).test {
            assertEquals(6, awaitItem().playedDays)

            // Сегодняшний день доигран: 5 → 14 из 18.
            progress.days.value = example1.dropLast(1) + complete(day7, 14)
            val updated = awaitItem()
            assertEquals(6, updated.playedDays)
            assertEquals(5, updated.completedDays)
            // (20 · 70 + 5) div 10 = 140 → «14,0»
            assertEquals(140, updated.averageTenths)
            assertEquals(Streaks(current = 3, best = 3), updated.streaks)

            // Новая строка — ещё один сыгранный день.
            progress.days.value = progress.days.value + partial(date(8), completedCount = 1, score = 6)
            assertEquals(7, awaitItem().playedDays)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `today is read exactly once per emission`() = runTest {
        val dates = CountingDateProvider(day7)
        val progress = ObservedOnlyProgress(example1)
        val refresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val useCase = GetStatisticsUseCase(progress, dates)

        useCase(refresh).test {
            awaitItem()
            assertEquals(1, dates.calls)

            refresh.emit(Unit)
            awaitItem()
            assertEquals(2, dates.calls)

            progress.days.value = example1 + complete(date(8), 9)
            awaitItem()
            assertEquals(3, dates.calls)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
