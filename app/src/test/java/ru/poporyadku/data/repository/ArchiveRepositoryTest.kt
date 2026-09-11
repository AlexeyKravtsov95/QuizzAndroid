package ru.poporyadku.data.repository

import java.time.DateTimeException
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import ru.poporyadku.data.db.dao.ArchiveDao
import ru.poporyadku.data.db.dao.ArchiveDao.ArchiveDayRow
import ru.poporyadku.domain.model.ArchiveDay

/**
 * `ArchiveRepositoryImpl` — ITERATION_5_DESIGN.md, §3.5 (п. 7), §5.1, §10.1: строгий
 * mapper строки архива (`I5-S8`) и выбор запроса `ArchiveDao` по курсору и границе.
 *
 * Чистый JVM на рукописном `ArchiveDao`: испорченную строку настоящий писатель
 * (`ProgressRepositoryImpl`, политика выдачи) создать не может, поэтому она подаётся
 * напрямую. Каждый случай проверяется на обоих путях отображения — разведке и окне.
 */
class ArchiveRepositoryTest {

    /** Отдаёт заданные строки на любой запрос и записывает, какой запрос был выбран. */
    private class FakeArchiveDao(private val rows: List<ArchiveDayRow> = emptyList()) : ArchiveDao {
        val calls = mutableListOf<String>()

        override suspend fun newest(limit: Int): List<ArchiveDayRow> {
            calls += "newest($limit)"
            return rows
        }

        override suspend fun olderThan(before: String, limit: Int): List<ArchiveDayRow> {
            calls += "olderThan($before, $limit)"
            return rows
        }

        override fun observeFrom(lowerBound: String): Flow<List<ArchiveDayRow>> {
            calls += "observeFrom($lowerBound)"
            return flowOf(rows)
        }

        override fun observeAll(): Flow<List<ArchiveDayRow>> {
            calls += "observeAll()"
            return flowOf(rows)
        }
    }

    private fun row(
        localDate: String = "2026-08-07",
        setIndex: Int = 11,
        totalScore: Int = 15,
        completedCount: Int = 3,
        isComplete: Boolean = completedCount == 3,
    ) = ArchiveDayRow(localDate, setIndex, totalScore, completedCount, isComplete)

    /** Строка отвергается и разведкой, и окном — оба пути идут через один mapper. */
    private fun <T : Throwable> assertRejected(expected: Class<T>, bad: ArchiveDayRow) {
        val repository = ArchiveRepositoryImpl(FakeArchiveDao(listOf(bad)))
        assertThrows("probe: $bad", expected) { runBlocking { repository.probe(null, 51) } }
        assertThrows("observeWindow: $bad", expected) { runBlocking { repository.observeWindow(null).first() } }
    }

    private fun assertRejected(bad: ArchiveDayRow) = assertRejected(IllegalStateException::class.java, bad)

    /** Строка принимается обоими путями и отображается без единой подмены. */
    private fun assertAccepted(good: ArchiveDayRow, expected: ArchiveDay) = runBlocking {
        val repository = ArchiveRepositoryImpl(FakeArchiveDao(listOf(good)))
        assertEquals(listOf(expected), repository.probe(null, 51))
        assertEquals(listOf(expected), repository.observeWindow(null).first())
    }

    // --- выбор запроса ----------------------------------------------------------

    @Test
    fun `probe without a cursor reads the newest rows, with a cursor - strictly older ones`() = runTest {
        val dao = FakeArchiveDao()
        val repository = ArchiveRepositoryImpl(dao)

        repository.probe(before = null, limit = 51)
        repository.probe(before = LocalDate.of(2026, 1, 9), limit = 51)

        assertEquals(listOf("newest(51)", "olderThan(2026-01-09, 51)"), dao.calls)
    }

    @Test
    fun `window without a lower bound observes all days, with a bound - days from it`() = runTest {
        val dao = FakeArchiveDao()
        val repository = ArchiveRepositoryImpl(dao)

        repository.observeWindow(lowerBound = null).first()
        repository.observeWindow(lowerBound = LocalDate.of(2025, 12, 31)).first()

        assertEquals(listOf("observeAll()", "observeFrom(2025-12-31)"), dao.calls)
    }

    @Test
    fun `a valid row maps field by field, dayNumber is setIndex plus one`() {
        assertAccepted(
            row(localDate = "2026-08-25", setIndex = 11, totalScore = 8, completedCount = 2),
            ArchiveDay(LocalDate.of(2026, 8, 25), dayNumber = 12, totalScore = 8, completedCount = 2, isComplete = false),
        )
    }

    // --- I5-S8: отвергаются --------------------------------------------------------

    @Test
    fun `I5-S8 - an unparsable date is rejected`() {
        assertRejected(DateTimeException::class.java, row(localDate = "2026-02-30"))
        assertRejected(DateTimeException::class.java, row(localDate = "2026-8-7"))
        assertRejected(DateTimeException::class.java, row(localDate = "не дата"))
    }

    @Test
    fun `I5-S8 - a negative set_index is rejected`() {
        assertRejected(row(setIndex = -1))
    }

    @Test
    fun `I5-S8 - set_index Int MAX_VALUE is rejected before dayNumber could overflow`() {
        // Без проверки строка превратилась бы в «День −2147483648», а не в исключение.
        assertRejected(row(setIndex = Int.MAX_VALUE))
    }

    @Test
    fun `I5-S8 - completed_count outside 1 to 3 is rejected`() {
        assertRejected(row(completedCount = 0, totalScore = 0, isComplete = false))
        assertRejected(row(completedCount = 4, totalScore = 18, isComplete = false))
        assertRejected(row(completedCount = 4, totalScore = 18, isComplete = true))
    }

    @Test
    fun `I5-S8 - a negative total_score is rejected`() {
        assertRejected(row(totalScore = -1, completedCount = 1))
        assertRejected(row(totalScore = -1, completedCount = 3))
    }

    @Test
    fun `I5-S8 - total_score above six per closed slot is rejected`() {
        assertRejected(row(totalScore = 7, completedCount = 1))
        assertRejected(row(totalScore = 13, completedCount = 2))
        assertRejected(row(totalScore = 19, completedCount = 3))
    }

    @Test
    fun `I5-S8 - is_complete disagreeing with completed_count is rejected`() {
        assertRejected(row(completedCount = 3, isComplete = false))
        assertRejected(row(completedCount = 2, totalScore = 12, isComplete = true))
        assertRejected(row(completedCount = 1, totalScore = 6, isComplete = true))
    }

    @Test
    fun `I5-S8 - one corrupt row fails the whole read instead of being dropped`() = runTest {
        val repository = ArchiveRepositoryImpl(
            FakeArchiveDao(listOf(row(localDate = "2026-08-07"), row(localDate = "2026-08-06", totalScore = 19))),
        )

        assertThrows(IllegalStateException::class.java) { runBlocking { repository.probe(null, 51) } }
        assertThrows(IllegalStateException::class.java) { runBlocking { repository.observeWindow(null).first() } }
    }

    // --- I5-S8: принимаются границы, без обрезки --------------------------------------

    @Test
    fun `I5-S8 - set_index 0 is day 1`() {
        assertAccepted(
            row(setIndex = 0),
            ArchiveDay(LocalDate.of(2026, 8, 7), dayNumber = 1, totalScore = 15, completedCount = 3, isComplete = true),
        )
    }

    @Test
    fun `I5-S8 - set_index Int MAX_VALUE minus 1 gives dayNumber Int MAX_VALUE`() {
        assertAccepted(
            row(setIndex = Int.MAX_VALUE - 1),
            ArchiveDay(
                LocalDate.of(2026, 8, 7),
                dayNumber = Int.MAX_VALUE,
                totalScore = 15,
                completedCount = 3,
                isComplete = true,
            ),
        )
    }

    @Test
    fun `I5-S8 - boundary scores for each number of closed slots are kept as is`() {
        val cases = listOf(
            // completedCount to totalScore
            1 to 0,
            1 to 6,
            2 to 12,
            3 to 18,
            // Три пропуска — завершённый день «0 из 18».
            3 to 0,
        )
        for ((completedCount, totalScore) in cases) {
            assertAccepted(
                row(completedCount = completedCount, totalScore = totalScore),
                ArchiveDay(
                    LocalDate.of(2026, 8, 7),
                    dayNumber = 12,
                    totalScore = totalScore,
                    completedCount = completedCount,
                    isComplete = completedCount == 3,
                ),
            )
        }
    }
}
