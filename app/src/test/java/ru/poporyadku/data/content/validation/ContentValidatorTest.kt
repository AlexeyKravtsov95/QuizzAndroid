package ru.poporyadku.data.content.validation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.poporyadku.data.content.ContentFixtures
import ru.poporyadku.data.content.ContentPaths
import ru.poporyadku.data.content.PackFixtures
import ru.poporyadku.data.content.RuntimeParityHarness
import ru.poporyadku.data.content.dto.ManifestDto
import ru.poporyadku.data.content.dto.ManifestFileDto
import ru.poporyadku.data.content.dto.ParsedPack
import ru.poporyadku.data.content.dto.PuzzleDto
import ru.poporyadku.domain.content.ContentInstallException

/**
 * Защитный набор рантайма — `I4-R12` и `I4-R14` (ITERATION_4_DESIGN.md, §7.2, §8.5).
 *
 * Валидатор работает только над построенным [ParsedPack], поэтому пакеты здесь
 * собираются из DTO: всё, что мешает их построить, до валидатора не доходит
 * и проверяется в `ContentPackReaderTest`.
 */
class ContentValidatorTest {

    private val validator = ContentValidator()

    private fun pack(
        puzzles: List<PuzzleDto>,
        sets: List<ru.poporyadku.data.content.dto.DailySetDto>,
        contentVersion: Int = 1,
        setCount: Int = sets.size,
        puzzleCount: Int = puzzles.size,
    ) = ParsedPack(
        manifest = ManifestDto(
            schemaVersion = 1,
            contentVersion = contentVersion,
            packId = PackFixtures.PACK_ID,
            packTitle = "Синтетический пакет фикстуры",
            setCount = setCount,
            puzzleCount = puzzleCount,
            files = listOf(
                ManifestFileDto("puzzles-001.json", "0".repeat(64)),
                ManifestFileDto("daily-sets-001.json", "1".repeat(64)),
            ),
        ),
        fingerprint = "f".repeat(64),
        puzzles = puzzles,
        sets = sets,
    )

    private fun validPack(): ParsedPack {
        val (puzzles, sets) = PackFixtures.linearPack(setCount = 2)
        return pack(puzzles, sets)
    }

    private fun codesOf(pack: ParsedPack): List<String> = validator.findings(pack).map { it.code }

    // --- позитив ------------------------------------------------------------

    @Test
    fun `a well-formed pack yields no findings`() {
        assertEquals(emptyList<String>(), codesOf(validPack()))
    }

    @Test
    fun `a retired puzzle outside every set is legitimate`() {
        val (puzzles, sets) = PackFixtures.linearPack(setCount = 1)
        val retired = PackFixtures.puzzle("syn-otozvannyy-099", retiredIn = 1)

        assertEquals(emptyList<String>(), codesOf(pack(puzzles + retired, sets)))
    }

    // --- I4-R12: защитный набор по одному коду на нарушение -----------------

    @Test
    fun `I4-R12 - R05 - a duplicate puzzleId`() {
        val (puzzles, sets) = PackFixtures.linearPack(setCount = 1)
        val duplicate = PackFixtures.puzzle(puzzles.first().puzzleId)

        assertEquals(
            listOf(ContentViolation.R05_DUPLICATE_PUZZLE_ID),
            codesOf(pack(puzzles + duplicate, sets)),
        )
    }

    @Test
    fun `I4-R12 - R19 - a gap and a duplicate in the setIndex sequence`() {
        val (puzzles, sets) = PackFixtures.linearPack(setCount = 2)
        val gap = sets.mapIndexed { i, set -> if (i == 1) set.copy(setIndex = 5) else set }
        val duplicated = sets.map { it.copy(setIndex = 0) }

        assertEquals(listOf(ContentViolation.R19_SET_INDEX_SEQUENCE), codesOf(pack(puzzles, gap)))
        assertEquals(
            listOf(ContentViolation.R19_SET_INDEX_SEQUENCE),
            codesOf(pack(puzzles, duplicated)),
        )
    }

    @Test
    fun `I4-R12 - R18 - a set references a puzzle that does not exist`() {
        val (puzzles, sets) = PackFixtures.linearPack(setCount = 1)
        val broken = listOf(PackFixtures.set(0, puzzles[0].puzzleId, puzzles[1].puzzleId, "net-takoy"))

        assertEquals(
            listOf(ContentViolation.R18_SET_REFERENCE_MISSING),
            codesOf(pack(puzzles, broken)),
        )
    }

    @Test
    fun `I4-R12 - R18A - a set references a retired puzzle`() {
        val (puzzles, sets) = PackFixtures.linearPack(setCount = 1)
        val retired = puzzles.mapIndexed { i, p -> if (i == 2) p.copy(retiredIn = 1) else p }

        assertEquals(
            listOf(ContentViolation.R18A_SET_REFERENCE_RETIRED),
            codesOf(pack(retired, sets)),
        )
    }

    @Test
    fun `I4-R12 - R18B - one puzzle used by two sets`() {
        val (puzzles, sets) = PackFixtures.linearPack(setCount = 2)
        val reused = listOf(
            sets[0],
            sets[1].copy(puzzleIds = listOf(puzzles[0].puzzleId, puzzles[4].puzzleId, puzzles[5].puzzleId)),
        )

        assertEquals(listOf(ContentViolation.R18B_PUZZLE_REUSED), codesOf(pack(puzzles, reused)))
    }

    @Test
    fun `I4-R12 - R18C - retiredIn from the future`() {
        val (puzzles, sets) = PackFixtures.linearPack(setCount = 1)
        val fromFuture = puzzles + PackFixtures.puzzle("syn-budushchaya-099", retiredIn = 2)

        assertEquals(
            listOf(ContentViolation.R18C_RETIRED_IN_FUTURE),
            codesOf(pack(fromFuture, sets, contentVersion = 1)),
        )
        // Равная текущей версии — законный отзыв, а не опечатка.
        val retiredNow = puzzles + PackFixtures.puzzle("syn-otozvannaya-099", retiredIn = 1)
        assertEquals(emptyList<String>(), codesOf(pack(retiredNow, sets, contentVersion = 1)))
    }

    @Test
    fun `I4-R12 - R21 - manifest counts differ from the actual ones`() {
        val (puzzles, sets) = PackFixtures.linearPack(setCount = 2)

        assertEquals(
            listOf(ContentViolation.R21_MANIFEST_COUNTS, ContentViolation.R21_MANIFEST_COUNTS),
            codesOf(pack(puzzles, sets, setCount = 9, puzzleCount = 99)),
        )
    }

    @Test
    fun `I4-R12 - D01 - four cards, unique cardIds, correctOrder is a permutation, texts are not blank`() {
        val (puzzles, sets) = PackFixtures.linearPack(setCount = 1)
        val cards = puzzles[0].cards

        val broken = listOf(
            puzzles[0].copy(cards = cards.drop(1), correctOrder = cards.drop(1).map { it.cardId }),
            puzzles[0].copy(cards = cards.map { it.copy(cardId = "c1") }),
            puzzles[0].copy(correctOrder = listOf("c1", "c1", "c2", "c3")),
            puzzles[0].copy(correctOrder = listOf("c1", "c2", "c3")),
            puzzles[0].copy(prompt = "   "),
            puzzles[0].copy(explanation = ""),
            puzzles[0].copy(directionLabel = " "),
        )

        for (puzzle in broken) {
            val codes = codesOf(pack(listOf(puzzle) + puzzles.drop(1), sets))

            assertTrue("нет D01 у ${puzzle.puzzleId}", ContentViolation.D01_PUZZLE_FORM in codes)
        }
    }

    @Test
    fun `I4-R12 - D02 - an unknown category or sortDirection token`() {
        val (puzzles, sets) = PackFixtures.linearPack(setCount = 1)

        val unknownCategory = puzzles[0].copy(category = "astronomiya")
        val unknownDirection = puzzles[0].copy(sortDirection = "sideways")

        assertEquals(
            listOf(ContentViolation.D02_ENUM_UNKNOWN),
            codesOf(pack(listOf(unknownCategory) + puzzles.drop(1), sets)),
        )
        assertEquals(
            listOf(ContentViolation.D02_ENUM_UNKNOWN),
            codesOf(pack(listOf(unknownDirection) + puzzles.drop(1), sets)),
        )
        // Все семь категорий и оба направления — известные токены.
        for (token in listOf("history", "geography", "science", "nature", "culture", "russia", "mixed")) {
            val ok = puzzles[0].copy(category = token)
            assertEquals(
                token,
                emptyList<String>(),
                codesOf(pack(listOf(ok) + puzzles.drop(1), sets)),
            )
        }
        for (token in listOf("ascending", "descending")) {
            val ok = puzzles[0].copy(sortDirection = token)
            assertEquals(
                token,
                emptyList<String>(),
                codesOf(pack(listOf(ok) + puzzles.drop(1), sets)),
            )
        }
    }

    // --- findings собирает ВСЁ, validate бросает первым ---------------------

    @Test
    fun `findings collects every violation and does not stop at the first`() {
        val (puzzles, sets) = PackFixtures.linearPack(setCount = 2)
        val broken = brokenPack(puzzles, sets)

        val codes = codesOf(broken)

        assertTrue(ContentViolation.D02_ENUM_UNKNOWN in codes)
        assertTrue(ContentViolation.R19_SET_INDEX_SEQUENCE in codes)
        assertTrue(ContentViolation.R21_MANIFEST_COUNTS in codes)
        assertTrue(ContentViolation.R18B_PUZZLE_REUSED in codes)
        assertTrue("нарушений меньше четырёх", codes.size >= 4)
    }

    @Test
    fun `validate throws BundleInvalid with the first code and the total count`() {
        val (puzzles, sets) = PackFixtures.linearPack(setCount = 2)
        val broken = brokenPack(puzzles, sets)
        val expected = validator.findings(broken)

        val failure = assertThrows(ContentInstallException.BundleInvalid::class.java) {
            validator.validate(broken)
        }

        assertEquals(expected.first().code, failure.code)
        assertEquals(expected.size, failure.violations)
    }

    @Test
    fun `validate is silent on a well-formed pack`() {
        validator.validate(validPack())
    }

    /** Пакет сразу с четырьмя разными нарушениями: D02, R19, R21 и R18B. */
    private fun brokenPack(
        puzzles: List<PuzzleDto>,
        sets: List<ru.poporyadku.data.content.dto.DailySetDto>,
    ) = pack(
        puzzles = listOf(puzzles[0].copy(category = "astronomiya")) + puzzles.drop(1),
        sets = listOf(
            sets[0].copy(setIndex = 0),
            // тот же индекс (R19) и переиспользованная головоломка (R18B)
            sets[1].copy(
                setIndex = 0,
                puzzleIds = listOf(puzzles[0].puzzleId, puzzles[4].puzzleId, puzzles[5].puzzleId),
            ),
        ),
        setCount = 99,
    )

    // --- порядок находок ----------------------------------------------------

    /**
     * Порядок тот же, что у диагностик CLI (§7.6): файл в порядке отображения,
     * затем указатель посегментно с ЧИСЛОВЫМ сравнением индексов, затем код.
     */
    @Test
    fun `findings are ordered like the CLI diagnostics`() {
        val (puzzles, sets) = PackFixtures.linearPack(setCount = 4)
        val broken = pack(
            puzzles = puzzles.mapIndexed { i, p ->
                if (i == 2 || i == 10) p.copy(category = "astronomiya") else p
            },
            sets = sets,
            setCount = 77,
        )

        val findings = validator.findings(broken)

        assertEquals(ContentPaths.MANIFEST, findings.first().file)
        val puzzlePointers = findings
            .filter { it.file.startsWith(ContentPaths.PUZZLES_PREFIX) }
            .map { it.pointer }
        // /puzzles/2 раньше /puzzles/10 — числовое, а не лексикографическое сравнение.
        assertEquals(listOf("/puzzles/2/category", "/puzzles/10/category"), puzzlePointers)
    }

    // --- I4-R14 -------------------------------------------------------------

    /**
     * `I4-R14`: `ContentValidator` НИКОГДА не возвращает `R02` и `R11`.
     *
     * Отсутствующий `correctOrder` до валидатора не доходит — отказ приходит раньше,
     * из `ContentPackReader`, кодом `R01`. Пустой `sourceIds` защитным инвариантом
     * не является вовсе: приложение не читает источники при выдаче головоломки,
     * и это правило остаётся за CI (§7.3, п. 3).
     */
    @Test
    fun `I4-R14 - the validator never returns R02 or R11 on any fixture`() = runBlocking {
        val forbidden = setOf("R02_CORRECT_ORDER_MISSING", "R11_SOURCE_IDS_EMPTY")

        assertEquals(emptySet<String>(), ContentValidator.OWNED_CODES intersect forbidden)

        for (expectation in ContentFixtures.expectations) {
            val codes = RuntimeParityHarness.run(expectation.name).codes

            assertEquals(
                expectation.name,
                emptyList<String>(),
                codes.filter { it in forbidden },
            )
        }
    }

    @Test
    fun `I4-R14 - the R02 fixture fails in the reader and the R11 fixture passes the runtime`() =
        runBlocking {
            assertEquals(
                listOf(ContentViolation.R01_SCHEMA),
                RuntimeParityHarness.run("invalid/r02-no-correct-order").codes,
            )
            assertEquals(
                emptyList<String>(),
                RuntimeParityHarness.run("invalid/r11-empty-source-ids").codes,
            )
        }

    // --- пустой sourceIds не ломает форму головоломки ------------------------

    @Test
    fun `an empty sourceIds is not a defensive invariant`() {
        val (puzzles, sets) = PackFixtures.linearPack(setCount = 1)
        val withoutSources = puzzles[0].copy(
            cards = puzzles[0].cards.map { it.copy(sourceIds = emptyList()) },
        )

        assertEquals(
            emptyList<String>(),
            codesOf(pack(listOf(withoutSources) + puzzles.drop(1), sets)),
        )
    }
}
