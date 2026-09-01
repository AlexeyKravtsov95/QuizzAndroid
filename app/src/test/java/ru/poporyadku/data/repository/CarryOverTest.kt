package ru.poporyadku.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
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
import ru.poporyadku.core.time.FakeClockProvider
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.data.db.entity.DailySetEntity
import ru.poporyadku.data.db.entity.PuzzleAttemptEntity
import ru.poporyadku.domain.assignment.Decision

// ITERATION_2_DESIGN.md, §4: A1–A8. Robolectric, Room.inMemoryDatabaseBuilder,
// репозиторий строится напрямую с FakeClockProvider — без Hilt.
@RunWith(RobolectricTestRunner::class)
class CarryOverTest {

    private lateinit var db: AppDatabase
    private lateinit var clock: FakeClockProvider

    private fun clockAt(date: LocalDate): Clock =
        Clock.fixed(date.atTime(LocalTime.NOON).atZone(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)

    private fun repository(activePackId: String = ContentPack.CORE_RU) =
        DayAssignmentRepositoryImpl(db, db.assignmentDao(), db.dailySetDao(), clock, activePackId)

    private fun attempt(localDate: String, slotIndex: Int = 0, score: Int = 6) = PuzzleAttemptEntity(
        localDate = localDate,
        slotIndex = slotIndex,
        puzzleId = "p-$localDate-$slotIndex",
        submittedOrder = "a,b,c,d",
        score = score,
        submittedAt = 1L,
    )

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        clock = FakeClockProvider(clockAt(LocalDate.of(2026, 9, 1)))
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
    fun `A1 - first session with no attempts creates a single row`() = runTest {
        val decision = repository().startSession().decision

        assertEquals(Decision.NewSet(ContentPack.CORE_RU, 0), decision)
        val all = db.assignmentDao().pendingAssignments()
        assertEquals(1, all.size)
        assertEquals("2026-09-01", all.single().localDate)
    }

    @Test
    fun `A2 - carrying over the next day updates the same row, not a second one`() = runTest {
        repository().startSession()

        clock.setDate(LocalDate.of(2026, 9, 2))
        val decision = repository().startSession().decision

        assertEquals(Decision.CarryOver(ContentPack.CORE_RU, 0, LocalDate.of(2026, 9, 1)), decision)
        val rows = db.assignmentDao().pendingAssignments()
        assertEquals(1, rows.size)
        assertEquals(0, rows.single().setIndex)
        assertEquals("2026-09-02", rows.single().localDate)
    }

    @Test
    fun `A3 - repeated start on the same day is idempotent`() = runTest {
        repository().startSession()
        clock.setDate(LocalDate.of(2026, 9, 2))
        repository().startSession()
        val afterCarryOver = db.assignmentDao().byDate("2026-09-02")

        val decision = repository().startSession().decision

        assertEquals(Decision.Assigned(ContentPack.CORE_RU, 0), decision)
        val afterRepeat = db.assignmentDao().byDate("2026-09-02")
        assertEquals(afterCarryOver, afterRepeat)
        assertEquals(1, db.assignmentDao().pendingAssignments().size)
    }

    @Test
    fun `A4 - a completed set gets N + 1 the next day, day 1 stays in the archive`() = runTest {
        repository().startSession()
        db.attemptDao().insert(attempt("2026-09-01"))

        clock.setDate(LocalDate.of(2026, 9, 2))
        val decision = repository().startSession().decision

        assertEquals(Decision.NewSet(ContentPack.CORE_RU, 1), decision)
        assertEquals(0, db.assignmentDao().byDate("2026-09-01")?.setIndex)
        assertEquals(1, db.assignmentDao().byDate("2026-09-02")?.setIndex)
    }

    @Test
    fun `A5 - turning the clock back returns AwaitingNextDay without touching the pending row`() = runTest {
        repository().startSession()

        clock.setDate(LocalDate.of(2026, 8, 31))
        val before = db.assignmentDao().byDate("2026-09-01")
        val decision = repository().startSession().decision
        val after = db.assignmentDao().byDate("2026-09-01")

        assertEquals(Decision.AwaitingNextDay, decision)
        assertEquals(before, after)
    }

    @Test
    fun `A6 - at most one pending assignment after each step`() = runTest {
        repository().startSession()
        assertTrue(db.assignmentDao().pendingAssignments().size <= 1)

        clock.setDate(LocalDate.of(2026, 9, 2))
        repository().startSession()
        assertTrue(db.assignmentDao().pendingAssignments().size <= 1)

        db.attemptDao().insert(attempt("2026-09-02"))
        clock.setDate(LocalDate.of(2026, 9, 3))
        repository().startSession()
        assertTrue(db.assignmentDao().pendingAssignments().size <= 1)
    }

    @Test
    fun `A7 - there and back does not create an extra set`() = runTest {
        repository().startSession()

        clock.setDate(LocalDate.of(2026, 8, 31))
        repository().startSession()

        clock.setDate(LocalDate.of(2026, 9, 1))
        repository().startSession()

        clock.setDate(LocalDate.of(2026, 8, 31))
        repository().startSession()

        assertEquals(1, db.assignmentDao().pendingAssignments().size)
    }

    @Test
    fun `A8 - the decision is derived from state read consistently inside the transaction`() = runTest {
        repository().startSession()
        clock.setDate(LocalDate.of(2026, 9, 2))

        // peek() строит тот же снимок в своей транзакции и ничего не пишет.
        val decision = repository().peek().decision

        assertEquals(Decision.CarryOver(ContentPack.CORE_RU, 0, LocalDate.of(2026, 9, 1)), decision)
        assertNull(db.assignmentDao().byDate("2026-09-02"))
        assertEquals("2026-09-01", db.assignmentDao().byDate("2026-09-01")?.localDate)
    }
}
