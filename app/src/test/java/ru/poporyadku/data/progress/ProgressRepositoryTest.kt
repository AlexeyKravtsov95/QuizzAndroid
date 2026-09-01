package ru.poporyadku.data.progress

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.poporyadku.core.model.PuzzleAttempt
import ru.poporyadku.core.time.FakeClockProvider
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.domain.repository.AttemptAlreadyExistsException

// ITERATION_2_DESIGN.md, §4: C4–C6. Robolectric, repository строится напрямую с
// FakeClockProvider.
@RunWith(RobolectricTestRunner::class)
class ProgressRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ProgressRepositoryImpl
    private val date = LocalDate.of(2026, 9, 1)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val clock = FakeClockProvider(
            Clock.fixed(date.atTime(LocalTime.NOON).atZone(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)
        )
        repo = ProgressRepositoryImpl(db, db.attemptDao(), db.dayResultDao(), clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun attempt(slotIndex: Int, score: Int) = PuzzleAttempt(
        id = 0,
        localDate = date,
        slotIndex = slotIndex,
        puzzleId = "p-$slotIndex",
        submittedOrder = listOf("a", "b", "c", "d"),
        score = score,
        submittedAt = 0L,
    )

    @Test
    fun `C4 - day_results is recalculated after 1, 2 and 3 attempts`() = runTest {
        repo.recordAttempt(attempt(0, 6))
        var result = repo.getDayResult(date)
        assertEquals(6, result?.totalScore)
        assertEquals(1, result?.completedCount)
        assertFalse(result?.isComplete ?: true)
        assertNull(result?.completedAt)

        repo.recordAttempt(attempt(1, 5))
        result = repo.getDayResult(date)
        assertEquals(11, result?.totalScore)
        assertEquals(2, result?.completedCount)
        assertFalse(result?.isComplete ?: true)
        assertNull(result?.completedAt)

        repo.recordAttempt(attempt(2, 4))
        result = repo.getDayResult(date)
        assertEquals(15, result?.totalScore)
        assertEquals(3, result?.completedCount)
        assertTrue(result?.isComplete ?: false)
        assertTrue(result?.completedAt != null)
    }

    @Test
    fun `C5 - a rejected attempt does not corrupt day_results`() = runTest {
        repo.recordAttempt(attempt(0, 6))

        // ITERATION_3_DESIGN.md, I3-D42 (PR 3B): доказанный повтор по (local_date, slot_index)
        // выходит наружу доменным типом, а не android.database.sqlite.SQLiteConstraintException;
        // транзакция по-прежнему откатывается целиком. Отказ по другой причине пробрасывается
        // как есть — это проверяет I3-U26.
        val repeated = assertThrows(AttemptAlreadyExistsException::class.java) {
            runBlocking { repo.recordAttempt(attempt(0, 3)) } // тот же (local_date, slot_index)
        }
        assertEquals(date, repeated.localDate)
        assertEquals(0, repeated.slotIndex)

        val result = repo.getDayResult(date)
        assertEquals(6, result?.totalScore)
        assertEquals(1, result?.completedCount)
    }

    @Test
    fun `C6 - out-of-range slotIndex and score are rejected before any write`() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.recordAttempt(attempt(3, 6)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.recordAttempt(attempt(0, 7)) }
        }

        assertNull(repo.getDayResult(date))
        assertEquals(0, db.attemptDao().getByDate(date.toString()).size)
    }
}
