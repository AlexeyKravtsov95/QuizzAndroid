package ru.poporyadku.data.content.validation

/**
 * Одна находка защитного набора (ITERATION_4_DESIGN.md, §8.5, §7.6).
 *
 * Стабильны **код, файл, указатель и порядок**; [message] пишется для человека и
 * контрактом не является — ни один тест его не сравнивает.
 *
 * @param code стабильный код правила; строки те же, что у CLI (`contentval/diagnostics.py`).
 * @param file имя файла пакета без каталога.
 * @param pointer JSON pointer внутри файла; `""` — файл целиком.
 */
data class ContentViolation(
    val code: String,
    val file: String,
    val pointer: String,
    val message: String,
) {
    companion object {

        // --- Коды читателя (ITERATION_4_DESIGN.md, таблица §7.2) -------------
        const val M01_SCHEMA_VERSION_UNSUPPORTED = "M01_SCHEMA_VERSION_UNSUPPORTED"
        const val M02_PACK_ID_MISMATCH = "M02_PACK_ID_MISMATCH"
        const val M03_FILE_LIST_INVALID = "M03_FILE_LIST_INVALID"
        const val M04_FILE_MISSING = "M04_FILE_MISSING"
        const val M05_MALFORMED_JSON = "M05_MALFORMED_JSON"
        const val M06_HASH_MISMATCH = "M06_HASH_MISMATCH"
        const val M07_SCHEMA_VERSION_MISMATCH = "M07_SCHEMA_VERSION_MISMATCH"
        const val M08_UNEXPECTED_FILE = "M08_UNEXPECTED_FILE"
        const val M09_ENCODING = "M09_ENCODING"
        const val R01_SCHEMA = "R01_SCHEMA"

        // --- Коды валидатора (там же) ----------------------------------------
        const val R05_DUPLICATE_PUZZLE_ID = "R05_DUPLICATE_PUZZLE_ID"
        const val R18_SET_REFERENCE_MISSING = "R18_SET_REFERENCE_MISSING"
        const val R18A_SET_REFERENCE_RETIRED = "R18A_SET_REFERENCE_RETIRED"
        const val R18B_PUZZLE_REUSED = "R18B_PUZZLE_REUSED"
        const val R18C_RETIRED_IN_FUTURE = "R18C_RETIRED_IN_FUTURE"
        const val R19_SET_INDEX_SEQUENCE = "R19_SET_INDEX_SEQUENCE"
        const val R21_MANIFEST_COUNTS = "R21_MANIFEST_COUNTS"
        const val D01_PUZZLE_FORM = "D01_PUZZLE_FORM"
        const val D02_ENUM_UNKNOWN = "D02_ENUM_UNKNOWN"

        /**
         * Порядок ОТОБРАЖЕНИЯ файлов (§4.7): манифест, головоломки, наборы.
         *
         * Он совпадает с направлением ссылок (наборы ссылаются на головоломки) и НЕ
         * обязан совпадать с порядком ввода-вывода, где сначала читается манифест,
         * затем наборы, и только на пути импорта — головоломки.
         */
        fun fileRank(fileName: String): Pair<Int, String> = when {
            fileName == ru.poporyadku.data.content.ContentPaths.MANIFEST -> 0 to ""
            fileName.startsWith(ru.poporyadku.data.content.ContentPaths.PUZZLES_PREFIX) ->
                1 to fileName

            fileName.startsWith(ru.poporyadku.data.content.ContentPaths.DAILY_SETS_PREFIX) ->
                2 to fileName
            else -> 3 to fileName
        }

        /**
         * Ключ сортировки JSON pointer: посегментно, индексы массивов — **численно**,
         * поэтому `/puzzles/2` идёт раньше `/puzzles/10`. Первый элемент пары разводит
         * числовые и именованные сегменты, чтобы порядок не зависел от их смешения.
         */
        fun pointerRank(pointer: String): List<Triple<Int, Int, String>> =
            pointer.split('/')
                .filter { it.isNotEmpty() }
                .map { segment ->
                    val index = segment.toIntOrNull()
                    if (index != null && segment.all { it.isDigit() }) {
                        Triple(0, index, "")
                    } else {
                        Triple(1, 0, segment)
                    }
                }
    }
}

/**
 * Детерминированный порядок находок (ITERATION_4_DESIGN.md, §7.6), совпадающий с CLI:
 * файл в порядке отображения → JSON pointer посегментно → код лексикографически.
 *
 * Совпадающие по всем трём ключам находки схлопываются в одну: одно нарушение,
 * увиденное дважды, — это одна ошибка контента, а не две.
 */
internal fun List<ContentViolation>.sortedAsDiagnostics(): List<ContentViolation> =
    distinctBy { Triple(it.file, it.pointer, it.code) }
        .sortedWith(
            compareBy<ContentViolation> { ContentViolation.fileRank(it.file).first }
                .thenBy { ContentViolation.fileRank(it.file).second }
                .thenBy(PointerComparator) { ContentViolation.pointerRank(it.pointer) }
                .thenBy { it.code }
        )

/** Лексикографическое сравнение сегментов указателя; числовые сегменты — как числа. */
private object PointerComparator : Comparator<List<Triple<Int, Int, String>>> {
    override fun compare(
        left: List<Triple<Int, Int, String>>,
        right: List<Triple<Int, Int, String>>,
    ): Int {
        for (i in 0 until minOf(left.size, right.size)) {
            val a = left[i]
            val b = right[i]
            if (a.first != b.first) return a.first.compareTo(b.first)
            if (a.second != b.second) return a.second.compareTo(b.second)
            val byName = a.third.compareTo(b.third)
            if (byName != 0) return byName
        }
        return left.size.compareTo(right.size)
    }
}
