package ru.poporyadku.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.poporyadku.data.db.entity.DayAssignmentEntity
import ru.poporyadku.data.db.entity.PuzzleAttemptEntity

// ITERATION_2_DESIGN.md, PR 2A: база открывается, продуктовые таблицы на месте,
// три ограничения уникальности (C1–C3) держатся настоящим SQLite, а не только кодом.
@RunWith(RobolectricTestRunner::class)
class DatabaseSchemaTest {

    private lateinit var db: AppDatabase

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
    fun `database opens and contains the five product tables`() {
        val tables = db.query("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

        assertTrue(
            tables.containsAll(
                setOf("puzzles", "daily_sets", "day_assignments", "puzzle_attempts", "day_results")
            )
        )
    }

    // C1: уникальность puzzle_attempts(local_date, slot_index).
    @Test
    fun `C1 - puzzle_attempts local_date slot_index is unique`() = runTest {
        val dao = db.attemptDao()
        dao.insert(
            PuzzleAttemptEntity(
                localDate = "2026-09-01",
                slotIndex = 0,
                puzzleId = "geo-vysota-gor-007",
                submittedOrder = "c1,c2,c3,c4",
                score = 6,
                submittedAt = 1L,
            )
        )

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                dao.insert(
                    PuzzleAttemptEntity(
                        localDate = "2026-09-01",
                        slotIndex = 0,
                        puzzleId = "hist-izobreteniya-012",
                        submittedOrder = "c2,c1,c3,c4",
                        score = 4,
                        submittedAt = 2L,
                    )
                )
            }
        }
    }

    // C2: уникальность day_assignments.local_date (первичный ключ).
    @Test
    fun `C2 - day_assignments local_date is unique`() = runTest {
        val dao = db.assignmentDao()
        dao.insert(DayAssignmentEntity(localDate = "2026-09-01", packId = "core-ru", setIndex = 0, assignedAt = 1L))

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                dao.insert(DayAssignmentEntity(localDate = "2026-09-01", packId = "core-ru", setIndex = 1, assignedAt = 2L))
            }
        }
    }

    // C3: уникальность day_assignments(pack_id, set_index).
    @Test
    fun `C3 - day_assignments pack_id set_index is unique`() = runTest {
        val dao = db.assignmentDao()
        dao.insert(DayAssignmentEntity(localDate = "2026-09-01", packId = "core-ru", setIndex = 0, assignedAt = 1L))

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                dao.insert(DayAssignmentEntity(localDate = "2026-09-02", packId = "core-ru", setIndex = 0, assignedAt = 2L))
            }
        }
    }

    @Test
    fun `assignment inserted through the DAO can be read back`() = runTest {
        val dao = db.assignmentDao()
        dao.insert(DayAssignmentEntity(localDate = "2026-09-01", packId = "core-ru", setIndex = 0, assignedAt = 1L))

        val read = dao.byDate("2026-09-01")

        assertNotNull(read)
        assertEquals("core-ru", read?.packId)
        assertEquals(0, read?.setIndex)
    }
}
