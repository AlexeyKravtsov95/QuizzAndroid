package ru.poporyadku.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.data.db.entity.DayAssignmentEntity

// ITERATION_2_DESIGN.md, §4: A13. Рубеж "только вперёд" на уровне DAO, отдельно от
// политики и от репозитория: прямой вызов carryOver с today <= pendingDate.
@RunWith(RobolectricTestRunner::class)
class AssignmentDaoTest {

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
    fun `A13 - carryOver with today not strictly after pendingDate updates nothing`() = runTest {
        val dao = db.assignmentDao()
        dao.insert(DayAssignmentEntity(localDate = "2026-09-05", packId = "core-ru", setIndex = 2, assignedAt = 100L))

        val sameDay = dao.carryOver(packId = "core-ru", pendingDate = "2026-09-05", today = "2026-09-05", now = 200L)
        val backwards = dao.carryOver(packId = "core-ru", pendingDate = "2026-09-05", today = "2026-09-04", now = 200L)

        assertEquals(0, sameDay)
        assertEquals(0, backwards)
        val row = dao.byDate("2026-09-05")
        assertEquals("2026-09-05", row?.localDate)
        assertEquals(100L, row?.assignedAt)
        assertEquals(2, row?.setIndex)
    }
}
