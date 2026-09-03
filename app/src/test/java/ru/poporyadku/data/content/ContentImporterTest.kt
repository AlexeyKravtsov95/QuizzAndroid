package ru.poporyadku.data.content

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.poporyadku.data.content.dto.DailySetDto
import ru.poporyadku.data.content.dto.ManifestDto
import ru.poporyadku.data.content.dto.ManifestFileDto
import ru.poporyadku.data.content.dto.PuzzleDto
import ru.poporyadku.data.content.mapper.toEntity
import ru.poporyadku.data.content.validation.ContentValidator
import ru.poporyadku.data.content.validation.ContentViolation
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.data.db.dao.DailySetDao
import ru.poporyadku.data.db.entity.DailySetEntity
import ru.poporyadku.data.db.entity.DayAssignmentEntity
import ru.poporyadku.data.db.entity.DayResultEntity
import ru.poporyadku.data.db.entity.PuzzleAttemptEntity
import ru.poporyadku.data.repository.PuzzleRepositoryImpl
import ru.poporyadku.di.ContentModule
import ru.poporyadku.domain.content.ContentInstallException

/**
 * Настоящий импортёр на in-memory Room — `I4-I1`…`I4-I24` (ITERATION_4_DESIGN.md, §10).
 *
 * Пакеты синтетические ([PackFixtures]): ветки отзыва, замены по слотам и двух версий
 * одного пакета в общих фикстурах валидатора не представлены и быть там не должны.
 *
 * Общий инвариант всех веток — `I4-I19`: `day_assignments`, `puzzle_attempts`
 * и `day_results` не изменяются ни разу. Он проверяется в каждом сценарии, где
 * история вообще есть, через [historySnapshot].
 */
@RunWith(RobolectricTestRunner::class)
class ContentImporterTest {

    private lateinit var db: AppDatabase
    private lateinit var prefs: FakeUserPreferencesRepository
    private lateinit var source: InMemoryContentSource
    private lateinit var puzzleDao: CountingPuzzleDao
    private lateinit var setDao: CountingDailySetDao

    private val packId = PackFixtures.PACK_ID
    private val assetJson: Json = ContentModule.assetJson()
    private val storageJson: Json = ContentModule.storageJson()

    /** Базовый пакет: пять наборов, пятнадцать головоломок, `contentVersion = 1`. */
    private val basePuzzles: List<PuzzleDto>
    private val baseSets: List<DailySetDto>

    init {
        val (puzzles, sets) = PackFixtures.linearPack(setCount = 5)
        basePuzzles = puzzles
        baseSets = sets
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        prefs = FakeUserPreferencesRepository()
        source = InMemoryContentSource(PackFixtures.files(basePuzzles, baseSets))
        puzzleDao = CountingPuzzleDao(db.puzzleDao())
        setDao = CountingDailySetDao(db.dailySetDao())
    }

    @After
    fun tearDown() = db.close()

    // ---------- сборка импортёра ----------

    private fun importer(
        verifyIntegrity: Boolean = true,
        sets: DailySetDao = setDao,
    ) = ContentImporter(
        db = db,
        puzzleDao = puzzleDao,
        setDao = sets,
        assignmentDao = db.assignmentDao(),
        reader = ContentPackReader(source, assetJson, verifyIntegrity),
        validator = ContentValidator(),
        prefs = prefs,
        storageJson = storageJson,
        activePackId = packId,
    )

    /** Отпечаток текущего содержимого источника — то, что импортёр обязан записать. */
    private fun fingerprint(): String =
        PackFixtures.sha256(source.currentFiles()[ContentPaths.MANIFEST]!!)

    private fun conflictFrom(block: suspend () -> Unit): ContentInstallException.Conflict =
        assertThrows(ContentInstallException.Conflict::class.java) { runBlocking { block() } }

    // ---------- посев истории ----------

    private suspend fun assign(setIndex: Int, date: String) {
        db.assignmentDao().insert(
            DayAssignmentEntity(localDate = date, packId = packId, setIndex = setIndex, assignedAt = 0)
        )
    }

    private suspend fun play(date: String, slotIndex: Int, puzzleId: String) {
        db.attemptDao().insert(
            PuzzleAttemptEntity(
                localDate = date,
                slotIndex = slotIndex,
                puzzleId = puzzleId,
                submittedOrder = "c1,c2,c3,c4",
                score = 6,
                submittedAt = 0,
            )
        )
        db.dayResultDao().upsert(
            DayResultEntity(
                localDate = date,
                totalScore = 6,
                completedCount = 1,
                isComplete = false,
                completedAt = null,
            )
        )
    }

    private suspend fun countPuzzles() = db.puzzleDao().countByPack(packId)
    private suspend fun countSets() = db.dailySetDao().countSets(packId)

    // ================= I4-I1: первая установка =================

    @Test
    fun `I4-I1 - the first install writes every row and stamps version and fingerprint`() =
        runBlocking {
            val expectedFingerprint = fingerprint()

            importer().ensureInstalled()

            assertEquals(15, countPuzzles())
            assertEquals(5, countSets())
            assertEquals(1, prefs.current.storedContentVersion)
            assertEquals(expectedFingerprint, prefs.current.storedContentFingerprint)
            assertEquals(1, prefs.writes)
            // Порядок наборов и состав троек — ровно из assets.
            val stored = db.dailySetDao().byPack(packId)
            assertEquals((0..4).toList(), stored.map { it.setIndex })
            assertEquals(baseSets[0].puzzleIds[0], stored[0].puzzleId1)
            assertEquals(baseSets[4].puzzleIds[2], stored[4].puzzleId3)
        }

    // ================= I4-I2: повтор =================

    @Test
    fun `I4-I2 - a repeat writes nothing, deletes nothing and never reads the body`() =
        runBlocking {
            val subject = importer()
            subject.ensureInstalled()
            val afterFirst = db.snapshot()
            val writesAfterFirst = prefs.writes
            val upsertsAfterFirst = puzzleDao.upsertCalls
            val bodyReadsAfterFirst = source.readCount("puzzles-001.json")

            subject.ensureInstalled()

            assertEquals(afterFirst, db.snapshot())
            assertEquals(writesAfterFirst, prefs.writes)
            assertEquals(upsertsAfterFirst, puzzleDao.upsertCalls)
            assertEquals(1, setDao.upsertCalls)
            assertEquals(1, setDao.deleteCalls)
            // Быстрый путь не читает тело пакета: ради этого он и существует.
            assertEquals(bodyReadsAfterFirst, source.readCount("puzzles-001.json"))
        }

    // ================= I4-I3: обновление =================

    @Test
    fun `I4-I3 - an update rewrites content and leaves history bit-identical`() = runBlocking {
        importer().ensureInstalled()
        assign(setIndex = 0, date = "2026-09-01")
        play("2026-09-01", 0, baseSets[0].puzzleIds[0])
        val historyBefore = db.historySnapshot()

        // Версия 2: у первой головоломки другое объяснение, составы наборов те же.
        val updated = basePuzzles.mapIndexed { i, p ->
            if (i == 0) p.copy(explanation = p.explanation + " Уточнение версии 2.") else p
        }
        source.replace(PackFixtures.files(updated, baseSets, contentVersion = 2))

        importer().ensureInstalled()

        assertEquals(2, prefs.current.storedContentVersion)
        assertEquals(fingerprint(), prefs.current.storedContentFingerprint)
        val puzzle = PuzzleRepositoryImpl(db.puzzleDao(), storageJson)
            .getPuzzle(basePuzzles[0].puzzleId)
        assertTrue(puzzle!!.explanation.endsWith("Уточнение версии 2."))
        assertEquals(2, puzzle.contentVersion)
        assertEquals(historyBefore, db.historySnapshot())
    }

    // ================= I4-I4: откат =================

    @Test
    fun `I4-I4 - a rollback re-imports because the fast path requires equality, not order`() =
        runBlocking {
            prefs.setInstalledContent(contentVersion = 9, fingerprint = "0".repeat(64))
            val writesBefore = prefs.writes

            importer().ensureInstalled()

            assertEquals(1, prefs.current.storedContentVersion)
            assertEquals(15, countPuzzles())
            assertTrue(prefs.writes > writesBefore)
        }

    @Test
    fun `I4-I4 - a rollback past the edge of the older pack is an honest conflict`() = runBlocking {
        // Пользователь успел уйти на набор 7 более крупного пакета.
        assign(setIndex = 7, date = "2026-09-08")
        val before = db.snapshot()

        val conflict = conflictFrom { importer().ensureInstalled() }

        assertEquals(listOf(7), conflict.staleSetIndexes)
        assertEquals(listOf(LocalDate.of(2026, 9, 8)), conflict.blockedDates)
        assertEquals(before, db.snapshot())
        assertEquals(0, prefs.writes)
    }

    // ================= I4-I5 … I4-I8: база расходится с отметкой =================

    @Test
    fun `I4-I5 - the same version with an empty Room triggers a full import`() = runBlocking {
        importer().ensureInstalled()
        execute("DELETE FROM daily_sets")
        execute("DELETE FROM puzzles")

        importer().ensureInstalled()

        assertEquals(15, countPuzzles())
        assertEquals(5, countSets())
    }

    @Test
    fun `I4-I6 - the same version with one missing set row restores it`() = runBlocking {
        val subject = importer()
        subject.ensureInstalled()
        execute("DELETE FROM daily_sets WHERE set_index = 3")
        assertEquals(4, countSets())

        subject.ensureInstalled()

        assertEquals(5, countSets())
        assertNotNull(db.dailySetDao().getSet(packId, 3))
    }

    @Test
    fun `I4-I7 - the same version with one missing puzzle row restores it`() = runBlocking {
        val subject = importer()
        subject.ensureInstalled()
        val victim = basePuzzles[4].puzzleId
        execute("DELETE FROM puzzles WHERE puzzle_id = '$victim'")
        assertEquals(14, countPuzzles())

        subject.ensureInstalled()

        assertEquals(15, countPuzzles())
        assertNotNull(db.puzzleDao().getById(victim))
    }

    @Test
    fun `I4-I8 - clearAllTables in the same process reinstalls the pack`() = runBlocking {
        val subject = importer()
        subject.ensureInstalled()
        // Отладочное действие опустошает Room и НЕ трогает DataStore (I3-D48):
        // отметка совпадает, база пуста — ровно тот случай, ради которого
        // ранний выход требует подтверждения базой.
        db.clearAllTables()
        assertEquals(0, countSets())
        assertEquals(1, prefs.current.storedContentVersion)

        subject.ensureInstalled()

        assertEquals(15, countPuzzles())
        assertEquals(5, countSets())
    }

    // ================= I4-I9: отпечаток =================

    @Test
    fun `I4-I9 - the same version with a different fingerprint is imported`() = runBlocking {
        importer().ensureInstalled()
        val firstFingerprint = prefs.current.storedContentFingerprint
        val upsertsBefore = puzzleDao.upsertCalls

        // Контент правили без повышения версии: хеш файла в манифесте изменился,
        // значит изменился и отпечаток самого манифеста.
        val edited = basePuzzles.mapIndexed { i, p ->
            if (i == 0) p.copy(prompt = "Другая формулировка того же задания") else p
        }
        source.replace(PackFixtures.files(edited, baseSets, contentVersion = 1))

        importer().ensureInstalled()

        assertEquals(1, prefs.current.storedContentVersion)
        assertTrue(prefs.current.storedContentFingerprint != firstFingerprint)
        assertEquals(fingerprint(), prefs.current.storedContentFingerprint)
        assertTrue(puzzleDao.upsertCalls > upsertsBefore)
    }

    // ================= I4-I10: безопасный излишек =================

    @Test
    fun `I4-I10 - sets outside the range with no assignments are deleted and progress is intact`() =
        runBlocking {
            db.dailySetDao().upsertAll(
                (5..8).map { DailySetEntity(packId, it, "tmp-a-$it", "tmp-b-$it", "tmp-c-$it") }
            )
            // Прогресс есть, но он относится к набору внутри диапазона.
            assign(setIndex = 0, date = "2026-09-01")
            val historyBefore = db.historySnapshot()

            importer().ensureInstalled()

            assertEquals(5, countSets())
            assertEquals((0..4).toList(), db.dailySetDao().byPack(packId).map { it.setIndex })
            assertEquals(historyBefore, db.historySnapshot())
        }

    // ================= I4-I11 … I4-I13: конфликты =================

    @Test
    fun `I4-I11 - an assignment on a set outside the range is a stale conflict`() = runBlocking {
        assign(setIndex = 40, date = "2026-10-11")
        play("2026-10-11", 0, "tmp-chuzhaya-001")
        val before = db.snapshot()

        val conflict = conflictFrom { importer().ensureInstalled() }

        assertEquals(listOf(40), conflict.staleSetIndexes)
        assertEquals(packId, conflict.packId)
        assertEquals(listOf(LocalDate.of(2026, 10, 11)), conflict.blockedDates)
        assertEquals("ни одна из пяти таблиц не изменена", before, db.snapshot())
        assertEquals(0, prefs.writes)
        assertEquals(0, prefs.current.storedContentVersion)
        assertNull(prefs.current.storedContentFingerprint)
    }

    @Test
    fun `I4-I12 - an assigned set whose stored composition differs is a changed conflict`() =
        runBlocking {
            importer().ensureInstalled()
            assign(setIndex = 2, date = "2026-09-03")
            // Состав набора 2 в базе подменён на чужой.
            db.dailySetDao().upsertAll(
                listOf(DailySetEntity(packId, 2, "syn-chuzhaya-a", "syn-chuzhaya-b", "syn-chuzhaya-c"))
            )
            val before = db.snapshot()

            val conflict = conflictFrom { importer().ensureInstalled() }

            assertEquals(emptyList<Int>(), conflict.staleSetIndexes)
            assertEquals(listOf(2), conflict.changedSetIndexes)
            assertEquals(listOf(LocalDate.of(2026, 9, 3)), conflict.blockedDates)
            assertEquals(before, db.snapshot())
        }

    @Test
    fun `I4-I13 - no set row but attempts on foreign puzzles is a conflict`() = runBlocking {
        assign(setIndex = 2, date = "2026-09-03")
        play("2026-09-03", 0, "tmp-geo-vysota-001")
        val before = db.snapshot()

        val conflict = conflictFrom { importer().ensureInstalled() }

        assertEquals(listOf(2), conflict.changedSetIndexes)
        assertEquals(before, db.snapshot())
    }

    // ================= I4-I14a … I4-I14e: замена отозванной =================

    /**
     * Пакет второй версии, где слот [slot] набора [setIndex] заменён: старая головоломка
     * помечена `retiredIn`, новая добавлена в пул.
     */
    private fun retirementPack(
        setIndex: Int,
        slot: Int,
        retiredIn: Int? = 2,
        replacementRetiredIn: Int? = null,
    ): Pair<List<PuzzleDto>, List<DailySetDto>> {
        val oldId = baseSets[setIndex].puzzleIds[slot]
        val newId = "syn-zamena-900"
        val puzzles = basePuzzles.map { if (it.puzzleId == oldId) it.copy(retiredIn = retiredIn) else it } +
            PackFixtures.puzzle(newId, retiredIn = replacementRetiredIn)
        val sets = baseSets.mapIndexed { index, set ->
            if (index == setIndex) {
                set.copy(puzzleIds = set.puzzleIds.toMutableList().also { it[slot] = newId })
            } else {
                set
            }
        }
        return puzzles to sets
    }

    @Test
    fun `I4-I14a - a slotwise replacement of a retired puzzle is imported`() = runBlocking {
        importer().ensureInstalled()
        assign(setIndex = 1, date = "2026-09-02")
        val retiredId = baseSets[1].puzzleIds[2]
        play("2026-09-02", 2, retiredId)
        val historyBefore = db.historySnapshot()

        val (puzzles, sets) = retirementPack(setIndex = 1, slot = 2)
        source.replace(PackFixtures.files(puzzles, sets, contentVersion = 2))

        importer().ensureInstalled()

        assertEquals(2, prefs.current.storedContentVersion)
        assertEquals("syn-zamena-900", db.dailySetDao().getSet(packId, 1)!!.puzzleId3)
        assertEquals(historyBefore, db.historySnapshot())
        // Архив по-прежнему разрешает старый идентификатор.
        val archived = PuzzleRepositoryImpl(db.puzzleDao(), storageJson).getPuzzle(retiredId)
        assertNotNull(archived)
        assertEquals(2, archived!!.retiredIn)
    }

    @Test
    fun `I4-I14b - a pure permutation of the same ids is a conflict`() = runBlocking {
        importer().ensureInstalled()
        assign(setIndex = 1, date = "2026-09-02")
        val swapped = baseSets.mapIndexed { index, set ->
            if (index == 1) set.copy(puzzleIds = listOf(set.puzzleIds[1], set.puzzleIds[0], set.puzzleIds[2]))
            else set
        }
        source.replace(PackFixtures.files(basePuzzles, swapped, contentVersion = 2))
        val before = db.snapshot()

        val conflict = conflictFrom { importer().ensureInstalled() }

        assertEquals(listOf(1), conflict.changedSetIndexes)
        assertEquals(before, db.snapshot())
    }

    @Test
    fun `I4-I14c - one retired replaced plus two active ids swapped is a conflict`() = runBlocking {
        importer().ensureInstalled()
        assign(setIndex = 1, date = "2026-09-02")
        val old = baseSets[1].puzzleIds
        val retiredId = old[2]
        val puzzles = basePuzzles.map { if (it.puzzleId == retiredId) it.copy(retiredIn = 2) else it } +
            PackFixtures.puzzle("syn-zamena-900")
        val sets = baseSets.mapIndexed { index, set ->
            // C отозвана и заменена, а активные A и B поменялись слотами.
            if (index == 1) set.copy(puzzleIds = listOf(old[1], old[0], "syn-zamena-900")) else set
        }
        source.replace(PackFixtures.files(puzzles, sets, contentVersion = 2))
        val before = db.snapshot()

        val conflict = conflictFrom { importer().ensureInstalled() }

        assertEquals(listOf(1), conflict.changedSetIndexes)
        assertEquals(before, db.snapshot())
    }

    @Test
    fun `I4-I14d - replacing a retired puzzle with another retired one is a conflict`() =
        runBlocking {
            importer().ensureInstalled()
            assign(setIndex = 1, date = "2026-09-02")
            val (puzzles, sets) = retirementPack(setIndex = 1, slot = 2, replacementRetiredIn = 2)
            source.replace(PackFixtures.files(puzzles, sets, contentVersion = 2))

            // Замена на отозванную — сначала R18A: набор ссылается на отозванную.
            val failure = assertThrows(ContentInstallException.BundleInvalid::class.java) {
                runBlocking { importer().ensureInstalled() }
            }

            assertEquals(ContentViolation.R18A_SET_REFERENCE_RETIRED, failure.code)
        }

    @Test
    fun `I4-I14e - retiredIn from the future is rejected as R18C before the transaction`() =
        runBlocking {
            importer().ensureInstalled()
            assign(setIndex = 1, date = "2026-09-02")
            play("2026-09-02", 2, baseSets[1].puzzleIds[2])
            val before = db.snapshot()
            // Старый ID помечен отозванным в БУДУЩЕЙ версии — это не отзыв, а опечатка,
            // и она открывала бы дорогу подмене состава.
            val (puzzles, sets) = retirementPack(setIndex = 1, slot = 2, retiredIn = 5)
            source.replace(PackFixtures.files(puzzles, sets, contentVersion = 2))

            val failure = assertThrows(ContentInstallException.BundleInvalid::class.java) {
                runBlocking { importer().ensureInstalled() }
            }

            assertEquals(ContentViolation.R18C_RETIRED_IN_FUTURE, failure.code)
            assertEquals(before, db.snapshot())
        }

    // ================= I4-I15: DataStore при конфликте =================

    @Test
    fun `I4-I15 - no conflict branch ever touches DataStore`() = runBlocking {
        importer().ensureInstalled()
        val markBefore = prefs.current
        assign(setIndex = 40, date = "2026-10-11")

        conflictFrom { importer().ensureInstalled() }

        assertEquals(markBefore.storedContentVersion, prefs.current.storedContentVersion)
        assertEquals(markBefore.storedContentFingerprint, prefs.current.storedContentFingerprint)
    }

    // ================= I4-I16: отмена =================

    @Test
    fun `I4-I16 - cancellation inside the transaction rolls Room back and leaves no mark`() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val blocking = CountingDailySetDao(db.dailySetDao(), entered, release)
            val subject = importer(sets = blocking)

            val job = launch(Dispatchers.Default) { subject.ensureInstalled() }
            entered.await()
            job.cancelAndJoin()

            assertEquals("транзакция откатилась целиком", 0, countSets())
            assertEquals(0, countPuzzles())
            assertEquals(0, prefs.writes)
            assertEquals(0, prefs.current.storedContentVersion)
            assertNull(prefs.current.storedContentFingerprint)

            // Повтор успешен: отмена не оставила ни половины пакета, ни отметки.
            importer().ensureInstalled()

            assertEquals(15, countPuzzles())
            assertEquals(5, countSets())
            assertEquals(1, prefs.current.storedContentVersion)
        }

    // ================= I4-I17: сбой между commit и DataStore =================

    @Test
    fun `I4-I17 - a DataStore failure after commit leaves a safe idempotent retry`() = runBlocking {
        prefs.failNextWrite = true

        assertThrows(IllegalStateException::class.java) {
            runBlocking { importer().ensureInstalled() }
        }

        // База записана, отметки нет — окно существует и оно безопасно.
        assertEquals(15, countPuzzles())
        assertEquals(5, countSets())
        assertEquals(0, prefs.current.storedContentVersion)
        assertNull(prefs.current.storedContentFingerprint)

        importer().ensureInstalled()

        assertEquals("upsert идемпотентен, дублей нет", 15, countPuzzles())
        assertEquals(5, countSets())
        assertEquals(1, prefs.current.storedContentVersion)
        assertEquals(fingerprint(), prefs.current.storedContentFingerprint)
    }

    // ================= I4-I18: конкурентность =================

    @Test
    fun `I4-I18 - eight concurrent calls perform exactly one import`() = runBlocking {
        val subject = importer()

        (1..8).map { async(Dispatchers.Default) { subject.ensureInstalled() } }.awaitAll()

        assertEquals("импорт выполнен ровно один раз", 1, puzzleDao.upsertCalls)
        assertEquals(1, setDao.upsertCalls)
        assertEquals(1, setDao.deleteCalls)
        assertEquals(1, prefs.writes)
        assertEquals(15, countPuzzles())
        assertEquals(5, countSets())
    }

    // ================= I4-I20: сценарий перехода =================

    @Test
    fun `I4-I20 - a database left by the temporary fixture raises a conflict and changes nothing`() =
        runBlocking {
            // Временные наборы 0..2 с временными составами, назначение и три попытки.
            db.dailySetDao().upsertAll(
                (0..2).map {
                    DailySetEntity(
                        packId, it,
                        "tmp-geo-vysota-00$it", "tmp-hist-izobreteniya-00$it", "tmp-sci-otkrytiya-00$it",
                    )
                }
            )
            assign(setIndex = 1, date = "2026-09-02")
            play("2026-09-02", 0, "tmp-geo-vysota-001")
            play("2026-09-02", 1, "tmp-hist-izobreteniya-001")
            play("2026-09-02", 2, "tmp-sci-otkrytiya-001")
            val before = db.snapshot()

            val conflict = conflictFrom { importer().ensureInstalled() }

            assertEquals(listOf(1), conflict.changedSetIndexes)
            assertEquals(emptyList<Int>(), conflict.staleSetIndexes)
            assertEquals("ни одна из пяти таблиц не изменена", before, db.snapshot())
            assertEquals(0, prefs.writes)
        }

    // ================= I4-I21: висячее назначение =================

    @Test
    fun `I4-I21 - a dangling assignment inside the range without attempts is not a conflict`() =
        runBlocking {
            // Строки набора нет и ни одной попытки не записано: доказательства иного
            // состава не существует, и осмысленное действие ровно одно — записать состав.
            assign(setIndex = 2, date = "2026-09-03")
            val historyBefore = db.historySnapshot()

            importer().ensureInstalled()

            assertEquals(5, countSets())
            assertEquals(baseSets[2].puzzleIds[0], db.dailySetDao().getSet(packId, 2)!!.puzzleId1)
            assertEquals(historyBefore, db.historySnapshot())
        }

    // ================= I4-I22 … I4-I22d: свидетельство C на быстром пути =========

    /** Устанавливает пакет и возвращает импортёр, у которого отметка уже совпадает. */
    private suspend fun installed(): ContentImporter {
        importer().ensureInstalled()
        return importer()
    }

    @Test
    fun `I4-I22 - an attempt holding an active foreign puzzleId forbids the early exit`() =
        runBlocking {
            val subject = installed()
            assign(setIndex = 0, date = "2026-09-01")
            // Головоломка существует и активна, но принадлежит другому набору.
            play("2026-09-01", 0, baseSets[3].puzzleIds[0])
            val before = db.snapshot()

            assertTrue(
                db.assignmentDao().countBlockingPlayedPuzzleMismatches(packId, 1) > 0
            )
            val conflict = conflictFrom { subject.ensureInstalled() }

            assertEquals(listOf(0), conflict.changedSetIndexes)
            assertEquals(before, db.snapshot())
            assertEquals(1, prefs.current.storedContentVersion)
        }

    @Test
    fun `I4-I22a - an attempt on a puzzleId absent from puzzles forbids the early exit`() =
        runBlocking {
            val subject = installed()
            assign(setIndex = 0, date = "2026-09-01")
            play("2026-09-01", 0, "syn-net-takoy-999")

            assertTrue(db.assignmentDao().countBlockingPlayedPuzzleMismatches(packId, 1) > 0)
            val conflict = conflictFrom { subject.ensureInstalled() }

            assertEquals(listOf(0), conflict.changedSetIndexes)
        }

    @Test
    fun `I4-I22b - an old id retired in a future version is not a legal replacement`() =
        runBlocking {
            importer().ensureInstalled()
            assign(setIndex = 0, date = "2026-09-01")
            play("2026-09-01", 0, "syn-otozvannaya-iz-budushchego")
            // Строка существует и отозвана, но retired_in больше текущей contentVersion.
            db.puzzleDao().upsertAll(
                listOf(
                    PackFixtures.puzzle("syn-otozvannaya-iz-budushchego", retiredIn = 9)
                        .toEntityForTest(contentVersion = 1)
                )
            )

            assertTrue(db.assignmentDao().countBlockingPlayedPuzzleMismatches(packId, 1) > 0)
            val conflict = conflictFrom { importer().ensureInstalled() }

            assertEquals(listOf(0), conflict.changedSetIndexes)
        }

    @Test
    fun `I4-I22c - a slot_index outside 0 to 2 forbids the early exit and is never compared to slot 3`() =
        runBlocking {
            val subject = installed()
            assign(setIndex = 0, date = "2026-09-01")
            // slot_index = 7 при значении puzzle_id, равном третьему слоту: прежняя форма
            // с ELSE s.puzzle_id_3 объявила бы такую строку совпадающей.
            play("2026-09-01", 7, baseSets[0].puzzleIds[2])

            assertEquals(1, db.assignmentDao().countBlockingPlayedPuzzleMismatches(packId, 1))
            val conflict = conflictFrom { subject.ensureInstalled() }

            assertEquals(listOf(0), conflict.changedSetIndexes)
        }

    @Test
    fun `I4-I22d - a set that references a retired puzzle is not an installed state`() =
        runBlocking {
            importer().ensureInstalled()
            // Ожидаемая головоломка на месте, но отозвана: предикат (4) не подтверждает
            // установку — «missing» означает «непригодна как текущий контент».
            val slotId = baseSets[0].puzzleIds[0]
            db.puzzleDao().upsertAll(
                listOf(
                    basePuzzles.first { it.puzzleId == slotId }.copy(retiredIn = 1)
                        .toEntityForTest(contentVersion = 1)
                )
            )

            assertEquals(1, db.dailySetDao().countSetsWithMissingPuzzles(packId, 1))

            // Полный импорт восстанавливает активную строку, конфликта нет.
            importer().ensureInstalled()

            assertNull(db.puzzleDao().getById(slotId)!!.retiredIn)
            assertEquals(0, db.dailySetDao().countSetsWithMissingPuzzles(packId, 1))
        }

    // ================= I4-I23 и I4-I24: повтор после легального отзыва ==========

    @Test
    fun `I4-I23 - the call right after a legal retirement takes the fast path`() = runBlocking {
        importer().ensureInstalled()
        assign(setIndex = 1, date = "2026-09-02")
        val retiredId = baseSets[1].puzzleIds[2]
        play("2026-09-02", 2, retiredId)

        val (puzzles, sets) = retirementPack(setIndex = 1, slot = 2)
        source.replace(PackFixtures.files(puzzles, sets, contentVersion = 2))
        val second = importer()
        second.ensureInstalled()

        val upsertsAfterImport = puzzleDao.upsertCalls
        val deletesAfterImport = setDao.deleteCalls
        val bodyReads = source.readCount("puzzles-001.json")
        val writes = prefs.writes

        second.ensureInstalled()

        assertEquals("ни одной записи головоломок", upsertsAfterImport, puzzleDao.upsertCalls)
        assertEquals("ни одного deleteOutsideRange", deletesAfterImport, setDao.deleteCalls)
        assertEquals("ни одного чтения тела пакета", bodyReads, source.readCount("puzzles-001.json"))
        assertEquals(writes, prefs.writes)
    }

    @Test
    fun `I4-I24 - a historical attempt on a correctly retired id does not block the fast path`() =
        runBlocking {
            importer().ensureInstalled()
            assign(setIndex = 1, date = "2026-09-02")
            val retiredId = baseSets[1].puzzleIds[2]
            play("2026-09-02", 2, retiredId)
            val (puzzles, sets) = retirementPack(setIndex = 1, slot = 2)
            source.replace(PackFixtures.files(puzzles, sets, contentVersion = 2))
            importer().ensureInstalled()

            assertEquals(
                "легальная историческая замена расхождением не считается",
                0,
                db.assignmentDao().countBlockingPlayedPuzzleMismatches(packId, 2),
            )
            assertEquals(0, db.dailySetDao().countSetsWithMissingPuzzles(packId, 2))
        }

    // ================= I4-I19: общий инвариант истории =================

    @Test
    fun `I4-I19 - history is untouched across import, conflict and fast path`() = runBlocking {
        assign(setIndex = 0, date = "2026-09-01")
        play("2026-09-01", 0, basePuzzles[0].puzzleId)
        val afterSeed = db.historySnapshot()

        // 1. импорт
        val subject = importer()
        subject.ensureInstalled()
        assertEquals(afterSeed, db.historySnapshot())

        // 2. быстрый путь
        subject.ensureInstalled()
        assertEquals(afterSeed, db.historySnapshot())

        // 3. конфликт
        assign(setIndex = 40, date = "2026-10-11")
        val beforeConflict = db.historySnapshot()
        conflictFrom { importer().ensureInstalled() }
        assertEquals(beforeConflict, db.historySnapshot())

        // Ни один DELETE не бил по истории: строки на месте.
        assertEquals(2, db.assignmentDao().assignedSets(packId).size)
        assertEquals(1, db.attemptDao().getByDate("2026-09-01").size)
        assertNotNull(db.dayResultDao().getByDate("2026-09-01"))
    }

    // ---------- вспомогательное ----------

    /** Прямая правка строки `puzzles` мимо импортёра — так тест ставит базу в состояние,
     *  которого корректный импорт не создаёт (`I4-I22b`, `I4-I22d`). */
    private fun PuzzleDto.toEntityForTest(contentVersion: Int) = toEntity(
        manifest = ManifestDto(
            schemaVersion = 1,
            contentVersion = contentVersion,
            packId = packId,
            packTitle = "Синтетический пакет фикстуры",
            setCount = baseSets.size,
            puzzleCount = basePuzzles.size,
            files = listOf(
                ManifestFileDto("puzzles-001.json", "0".repeat(64)),
                ManifestFileDto("daily-sets-001.json", "1".repeat(64)),
            ),
        ),
        json = storageJson,
    )

    /** Прямой SQL мимо DAO: тест портит базу так, как её мог бы испортить человек. */
    private fun execute(sql: String) = db.openHelper.writableDatabase.execSQL(sql)
}
