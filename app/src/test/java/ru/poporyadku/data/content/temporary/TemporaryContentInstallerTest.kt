package ru.poporyadku.data.content.temporary

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.poporyadku.core.model.ContentPack
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.data.db.dao.DailySetDao
import ru.poporyadku.data.db.entity.DailySetEntity
import ru.poporyadku.data.db.entity.DayAssignmentEntity
import ru.poporyadku.data.db.entity.DayResultEntity
import ru.poporyadku.data.db.entity.PuzzleAttemptEntity
import ru.poporyadku.domain.content.ContentInstallException

// ITERATION_3_DESIGN.md, §19: I3-U2, I3-U24, I3-U25, I3-U29, I3-U37, I3-U42.
// Только установщик: TodayState и HomeState появляются в PR 3B и 3C (I3-U43 — не здесь).
@RunWith(RobolectricTestRunner::class)
class TemporaryContentInstallerTest {

    private lateinit var db: AppDatabase
    private lateinit var sets: CountingDailySetDao

    private val packId = ContentPack.CORE_RU
    private val expected = listOf(0, 1, 2)

    /** Считает записи установщика: «второй вызов ничего не пишет» иначе не проверить. */
    private class CountingDailySetDao(private val delegate: DailySetDao) : DailySetDao {
        var upsertCalls = 0
        var deleteCalls = 0

        override suspend fun upsertAll(sets: List<DailySetEntity>) {
            upsertCalls++
            delegate.upsertAll(sets)
        }

        override suspend fun getSet(packId: String, setIndex: Int): DailySetEntity? =
            delegate.getSet(packId, setIndex)

        override suspend fun countSets(packId: String): Int = delegate.countSets(packId)

        override suspend fun setIndexes(packId: String): List<Int> = delegate.setIndexes(packId)

        override suspend fun deleteOutside(packId: String, keep: List<Int>) {
            deleteCalls++
            delegate.deleteOutside(packId, keep)
        }

        // Диапазонные запросы PR 4B временный установщик не вызывает: он живёт
        // на списочных предикатах до PR 4D. Делегируются ради полноты интерфейса.
        override suspend fun byPack(packId: String): List<DailySetEntity> = delegate.byPack(packId)

        override suspend fun deleteOutsideRange(packId: String, setCount: Int): Int =
            delegate.deleteOutsideRange(packId, setCount)

        override suspend fun countSetsWithMissingPuzzles(packId: String, contentVersion: Int): Int =
            delegate.countSetsWithMissingPuzzles(packId, contentVersion)

        override fun observeAll(): Flow<List<DailySetEntity>> = delegate.observeAll()
    }

    /** Приостанавливает запись, чтобы отмена пришлась ровно на середину транзакции. */
    private class BlockingUpsertDailySetDao(
        private val delegate: DailySetDao,
        private val entered: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : DailySetDao by delegate {
        var deleteCalls = 0

        override suspend fun deleteOutside(packId: String, keep: List<Int>) {
            deleteCalls++
            delegate.deleteOutside(packId, keep)
        }

        override suspend fun upsertAll(sets: List<DailySetEntity>) {
            entered.complete(Unit)
            release.await()
            delegate.upsertAll(sets)
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        sets = CountingDailySetDao(db.dailySetDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun installer(dailySets: DailySetDao = sets) = TemporaryContentInstaller(
        db = db,
        sets = dailySets,
        assignments = db.assignmentDao(),
        activePackId = packId,
    )

    private suspend fun setIndexes(): List<Int> = db.dailySetDao().setIndexes(packId)

    private fun fixtureSet(setIndex: Int) = DailySetEntity(
        packId = packId,
        setIndex = setIndex,
        puzzleId1 = "debug-fixture-%03d".format(setIndex * 3 + 1),
        puzzleId2 = "debug-fixture-%03d".format(setIndex * 3 + 2),
        puzzleId3 = "debug-fixture-%03d".format(setIndex * 3 + 3),
    )

    private suspend fun seedProgress(date: String, setIndex: Int) {
        db.assignmentDao().insert(
            DayAssignmentEntity(localDate = date, packId = packId, setIndex = setIndex, assignedAt = 100L)
        )
        db.attemptDao().insert(
            PuzzleAttemptEntity(
                localDate = date,
                slotIndex = 0,
                puzzleId = "any-puzzle",
                submittedOrder = "c1,c2,c3,c4",
                score = 4,
                submittedAt = 200L,
            )
        )
        db.dayResultDao().upsert(
            DayResultEntity(
                localDate = date,
                totalScore = 4,
                completedCount = 1,
                isComplete = false,
                completedAt = null,
            )
        )
    }

    private suspend fun progressSnapshot(): Triple<List<DayAssignmentEntity>, List<PuzzleAttemptEntity>, List<DayResultEntity>> =
        Triple(
            db.assignmentDao().byDate("2026-09-01")?.let(::listOf).orEmpty(),
            db.attemptDao().getByDate("2026-09-01"),
            db.dayResultDao().getByDate("2026-09-01")?.let(::listOf).orEmpty(),
        )

    @Test
    fun `I3-U2 - ensureInstalled is idempotent and the second call writes nothing`() = runBlocking {
        val installer = installer()

        installer.ensureInstalled()
        assertEquals(expected, setIndexes())
        assertEquals(1, sets.upsertCalls)

        installer.ensureInstalled()

        // Три строки, а не шесть; и второй вызов не сделал ни одной записи.
        assertEquals(expected, setIndexes())
        assertEquals(3, db.dailySetDao().countSets(packId))
        assertEquals(1, sets.upsertCalls)
        assertEquals(0, sets.deleteCalls)
    }

    @Test
    fun `I3-U2 - installed sets match the bundled rotation`() = runBlocking {
        installer().ensureInstalled()

        val rows = expected.map { db.dailySetDao().getSet(packId, it) }
        assertEquals(BundledPuzzles.sets.map { it.puzzleId1 }, rows.map { it?.puzzleId1 })
        assertEquals(BundledPuzzles.sets.map { it.puzzleId2 }, rows.map { it?.puzzleId2 })
        assertEquals(BundledPuzzles.sets.map { it.puzzleId3 }, rows.map { it?.puzzleId3 })
    }

    @Test
    fun `I3-U24 - clearAllTables in the same process does not disable installation`() = runBlocking {
        val installer = installer()
        installer.ensureInstalled()
        assertEquals(expected, setIndexes())

        // Ровно то, что делает кнопка debug-экрана: тот же процесс, тот же экземпляр.
        db.clearAllTables()
        assertEquals(emptyList<Int>(), setIndexes())

        installer.ensureInstalled()

        // Источник истины — база, а не флаг: наборы восстановлены без перезапуска.
        assertEquals(expected, setIndexes())
        assertEquals(2, sets.upsertCalls)
    }

    @Test
    fun `I3-U25 - stale fixture sets without assignments are removed and progress is untouched`() = runBlocking {
        db.dailySetDao().upsertAll((0..4).map(::fixtureSet))
        seedProgress(date = "2026-09-01", setIndex = 1) // назначение ВНУТРИ expected
        val before = progressSnapshot()

        installer().ensureInstalled()

        assertEquals(expected, setIndexes())
        assertEquals(1, sets.deleteCalls)
        assertEquals(before, progressSnapshot())
        // Наборы 0..2 перезаписаны настоящим содержимым, а не остались фикстурой.
        assertEquals(
            BundledPuzzles.sets.first().puzzleId1,
            db.dailySetDao().getSet(packId, 0)?.puzzleId1,
        )
    }

    @Test
    fun `I3-U29 - a stale set with an assignment is a conflict and nothing is deleted`() = runBlocking {
        db.dailySetDao().upsertAll((0..4).map(::fixtureSet))
        seedProgress(date = "2026-09-01", setIndex = 4)
        val before = progressSnapshot()

        val conflict = assertThrows(ContentInstallException.Conflict::class.java) {
            runBlocking { installer().ensureInstalled() }
        }

        assertEquals(packId, conflict.packId)
        assertEquals(listOf(3, 4), conflict.staleSetIndexes)
        assertEquals(listOf(LocalDate.of(2026, 9, 1)), conflict.blockedDates)

        // Ни одной удалённой и ни одной записанной строки: транзакция откачена.
        assertEquals(listOf(0, 1, 2, 3, 4), setIndexes())
        assertEquals(fixtureSet(0).puzzleId1, db.dailySetDao().getSet(packId, 0)?.puzzleId1)
        assertEquals(before, progressSnapshot())
    }

    @Test
    fun `I3-U42 - a dangling assignment is detected even when daily_sets is exactly expected`() = runBlocking {
        // I3-D50: строки набора 4 в daily_sets уже нет, а назначение на неё осталось.
        db.dailySetDao().upsertAll(BundledPuzzles.sets.map { set ->
            DailySetEntity(packId, set.setIndex, set.puzzleId1, set.puzzleId2, set.puzzleId3)
        })
        seedProgress(date = "2026-09-01", setIndex = 4)
        val before = progressSnapshot()

        val conflict = assertThrows(ContentInstallException.Conflict::class.java) {
            runBlocking { installer().ensureInstalled() }
        }

        // staleSetIndexes взяты из day_assignments — daily_sets про индекс 4 не знает.
        assertTrue(conflict.staleSetIndexes.isNotEmpty())
        assertEquals(listOf(4), conflict.staleSetIndexes)
        assertTrue(conflict.blockedDates.isNotEmpty())
        assertEquals(listOf(LocalDate.of(2026, 9, 1)), conflict.blockedDates)

        // Ранний выход по совпадению состава не сработал, и ни одна таблица не изменена.
        assertEquals(expected, setIndexes())
        assertEquals(before, progressSnapshot())
        assertEquals(0, sets.deleteCalls)
        assertEquals(0, sets.upsertCalls)
    }

    @Test
    fun `I3-U37 - concurrent ensureInstalled installs three sets, not six`() = runBlocking {
        val installer = installer()

        val calls = (1..4).map {
            async(Dispatchers.IO) { installer.ensureInstalled() }
        }
        calls.awaitAll()

        assertEquals(expected, setIndexes())
        assertEquals(3, db.dailySetDao().countSets(packId))
        // Mutex сериализовал вызовы: остальные три увидели точный состав и вышли рано.
        assertEquals(1, sets.upsertCalls)
    }

    @Test
    fun `I3-U37 - cancellation leaves the database strictly before or strictly after`() = runBlocking {
        // «До» — остатки 3..4; «после» — ровно 0..2. Промежуточное состояние (излишек
        // удалён, нужное не записано) дало бы пустую таблицу.
        db.dailySetDao().upsertAll(listOf(fixtureSet(3), fixtureSet(4)))

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocking = BlockingUpsertDailySetDao(db.dailySetDao(), entered, release)

        val job = launch(Dispatchers.IO) { installer(blocking).ensureInstalled() }
        entered.await()
        job.cancelAndJoin()

        // Удаление излишка внутри транзакции состоялось — значит, [3, 4] ниже это
        // именно откат, а не «до deleteOutside дело не дошло».
        assertEquals(1, blocking.deleteCalls)

        val after = setIndexes()
        assertTrue(
            "промежуточное состояние: $after",
            after == listOf(3, 4) || after == expected,
        )
        assertEquals("транзакция обязана откатиться целиком", listOf(3, 4), after)

        // Установщик исправен после отмены: Mutex ничего не запомнил, следов транзакция
        // не оставила.
        installer().ensureInstalled()
        assertEquals(expected, setIndexes())
    }
}
