package ru.poporyadku.data.content.dto

import ru.poporyadku.data.db.entity.DailySetEntity

/**
 * Заголовок пакета — манифест, его отпечаток и наборы (ITERATION_4_DESIGN.md, §8.4).
 *
 * Читается на КАЖДОМ вызове `ensureInstalled()` и кэшируется импортёром как содержимое
 * ассетов, а не как вывод о базе (**I4-D10**). Файл головоломок сюда не входит: 300 КБ
 * разбора ради вывода «делать нечего» — ровно та плата, ради которой существует
 * быстрый путь.
 *
 * @param fingerprint нижний регистр `sha256` **точных байтов** `manifest.json`.
 * @param expectedSetRows строки `daily_sets`, которые обязаны лежать в базе, в порядке
 * `set_index` — то, с чем предикат (1) быстрого пути сравнивает `DailySetDao.byPack`.
 */
data class PackHeader(
    val manifest: ManifestDto,
    val fingerprint: String,
    val sets: List<DailySetDto>,
    val expectedSetRows: List<DailySetEntity>,
) {
    /**
     * Фактическое число наборов в файле. Совпадение с `manifest.setCount` охраняет
     * `R21`; диапазонные предикаты берут именно фактическое значение, потому что
     * ровно из него построен [expectedSetRows].
     */
    val setCount: Int get() = sets.size
}

/**
 * Полностью разобранный пакет (ITERATION_4_DESIGN.md, §8.4, §8.5).
 *
 * Существование `ParsedPack` означает, что байты прочитаны, JSON разобран и все DTO
 * построены: `M05` и `R01` остались позади, и `ContentValidator` работает уже
 * над значениями, а не над формой (§7.3).
 */
data class ParsedPack(
    val manifest: ManifestDto,
    val fingerprint: String,
    val puzzles: List<PuzzleDto>,
    val sets: List<DailySetDto>,
) {
    /** Фактическое число наборов; см. [PackHeader.setCount]. */
    val setCount: Int get() = sets.size

    // Дубликаты идентификаторов — нарушения R05 и R19, и о них сообщает валидатор;
    // индексы строятся так, чтобы поиск оставался тотальным и на испорченном пакете.
    private val setsByIndex: Map<Int, DailySetDto> by lazy(LazyThreadSafetyMode.NONE) {
        sets.associateBy { it.setIndex }
    }
    private val puzzlesById: Map<String, PuzzleDto> by lazy(LazyThreadSafetyMode.NONE) {
        puzzles.associateBy { it.puzzleId }
    }

    fun setAt(setIndex: Int): DailySetDto? = setsByIndex[setIndex]

    fun puzzleById(puzzleId: String): PuzzleDto? = puzzlesById[puzzleId]
}
