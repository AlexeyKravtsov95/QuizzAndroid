package ru.poporyadku.data.content

import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import ru.poporyadku.core.model.SLOTS_PER_DAY
import ru.poporyadku.data.content.dto.DailySetsFileDto
import ru.poporyadku.data.content.dto.ManifestDto
import ru.poporyadku.data.content.dto.PackHeader
import ru.poporyadku.data.content.dto.ParsedPack
import ru.poporyadku.data.content.dto.PuzzlesFileDto
import ru.poporyadku.data.content.mapper.toEntity
import ru.poporyadku.data.content.validation.ContentViolation
import ru.poporyadku.di.AssetJson
import ru.poporyadku.di.VerifyBundleIntegrity
import ru.poporyadku.domain.content.ContentInstallException

/**
 * Путь «байты → [ParsedPack]» и **ничего более** (ITERATION_4_DESIGN.md, §8.4).
 *
 * Владеет кодами `M01`–`M05`, `M07`, `R01` во всех сборках и `M06`, `M08`, `M09` при
 * включённой проверке целостности (таблица §7.2). Защитные инварианты над построенным
 * пакетом — не его работа: их владелец `ContentValidator`, и пересечения между
 * наборами кодов нет (`I4-P5`).
 *
 * Две операции, и порядок ввода-вывода определяется тем, какая вызвана (§4.7):
 * [readHeader] читает `manifest.json` и файл наборов на каждом вызове
 * `ensureInstalled()`, [readBody] — файл головоломок только на пути импорта.
 *
 * **`M05` против `R01`** (§7.3). Байты сначала разбираются в дерево, и только потом из
 * дерева строится DTO: «это не JSON» и «это JSON, но не наш документ» — разные ошибки
 * с разными кодами, и свести их к одному `decodeFromString` в одном `catch` значило бы
 * назвать опечатку в запятой нарушением схемы.
 */
class ContentPackReader @Inject constructor(
    private val source: ContentAssetSource,
    @AssetJson private val json: Json,
    @VerifyBundleIntegrity private val verifyIntegrity: Boolean,
) {

    /**
     * Манифест, его отпечаток и наборы.
     *
     * Отпечаток считается ВСЕГДА, включая release: он не проверка целостности, а ответ
     * на вопрос «тот ли это контент», без которого правка пакета без повышения
     * `contentVersion` молча игнорировалась бы (**I4-D10**).
     *
     * @throws ContentInstallException.UnsupportedSchema `M01`.
     * @throws ContentInstallException.BundleInvalid `M02`–`M05`, `M07`, `R01`
     * (+ `M06`, `M08`, `M09` при включённой проверке целостности).
     * @throws ContentInstallException.AssetUnreadable ввод-вывод, а не содержимое.
     */
    suspend fun readHeader(activePackId: String): PackHeader {
        val manifestBytes = read(ContentPaths.MANIFEST)
        val fingerprint = sha256Lower(manifestBytes)

        // Проверка кодировки идёт ДО разбора: BOM ломает именно разбор, и увидеть его
        // как «сломанный JSON» значило бы назвать причину следствием.
        checkEncoding(ContentPaths.MANIFEST, manifestBytes)

        val manifestText = parse(ContentPaths.MANIFEST, manifestBytes)
        val manifest = decode<ManifestDto>(ContentPaths.MANIFEST, manifestText)

        checkSupportedSchema(manifest)
        checkActivePack(manifest, activePackId)
        checkFileList(manifest)
        checkDirectory(manifest)

        val setsFileName = declaredFileName(manifest, DAILY_SETS_PREFIX)
        val setsBytes = readDeclared(manifest, setsFileName)
        val setsFile = decode<DailySetsFileDto>(setsFileName, parse(setsFileName, setsBytes))
        checkEnvelope(setsFileName, manifest, setsFile.schemaVersion, setsFile.packId)
        checkSetShape(setsFileName, setsFile)

        return PackHeader(
            manifest = manifest,
            fingerprint = fingerprint,
            sets = setsFile.sets,
            expectedSetRows = setsFile.sets
                .map { it.toEntity(manifest.packId) }
                .sortedBy { it.setIndex },
        )
    }

    /**
     * Полный пакет. Вызывается ТОЛЬКО когда импорт действительно нужен: на быстром пути
     * файл головоломок не читается вовсе (**I4-D10**).
     */
    suspend fun readBody(header: PackHeader): ParsedPack {
        val manifest = header.manifest
        val puzzlesFileName = declaredFileName(manifest, PUZZLES_PREFIX)
        val puzzlesBytes = readDeclared(manifest, puzzlesFileName)
        val puzzlesFile =
            decode<PuzzlesFileDto>(puzzlesFileName, parse(puzzlesFileName, puzzlesBytes))
        checkEnvelope(puzzlesFileName, manifest, puzzlesFile.schemaVersion, puzzlesFile.packId)

        return ParsedPack(
            manifest = manifest,
            fingerprint = header.fingerprint,
            puzzles = puzzlesFile.puzzles,
            sets = header.sets,
        )
    }

    // ---------- байты ----------

    /**
     * Отсутствующий файл пакета — свойство пакета (`M04`), любой другой отказ
     * ввода-вывода — свойство устройства (`AssetUnreadable`, повтор имеет смысл).
     * `CancellationException` не преобразуется: она не `IOException` и летит как есть.
     */
    private suspend fun read(fileName: String): ByteArray =
        try {
            source.read(fileName)
        } catch (e: FileNotFoundException) {
            throw bundleInvalid(
                ContentViolation(
                    code = ContentViolation.M04_FILE_MISSING,
                    file = fileName,
                    pointer = "",
                    message = "файл пакета не найден: ${e.message ?: fileName}",
                )
            )
        } catch (e: IOException) {
            throw ContentInstallException.AssetUnreadable(fileName, e)
        }

    /** Чтение объявленного файла: байты, затем целостность — до любого разбора. */
    private suspend fun readDeclared(manifest: ManifestDto, fileName: String): ByteArray {
        val bytes = read(fileName)
        checkHash(manifest, fileName, bytes)
        checkEncoding(fileName, bytes)
        return bytes
    }

    // ---------- разбор ----------

    /**
     * Шаг «это вообще JSON». Дерево строится только ради ответа на этот вопрос и тут же
     * выбрасывается: документ дальше декодируется из ИСХОДНОГО текста, а не из дерева,
     * потому что дерево теряет различие «число» и «строка, похожая на число», —
     * `"difficulty": "1"` прошёл бы мимо `R01`, хотя `isLenient = false` его запрещает.
     * Двойной разбор платится один раз на импорт и покупает точность кода.
     */
    private fun parse(fileName: String, bytes: ByteArray): String {
        val text = String(bytes, Charsets.UTF_8)
        try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw bundleInvalid(
                ContentViolation(
                    code = ContentViolation.M05_MALFORMED_JSON,
                    file = fileName,
                    pointer = "",
                    message = "файл не разбирается как JSON: ${e.message}",
                )
            )
        }
        return text
    }

    /**
     * JSON корректен, но документ не наш: нет обязательного поля, неверный тип, неверная
     * форма массива. Один код на все случаи (**§7.3**): точность схемы воспроизводима
     * в Kotlin только второй JSON Schema, написанной руками.
     *
     * Неизвестное **необязательное** поле сюда не попадает: `@AssetJson` его игнорирует
     * по требованию политики версионирования (**I4-D9**).
     */
    private inline fun <reified T> decode(fileName: String, text: String): T =
        try {
            json.decodeFromString<T>(text)
        } catch (e: SerializationException) {
            throw bundleInvalid(
                ContentViolation(
                    code = ContentViolation.R01_SCHEMA,
                    file = fileName,
                    pointer = "",
                    message = "документ не соответствует формату: ${e.message}",
                )
            )
        } catch (e: IllegalArgumentException) {
            // Несовпадение вида элемента приходит именно так.
            throw bundleInvalid(
                ContentViolation(
                    code = ContentViolation.R01_SCHEMA,
                    file = fileName,
                    pointer = "",
                    message = "документ не соответствует формату: ${e.message}",
                )
            )
        }

    // ---------- правила пакета ----------

    private fun checkSupportedSchema(manifest: ManifestDto) {
        if (manifest.schemaVersion > SUPPORTED_SCHEMA_VERSION) {
            throw ContentInstallException.UnsupportedSchema(
                manifest = manifest.schemaVersion,
                supported = SUPPORTED_SCHEMA_VERSION,
            )
        }
    }

    private fun checkActivePack(manifest: ManifestDto, activePackId: String) {
        if (manifest.packId != activePackId) {
            throw bundleInvalid(
                ContentViolation(
                    code = ContentViolation.M02_PACK_ID_MISMATCH,
                    file = ContentPaths.MANIFEST,
                    pointer = "/packId",
                    message = "packId пакета '${manifest.packId}' " +
                        "не совпадает с активным пакетом '$activePackId'",
                )
            )
        }
    }

    /**
     * `M03` — состав списка файлов и **имя каждого объявленного файла** (**I4-D6**).
     *
     * Проверяется до чтения хоть одного объявленного файла: «прочитали, но потом
     * сообщили» защитой не является.
     */
    private fun checkFileList(manifest: ManifestDto) {
        val found = mutableListOf<ContentViolation>()
        val files = manifest.files

        if (files.size != EXPECTED_FILE_COUNT) {
            found += violation(
                ContentViolation.M03_FILE_LIST_INVALID,
                "/files",
                "files содержит ${files.size} элементов вместо $EXPECTED_FILE_COUNT",
            )
        }
        files.forEachIndexed { index, file ->
            if (!ContentPaths.CONTENT_FILE_NAME.matches(file.path)) {
                found += violation(
                    ContentViolation.M03_FILE_LIST_INVALID,
                    "/files/$index/path",
                    "имя '${file.path}' не проходит закрытый шаблон контентного файла",
                )
            }
        }
        if (files.map { it.path }.distinct().size != files.size) {
            found += violation(
                ContentViolation.M03_FILE_LIST_INVALID,
                "/files",
                "пути в files не уникальны: ${files.map { it.path }}",
            )
        }
        for (prefix in listOf(PUZZLES_PREFIX, DAILY_SETS_PREFIX)) {
            val count = files.count { it.path.startsWith(prefix) }
            if (count != 1) {
                found += violation(
                    ContentViolation.M03_FILE_LIST_INVALID,
                    "/files",
                    "файлов с префиксом '$prefix' — $count, ожидается ровно 1",
                )
            }
        }
        if (found.isNotEmpty()) throw bundleInvalid(found)
    }

    /** `M08` — лишний файл в каталоге. Только при включённой проверке целостности. */
    private suspend fun checkDirectory(manifest: ManifestDto) {
        if (!verifyIntegrity) return
        val declared = manifest.files.map { it.path }.toSet() + ContentPaths.MANIFEST
        val unexpected = source.list().filterNot { it in declared }.sorted()
        if (unexpected.isNotEmpty()) {
            throw bundleInvalid(
                violation(
                    ContentViolation.M08_UNEXPECTED_FILE,
                    "",
                    "в каталоге ${ContentPaths.ROOT}/ есть необъявленные файлы: $unexpected",
                )
            )
        }
    }

    /** `M06` — `sha256` точных байтов файла, нижний регистр. Debug и CI, не release. */
    private fun checkHash(manifest: ManifestDto, fileName: String, bytes: ByteArray) {
        if (!verifyIntegrity) return
        val declared = manifest.files.first { it.path == fileName }.sha256
        val actual = sha256Lower(bytes)
        if (declared != actual) {
            throw bundleInvalid(
                ContentViolation(
                    code = ContentViolation.M06_HASH_MISMATCH,
                    file = fileName,
                    pointer = "",
                    message = "sha256 файла $actual не совпадает с объявленным $declared",
                )
            )
        }
    }

    /**
     * `M09` — BOM, не-UTF-8 байты или отсутствие завершающего перевода строки.
     * Debug и CI, не release (**I4-D7**).
     */
    private fun checkEncoding(fileName: String, bytes: ByteArray) {
        if (!verifyIntegrity) return
        val problem = when {
            bytes.size >= BOM.size && BOM.indices.all { bytes[it] == BOM[it] } ->
                "файл начинается с BOM"

            bytes.isEmpty() || bytes.last() != NEWLINE ->
                "файл не заканчивается переводом строки"

            !isStrictUtf8(bytes) -> "файл содержит байты, не образующие UTF-8"
            else -> null
        }
        if (problem != null) {
            throw bundleInvalid(
                ContentViolation(ContentViolation.M09_ENCODING, fileName, "", problem)
            )
        }
    }

    /**
     * Форма набора — часть формы документа, а не защитный инвариант: «неверная форма
     * массива» названа кодом `R01` (**§7.3**). Проверяется здесь, потому что тройка
     * раскладывается по плоским полям `puzzle_id_1..3` ещё в заголовке, до валидатора,
     * и список другой длины не должен превращаться в `IndexOutOfBoundsException`.
     */
    private fun checkSetShape(fileName: String, file: DailySetsFileDto) {
        val broken = file.sets.indexOfFirst { it.puzzleIds.size != SLOTS_PER_DAY }
        if (broken >= 0) {
            throw bundleInvalid(
                ContentViolation(
                    code = ContentViolation.R01_SCHEMA,
                    file = fileName,
                    pointer = "/sets/$broken/puzzleIds",
                    message = "в наборе ${file.sets[broken].setIndex} " +
                        "${file.sets[broken].puzzleIds.size} идентификаторов вместо $SLOTS_PER_DAY",
                )
            )
        }
    }

    /** `M07` + вторая половина `M02`: конверт файла обязан совпасть с манифестом. */
    private fun checkEnvelope(
        fileName: String,
        manifest: ManifestDto,
        schemaVersion: Int,
        packId: String,
    ) {
        if (schemaVersion != manifest.schemaVersion) {
            throw bundleInvalid(
                ContentViolation(
                    code = ContentViolation.M07_SCHEMA_VERSION_MISMATCH,
                    file = fileName,
                    pointer = "/schemaVersion",
                    message = "schemaVersion файла $schemaVersion " +
                        "расходится с манифестом ${manifest.schemaVersion}",
                )
            )
        }
        if (packId != manifest.packId) {
            throw bundleInvalid(
                ContentViolation(
                    code = ContentViolation.M02_PACK_ID_MISMATCH,
                    file = fileName,
                    pointer = "/packId",
                    message = "packId файла '$packId' расходится с манифестом '${manifest.packId}'",
                )
            )
        }
    }

    // ---------- вспомогательное ----------

    private fun declaredFileName(manifest: ManifestDto, prefix: String): String =
        manifest.files.first { it.path.startsWith(prefix) }.path

    private fun violation(code: String, pointer: String, message: String) =
        ContentViolation(code, ContentPaths.MANIFEST, pointer, message)

    private fun bundleInvalid(violation: ContentViolation) = bundleInvalid(listOf(violation))

    private fun bundleInvalid(violations: List<ContentViolation>): ContentInstallException {
        val first = violations.first()
        return ContentInstallException.BundleInvalid(
            code = first.code,
            violations = violations.size,
            detail = "${first.file}#${first.pointer} — ${first.message}",
        )
    }

    private fun sha256Lower(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun isStrictUtf8(bytes: ByteArray): Boolean =
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            true
        } catch (e: java.nio.charset.CharacterCodingException) {
            false
        }

    companion object {
        private const val PUZZLES_PREFIX = ContentPaths.PUZZLES_PREFIX
        private const val DAILY_SETS_PREFIX = ContentPaths.DAILY_SETS_PREFIX
        private const val EXPECTED_FILE_COUNT = 2
        private const val NEWLINE = '\n'.code.toByte()
        private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        /**
         * Коды, которые объявляет читатель (таблица §7.2). Сверяется тестом `I4-P5`:
         * ни один код не объявлен обоими владельцами и ни один не потерян.
         */
        val OWNED_CODES: Set<String> = setOf(
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
        )
    }
}
