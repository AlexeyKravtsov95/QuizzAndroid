package ru.poporyadku.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.poporyadku.core.model.ContentPack
import ru.poporyadku.core.model.Puzzle
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.data.db.dao.PuzzleDao
import ru.poporyadku.data.db.entity.PuzzleAttemptEntity
import ru.poporyadku.data.db.entity.PuzzleEntity
import ru.poporyadku.data.db.json.StoredSource
import ru.poporyadku.di.ContentModule
import ru.poporyadku.domain.repository.PuzzleSources

/**
 * `PlayedSourcesRepository` на in-memory Room — `I5-Q2` (ITERATION_5_DESIGN.md, §3.11,
 * §5.3, I5-D17).
 *
 * Строки `puzzles` и `puzzle_attempts` вставляются DAO напрямую: проверяется запрос и
 * разбор, а не путь записи попытки. Пропуск — попытка с пустым `submitted_order`
 * (`ProgressMappers`), как его пишет продукт.
 */
@RunWith(RobolectricTestRunner::class)
class PlayedSourcesRepositoryTest {

    private lateinit var db: AppDatabase
    private val json: Json = ContentModule.storageJson()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    /**
     * `I5-Q2`. Только сыгранные непропущенные: пропуск исключён, отозванная включена,
     * несыгранная не видна, попытка на отсутствующую головоломку список не ломает; порядок —
     * по возрастанию `puzzleId`; источники разобраны целиком.
     */
    @Test
    fun `I5-Q2 only played not skipped puzzles including retired ones`() = runBlocking {
        insertPuzzle("geo-a-001", listOf(stored("s1", "Атлас", url = "https://e.test/a")))
        insertPuzzle(
            "hist-b-002",
            listOf(stored("s1", "Летопись", reference = "Т. 1, с. 5"), stored("s2", "Британника", url = "https://e.test/b")),
            retiredIn = 1,
        )
        insertPuzzle("sci-c-003", listOf(stored("s1", "Пропущенная", url = "https://e.test/skip")))
        insertPuzzle("nat-d-004", listOf(stored("s1", "Несыгранная", url = "https://e.test/unplayed")))

        attempt("2026-09-01", 0, "hist-b-002", submittedOrder = "c2,c1,c3,c4")
        attempt("2026-09-01", 1, "sci-c-003", submittedOrder = "") // пропуск
        attempt("2026-09-01", 2, "geo-a-001", submittedOrder = "c1,c2,c3,c4")
        attempt("2026-09-02", 0, "missing-x-009", submittedOrder = "c1,c2,c3,c4") // строки puzzles нет
        attempt("2026-09-02", 1, "geo-a-001", submittedOrder = "c4,c3,c2,c1") // вторая попытка — одна строка

        val result = repository().getPlayedPuzzleSources()

        assertEquals(
            listOf(
                PuzzleSources("geo-a-001", listOf(domain("s1", "Атлас", url = "https://e.test/a"))),
                PuzzleSources(
                    "hist-b-002",
                    listOf(
                        domain("s1", "Летопись", reference = "Т. 1, с. 5"),
                        domain("s2", "Британника", url = "https://e.test/b"),
                    ),
                ),
            ),
            result,
        )
    }

    /** `I5-Q2`. Пропуск той же головоломки в другой день не исключает её сыгранную попытку. */
    @Test
    fun `I5-Q2 a puzzle answered once is included even if skipped elsewhere`() = runBlocking {
        insertPuzzle("geo-a-001", listOf(stored("s1", "Атлас", url = "https://e.test/a")))
        attempt("2026-09-01", 0, "geo-a-001", submittedOrder = "")
        attempt("2026-09-02", 0, "geo-a-001", submittedOrder = "c1,c2,c3,c4")

        assertEquals(listOf("geo-a-001"), repository().getPlayedPuzzleSources().map { it.puzzleId })
    }

    /** `I5-Q2`. Ни одной попытки или только пропуски — пустой список. */
    @Test
    fun `I5-Q2 no played puzzles gives an empty list`() = runBlocking {
        insertPuzzle("geo-a-001", listOf(stored("s1", "Атлас", url = "https://e.test/a")))
        assertTrue(repository().getPlayedPuzzleSources().isEmpty())

        attempt("2026-09-01", 0, "geo-a-001", submittedOrder = "")
        assertTrue(repository().getPlayedPuzzleSources().isEmpty())
    }

    /**
     * `I5-Q2`. Испорченный `sources_json` любой сыгранной головоломки — неизвестный ключ или
     * не-JSON — бросает целиком, а не даёт частичный список.
     */
    @Test
    fun `I5-Q2 corrupted sources json throws instead of a partial list`(): Unit = runBlocking {
        insertPuzzle("geo-a-001", listOf(stored("s1", "Атлас", url = "https://e.test/a")))
        insertRawPuzzle(
            "hist-b-002",
            """[{"sourceId":"s1","title":"Т","kind":"other","accessedAt":"2026-08-20","unexpected":1}]""",
        )
        attempt("2026-09-01", 0, "geo-a-001", submittedOrder = "c1,c2,c3,c4")
        attempt("2026-09-01", 1, "hist-b-002", submittedOrder = "c1,c2,c3,c4")

        assertThrows(SerializationException::class.java) {
            runBlocking { repository().getPlayedPuzzleSources() }
        }

        insertRawPuzzle("hist-b-002", "не json")
        assertThrows(SerializationException::class.java) {
            runBlocking { repository().getPlayedPuzzleSources() }
        }
    }

    /**
     * `I5-Q2`. Assets не читаются: у репозитория ровно две зависимости — DAO и строгий
     * `Json`, источника байтов пакета (`ContentAssetSource`) среди них нет, поэтому
     * вызвать его нечем.
     */
    @Test
    fun `I5-Q2 the repository cannot read assets`() {
        val constructors = PlayedSourcesRepositoryImpl::class.java.constructors
        assertEquals(1, constructors.size)
        assertEquals(
            listOf(PuzzleDao::class.java, Json::class.java),
            constructors.single().parameterTypes.toList(),
        )
    }

    // --- Инфраструктура -----------------------------------------------------------------

    private fun repository() = PlayedSourcesRepositoryImpl(db.puzzleDao(), json)

    private suspend fun insertPuzzle(puzzleId: String, sources: List<StoredSource>, retiredIn: Int? = null) =
        insertRawPuzzle(puzzleId, json.encodeToString(sources), retiredIn)

    private suspend fun insertRawPuzzle(puzzleId: String, sourcesJson: String, retiredIn: Int? = null) {
        db.puzzleDao().upsertAll(
            listOf(
                PuzzleEntity(
                    puzzleId = puzzleId,
                    packId = ContentPack.CORE_RU,
                    category = "geography",
                    prompt = "Расположите по признаку",
                    sortKey = "year",
                    sortDirection = "ascending",
                    directionLabel = "Сверху — наименьшее",
                    cardsJson = "[]",
                    correctOrder = "c1,c2,c3,c4",
                    explanation = "Объяснение фикстуры.",
                    sourcesJson = sourcesJson,
                    difficulty = 1,
                    retiredIn = retiredIn,
                    contentVersion = 2,
                ),
            ),
        )
    }

    private suspend fun attempt(date: String, slot: Int, puzzleId: String, submittedOrder: String) {
        db.attemptDao().insert(
            PuzzleAttemptEntity(
                localDate = date,
                slotIndex = slot,
                puzzleId = puzzleId,
                submittedOrder = submittedOrder,
                score = if (submittedOrder.isEmpty()) 0 else 3,
                submittedAt = 0L,
            ),
        )
    }

    private fun stored(sourceId: String, title: String, url: String? = null, reference: String? = null) =
        StoredSource(sourceId = sourceId, title = title, kind = "encyclopedia", url = url, reference = reference, accessedAt = "2026-08-20")

    private fun domain(sourceId: String, title: String, url: String? = null, reference: String? = null) =
        Puzzle.Source(
            sourceId = sourceId,
            title = title,
            kind = "encyclopedia",
            url = url,
            reference = reference,
            accessedAt = "2026-08-20",
            note = null,
        )
}
