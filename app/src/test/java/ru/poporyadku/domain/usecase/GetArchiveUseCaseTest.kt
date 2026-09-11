package ru.poporyadku.domain.usecase

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import ru.poporyadku.data.db.entity.DayAssignmentEntity
import ru.poporyadku.data.db.entity.DayResultEntity
import ru.poporyadku.data.progress.ProgressRepositoryImpl
import ru.poporyadku.data.repository.ArchiveRepositoryImpl
import ru.poporyadku.domain.model.ArchiveDay
import ru.poporyadku.domain.repository.ArchiveRepository

/**
 * `GetArchiveUseCase` поверх настоящих `ArchiveRepositoryImpl` и `ArchiveDao` —
 * ITERATION_5_DESIGN.md, §3.4, §5.1, §7.4, §10.2: `I5-A6`, `I5-A7`.
 *
 * Сценарий подгрузки повторяет то, что сделает экран архива в PR 5B: разведка
 * двигает нижнюю границу, показанные строки — всегда окно `>=` неё. Репозиторий
 * обёрнут счётчиком: число разведок, их лимиты и размеры ответов — предмет проверки
 * («на 50 и 100 строках лишней разведки нет»).
 *
 * `runBlocking`, а не `runTest`: Room-`Flow` отвечает с потоков Room.
 */
@RunWith(RobolectricTestRunner::class)
class GetArchiveUseCaseTest {

    private lateinit var db: AppDatabase
    private lateinit var progress: ProgressRepositoryImpl
    private lateinit var archive: CountingArchive
    private lateinit var useCase: GetArchiveUseCase

    /** Первая дата истории; дни идут подряд и пересекают границы месяцев и года. */
    private val firstDay = LocalDate.of(2025, 11, 15)

    private class Probe(val before: LocalDate?, val limit: Int, val returned: List<ArchiveDay>)

    private class CountingArchive(private val delegate: ArchiveRepository) : ArchiveRepository {
        val probes = mutableListOf<Probe>()

        override suspend fun probe(before: LocalDate?, limit: Int): List<ArchiveDay> =
            delegate.probe(before, limit).also { probes += Probe(before, limit, it) }

        override fun observeWindow(lowerBound: LocalDate?): Flow<List<ArchiveDay>> =
            delegate.observeWindow(lowerBound)
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val clock = Clock.fixed(firstDay.atTime(LocalTime.NOON).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
        progress = ProgressRepositoryImpl(db, db.attemptDao(), db.dayResultDao(), FakeClockProvider(clock))
        archive = CountingArchive(ArchiveRepositoryImpl(db.archiveDao()))
        useCase = GetArchiveUseCase(archive)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** [count] сыгранных дней подряд с `firstDay`; `set_index` — порядковый номер дня. */
    private suspend fun seedDays(count: Int) {
        db.withTransaction {
            repeat(count) { index ->
                val date = firstDay.plusDays(index.toLong()).toString()
                val completedCount = index % 3 + 1
                db.assignmentDao().insert(DayAssignmentEntity(date, ContentPack.CORE_RU, index, 1L))
                db.dayResultDao().upsert(
                    DayResultEntity(
                        localDate = date,
                        totalScore = index % (completedCount * 6 + 1),
                        completedCount = completedCount,
                        isComplete = completedCount == 3,
                        completedAt = if (completedCount == 3) 1L else null,
                    )
                )
            }
        }
    }

    /** Новый сыгранный день продуктовым путём: назначение, затем первая попытка. */
    private suspend fun playNewDay(date: LocalDate, setIndex: Int, score: Int) {
        db.assignmentDao().insert(DayAssignmentEntity(date.toString(), ContentPack.CORE_RU, setIndex, 1L))
        progress.recordAttempt(
            PuzzleAttempt(0, date, 0, "p-$date", listOf("c1", "c2", "c3", "c4"), score, 0L)
        )
    }

    private fun List<ArchiveDay>.dates(): List<LocalDate> = map { it.localDate }

    /** Все даты истории из [count] дней — по убыванию, как их обязан показать архив. */
    private fun allDatesDesc(count: Int): List<LocalDate> =
        (count - 1 downTo 0).map { firstDay.plusDays(it.toLong()) }

    /** Итог прогона подгрузки до конца: окна после каждой разведки и выбранные границы. */
    private class LoadRun(val windows: List<List<ArchiveDay>>, val bounds: List<LocalDate?>)

    /** То, что сделает экран: разведка → окно; пока есть продолжение — разведка от границы. */
    private suspend fun loadEverything(): LoadRun {
        val windows = mutableListOf<List<ArchiveDay>>()
        val bounds = mutableListOf<LocalDate?>()
        var page = useCase.probe(before = null)
        while (true) {
            bounds += page.nextLowerBound
            windows += useCase.observeWindow(page.nextLowerBound).first()
            if (!page.hasMore) return LoadRun(windows, bounds)
            page = useCase.probe(before = checkNotNull(page.nextLowerBound) { "hasMore без границы" })
        }
    }

    // --- контракт разведки ------------------------------------------------------

    @Test
    fun `probe asks for PAGE_SIZE plus one rows and forwards the cursor unchanged`() = runBlocking {
        seedDays(120)

        val first = useCase.probe(before = null)
        useCase.probe(before = first.nextLowerBound)

        assertEquals(listOf(51, 51), archive.probes.map { it.limit })
        assertEquals(listOf(null, first.nextLowerBound), archive.probes.map { it.before })
        assertEquals(50, GetArchiveUseCase.PAGE_SIZE)
    }

    // --- I5-A6 ---------------------------------------------------------------------

    @Test
    fun `I5-A6 - 0 rows - one probe, unbounded window, nothing shown`() = runBlocking {
        seedDays(0)

        val run = loadEverything()

        assertEquals(listOf(0), archive.probes.map { it.returned.size })
        assertEquals(listOf<LocalDate?>(null), run.bounds)
        assertEquals(listOf(0), run.windows.map { it.size })
    }

    @Test
    fun `I5-A6 - 1, 49 and 50 rows - one probe, no continuation, no extra empty probe`() = runBlocking {
        for (count in listOf(1, 49, 50)) {
            db.clearAllTables()
            archive.probes.clear()
            seedDays(count)

            val run = loadEverything()

            assertEquals("count=$count", listOf(count), archive.probes.map { it.returned.size })
            assertEquals("count=$count", listOf<LocalDate?>(null), run.bounds)
            assertEquals("count=$count", allDatesDesc(count), run.windows.single().dates())
        }
    }

    @Test
    fun `I5-A6 - 51 rows - boundary is the 50th date, second probe returns the last row`() = runBlocking {
        seedDays(51)
        val all = allDatesDesc(51)

        val run = loadEverything()

        assertEquals(listOf(51, 1), archive.probes.map { it.returned.size })
        assertEquals(listOf(null, all[49]), archive.probes.map { it.before })
        assertEquals(listOf(all[49], null), run.bounds)
        assertEquals(listOf(50, 51), run.windows.map { it.size })
        assertEquals(all, run.windows.last().dates())
    }

    @Test
    fun `I5-A6 - 100 rows - two probes, the second returns 50 rows and ends without an empty probe`() = runBlocking {
        seedDays(100)
        val all = allDatesDesc(100)

        val run = loadEverything()

        assertEquals(listOf(51, 50), archive.probes.map { it.returned.size })
        assertEquals(listOf(all[49], null), run.bounds)
        assertEquals(listOf(50, 100), run.windows.map { it.size })
        assertEquals(all, run.windows.last().dates())
    }

    @Test
    fun `I5-A6 - 101 rows - three probes with boundaries at the 50th and 100th dates`() = runBlocking {
        seedDays(101)
        val all = allDatesDesc(101)

        val run = loadEverything()

        assertEquals(listOf(51, 51, 1), archive.probes.map { it.returned.size })
        assertEquals(listOf(null, all[49], all[99]), archive.probes.map { it.before })
        assertEquals(listOf(all[49], all[99], null), run.bounds)
        assertEquals(listOf(50, 100, 101), run.windows.map { it.size })
        assertEquals(all, run.windows.last().dates())
    }

    @Test
    fun `I5-A6 - for every size the union of shown rows is all days, once each, strictly descending`() =
        runBlocking {
            val expectedProbes = mapOf(0 to 1, 1 to 1, 49 to 1, 50 to 1, 51 to 2, 100 to 2, 101 to 3)
            for ((count, probes) in expectedProbes) {
                db.clearAllTables()
                archive.probes.clear()
                seedDays(count)

                val run = loadEverything()
                val shown = run.windows.flatten().map { it.localDate }.toSet()
                val finalDates = run.windows.last().dates()

                assertEquals("count=$count: разведок", probes, archive.probes.size)
                assertTrue("count=$count: пустая разведка", count == 0 || archive.probes.none { it.returned.isEmpty() })
                assertTrue("count=$count: лимит", archive.probes.all { it.limit == GetArchiveUseCase.PAGE_SIZE + 1 })
                assertEquals("count=$count: объединение показанного", allDatesDesc(count).toSet(), shown)
                assertEquals("count=$count: порядок и дубли", allDatesDesc(count), finalDates)
                // Окно только растёт: каждое следующее начинается с предыдущего целиком.
                run.windows.zipWithNext().forEach { (earlier, later) ->
                    assertEquals("count=$count: окно потеряло строки", earlier, later.take(earlier.size))
                }
                val page = useCase.probe(before = null)
                assertEquals("count=$count", count > GetArchiveUseCase.PAGE_SIZE, page.hasMore)
                if (!page.hasMore) assertNull(page.nextLowerBound)
            }
        }

    // --- I5-A7 ---------------------------------------------------------------------

    /** Следующая эмиссия, удовлетворяющая [predicate]; промежуточные обязаны равняться [unchanged]. */
    private suspend fun ReceiveTurbine<List<ArchiveDay>>.awaitUntil(
        unchanged: List<ArchiveDay>,
        predicate: (List<ArchiveDay>) -> Boolean,
    ): List<ArchiveDay> {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
            assertEquals("промежуточная эмиссия окна изменила строки", unchanged, item)
        }
    }

    @Test
    fun `I5-A7 - new days on top after the probe and during observation - no loss, no duplicates`() = runBlocking {
        seedDays(51)
        val seeded = allDatesDesc(51)
        val dayX = seeded.first().plusDays(1)
        val dayY = seeded.first().plusDays(2)

        val page = useCase.probe(before = null)
        assertTrue(page.hasMore)
        val bound = checkNotNull(page.nextLowerBound)
        assertEquals(seeded[49], bound)

        // Между разведкой и подпиской: окно ограничено датой, новый день попадает в него.
        playNewDay(dayX, setIndex = 51, score = 6)

        useCase.observeWindow(bound).test {
            val first = awaitItem()
            assertEquals(listOf(dayX) + seeded.take(50), first.dates())

            // Во время наблюдения: день появляется сверху, прежние 51 строка на месте.
            playNewDay(dayY, setIndex = 52, score = 4)
            val second = awaitUntil(first) { it.size == 52 }
            assertEquals(listOf(dayY) + first.dates(), second.dates())
            assertEquals(first, second.drop(1))

            cancelAndIgnoreRemainingEvents()
        }

        // Подгрузка от той же границы: строго раньше неё — ровно последняя строка,
        // без повтора 50-й (так ошибся бы OFFSET после двух новых строк сверху).
        val next = useCase.probe(before = bound)
        assertFalse(next.hasMore)
        assertNull(next.nextLowerBound)
        assertEquals(listOf(seeded.last()), archive.probes.last().returned.dates())

        val everything = useCase.observeWindow(next.nextLowerBound).first().dates()
        assertEquals(listOf(dayY, dayX) + seeded, everything)
        assertEquals(everything.distinct(), everything)
    }
}
