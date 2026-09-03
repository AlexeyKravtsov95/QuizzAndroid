package ru.poporyadku.data.content

import java.io.FileNotFoundException
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.poporyadku.core.model.ContentPack
import ru.poporyadku.data.content.dto.CardDto
import ru.poporyadku.data.content.dto.DailySetDto
import ru.poporyadku.data.content.dto.DailySetsFileDto
import ru.poporyadku.data.content.dto.ManifestDto
import ru.poporyadku.data.content.dto.ManifestFileDto
import ru.poporyadku.data.content.dto.PuzzleDto
import ru.poporyadku.data.content.dto.PuzzlesFileDto
import ru.poporyadku.data.content.dto.SourceDto

/**
 * Синтетические пакеты для тестов маппинга и импортёра (ITERATION_4_DESIGN.md, PR 4B).
 *
 * Общие фикстуры `tools/validate-content/fixtures` покрывают parity и защитный набор;
 * ветки импортёра требуют пакетов, которых там нет и быть не должно: отозванные
 * головоломки, замены по слотам, две версии одного пакета. Строятся они здесь —
 * из DTO, а не из литеральных JSON-строк, чтобы формат нельзя было разойтись с кодом.
 *
 * Временный источник итерации 3 тут не участвует ни в какой форме: настоящий конвейер
 * не имеет к временной фикстуре отношения.
 */
object PackFixtures {

    const val PACK_ID: String = ContentPack.CORE_RU

    /**
     * Отдельный `Json` для СБОРКИ файлов пакета: `explicitNulls = true`, потому что
     * `retiredIn` в формате обязателен и nullable — опустить его значило бы собрать
     * пакет, который сам же формат и нарушает.
     */
    private val writer = Json {
        prettyPrint = true
        explicitNulls = true
    }

    fun card(
        cardId: String,
        title: String = "Карточка $cardId",
        subtitle: String? = null,
        sortValue: Double,
        displayValue: String = "$sortValue",
        note: String? = null,
        sourceIds: List<String> = listOf("s1"),
        disputed: Boolean = false,
    ) = CardDto(cardId, title, subtitle, sortValue, displayValue, note, sourceIds, disputed)

    fun source(
        sourceId: String = "s1",
        title: String = "Синтетический справочник фикстуры импортёра",
        kind: String = "encyclopedia",
        url: String? = "https://example.invalid/fixture/$sourceId",
        reference: String? = null,
        accessedAt: String = "2026-08-20",
        note: String? = null,
    ) = SourceDto(sourceId, title, kind, url, reference, accessedAt, note)

    fun puzzle(
        puzzleId: String,
        category: String = "geography",
        sortDirection: String = "ascending",
        difficulty: Int = 1,
        retiredIn: Int? = null,
        cards: List<CardDto> = (1..4).map { card("c$it", sortValue = 1900.0 + it * 10) },
        correctOrder: List<String> = cards.map { it.cardId },
        sources: List<SourceDto> = listOf(source()),
        prompt: String = "Расположите образцы по признаку «год»",
        explanation: String = "Синтетическая головоломка фикстуры импортёра: " +
            "порядок следует из значений признака в поле sortValue каждой карточки.",
    ) = PuzzleDto(
        puzzleId = puzzleId,
        category = category,
        prompt = prompt,
        sortKey = "year",
        sortDirection = sortDirection,
        directionLabel = "Сверху — наименьшее",
        cards = cards,
        correctOrder = correctOrder,
        explanation = explanation,
        sources = sources,
        volatility = "stable",
        difficulty = difficulty,
        verifiedAt = "2026-08-20",
        verifiedBy = "fixture-editor",
        retiredIn = retiredIn,
    )

    fun set(setIndex: Int, vararg puzzleIds: String) = DailySetDto(setIndex, puzzleIds.toList())

    /**
     * Пакет из [setCount] наборов: головоломки называются `syn-XXX-NNN`, набор `i`
     * занимает три подряд идущие. Возвращает `(puzzles, sets)`.
     */
    fun linearPack(setCount: Int, idPrefix: String = "syn"): Pair<List<PuzzleDto>, List<DailySetDto>> {
        val puzzles = (0 until setCount * 3).map { index ->
            puzzle(
                puzzleId = "%s-obrazec-%03d".format(idPrefix, index),
                difficulty = if (index % 3 == 0) 1 else 2,
                category = CATEGORIES[index % CATEGORIES.size],
            )
        }
        val sets = (0 until setCount).map { setIndex ->
            set(
                setIndex,
                puzzles[setIndex * 3].puzzleId,
                puzzles[setIndex * 3 + 1].puzzleId,
                puzzles[setIndex * 3 + 2].puzzleId,
            )
        }
        return puzzles to sets
    }

    private val CATEGORIES = listOf("geography", "history", "science")

    /** Готовый пакет в виде трёх файлов с корректными хешами и переводами строк. */
    fun files(
        puzzles: List<PuzzleDto>,
        sets: List<DailySetDto>,
        contentVersion: Int = 1,
        packId: String = PACK_ID,
        schemaVersion: Int = 1,
        setCount: Int = sets.size,
        puzzleCount: Int = puzzles.size,
        puzzlesFileName: String = "puzzles-001.json",
        setsFileName: String = "daily-sets-001.json",
    ): Map<String, ByteArray> {
        val puzzlesBytes = bytes(
            writer.encodeToString(PuzzlesFileDto(schemaVersion, packId, puzzles))
        )
        val setsBytes = bytes(
            writer.encodeToString(DailySetsFileDto(schemaVersion, packId, sets))
        )
        val manifest = ManifestDto(
            schemaVersion = schemaVersion,
            contentVersion = contentVersion,
            packId = packId,
            packTitle = "Синтетический пакет фикстуры",
            setCount = setCount,
            puzzleCount = puzzleCount,
            files = listOf(
                ManifestFileDto(puzzlesFileName, sha256(puzzlesBytes)),
                ManifestFileDto(setsFileName, sha256(setsBytes)),
            ),
        )
        return mapOf(
            ContentPaths.MANIFEST to bytes(writer.encodeToString(manifest)),
            puzzlesFileName to puzzlesBytes,
            setsFileName to setsBytes,
        )
    }

    /** Файл заканчивается ровно одним переводом строки — как требует формат (§4.4). */
    private fun bytes(text: String): ByteArray = (text + "\n").toByteArray(Charsets.UTF_8)

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { "%02x".format(it) }
}

/**
 * [ContentAssetSource] поверх карты «имя файла → байты». Считает чтения, потому что
 * «тело пакета не читалось» — утверждение о вызовах, а не о результате (`I4-I23`).
 */
class InMemoryContentSource(private var files: Map<String, ByteArray>) : ContentAssetSource {

    val reads: MutableList<String> = mutableListOf()

    /** Лишние имена, которые вернёт [list], не будучи файлами: проверка `M08`. */
    var extraNames: List<String> = emptyList()

    fun replace(files: Map<String, ByteArray>) {
        this.files = files
    }

    /** Текущее содержимое источника: тесту нужен манифест, чтобы посчитать отпечаток. */
    fun currentFiles(): Map<String, ByteArray> = files

    override suspend fun read(fileName: String): ByteArray {
        val path = ContentPaths.assetPath(fileName) // имя проверяется ДО обращения к байтам
        reads += fileName
        return files[fileName] ?: throw FileNotFoundException("нет ассета: $path")
    }

    override suspend fun list(): List<String> = (files.keys + extraNames).sorted()

    fun readCount(fileName: String): Int = reads.count { it == fileName }
}
