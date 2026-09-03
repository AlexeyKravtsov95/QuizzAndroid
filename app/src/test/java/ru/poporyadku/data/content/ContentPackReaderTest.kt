package ru.poporyadku.data.content

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.poporyadku.core.model.ContentPack
import ru.poporyadku.data.content.validation.ContentViolation
import ru.poporyadku.di.ContentModule
import ru.poporyadku.domain.content.ContentInstallException

/**
 * Чтение и разбор пакета — `I4-R1`…`I4-R11` (ITERATION_4_DESIGN.md, §8.4, §17).
 *
 * Чистый JVM-тест: читатель не знает ни про Android, ни про Room. Байты приходят
 * из общего каталога фикстур через [ResourceContentSource].
 */
class ContentPackReaderTest {

    private val packId = ContentPack.CORE_RU

    private fun readerOver(
        source: ContentAssetSource,
        verifyIntegrity: Boolean = true,
    ) = ContentPackReader(source, ContentModule.assetJson(), verifyIntegrity)

    private fun readerFor(fixture: String, verifyIntegrity: Boolean = true) =
        readerOver(ContentFixtures.source(fixture), verifyIntegrity)

    private fun bundleInvalid(block: suspend () -> Unit): ContentInstallException.BundleInvalid =
        assertThrows(ContentInstallException.BundleInvalid::class.java) {
            runBlocking { block() }
        }

    // --- I4-R1 --------------------------------------------------------------

    @Test
    fun `I4-R1 - a valid pack parses and an unknown optional field is ignored`() = runBlocking {
        val reader = readerFor("valid")
        val header = reader.readHeader(packId)
        val pack = reader.readBody(header)

        assertEquals(8, pack.sets.size)
        assertEquals(25, pack.puzzles.size)
        assertEquals(1, header.manifest.contentVersion)

        // Пакет с лишним ключом в manifest.files[] разбирается и импортируется:
        // терпимость рантайма — требование политики версионирования (I4-D9).
        val tolerant = readerFor("invalid/r01-files-unknown-field")
        val tolerantHeader = tolerant.readHeader(packId)

        assertEquals(1, tolerantHeader.sets.size)
        assertNotNull(tolerant.readBody(tolerantHeader))
    }

    @Test
    fun `I4-R1 - the fingerprint is the lowercase sha256 of the manifest bytes`() = runBlocking {
        val header = readerFor("valid").readHeader(packId)
        val expected = PackFixtures.sha256(
            ContentFixtures.pack("valid").resolve(ContentPaths.MANIFEST).readBytes()
        )

        assertEquals(expected, header.fingerprint)
        assertEquals(header.fingerprint, header.fingerprint.lowercase())
    }

    @Test
    fun `I4-R1 - the fingerprint is computed even with integrity checks disabled`() = runBlocking {
        // Отключение проверки целостности снимает sha256, BOM и лишние файлы,
        // но не отпечаток: он отвечает на другой вопрос, «тот ли это контент».
        val withChecks = readerFor("valid", verifyIntegrity = true).readHeader(packId)
        val without = readerFor("valid", verifyIntegrity = false).readHeader(packId)

        assertEquals(withChecks.fingerprint, without.fingerprint)
    }

    // --- I4-R2: M05 против R01 ----------------------------------------------

    @Test
    fun `I4-R2 - malformed JSON is M05 and an undecodable document is R01`() = runBlocking {
        val malformed = bundleInvalid {
            val reader = readerFor("invalid/m05-malformed")
            reader.readBody(reader.readHeader(packId))
        }
        assertEquals(ContentViolation.M05_MALFORMED_JSON, malformed.code)

        val undecodable = bundleInvalid {
            val reader = readerFor("invalid/r02-no-correct-order")
            reader.readBody(reader.readHeader(packId))
        }
        // Именно R01, а не R02: точный код — специализация CLI (§7.3).
        assertEquals(ContentViolation.R01_SCHEMA, undecodable.code)
    }

    // --- I4-R3 --------------------------------------------------------------

    @Test
    fun `I4-R3 - a newer schemaVersion is UnsupportedSchema and an equal one passes`() =
        runBlocking {
            val unsupported = assertThrows(ContentInstallException.UnsupportedSchema::class.java) {
                runBlocking { readerFor("invalid/m01-schema-version").readHeader(packId) }
            }

            assertEquals(2, unsupported.manifest)
            assertEquals(SUPPORTED_SCHEMA_VERSION, unsupported.supported)

            val ok = readerFor("valid-minimal").readHeader(packId)
            assertEquals(SUPPORTED_SCHEMA_VERSION, ok.manifest.schemaVersion)
        }

    // --- I4-R4 --------------------------------------------------------------

    @Test
    fun `I4-R4 - a hash mismatch is M06 when checked and ignored when not`() = runBlocking {
        val failure = bundleInvalid {
            val reader = readerFor("invalid/m06-hash-mismatch")
            reader.readBody(reader.readHeader(packId))
        }
        assertEquals(ContentViolation.M06_HASH_MISMATCH, failure.code)

        // release-поведение: пакет импортируется, целостность обеспечивает система пакетов.
        val relaxed = readerFor("invalid/m06-hash-mismatch", verifyIntegrity = false)
        assertNotNull(relaxed.readBody(relaxed.readHeader(packId)))
    }

    @Test
    fun `I4-R4 - an uppercase hash does not match the lowercase contract`() = runBlocking {
        val failure = bundleInvalid {
            val reader = readerFor("invalid/m06-hash-uppercase")
            reader.readBody(reader.readHeader(packId))
        }

        assertEquals(ContentViolation.M06_HASH_MISMATCH, failure.code)
    }

    // --- I4-R5 --------------------------------------------------------------

    @Test
    fun `I4-R5 - a declared file that is missing is M04`() {
        val failure = bundleInvalid { readerFor("invalid/m04-missing-file").readHeader(packId) }

        assertEquals(ContentViolation.M04_FILE_MISSING, failure.code)
    }

    @Test
    fun `I4-R5 - an unreadable stream is AssetUnreadable with the original cause`() {
        val source = UnreadableContentSource(
            delegate = ContentFixtures.source("valid"),
            failingFileName = "daily-sets-001.json",
        )

        val failure = assertThrows(ContentInstallException.AssetUnreadable::class.java) {
            runBlocking { readerOver(source).readHeader(packId) }
        }

        assertEquals("daily-sets-001.json", failure.fileName)
        assertTrue(failure.cause is java.io.IOException)
    }

    // --- I4-R6 --------------------------------------------------------------

    @Test
    fun `I4-R6 - an unexpected file in the directory is M08 only when checked`() = runBlocking {
        val failure = bundleInvalid { readerFor("invalid/m08-extra-file").readHeader(packId) }
        assertEquals(ContentViolation.M08_UNEXPECTED_FILE, failure.code)

        val relaxed = readerFor("invalid/m08-extra-file", verifyIntegrity = false)
        assertNotNull(relaxed.readBody(relaxed.readHeader(packId)))
    }

    @Test
    fun `I4-R6 - listing the directory never opens a file`() = runBlocking {
        val source = ContentFixtures.source("invalid/m08-extra-file")

        bundleInvalid { readerOver(source).readHeader(packId) }

        // Прочитан только манифест: лишний файл не должен читаться уже потому, что он лишний.
        assertEquals(listOf(ContentPaths.MANIFEST), source.reads)
    }

    // --- I4-R7 --------------------------------------------------------------

    @Test
    fun `I4-R7 - a duplicate path and a third files element are both M03`() {
        for (fixture in listOf("invalid/m03-duplicate-path", "invalid/m03-three-files")) {
            val failure = bundleInvalid { readerFor(fixture).readHeader(packId) }

            assertEquals(fixture, ContentViolation.M03_FILE_LIST_INVALID, failure.code)
        }
    }

    // --- I4-R8 --------------------------------------------------------------

    /**
     * `I4-R8`: обход каталога отвергается ДО любой попытки открыть ассет.
     * «Прочитали, но потом сообщили» защитой не является (**I4-D6**).
     */
    @Test
    fun `I4-R8 - directory traversal is M03 and no content file is ever opened`() {
        val fixtures = listOf(
            "invalid/m03-traversal",
            "invalid/m03-absolute-path",
            "invalid/m03-subdirectory",
            "invalid/m03-uppercase-extension",
        )

        for (fixture in fixtures) {
            val source = ContentFixtures.source(fixture)

            val failure = bundleInvalid { readerOver(source).readHeader(packId) }

            assertEquals(fixture, ContentViolation.M03_FILE_LIST_INVALID, failure.code)
            assertEquals(fixture, listOf(ContentPaths.MANIFEST), source.reads)
        }
    }

    @Test
    fun `I4-R8 - ContentPaths rejects every unsafe name before any path is built`() {
        val unsafe = listOf(
            "../secret.json",
            "/etc/passwd",
            "sub/dir.json",
            "puzzles-001.JSON",
            "puzzles-001.json/../manifest.json",
            "~/puzzles-001.json",
            "puzzles-1.json",
            "puzzles-0001.json",
            "",
            "content",
            "daily-sets-001.txt",
        )

        for (name in unsafe) {
            assertTrue("имя '$name' признано допустимым", !ContentPaths.isReadable(name))
            assertThrows(IllegalArgumentException::class.java) { ContentPaths.assetPath(name) }
        }

        // Допустимы ровно два вида имён, и путь строится в одном месте.
        assertEquals("content/manifest.json", ContentPaths.assetPath(ContentPaths.MANIFEST))
        assertEquals("content/puzzles-001.json", ContentPaths.assetPath("puzzles-001.json"))
        assertEquals("content/daily-sets-042.json", ContentPaths.assetPath("daily-sets-042.json"))
        // manifest.json не может быть объявлен манифестом: он не проходит шаблон.
        assertTrue(!ContentPaths.CONTENT_FILE_NAME.matches(ContentPaths.MANIFEST))
    }

    // --- I4-R9 --------------------------------------------------------------

    @Test
    fun `I4-R9 - packId that differs from the active pack or from a file is M02`() {
        val vsActive = bundleInvalid {
            readerFor("invalid/m02-active-pack-mismatch").readHeader(packId)
        }
        assertEquals(ContentViolation.M02_PACK_ID_MISMATCH, vsActive.code)

        val vsFile = bundleInvalid {
            val reader = readerFor("invalid/m02-pack-mismatch")
            reader.readBody(reader.readHeader(packId))
        }
        assertEquals(ContentViolation.M02_PACK_ID_MISMATCH, vsFile.code)
    }

    // --- M07 ----------------------------------------------------------------

    @Test
    fun `schemaVersion that differs between files is M07`() {
        val failure = bundleInvalid {
            val reader = readerFor("invalid/m07-schema-mismatch")
            reader.readBody(reader.readHeader(packId))
        }

        assertEquals(ContentViolation.M07_SCHEMA_VERSION_MISMATCH, failure.code)
    }

    // --- I4-R11 -------------------------------------------------------------

    @Test
    fun `I4-R11 - a BOM and a missing trailing newline are M09 when checked`() = runBlocking {
        val bom = bundleInvalid {
            val reader = readerFor("invalid/m09-bom")
            reader.readBody(reader.readHeader(packId))
        }
        assertEquals(ContentViolation.M09_ENCODING, bom.code)

        val newline =
            bundleInvalid { readerFor("invalid/m09-no-trailing-newline").readHeader(packId) }
        assertEquals(ContentViolation.M09_ENCODING, newline.code)

        // При выключенной проверке кодировка не проверяется (release, I4-D7).
        val relaxed = readerFor("invalid/m09-bom", verifyIntegrity = false)
        assertNotNull(relaxed.readHeader(packId))
    }

    // --- порядок ввода-вывода -----------------------------------------------

    /**
     * Файл головоломок на быстром пути не читается вовсе: 300 КБ разбора ради вывода
     * «делать нечего» — плата, ради которой быстрый путь и существует (**I4-D10**).
     */
    @Test
    fun `readHeader reads only the manifest and the sets file`() = runBlocking {
        val source = ContentFixtures.source("valid")
        val reader = readerOver(source)

        val header = reader.readHeader(packId)

        assertEquals(listOf(ContentPaths.MANIFEST, "daily-sets-001.json"), source.reads)

        reader.readBody(header)

        assertEquals(1, source.readCount("puzzles-001.json"))
    }
}
