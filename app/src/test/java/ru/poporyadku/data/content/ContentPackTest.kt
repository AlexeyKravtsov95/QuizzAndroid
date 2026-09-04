package ru.poporyadku.data.content

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.poporyadku.core.model.ContentPack
import ru.poporyadku.data.content.dto.ParsedPack
import ru.poporyadku.data.content.mapper.toEntity
import ru.poporyadku.data.content.validation.ContentValidator
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.di.ContentModule
import ru.poporyadku.domain.shuffle.DeterministicShuffler

/**
 * НАСТОЯЩИЙ пакет из `app/src/main/assets/content` — `I4-C1`…`I4-C3`
 * (ITERATION_4_DESIGN.md, §17, группа `I4-C`; §12.2).
 *
 * Читается именно каталог ассетов приложения, а не общая фикстура валидатора:
 * `AssetContentSource` работает поверх `AssetManager`, который под Robolectric видит
 * `src/main/assets` (`testOptions.unitTests.isIncludeAndroidResources`). Копии пакета
 * в `src/test/resources` не заводится — две копии разошлись бы молча.
 *
 * Пакет этими тестами НЕ активируется: продуктовый граф до PR 4D связывает временный
 * источник, и ни один из тестов ниже в граф Hilt не заглядывает.
 *
 * Ожидаемый объём батча (`7 / 21`) здесь НЕ фиксируется: он проверяется отдельно
 * командой CLI `--expect-sets/--expect-puzzles` и записан в чек-листе батча. Тесты
 * сверяют пакет сам с собой, поэтому переживают батчи 4C-2…4C-5 без правок.
 */
@RunWith(RobolectricTestRunner::class)
class ContentPackTest {

    private val packId = ContentPack.CORE_RU

    private lateinit var db: AppDatabase
    private lateinit var source: AssetContentSource

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        source = AssetContentSource(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = db.close()

    /** Тот же читатель, что в продуктовом графе; целостность включена, как в debug. */
    private fun reader() =
        ContentPackReader(source, ContentModule.assetJson(), verifyIntegrity = true)

    private suspend fun readPack(): ParsedPack {
        val reader = reader()
        return reader.readBody(reader.readHeader(packId))
    }

    // ---------- I4-C1 ----------

    /**
     * `I4-C1`. Полный рантайм-путь настоящего пакета: `AssetContentSource` →
     * `ContentPackReader` → `ContentValidator`. Читатель бросает на `M01`–`M09`
     * и `R01`, валидатор возвращает защитные нарушения — здесь не должно быть ни того,
     * ни другого.
     */
    @Test
    fun `I4-C1 настоящий пакет проходит рантайм-путь без нарушений`() = runBlocking {
        val pack = readPack()

        assertEquals("packId манифеста", packId, pack.manifest.packId)
        assertEquals("schemaVersion", SUPPORTED_SCHEMA_VERSION, pack.manifest.schemaVersion)

        val violations = ContentValidator().findings(pack)
        assertTrue(
            "рантайм-валидатор вернул нарушения: " +
                violations.joinToString { "${it.code}@${it.pointer}" },
            violations.isEmpty(),
        )

        // Манифест и тела файлов согласованы: счётчики — это R21, и он уже отработал
        // выше; здесь утверждение выписано явно, чтобы падение было читаемым.
        assertEquals("setCount", pack.manifest.setCount, pack.sets.size)
        assertEquals("puzzleCount", pack.manifest.puzzleCount, pack.puzzles.size)
    }

    /** Каталог пакета содержит ровно три объявленных файла и ничего кроме них. */
    @Test
    fun `I4-C1 в каталоге ассетов ровно три файла пакета`() = runBlocking {
        val header = reader().readHeader(packId)
        val declared = header.manifest.files.map { it.path }.toSet() + ContentPaths.MANIFEST

        assertEquals("объявлено файлов", 3, declared.size)
        assertEquals(
            "содержимое каталога content/",
            declared.sorted(),
            source.list().sorted(),
        )
    }

    // ---------- I4-C2 ----------

    /**
     * `I4-C2`. Импорт настоящих ассетов НАСТОЯЩИМ `ContentImporter` в чистую
     * in-memory Room: число строк равно манифесту, каждая ссылка набора разрешается,
     * сохранённые наборы совпадают с ассетами, отметка соответствует манифесту,
     * а повторный вызов не пишет ничего.
     */
    @Test
    fun `I4-C2 импорт настоящего пакета и идемпотентный повтор`() = runBlocking {
        val prefs = FakeUserPreferencesRepository()
        val puzzleDao = CountingPuzzleDao(db.puzzleDao())
        val setDao = CountingDailySetDao(db.dailySetDao())
        val importer = ContentImporter(
            db = db,
            puzzleDao = puzzleDao,
            setDao = setDao,
            assignmentDao = db.assignmentDao(),
            reader = reader(),
            validator = ContentValidator(),
            prefs = prefs,
            storageJson = ContentModule.storageJson(),
            activePackId = packId,
        )

        importer.ensureInstalled()

        val pack = readPack()
        val manifest = pack.manifest

        // Объём базы равен объявленному манифестом — литералы батча не используются.
        assertEquals("наборов в базе", manifest.setCount, db.dailySetDao().countSets(packId))
        assertEquals("головоломок в базе", manifest.puzzleCount, db.puzzleDao().countByPack(packId))

        // Каждая ссылка каждого набора разрешается в установленную головоломку.
        for (set in pack.sets) {
            for (puzzleId in set.puzzleIds) {
                assertNotNull(
                    "набор ${set.setIndex} ссылается на неустановленную '$puzzleId'",
                    db.puzzleDao().getById(puzzleId),
                )
            }
        }

        // Сохранённые наборы совпадают с ассетами побайтово — сравниваются строки Room,
        // построенные из DTO ассетов, с тем, что импортёр действительно записал.
        assertEquals(
            "строки daily_sets",
            pack.sets.map { it.toEntity(packId) }.sortedBy { it.setIndex },
            db.dailySetDao().byPack(packId),
        )

        // Отметка: версия и отпечаток — из манифеста, а не из литералов теста.
        val fingerprint = PackFixtures.sha256(source.read(ContentPaths.MANIFEST))
        assertEquals("storedContentVersion", manifest.contentVersion, prefs.current.storedContentVersion)
        assertEquals("storedContentFingerprint", fingerprint, prefs.current.storedContentFingerprint)

        val afterFirst = db.snapshot()
        val writesAfterFirst = prefs.writes
        val puzzleUpsertsAfterFirst = puzzleDao.upsertCalls
        val setUpsertsAfterFirst = setDao.upsertCalls

        importer.ensureInstalled()

        assertEquals("снимок базы после повтора", afterFirst, db.snapshot())
        assertEquals("записей головоломок", puzzleUpsertsAfterFirst, puzzleDao.upsertCalls)
        assertEquals("записей наборов", setUpsertsAfterFirst, setDao.upsertCalls)
        assertEquals("записей отметки", writesAfterFirst, prefs.writes)
    }

    // ---------- I4-C3 ----------

    /**
     * `I4-C3`. Правило 10 настоящим `DeterministicShuffler` (замена `I3-H4`): перебор
     * идёт по фактическому содержимому пакета, списка идентификаторов в тесте нет,
     * поэтому батчи 4C-2…4C-5 покрываются автоматически.
     *
     * Совпадение стартового порядка с правильным чинится СМЕНОЙ `puzzleId`, а не
     * правкой шаффлера (**I3-D8**).
     */
    @Test
    fun `I4-C3 ни одна головоломка не стартует в правильном порядке`() = runBlocking {
        val pack = readPack()
        assertTrue("пакет пуст", pack.puzzles.isNotEmpty())

        for (puzzle in pack.puzzles) {
            val cardIds = puzzle.cards.map { it.cardId }
            val start = DeterministicShuffler.shuffle(puzzle.puzzleId, cardIds)

            assertEquals(
                "${puzzle.puzzleId}: стартовый порядок не перестановка карточек",
                cardIds.sorted(),
                start.sorted(),
            )
            assertEquals(
                "${puzzle.puzzleId}: карточка повторяется в стартовом порядке",
                start.size,
                start.toSet().size,
            )
            assertNotEquals(
                "${puzzle.puzzleId}: головоломка открывается уже решённой — " +
                    "нужен другой естественный суффикс puzzleId, а не правка шаффлера",
                puzzle.correctOrder,
                start,
            )
        }
    }
}
