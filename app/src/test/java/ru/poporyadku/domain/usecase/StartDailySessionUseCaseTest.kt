package ru.poporyadku.domain.usecase

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.poporyadku.core.model.ContentPack
import ru.poporyadku.core.model.PuzzleAttempt
import ru.poporyadku.core.time.FakeClockProvider
import ru.poporyadku.data.content.temporary.TemporaryContentInstaller
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.data.progress.ProgressRepositoryImpl
import ru.poporyadku.data.repository.DailySetRepositoryImpl
import ru.poporyadku.data.repository.DayAssignmentRepositoryImpl

/**
 * ITERATION_3_DESIGN.md, §19: `I3-U10`, `I3-U11`.
 *
 * Настоящие репозитории и настоящий установщик контента: «второго назначения не
 * появилось» проверяется по строкам базы, а не по возврату подставного репозитория.
 */
@RunWith(RobolectricTestRunner::class)
class StartDailySessionUseCaseTest {

    private lateinit var db: AppDatabase
    private lateinit var clock: FakeClockProvider
    private lateinit var progress: ProgressRepositoryImpl
    private lateinit var useCase: StartDailySessionUseCase

    private val today = LocalDate.of(2026, 9, 1)
    private val zone = ZoneOffset.UTC

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        clock = FakeClockProvider(Clock.fixed(today.atTime(LocalTime.NOON).atZone(zone).toInstant(), zone))
        progress = ProgressRepositoryImpl(db, db.attemptDao(), db.dayResultDao(), clock)
        useCase = StartDailySessionUseCase(
            content = TemporaryContentInstaller(db, db.dailySetDao(), db.assignmentDao(), ContentPack.CORE_RU),
            assignments = DayAssignmentRepositoryImpl(
                db, db.assignmentDao(), db.dailySetDao(), clock, ContentPack.CORE_RU,
            ),
            sets = DailySetRepositoryImpl(db.dailySetDao()),
            progress = progress,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun close(slotIndex: Int) {
        progress.recordAttempt(
            PuzzleAttempt(
                id = 0,
                localDate = today,
                slotIndex = slotIndex,
                puzzleId = "p-$slotIndex",
                submittedOrder = listOf("c1", "c2", "c3", "c4"),
                score = 6,
                submittedAt = 0L,
            )
        )
    }

    @Test
    fun `I3-U10 - the first unclosed slot is resumed, a full day is AlreadyCompleted`() = runTest {
        assertEquals(
            SessionStart.Started(today, ContentPack.CORE_RU, setIndex = 0, slotIndex = 0),
            useCase(),
        )

        close(0)
        close(1)
        assertEquals(
            SessionStart.Started(today, ContentPack.CORE_RU, setIndex = 0, slotIndex = 2),
            useCase(),
        )

        close(2)
        assertEquals(SessionStart.AlreadyCompleted(today), useCase())
    }

    @Test
    fun `I3-U10 - the minimum unclosed slot wins, not the attempt count`() = runTest {
        useCase()
        // Дырка в последовательности: закрыт слот 1, слоты 0 и 2 — нет. По «числу попыток»
        // ответом был бы слот 1, по правилу «минимальный незакрытый» — слот 0.
        close(1)

        assertEquals(
            SessionStart.Started(today, ContentPack.CORE_RU, setIndex = 0, slotIndex = 0),
            useCase(),
        )
    }

    @Test
    fun `I3-U11 - a repeated start creates no second assignment and does not touch assignedAt`() = runTest {
        val first = useCase()
        val afterFirst = db.assignmentDao().byDate(today.toString())

        // Тот же календарный день, другой момент: полдень в зоне UTC+3 — это 09:00 UTC.
        // Если бы вторая транзакция писала, assigned_at изменился бы и это стало бы видно.
        clock.setDate(today, ZoneOffset.ofHours(3))
        val second = useCase()
        val afterSecond = db.assignmentDao().byDate(today.toString())

        assertEquals(first, second)
        assertEquals(SessionStart.Started(today, ContentPack.CORE_RU, 0, 0), second)
        assertEquals(1, db.assignmentDao().pendingAssignments().size)
        assertEquals(afterFirst, afterSecond)
        assertEquals(afterFirst?.assignedAt, afterSecond?.assignedAt)
    }
}
