package ru.poporyadku.data.content

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.poporyadku.core.model.ContentPack
import ru.poporyadku.core.model.SLOTS_PER_DAY
import ru.poporyadku.core.model.isPlayable
import ru.poporyadku.core.model.puzzleIdAt
import ru.poporyadku.data.content.dto.ParsedPack
import ru.poporyadku.data.content.mapper.toEntity
import ru.poporyadku.data.content.validation.ContentValidator
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.data.db.dao.DailySetDao
import ru.poporyadku.data.db.dao.PuzzleDao
import ru.poporyadku.data.repository.DailySetRepositoryImpl
import ru.poporyadku.data.repository.PuzzleRepositoryImpl
import ru.poporyadku.di.ContentModule
import ru.poporyadku.domain.repository.DailySetRepository
import ru.poporyadku.domain.repository.PuzzleRepository
import ru.poporyadku.domain.shuffle.DeterministicShuffler

/**
 * НАСТОЯЩИЙ пакет из `app/src/main/assets/content` — `I4-C1`…`I4-C6`
 * (ITERATION_4_DESIGN.md, §17, группа `I4-C`; §12.2).
 *
 * Читается именно каталог ассетов приложения, а не общая фикстура валидатора:
 * `AssetContentSource` работает поверх `AssetManager`, который под Robolectric видит
 * `src/main/assets` (`testOptions.unitTests.isIncludeAndroidResources`). Копии пакета
 * в `src/test/resources` не заводится — две копии разошлись бы молча.
 *
 * `I4-C1`–`I4-C3` сверяют пакет сам с собой и литералов объёма не содержат. Объём
 * `35 / 105` закрепляет ровно один тест — `I4-C4`, критерий релиза (**I4-D21**); тот же
 * критерий держит CI флагами `--expect-sets 35 --expect-puzzles 105`. 35 дней подряд
 * через use cases — `I4-C7` (`ThirtyFiveDaysTest`).
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

    /** Настоящий импортёр над настоящими ассетами; DAO можно обернуть счётчиками. */
    private fun importer(
        prefs: FakeUserPreferencesRepository,
        database: AppDatabase = db,
        puzzleDao: PuzzleDao = database.puzzleDao(),
        setDao: DailySetDao = database.dailySetDao(),
    ) = ContentImporter(
        db = database,
        puzzleDao = puzzleDao,
        setDao = setDao,
        assignmentDao = database.assignmentDao(),
        reader = reader(),
        validator = ContentValidator(),
        prefs = prefs,
        storageJson = ContentModule.storageJson(),
        activePackId = packId,
    )

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
        val importer = importer(prefs, puzzleDao = puzzleDao, setDao = setDao)

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
        // Единственный DELETE импортёра. Без него «повтор ничего не пишет» проверялось бы
        // только по upsert-ам, а удаление наборов вне диапазона осталось бы незамеченным.
        val setDeletesAfterFirst = setDao.deleteCalls

        importer.ensureInstalled()

        assertEquals("снимок базы после повтора", afterFirst, db.snapshot())
        assertEquals("записей головоломок", puzzleUpsertsAfterFirst, puzzleDao.upsertCalls)
        assertEquals("записей наборов", setUpsertsAfterFirst, setDao.upsertCalls)
        assertEquals("удалений наборов", setDeletesAfterFirst, setDao.deleteCalls)
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

    // ---------- I4-C4 ----------

    /**
     * `I4-C4`. **Критерий релиза**: пакет альфы — ровно 35 наборов и 105 головоломок
     * первой настоящей версии контента, и манифест говорит о пакете то же, что его файлы.
     *
     * Литералы здесь — сам критерий (**I4-D21**), а не описание батча: уменьшение пакета
     * обязано красить сборку так же, как `--expect-*` в CI. Остальной контракт пакета —
     * работа валидатора CLI и `I4-C1`; здесь он не дублируется.
     */
    @Test
    fun `I4-C4 финальный пакет — ровно 35 наборов и 105 головоломок версии 1`() = runBlocking {
        val pack = readPack()

        assertEquals("setCount манифеста", RELEASE_SET_COUNT, pack.manifest.setCount)
        assertEquals("puzzleCount манифеста", RELEASE_PUZZLE_COUNT, pack.manifest.puzzleCount)
        assertEquals("наборов в файле", pack.manifest.setCount, pack.sets.size)
        assertEquals("головоломок в файле", pack.manifest.puzzleCount, pack.puzzles.size)
        assertEquals("contentVersion", RELEASE_CONTENT_VERSION, pack.manifest.contentVersion)
    }

    // ---------- I4-C5 ----------

    /**
     * `I4-C5`. После импорта каждый из наборов читается через [DailySetRepository],
     * каждая ссылка слота разрешается через [PuzzleRepository] — теми же реализациями,
     * что связаны в продуктовом графе, — и каждая из 105 головоломок играбельна.
     *
     * Room DAO тест не читает: предмет проверки — продуктовые границы, а не таблицы.
     * Число наборов берётся из манифеста ассетов, а не из базы.
     */
    @Test
    fun `I4-C5 наборы и головоломки читаются через продуктовые репозитории`() = runBlocking {
        importer(FakeUserPreferencesRepository()).ensureInstalled()
        val setCount = reader().readHeader(packId).manifest.setCount
        val sets: DailySetRepository = DailySetRepositoryImpl(db.dailySetDao())
        val puzzles: PuzzleRepository = PuzzleRepositoryImpl(db.puzzleDao(), ContentModule.storageJson())

        val seen = mutableSetOf<String>()
        for (setIndex in 0 until setCount) {
            val set = sets.getSet(packId, setIndex)
            assertNotNull("набор $setIndex не читается через DailySetRepository", set)
            for (slot in 0 until SLOTS_PER_DAY) {
                val puzzleId = set!!.puzzleIdAt(slot)
                val puzzle = puzzles.getPuzzle(puzzleId)
                assertNotNull("набор $setIndex, слот $slot: '$puzzleId' не разрешается", puzzle)
                assertEquals(puzzleId, puzzle!!.puzzleId)
                assertEquals(packId, puzzle.packId)
                assertTrue("'$puzzleId' не играбельна", puzzle.isPlayable())
                assertTrue("'$puzzleId' встречается в пакете дважды", seen.add(puzzleId))
            }
        }
        assertNull("за последним набором пакета наборов нет", sets.getSet(packId, setCount))
        assertEquals("уникальных головоломок", RELEASE_PUZZLE_COUNT, seen.size)
    }

    // ---------- I4-C6 ----------

    /**
     * `I4-C6`. ДИАГНОСТИЧЕСКИЙ замер полного импорта настоящего пакета в чистую базу.
     *
     * Порога нет и не будет: время общего раннера невоспроизводимо, и порог давал бы
     * плавающие падения (§16, §17). Решение о выносе установки из `mapLatest`
     * принимается только по замеру на реальном устройстве при ручной приёмке, а не по
     * этому числу. Значение печатается в вывод теста (`system-out` отчёта JUnit).
     *
     * Меряется именно полный путь, а не ранний выход: каждый прогон — новая in-memory
     * база, новый импортёр (заголовок пакета не закэширован) и пустая отметка; после
     * вызова проверяется, что тело пакета действительно записано. Проверка целостности
     * включена, как в debug. Первый прогон — «холодный» (загрузка классов, JIT хоста).
     */
    @Test
    fun `I4-C6 длительность полного импорта измеряется без порога`() = runBlocking {
        val millis = (1..TIMING_RUNS).map {
            val database = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                AppDatabase::class.java,
            ).allowMainThreadQueries().build()
            try {
                val puzzleDao = CountingPuzzleDao(database.puzzleDao())
                val importer = importer(FakeUserPreferencesRepository(), database, puzzleDao)

                val started = System.nanoTime()
                importer.ensureInstalled()
                val elapsed = (System.nanoTime() - started) / NANOS_PER_MILLI

                assertEquals("полный путь: тело пакета записано", 1, puzzleDao.upsertCalls)
                assertEquals(RELEASE_PUZZLE_COUNT, database.puzzleDao().countByPack(packId))
                elapsed
            } finally {
                database.close()
            }
        }

        println(
            "I4-C6: полный импорт настоящего пакета ($RELEASE_SET_COUNT наборов / " +
                "$RELEASE_PUZZLE_COUNT головоломок) в чистую базу, JVM + Robolectric, " +
                "целостность включена: холодный ${"%.1f".format(millis.first())} мс; " +
                "тёплые ${millis.drop(1).joinToString { "%.1f".format(it) }} мс",
        )
    }

    private companion object {
        /** Критерий релиза пакета альфы (`IMPLEMENTATION_PLAN.md`, итерация 4; **I4-D21**). */
        const val RELEASE_SET_COUNT = 35
        const val RELEASE_PUZZLE_COUNT = 105

        /** Начальная настоящая версия контента (ITERATION_4_DESIGN.md, §4.2). */
        const val RELEASE_CONTENT_VERSION = 1

        const val TIMING_RUNS = 4
        const val NANOS_PER_MILLI = 1_000_000.0
    }
}
