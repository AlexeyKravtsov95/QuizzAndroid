package ru.poporyadku.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.poporyadku.core.model.ContentPack
import ru.poporyadku.core.model.PuzzleAttempt
import ru.poporyadku.core.time.FakeClockProvider
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.data.db.dao.ArchiveDao.ArchiveDayRow
import ru.poporyadku.data.db.entity.DayAssignmentEntity
import ru.poporyadku.data.db.entity.DayResultEntity
import ru.poporyadku.data.progress.ProgressRepositoryImpl
import ru.poporyadku.domain.scoring.StatisticsCalculator

/**
 * `ArchiveDao` на in-memory Room — ITERATION_5_DESIGN.md, §3.3, §5.1, §10.2:
 * `I5-A1`–`I5-A5`, `I5-A8`–`I5-A10`. Пагинация (`I5-A6`, `I5-A7`) — `GetArchiveUseCaseTest`.
 *
 * `day_results` пишет настоящий `ProgressRepositoryImpl.recordAttempt` — единственный
 * продуктовый писатель таблицы; назначения вставляются DAO напрямую. Испорченное
 * состояние (`I5-A5`) создаётся прямой вставкой, потому что продуктовый путь его не создаёт.
 *
 * Собирается через `runBlocking`: Room-`Flow` отвечает с потоков Room, и виртуальное
 * время `runTest` проматывалось бы мимо них (как в `GetTodayStateUseCaseTest`).
 */
@RunWith(RobolectricTestRunner::class)
class ArchiveDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ArchiveDao
    private lateinit var progress: ProgressRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.archiveDao()
        val clock = Clock.fixed(LocalDate.of(2026, 9, 1).atTime(LocalTime.NOON).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
        progress = ProgressRepositoryImpl(db, db.attemptDao(), db.dayResultDao(), FakeClockProvider(clock))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun assign(date: String, setIndex: Int) {
        db.assignmentDao().insert(
            DayAssignmentEntity(localDate = date, packId = ContentPack.CORE_RU, setIndex = setIndex, assignedAt = 1L)
        )
    }

    /**
     * Попытки по слотам [firstSlot], [firstSlot] + 1, …; `null` — пропуск
     * (`Submission.Skip`: пустой порядок, 0 баллов).
     */
    private suspend fun play(date: String, vararg scores: Int?, firstSlot: Int = 0) {
        scores.forEachIndexed { index, score ->
            val slotIndex = firstSlot + index
            progress.recordAttempt(
                PuzzleAttempt(
                    id = 0,
                    localDate = LocalDate.parse(date),
                    slotIndex = slotIndex,
                    puzzleId = "p-$date-$slotIndex",
                    submittedOrder = if (score == null) emptyList() else listOf("c1", "c2", "c3", "c4"),
                    score = score ?: 0,
                    submittedAt = 0L,
                )
            )
        }
    }

    /** Назначение и полный день по 5 баллов за слот. */
    private suspend fun playedDay(date: String, setIndex: Int) {
        assign(date, setIndex)
        play(date, 5, 5, 5)
    }

    private fun List<ArchiveDayRow>.dates(): List<String> = map { it.localDate }

    /** Следующая эмиссия окна, удовлетворяющая [predicate]; промежуточные обязаны равняться [unchanged]. */
    private suspend fun ReceiveTurbine<List<ArchiveDayRow>>.awaitUntil(
        unchanged: List<ArchiveDayRow>,
        predicate: (List<ArchiveDayRow>) -> Boolean,
    ): List<ArchiveDayRow> {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
            assertEquals("промежуточная эмиссия окна изменила строки", unchanged, item)
        }
    }

    // --- I5-A1 ---------------------------------------------------------------------

    @Test
    fun `I5-A1 - strict local_date DESC across month and year boundaries`() = runBlocking {
        val expected = listOf(
            "2026-10-01", "2026-09-30", "2026-03-01", "2026-02-28", "2026-02-01",
            "2026-01-31", "2026-01-01", "2025-12-31", "2025-12-30", "2024-02-29",
        )
        // Вставка вперемешку: порядок обязан задавать ORDER BY, а не порядок вставки.
        expected.shuffled(Random(5)).forEachIndexed { setIndex, date -> playedDay(date, setIndex) }

        assertEquals(expected, dao.newest(51).dates())
        assertEquals(expected, dao.observeAll().first().dates())
        assertEquals(expected.take(3), dao.newest(3).dates())
        assertEquals(listOf("2025-12-31", "2025-12-30", "2024-02-29"), dao.olderThan("2026-01-01", 51).dates())
        assertEquals(listOf("2025-12-31"), dao.olderThan("2026-01-01", 1).dates())
        assertEquals(
            listOf("2026-10-01", "2026-09-30", "2026-03-01", "2026-02-28", "2026-02-01", "2026-01-31"),
            dao.observeFrom("2026-01-31").first().dates(),
        )
    }

    // --- I5-A2 ---------------------------------------------------------------------

    @Test
    fun `I5-A2 - a pending assignment without attempts is not in the archive`() = runBlocking {
        playedDay("2026-09-01", setIndex = 0)
        assign("2026-09-02", setIndex = 1) // «Играть» нажата, ни одной попытки

        assertEquals(listOf("2026-09-01"), dao.newest(51).dates())
        assertEquals(listOf("2026-09-01"), dao.observeAll().first().dates())
        assertEquals(listOf("2026-09-01"), dao.observeFrom("2026-09-01").first().dates())
        assertTrue(dao.olderThan("2026-09-01", 51).isEmpty())
    }

    // --- I5-A3 ---------------------------------------------------------------------

    @Test
    fun `I5-A3 - an incomplete day is listed with its partial score and closed slots`() = runBlocking {
        assign("2026-09-01", setIndex = 4)
        play("2026-09-01", 5)
        assertEquals(listOf(ArchiveDayRow("2026-09-01", 4, 5, 1, false)), dao.newest(51))

        assign("2026-09-02", setIndex = 5)
        play("2026-09-02", 4, 3)
        assertEquals(
            listOf(ArchiveDayRow("2026-09-02", 5, 7, 2, false), ArchiveDayRow("2026-09-01", 4, 5, 1, false)),
            dao.newest(51),
        )
    }

    // --- I5-A4 ---------------------------------------------------------------------

    @Test
    fun `I5-A4 - a skip is a closed slot with zero points, three skips make a complete 0 of 18`() = runBlocking {
        assign("2026-09-01", setIndex = 0)
        play("2026-09-01", null)
        assign("2026-09-02", setIndex = 1)
        play("2026-09-02", 6, null, 4)
        assign("2026-09-03", setIndex = 2)
        play("2026-09-03", null, null, null)

        assertEquals(
            listOf(
                ArchiveDayRow("2026-09-03", 2, 0, 3, true),
                ArchiveDayRow("2026-09-02", 1, 10, 3, true),
                ArchiveDayRow("2026-09-01", 0, 0, 1, false),
            ),
            dao.newest(51),
        )
    }

    // --- I5-A5 ---------------------------------------------------------------------

    @Test
    fun `I5-A5 - an orphan day_results row is absent from the archive but counted by statistics`() = runBlocking {
        playedDay("2026-09-01", setIndex = 0)
        playedDay("2026-09-03", setIndex = 1)
        // Нарушение инварианта записи: результат без назначения на ту же дату.
        db.dayResultDao().upsert(
            DayResultEntity(localDate = "2026-09-02", totalScore = 18, completedCount = 3, isComplete = true, completedAt = 1L)
        )

        val archived = listOf("2026-09-03", "2026-09-01")
        assertEquals(archived, dao.newest(51).dates())
        assertEquals(archived, dao.observeAll().first().dates())
        assertEquals(archived, dao.observeFrom("2026-09-01").first().dates())
        assertEquals(listOf("2026-09-01"), dao.olderThan("2026-09-03", 51).dates())

        // Статистика считается, как у Home, по всем строкам day_results — строка учтена.
        val statistics = StatisticsCalculator.of(progress.getAllDayResults(), LocalDate.of(2026, 9, 3))
        assertEquals(3, statistics.playedDays)
        assertEquals(3, statistics.completedDays)
        assertEquals(18, statistics.bestDayScore)
        assertEquals(3, statistics.streaks.current)
    }

    @Test
    fun `I5-A5 - the window is invalidated by day_assignments - an orphan appears once its assignment exists`() =
        runBlocking {
            playedDay("2026-09-01", setIndex = 0)
            db.dayResultDao().upsert(
                DayResultEntity(localDate = "2026-09-02", totalScore = 9, completedCount = 2, isComplete = false, completedAt = null)
            )

            dao.observeFrom("2026-09-01").test {
                val before = awaitItem()
                assertEquals(listOf("2026-09-01"), before.dates())

                // Пишется ТОЛЬКО day_assignments: новое окно обязан перечитать сам Room.
                assign("2026-09-02", setIndex = 1)
                val after = awaitUntil(before) { it.size == 2 }
                assertEquals(
                    listOf(ArchiveDayRow("2026-09-02", 1, 9, 2, false), ArchiveDayRow("2026-09-01", 0, 15, 3, true)),
                    after,
                )

                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- I5-A8 ---------------------------------------------------------------------

    @Test
    fun `I5-A8 - the second and third attempts update the same row in the window`() = runBlocking {
        playedDay("2026-09-01", setIndex = 0)
        assign("2026-09-02", setIndex = 1)
        play("2026-09-02", 4)

        dao.observeFrom("2026-09-01").test {
            val first = awaitItem()
            assertEquals(
                listOf(ArchiveDayRow("2026-09-02", 1, 4, 1, false), ArchiveDayRow("2026-09-01", 0, 15, 3, true)),
                first,
            )

            play("2026-09-02", 5, firstSlot = 1)
            val second = awaitUntil(first) { it.first().completedCount == 2 }
            assertEquals(
                listOf(ArchiveDayRow("2026-09-02", 1, 9, 2, false), ArchiveDayRow("2026-09-01", 0, 15, 3, true)),
                second,
            )

            play("2026-09-02", 6, firstSlot = 2)
            val third = awaitUntil(second) { it.first().completedCount == 3 }
            assertEquals(
                listOf(ArchiveDayRow("2026-09-02", 1, 15, 3, true), ArchiveDayRow("2026-09-01", 0, 15, 3, true)),
                third,
            )

            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- I5-A9 ---------------------------------------------------------------------

    @Test
    fun `I5-A9 - carrying over a pending assignment changes neither the rows nor their count`() = runBlocking {
        playedDay("2026-09-01", setIndex = 0)
        playedDay("2026-09-02", setIndex = 1)
        assign("2026-09-03", setIndex = 2) // отложенное: ни одной попытки

        dao.observeAll().test {
            val before = awaitItem()
            assertEquals(listOf("2026-09-02", "2026-09-01"), before.dates())

            // Перенос — UPDATE той же строки day_assignments, вставки нет (ARCHITECTURE.md, §3).
            val moved = db.assignmentDao().carryOver(
                packId = ContentPack.CORE_RU,
                pendingDate = "2026-09-03",
                today = "2026-09-05",
                now = 2L,
            )
            assertEquals(1, moved)
            assertEquals(before, dao.newest(51))
            assertEquals(before, dao.observeFrom("2026-09-01").first())

            // Первая попытка на новую дату: день появляется сверху ровно один раз,
            // с номером дня перенесённого назначения; прежние строки не тронуты.
            play("2026-09-05", 6)
            val after = awaitUntil(before) { it.size == 3 }
            assertEquals(listOf(ArchiveDayRow("2026-09-05", 2, 6, 1, false)) + before, after)
            assertEquals(after.dates().distinct(), after.dates())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- I5-A10 --------------------------------------------------------------------

    @Test
    fun `I5-A10 - a date in the future relative to today is listed first`() = runBlocking {
        // Сегодня 2026-09-03; 09-20 сыгран при переведённых вперёд часах, затем часы вернули.
        playedDay("2026-09-01", setIndex = 0)
        playedDay("2026-09-02", setIndex = 1)
        playedDay("2026-09-20", setIndex = 2)
        assign("2026-09-03", setIndex = 3)
        play("2026-09-03", 3)

        val expected = listOf("2026-09-20", "2026-09-03", "2026-09-02", "2026-09-01")
        assertEquals(expected, dao.newest(51).dates())
        assertEquals(expected, dao.observeAll().first().dates())
        assertEquals(expected.take(2), dao.observeFrom("2026-09-03").first().dates())
    }
}
