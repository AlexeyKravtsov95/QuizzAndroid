package ru.poporyadku.data.content.mapper

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.poporyadku.data.content.PackFixtures
import ru.poporyadku.data.content.dto.ManifestDto
import ru.poporyadku.data.content.dto.ManifestFileDto
import ru.poporyadku.data.db.json.StoredCard
import ru.poporyadku.data.db.json.StoredSource
import ru.poporyadku.data.db.mapper.ContentTokens
import ru.poporyadku.data.db.mapper.toDomain
import ru.poporyadku.di.ContentModule

/**
 * Преобразование asset-DTO → строка Room — `I4-M1`, `I4-M3`, `I4-M4`, `I4-M5`, `I4-M6`
 * (ITERATION_4_DESIGN.md, §9.4).
 *
 * Чистый JVM-тест: ни базы, ни Android. Room здесь представлена своими `Entity`.
 */
class ContentEntityMappersTest {

    private val storageJson: Json = ContentModule.storageJson()

    private fun manifest(
        packId: String = PackFixtures.PACK_ID,
        contentVersion: Int = 7,
    ) = ManifestDto(
        schemaVersion = 1,
        contentVersion = contentVersion,
        packId = packId,
        packTitle = "Заголовок пакета, который в Room не переносится",
        setCount = 1,
        puzzleCount = 1,
        files = listOf(
            ManifestFileDto("puzzles-001.json", "0".repeat(64)),
            ManifestFileDto("daily-sets-001.json", "1".repeat(64)),
        ),
    )

    // --- I4-M1 --------------------------------------------------------------

    @Test
    fun `I4-M1 - packId and contentVersion come from the manifest, not from the puzzle`() {
        val dto = PackFixtures.puzzle("syn-obrazec-001")

        val entity = dto.toEntity(manifest(packId = "core-ru", contentVersion = 7), storageJson)

        assertEquals("core-ru", entity.packId)
        assertEquals(7, entity.contentVersion)
    }

    @Test
    fun `I4-M1 - every column of the puzzle row is filled from the DTO`() {
        val dto = PackFixtures.puzzle(
            puzzleId = "syn-obrazec-001",
            category = "russia",
            sortDirection = "descending",
            difficulty = 3,
            retiredIn = 4,
        )

        val entity = dto.toEntity(manifest(), storageJson)

        assertEquals("syn-obrazec-001", entity.puzzleId)
        assertEquals(dto.prompt, entity.prompt)
        assertEquals("year", entity.sortKey)
        assertEquals(dto.directionLabel, entity.directionLabel)
        assertEquals(dto.explanation, entity.explanation)
        assertEquals(3, entity.difficulty)
        assertEquals(4, entity.retiredIn)
    }

    // --- I4-M4 --------------------------------------------------------------

    @Test
    fun `I4-M4 - correctOrder is stored as a comma separated list in the given order`() {
        val dto = PackFixtures.puzzle("syn-obrazec-001")
            .copy(correctOrder = listOf("c2", "c1", "c3", "c4"))

        val entity = dto.toEntity(manifest(), storageJson)

        assertEquals("c2,c1,c3,c4", entity.correctOrder)
        // И обратно — тот же список, тот же порядок.
        assertEquals(listOf("c2", "c1", "c3", "c4"), entity.toDomain(storageJson).correctOrder)
    }

    // --- I4-M5 --------------------------------------------------------------

    @Test
    fun `I4-M5 - enum tokens are stored in the content format, not as Kotlin names`() {
        val tokens = listOf(
            "history", "geography", "science", "nature", "culture", "russia", "mixed",
        )

        for (token in tokens) {
            val entity = PackFixtures.puzzle("syn-obrazec-001", category = token)
                .toEntity(manifest(), storageJson)

            assertEquals(token, entity.category)
            assertEquals(token, ContentTokens.tokenOf(entity.toDomain(storageJson).category))
        }
        for (token in listOf("ascending", "descending")) {
            val entity = PackFixtures.puzzle("syn-obrazec-001", sortDirection = token)
                .toEntity(manifest(), storageJson)

            assertEquals(token, entity.sortDirection)
            assertEquals(token, ContentTokens.tokenOf(entity.toDomain(storageJson).sortDirection))
        }
    }

    // --- I4-M3: round trip JSON-колонок -------------------------------------

    @Test
    fun `I4-M3 - cards_json survives Unicode, dashes, non-breaking spaces and nullables`() {
        val cards = listOf(
            PackFixtures.card(
                cardId = "c1",
                title = "Ёлка — длинное тире",
                subtitle = "Подзаголовок с неразрывным пробелом",
                sortValue = -1250.0,
                displayValue = "1250 год до н. э.",
                note = "Примечание «в кавычках-ёлочках»",
                sourceIds = listOf("s1", "s2"),
                disputed = true,
            ),
            PackFixtures.card("c2", sortValue = 0.5, sourceIds = listOf("s3")),
            PackFixtures.card("c3", sortValue = 1234.567, sourceIds = listOf("s4", "s5")),
            PackFixtures.card("c4", sortValue = 2026.0, sourceIds = listOf("s6", "s7", "s8")),
        )
        val sources = (1..8).map { PackFixtures.source("s$it") }
        val dto = PackFixtures.puzzle("syn-obrazec-001", cards = cards, sources = sources)

        val entity = dto.toEntity(manifest(), storageJson)
        val stored = storageJson.decodeFromString<List<StoredCard>>(entity.cardsJson)
        val domain = entity.toDomain(storageJson)

        assertEquals(4, stored.size)
        assertEquals("Ёлка — длинное тире", domain.cards[0].title)
        assertEquals("Подзаголовок с неразрывным пробелом", domain.cards[0].subtitle)
        assertEquals(-1250.0, domain.cards[0].sortValue, 0.0)
        assertEquals(0.5, domain.cards[1].sortValue, 0.0)
        assertEquals(1234.567, domain.cards[2].sortValue, 0.0)
        assertTrue(domain.cards[0].disputed)
        assertFalse(domain.cards[1].disputed)
        // Отсутствующие необязательные поля остаются null, а не пустой строкой.
        assertNull(domain.cards[1].subtitle)
        assertNull(domain.cards[1].note)
        // Порядок карточек сохраняется как в assets.
        assertEquals(listOf("c1", "c2", "c3", "c4"), domain.cards.map { it.cardId })
        // Восемь источников на четыре карточки.
        assertEquals(8, domain.sources.size)
        assertEquals((1..8).map { "s$it" }, domain.sources.map { it.sourceId })
    }

    @Test
    fun `I4-M3 - sources_json keeps both locators and the accessedAt string`() {
        val sources = listOf(
            PackFixtures.source("s1", url = "https://example.invalid/a", reference = null),
            PackFixtures.source(
                sourceId = "s2",
                url = null,
                reference = "Справочник, издание второе, страница 42",
                note = "Примечание источника",
            ),
        )
        val dto = PackFixtures.puzzle(
            "syn-obrazec-001",
            cards = (1..4).map { PackFixtures.card("c$it", sortValue = 1900.0 + it, sourceIds = listOf("s1", "s2")) },
            sources = sources,
        )

        val entity = dto.toEntity(manifest(), storageJson)
        val stored = storageJson.decodeFromString<List<StoredSource>>(entity.sourcesJson)
        val domain = entity.toDomain(storageJson)

        assertEquals(2, stored.size)
        assertEquals("https://example.invalid/a", domain.sources[0].url)
        assertNull(domain.sources[0].reference)
        assertNull(domain.sources[1].url)
        assertEquals("Справочник, издание второе, страница 42", domain.sources[1].reference)
        assertEquals("2026-08-20", domain.sources[1].accessedAt)
        assertEquals("Примечание источника", domain.sources[1].note)
        assertNull(domain.sources[0].note)
    }

    @Test
    fun `I4-M3 - disputed defaults to false when the asset omits it and is stored explicitly`() {
        // Умолчание применяется при разборе asset-DTO; в хранилище значение всегда явное.
        val decoded = ContentModule.assetJson().decodeFromString<ru.poporyadku.data.content.dto.CardDto>(
            """{"cardId":"c1","title":"Т","sortValue":1.0,"displayValue":"1","sourceIds":["s1"]}"""
        )

        assertFalse(decoded.disputed)

        val entity = PackFixtures.puzzle("syn-obrazec-001").toEntity(manifest(), storageJson)

        assertTrue("disputed обязан быть записан явно", entity.cardsJson.contains("\"disputed\""))
    }

    // --- I4-M6 --------------------------------------------------------------

    /**
     * `I4-M6`: `volatility`, `verifiedAt`, `verifiedBy` и `packTitle` **намеренно** не
     * попадают в Room (**I4-D16**). Если однажды кто-то решит их хранить, этот тест
     * обязан упасть первым и потребовать решения, а не молча пропустить изменение схемы.
     */
    @Test
    fun `I4-M6 - editorial fields and packTitle never reach the puzzle row`() {
        val dto = PackFixtures.puzzle("syn-obrazec-001").copy(
            volatility = "slow",
            verifiedAt = "2026-07-01",
            verifiedBy = "redaktor-fikstury",
        )

        val entity = dto.toEntity(
            manifest().copy(packTitle = "Заголовок, которого в базе быть не должно"),
            storageJson,
        )

        val everything = listOf(
            entity.puzzleId, entity.packId, entity.category, entity.prompt, entity.sortKey,
            entity.sortDirection, entity.directionLabel, entity.cardsJson, entity.correctOrder,
            entity.explanation, entity.sourcesJson,
        ).joinToString(" ")

        assertFalse(everything.contains("slow"))
        assertFalse(everything.contains("2026-07-01"))
        assertFalse(everything.contains("redaktor-fikstury"))
        assertFalse(everything.contains("Заголовок"))
        // У PuzzleEntity нет и самих полей: колонок под них в схеме версии 1 нет.
        val columns = ru.poporyadku.data.db.entity.PuzzleEntity::class.java.declaredFields.map { it.name }
        assertFalse("volatility" in columns)
        assertFalse("verifiedAt" in columns)
        assertFalse("verifiedBy" in columns)
        assertFalse("packTitle" in columns)
    }

    // --- наборы -------------------------------------------------------------

    @Test
    fun `I4-M1 - puzzleIds 0 to 2 become puzzle_id_1 to 3 in order`() {
        val set = PackFixtures.set(4, "syn-a-001", "syn-b-002", "syn-c-003")

        val entity = set.toEntity(PackFixtures.PACK_ID)

        assertEquals(PackFixtures.PACK_ID, entity.packId)
        assertEquals(4, entity.setIndex)
        assertEquals("syn-a-001", entity.puzzleId1)
        assertEquals("syn-b-002", entity.puzzleId2)
        assertEquals("syn-c-003", entity.puzzleId3)
    }

    // --- retiredIn ----------------------------------------------------------

    @Test
    fun `I4-M7 - a null retiredIn stays null and a set one is carried through unchanged`() {
        val active = PackFixtures.puzzle("syn-a-001", retiredIn = null).toEntity(manifest(), storageJson)
        val retired = PackFixtures.puzzle("syn-b-002", retiredIn = 2).toEntity(manifest(), storageJson)

        assertNull(active.retiredIn)
        assertEquals(2, retired.retiredIn)
        assertNull(active.toDomain(storageJson).retiredIn)
        assertEquals(2, retired.toDomain(storageJson).retiredIn)
    }
}
