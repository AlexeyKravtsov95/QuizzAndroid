package ru.poporyadku.data.content

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.poporyadku.data.content.dto.ParsedPack
import ru.poporyadku.data.content.validation.ContentValidator
import ru.poporyadku.data.content.validation.ContentViolation
import ru.poporyadku.domain.shuffle.DeterministicShuffler

/**
 * Эквивалентность CLI и рантайма — `I4-P1`…`I4-P6` (ITERATION_4_DESIGN.md, §7.4).
 *
 * Все проверки идут по ОБЩЕМУ каталогу фикстур `tools/validate-content/fixtures`:
 * второй копии в `src/test/resources` нет намеренно — копии разошлись бы молча,
 * и parity остался бы зелёным, перестав что-либо доказывать.
 */
class ContentParityTest {

    // --- I4-P1 --------------------------------------------------------------

    @Test
    fun `I4-P1 - expectations cover every fixture and every expectation has a fixture`() {
        val onDisk = ContentFixtures.allPackNames().toSet()
        val declared = ContentFixtures.expectations.map { it.name }.toSet()

        assertEquals("осиротевших фикстур нет", emptySet<String>(), onDisk - declared)
        assertEquals("лишних записей нет", emptySet<String>(), declared - onDisk)
    }

    @Test
    fun `I4-P1 - fixtures directory is the one the Python CLI reads`() {
        // Каталог задан системным свойством и абсолютен: тест не зависит от того,
        // из какого рабочего каталога запущен Gradle.
        assertTrue(ContentFixtures.root.isAbsolute)
        assertTrue(File(ContentFixtures.root, "expectations.json").isFile)
        assertTrue(File(ContentFixtures.root, "shuffle-vectors.json").isFile)
    }

    // --- I4-P2 и I4-P3 ------------------------------------------------------

    /**
     * `I4-P2`: полный runtime-путь `ContentPackReader → ContentValidator` на каждой
     * фикстуре даёт ровно колонку `runtime`.
     *
     * `I4-P3` — та же проверка на подмножестве, где колонка пуста: фикстуры,
     * нарушающие ТОЛЬКО авторские правила, рантайм не отвергает. Асимметрия не
     * побочный эффект, а проверяемое свойство: начни рантайм отвергать авторские
     * нарушения — тест упадёт и потребует осознанного решения.
     */
    @Test
    fun `I4-P2 - the full runtime path yields exactly the runtime column`() = runBlocking {
        val mismatches = ContentFixtures.expectations.mapNotNull { expectation ->
            val actual = RuntimeParityHarness.run(expectation.name).codes
            if (actual == expectation.runtime) null else {
                "${expectation.name}: ожидалось ${expectation.runtime}, получено $actual"
            }
        }

        assertEquals(emptyList<String>(), mismatches)
    }

    @Test
    fun `I4-P3 - author-only violations are not rejected by the runtime`() = runBlocking {
        val authorOnly = ContentFixtures.expectations
            .filter { it.name.startsWith("invalid/") && it.runtime.isEmpty() }

        // Список не должен опустеть: если он опустеет, асимметрия перестанет
        // проверяться, а тест останется зелёным.
        assertTrue("авторских фикстур в наборе нет", authorOnly.size >= 20)
        val rejected = authorOnly.filter { RuntimeParityHarness.run(it.name).codes.isNotEmpty() }

        assertEquals(emptyList<ContentFixtures.Expectation>(), rejected)
    }

    // --- I4-P4 --------------------------------------------------------------

    /**
     * `I4-P4`: общие векторы совпадают с НАСТОЯЩИМ `DeterministicShuffler`.
     *
     * До PR 4B векторы были снимком Kotlin-поведения, сверенным только с Python-портом.
     * Здесь равенство становится автоматически подтверждаемым: расхождение порта
     * и оригинала падает в том же прогоне CI, что и расхождение самих векторов.
     */
    @Test
    fun `I4-P4 - shuffle vectors match the real DeterministicShuffler`() {
        val vectors = ContentFixtures.shuffleVectors

        assertTrue("векторов меньше семи", vectors.vectors.size >= 7)
        for (vector in vectors.vectors) {
            assertEquals(
                "seed ${vector.puzzleId} (${vector.why})",
                vector.seed,
                DeterministicShuffler.seedOf(vector.puzzleId),
            )
            assertEquals(
                "стартовый порядок ${vector.puzzleId} (${vector.why})",
                vector.startOrder,
                DeterministicShuffler.shuffle(vector.puzzleId, vectors.cardIds),
            )
        }
    }

    // --- I4-P5 --------------------------------------------------------------

    /**
     * `I4-P5`: наборы кодов раздельно по владельцам (таблица §7.2).
     *
     * Ни один код не объявлен обоими и ни один не потерян: новый код нельзя добавить
     * в рантайм, не обновив документ и фикстуры.
     */
    @Test
    fun `I4-P5 - reader and validator own disjoint and complete code sets`() {
        val reader = ContentPackReader.OWNED_CODES
        val validator = ContentValidator.OWNED_CODES

        assertEquals(
            "владельцы кодов пересекаются",
            emptySet<String>(),
            reader intersect validator,
        )
        assertEquals(
            setOf(
                ContentViolation.M01_SCHEMA_VERSION_UNSUPPORTED,
                ContentViolation.M02_PACK_ID_MISMATCH,
                ContentViolation.M03_FILE_LIST_INVALID,
                ContentViolation.M04_FILE_MISSING,
                ContentViolation.M05_MALFORMED_JSON,
                ContentViolation.M06_HASH_MISMATCH,
                ContentViolation.M07_SCHEMA_VERSION_MISMATCH,
                ContentViolation.M08_UNEXPECTED_FILE,
                ContentViolation.M09_ENCODING,
                ContentViolation.R01_SCHEMA,
            ),
            reader,
        )
        assertEquals(
            setOf(
                ContentViolation.R05_DUPLICATE_PUZZLE_ID,
                ContentViolation.R18_SET_REFERENCE_MISSING,
                ContentViolation.R18A_SET_REFERENCE_RETIRED,
                ContentViolation.R18B_PUZZLE_REUSED,
                ContentViolation.R18C_RETIRED_IN_FUTURE,
                ContentViolation.R19_SET_INDEX_SEQUENCE,
                ContentViolation.R21_MANIFEST_COUNTS,
                ContentViolation.D01_PUZZLE_FORM,
                ContentViolation.D02_ENUM_UNKNOWN,
            ),
            validator,
        )
    }

    @Test
    fun `I4-P5 - every code the fixtures expect from the runtime has a declared owner`() {
        val owners = ContentPackReader.OWNED_CODES + ContentValidator.OWNED_CODES
        val expected = ContentFixtures.expectations.flatMap { it.runtime }.toSet()

        assertEquals("код без владельца", emptySet<String>(), expected - owners)
    }

    // --- I4-P6 --------------------------------------------------------------

    /**
     * `I4-P6`: на фикстуре с несобираемым DTO читатель даёт `R01`, а `ContentValidator`
     * **не вызывается вовсе**. Без этого теста «reader отвечает за R01» осталось бы
     * соглашением на словах.
     */
    @Test
    fun `I4-P6 - an undecodable DTO fails in the reader and never reaches the validator`() =
        runBlocking {
            var calls = 0
            val counting: (ParsedPack) -> List<ContentViolation> = {
                calls++
                ContentValidator().findings(it)
            }

            val result = RuntimeParityHarness.run(
                packName = "invalid/r02-no-correct-order",
                validate = counting,
            )

            assertEquals(listOf(ContentViolation.R01_SCHEMA), result.codes)
            assertEquals("валидатор вызван, хотя DTO не построен", 0, calls)
        }

    @Test
    fun `I4-P6 - a valid fixture does reach the validator`() = runBlocking {
        var calls = 0
        val counting: (ParsedPack) -> List<ContentViolation> = {
            calls++
            ContentValidator().findings(it)
        }

        val result = RuntimeParityHarness.run(packName = "valid", validate = counting)

        assertEquals(emptyList<String>(), result.codes)
        assertEquals(1, calls)
    }
}
