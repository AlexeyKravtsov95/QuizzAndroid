package ru.poporyadku.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.poporyadku.core.time.FakeClockProvider
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.data.db.entity.DailySetEntity
import ru.poporyadku.data.db.entity.DayAssignmentEntity
import ru.poporyadku.data.db.entity.PuzzleAttemptEntity
import ru.poporyadku.domain.assignment.Decision

// ITERATION_2_DESIGN.md, §4: A9–A12. Строки двух пакетов подтверждают, что три запроса
// снимка (pending/byDate/lastAssignedDate) глобальны, а не pack-scoped (D-20).
@RunWith(RobolectricTestRunner::class)
class PackScopeTest {

    private lateinit var db: AppDatabase

    private fun clockAt(date: LocalDate): FakeClockProvider =
        FakeClockProvider(Clock.fixed(date.atTime(LocalTime.NOON).atZone(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC))

    private fun repository(activePackId: String, date: LocalDate) =
        DayAssignmentRepositoryImpl(db, db.assignmentDao(), db.dailySetDao(), clockAt(date), activePackId)

    private fun seedSets(packId: String, count: Int = 5) = runBlocking {
        db.dailySetDao().upsertAll(
            (0 until count).map { i ->
                DailySetEntity(
                    packId = packId,
                    setIndex = i,
                    puzzleId1 = "$packId-$i-1",
                    puzzleId2 = "$packId-$i-2",
                    puzzleId3 = "$packId-$i-3",
                )
            }
        )
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `A9 - two pending assignments from different packs violate the global invariant`() = runTest {
        seedSets("pack-a")
        seedSets("pack-b")
        db.assignmentDao().insert(DayAssignmentEntity(localDate = "2026-09-01", packId = "pack-a", setIndex = 0, assignedAt = 1L))
        db.assignmentDao().insert(DayAssignmentEntity(localDate = "2026-09-02", packId = "pack-b", setIndex = 0, assignedAt = 2L))

        assertEquals(2, db.assignmentDao().pendingAssignments().size)

        val repo = repository("pack-a", LocalDate.of(2026, 9, 3))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.startSession() }
        }
    }

    @Test
    fun `A10 - a calendar date can only ever hold one assignment across packs`() = runTest {
        seedSets("pack-a")
        seedSets("pack-b")
        db.assignmentDao().insert(DayAssignmentEntity(localDate = "2026-09-01", packId = "pack-a", setIndex = 3, assignedAt = 1L))

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                db.assignmentDao().insert(DayAssignmentEntity(localDate = "2026-09-01", packId = "pack-b", setIndex = 0, assignedAt = 2L))
            }
        }

        val decision = repository("pack-b", LocalDate.of(2026, 9, 1)).startSession().decision

        assertEquals(Decision.Assigned("pack-a", 3), decision)
        assertEquals(1, db.assignmentDao().pendingAssignments().size)
    }

    @Test
    fun `A11 - carry over preserves the original pack and index of a foreign pending row`() = runTest {
        seedSets("pack-a")
        seedSets("pack-b")
        db.assignmentDao().insert(DayAssignmentEntity(localDate = "2026-09-01", packId = "pack-a", setIndex = 3, assignedAt = 1L))

        repository("pack-b", LocalDate.of(2026, 9, 2)).startSession()

        val rows = db.assignmentDao().pendingAssignments()
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("2026-09-02", row.localDate)
        assertEquals("pack-a", row.packId)
        assertEquals(3, row.setIndex)
        assertEquals(0, rows.count { it.packId == "pack-b" })
    }

    @Test
    fun `A12 - switching the active pack does not bypass the forward-only guard`() = runTest {
        seedSets("pack-a", count = 1)
        seedSets("pack-b", count = 5)
        db.assignmentDao().insert(DayAssignmentEntity(localDate = "2026-09-01", packId = "pack-a", setIndex = 0, assignedAt = 1L))
        db.attemptDao().insert(
            PuzzleAttemptEntity(
                localDate = "2026-09-01",
                slotIndex = 0,
                puzzleId = "p",
                submittedOrder = "a,b,c,d",
                score = 6,
                submittedAt = 1L,
            )
        )
        // lastAssignedDate == 2026-09-01. Дата ниже строго раньше него, чтобы проверить
        // именно ветку "today <= lastAssignedDate", а не todayAssignment (её покрывает A10).
        val decision = repository("pack-b", LocalDate.of(2026, 8, 31)).startSession().decision

        assertEquals(Decision.AwaitingNextDay, decision)
        // pendingAssignments пуст: у pack-a есть попытка, у pack-b строка не создана.
        assertEquals(0, db.assignmentDao().pendingAssignments().size)
        assertEquals("pack-a", db.assignmentDao().byDate("2026-09-01")?.packId)
        assertEquals(0, db.assignmentDao().byDate("2026-09-01")?.setIndex)
    }
}
