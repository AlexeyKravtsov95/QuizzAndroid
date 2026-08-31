package ru.poporyadku

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.poporyadku.core.model.ContentPack
import ru.poporyadku.core.model.PuzzleAttempt
import ru.poporyadku.core.time.FakeClockProvider
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.data.db.entity.DailySetEntity
import ru.poporyadku.data.progress.ProgressRepositoryImpl
import ru.poporyadku.data.repository.DayAssignmentRepositoryImpl
import ru.poporyadku.domain.assignment.Decision

// ITERATION_2_DESIGN.md, PR 2C: один сквозной сценарий на настоящей in-memory Room-базе —
// автоматизированная версия того, что на debug-экране (раздел 6) проверяется руками.
// FakeClockProvider вместо HiltAndroidTest — репозитории строятся напрямую.
@RunWith(RobolectricTestRunner::class)
class AcceptanceScenarioTest {

    private lateinit var db: AppDatabase
    private lateinit var clock: FakeClockProvider
    private lateinit var assignments: DayAssignmentRepositoryImpl
    private lateinit var progress: ProgressRepositoryImpl

    private fun clockAt(date: LocalDate): Clock =
        Clock.fixed(date.atTime(LocalTime.NOON).atZone(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        clock = FakeClockProvider(clockAt(LocalDate.of(2026, 9, 1)))
        assignments =
            DayAssignmentRepositoryImpl(db, db.assignmentDao(), db.dailySetDao(), clock, ContentPack.CORE_RU)
        progress = ProgressRepositoryImpl(db, db.attemptDao(), db.dayResultDao(), clock)

        // 1. daily_sets заполнены тестовыми строками.
        db.dailySetDao().upsertAll(
            (0 until 5).map { i ->
                DailySetEntity(
                    packId = ContentPack.CORE_RU,
                    setIndex = i,
                    puzzleId1 = "p-$i-1",
                    puzzleId2 = "p-$i-2",
                    puzzleId3 = "p-$i-3",
                )
            }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `full sequential assignment scenario end to end`() = runTest {
        // 2. День 1 — startSession без попыток создаёт набор N (= 0).
        val day1 = assignments.startSession()
        assertEquals(Decision.NewSet(ContentPack.CORE_RU, 0), day1)
        assertEquals(1, db.assignmentDao().pendingAssignments().size)
        assertEquals("2026-09-01", db.assignmentDao().pendingAssignments().single().localDate)

        // 3. День 2 — переносит ту же строку: packId и setIndex сохранены, второй строки нет.
        clock.setDate(LocalDate.of(2026, 9, 2))
        val day2 = assignments.startSession()
        assertEquals(Decision.CarryOver(ContentPack.CORE_RU, 0, LocalDate.of(2026, 9, 1)), day2)
        val carried = db.assignmentDao().pendingAssignments().single()
        assertEquals(ContentPack.CORE_RU, carried.packId)
        assertEquals(0, carried.setIndex)
        assertEquals("2026-09-02", carried.localDate)
        assertNull(db.assignmentDao().byDate("2026-09-01"))

        // 4. Записать попытку — набор становится израсходованным, отложенных больше нет.
        progress.recordAttempt(
            PuzzleAttempt(
                id = 0,
                localDate = LocalDate.of(2026, 9, 2),
                slotIndex = 0,
                puzzleId = "p-0-1",
                submittedOrder = listOf("a", "b", "c", "d"),
                score = 6,
                submittedAt = 0L,
            )
        )
        assertTrue(db.assignmentDao().pendingAssignments().isEmpty())

        // 5. День 3 — выдаёт N + 1 (= 1), а не восьмой и не тот же самый.
        clock.setDate(LocalDate.of(2026, 9, 3))
        val day3 = assignments.startSession()
        assertEquals(Decision.NewSet(ContentPack.CORE_RU, 1), day3)

        // 6. Перевод даты назад.
        clock.setDate(LocalDate.of(2026, 9, 2))

        // 7. AwaitingNextDay — не выдан лишний набор, старая строка не тронута.
        val decision = assignments.startSession()
        assertEquals(Decision.AwaitingNextDay, decision)

        // 8. Ни лишних строк, ни повреждённого progress.
        val allAssignments = db.assignmentDao().observeAll().first()
        assertEquals(2, allAssignments.size)
        assertEquals(
            setOf("2026-09-02" to 0, "2026-09-03" to 1),
            allAssignments.map { it.localDate to it.setIndex }.toSet(),
        )

        val allAttempts = db.attemptDao().observeAll().first()
        assertEquals(1, allAttempts.size)

        val allResults = db.dayResultDao().observeAll().first()
        assertEquals(1, allResults.size)
        val result = allResults.single()
        assertEquals("2026-09-02", result.localDate)
        assertEquals(6, result.totalScore)
        assertEquals(1, result.completedCount)
        assertEquals(false, result.isComplete)
    }
}
