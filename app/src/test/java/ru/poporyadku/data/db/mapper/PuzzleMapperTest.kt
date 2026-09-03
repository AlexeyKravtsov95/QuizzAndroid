package ru.poporyadku.data.db.mapper

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import ru.poporyadku.core.model.Category
import ru.poporyadku.core.model.SortDirection
import ru.poporyadku.core.model.isPlayable
import ru.poporyadku.data.content.PackFixtures
import ru.poporyadku.data.content.dto.ManifestDto
import ru.poporyadku.data.content.dto.ManifestFileDto
import ru.poporyadku.data.content.mapper.toEntity
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.data.repository.PuzzleRepositoryImpl
import ru.poporyadku.di.ContentModule

/**
 * Чтение строки `puzzles` в доменную модель — `I4-M2`, `I4-M5`, `I4-M7`
 * (ITERATION_4_DESIGN.md, §9.1, §9.3, §9.6).
 *
 * Робот нужен только ради in-memory Room в тестах репозитория; сам маппер
 * от Android не зависит.
 */
@RunWith(RobolectricTestRunner::class)
class PuzzleMapperTest {

    private lateinit var db: AppDatabase
    private val storageJson: Json = ContentModule.storageJson()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun manifest(contentVersion: Int = 3) = ManifestDto(
        schemaVersion = 1,
        contentVersion = contentVersion,
        packId = PackFixtures.PACK_ID,
        packTitle = "Синтетический пакет фикстуры",
        setCount = 1,
        puzzleCount = 1,
        files = listOf(
            ManifestFileDto("puzzles-001.json", "0".repeat(64)),
            ManifestFileDto("daily-sets-001.json", "1".repeat(64)),
        ),
    )

    // --- I4-M2: round trip DTO → Entity → domain ----------------------------

    /**
     * `I4-M2`: ни одно поле, попадающее в базу, не теряется. Поля редакторского
     * протокола в базу не попадают вовсе, и это фиксирует `I4-M6`.
     */
    @Test
    fun `I4-M2 - nothing that reaches the database is lost on the way back`() {
        val dto = PackFixtures.puzzle(
            puzzleId = "syn-obrazec-001",
            category = "culture",
            sortDirection = "descending",
            difficulty = 2,
            retiredIn = null,
            cards = listOf(
                PackFixtures.card("c1", "Первая", subtitle = "Подзаголовок", sortValue = -3.5, note = "Заметка"),
                PackFixtures.card("c2", "Вторая", sortValue = 0.0),
                PackFixtures.card("c3", "Третья", sortValue = 12.25, disputed = true),
                PackFixtures.card("c4", "Четвёртая", sortValue = 9000.0),
            ),
            correctOrder = listOf("c1", "c2", "c3", "c4"),
        )

        val domain = dto.toEntity(manifest(contentVersion = 3), storageJson).toDomain(storageJson)

        assertEquals(dto.puzzleId, domain.puzzleId)
        assertEquals(PackFixtures.PACK_ID, domain.packId)
        assertEquals(Category.CULTURE, domain.category)
        assertEquals(SortDirection.DESCENDING, domain.sortDirection)
        assertEquals(dto.prompt, domain.prompt)
        assertEquals(dto.sortKey, domain.sortKey)
        assertEquals(dto.directionLabel, domain.directionLabel)
        assertEquals(dto.explanation, domain.explanation)
        assertEquals(dto.difficulty, domain.difficulty)
        assertEquals(3, domain.contentVersion)
        assertEquals(dto.correctOrder, domain.correctOrder)
        assertEquals(dto.cards.map { it.cardId }, domain.cards.map { it.cardId })
        assertEquals(dto.cards.map { it.title }, domain.cards.map { it.title })
        assertEquals(dto.cards.map { it.subtitle }, domain.cards.map { it.subtitle })
        assertEquals(dto.cards.map { it.sortValue }, domain.cards.map { it.sortValue })
        assertEquals(dto.cards.map { it.displayValue }, domain.cards.map { it.displayValue })
        assertEquals(dto.cards.map { it.note }, domain.cards.map { it.note })
        assertEquals(dto.cards.map { it.sourceIds }, domain.cards.map { it.sourceIds })
        assertEquals(dto.cards.map { it.disputed }, domain.cards.map { it.disputed })
        assertEquals(dto.sources.map { it.sourceId }, domain.sources.map { it.sourceId })
        assertEquals(dto.sources.map { it.accessedAt }, domain.sources.map { it.accessedAt })
        assertTrue(domain.isPlayable())
    }

    // --- I4-M5 --------------------------------------------------------------

    /**
     * `I4-M5`: неизвестный токен при ЧТЕНИИ — повреждение того, что писали мы сами.
     * Это дефект, а не состояние экрана, и сообщение обязано называть `puzzleId`:
     * без него непонятно, какую строку чинить.
     */
    @Test
    fun `I4-M5 - an unknown storage token throws with the puzzleId in the message`() {
        val row = PackFixtures.puzzle("syn-obrazec-042").toEntity(manifest(), storageJson)

        val brokenCategory = assertThrows(IllegalStateException::class.java) {
            row.copy(category = "astronomiya").toDomain(storageJson)
        }
        val brokenDirection = assertThrows(IllegalStateException::class.java) {
            row.copy(sortDirection = "sideways").toDomain(storageJson)
        }

        assertTrue(brokenCategory.message!!.contains("syn-obrazec-042"))
        assertTrue(brokenCategory.message!!.contains("astronomiya"))
        assertTrue(brokenDirection.message!!.contains("syn-obrazec-042"))
        assertTrue(brokenDirection.message!!.contains("sideways"))
    }

    @Test
    fun `I4-M5 - the storage JSON is strict about unknown keys`() {
        val row = PackFixtures.puzzle("syn-obrazec-001").toEntity(manifest(), storageJson)
        val tampered = row.copy(
            cardsJson = row.cardsJson.replace("\"cardId\"", "\"cardID\",\"cardId\""),
        )

        // Повреждение собственной колонки не «совместимость», а порча: разбор падает.
        assertThrows(Exception::class.java) { tampered.toDomain(storageJson) }
    }

    // --- I4-M7 --------------------------------------------------------------

    /**
     * `I4-M7`: активная и отозванная головоломки различимы в SQL, и отозванная
     * читается репозиторием наравне с активной — иначе архив за прошлые дни
     * перестал бы открываться (`CONTENT_MODEL.md` §7).
     */
    @Test
    fun `I4-M7 - active and retired puzzles are distinguishable and both readable`() = runBlocking {
        val active = PackFixtures.puzzle("syn-aktivnaya-001", retiredIn = null)
            .toEntity(manifest(), storageJson)
        val retired = PackFixtures.puzzle("syn-otozvannaya-002", retiredIn = 2)
            .toEntity(manifest(), storageJson)
        db.puzzleDao().upsertAll(listOf(active, retired))

        val repository = PuzzleRepositoryImpl(db.puzzleDao(), storageJson)

        assertNull(db.puzzleDao().getById("syn-aktivnaya-001")!!.retiredIn)
        assertEquals(2, db.puzzleDao().getById("syn-otozvannaya-002")!!.retiredIn)

        val fromRepository = repository.getPuzzle("syn-otozvannaya-002")

        assertNotNull(fromRepository)
        assertEquals(2, fromRepository!!.retiredIn)
        assertTrue(fromRepository.isPlayable())
        assertNull(repository.getPuzzle("syn-aktivnaya-001")!!.retiredIn)
        assertNull("несуществующей головоломки нет", repository.getPuzzle("syn-net-takoy-003"))
    }

    @Test
    fun `I4-M2 - a row written by the importer survives a real round trip through Room`() =
        runBlocking {
            val dto = PackFixtures.puzzle(
                puzzleId = "syn-obrazec-001",
                cards = listOf(
                    PackFixtures.card("c1", "Ёлка — тире", subtitle = "Подзаголовок", sortValue = -1.5),
                    PackFixtures.card("c2", "Вторая", sortValue = 0.25),
                    PackFixtures.card("c3", "Третья", sortValue = 3.0, disputed = true),
                    PackFixtures.card("c4", "Четвёртая", sortValue = 4.0),
                ),
            )
            db.puzzleDao().upsertAll(listOf(dto.toEntity(manifest(), storageJson)))

            val puzzle = PuzzleRepositoryImpl(db.puzzleDao(), storageJson).getPuzzle("syn-obrazec-001")

            assertNotNull(puzzle)
            assertEquals("Ёлка — тире", puzzle!!.cards[0].title)
            assertEquals(-1.5, puzzle.cards[0].sortValue, 0.0)
            assertTrue(puzzle.cards[2].disputed)
            assertEquals(listOf("c1", "c2", "c3", "c4"), puzzle.correctOrder)
        }
}
