package ru.poporyadku.domain.shuffle

/**
 * Стартовый порядок карточек головоломки (ARCHITECTURE.md, ADR-010;
 * ITERATION_3_DESIGN.md, I3-D7 — I3-D9).
 *
 * Собственные FNV-1a 64 + SplitMix64 + Fisher–Yates: алгоритм записан здесь целиком,
 * а не заимствован у стандартного хеша строки и у генераторов псевдослучайных чисел
 * из stdlib и JDK. Почему каждый из трёх не подходит — ADR-010 и I3-D7: ни один
 * не даёт нам локального и проверяемого контракта стабильности.
 *
 * Результат зависит ТОЛЬКО от байтов [puzzleId] и от длины входного списка: ни даты,
 * ни `packId`, ни `setIndex`, ни локали, ни времени, ни хеша объектов. Тестовые векторы
 * `I3-H2` фиксируют это литералами.
 *
 * I3-D8: совпадение результата с `correctOrder` НЕ исправляется в рантайме — ни цикла,
 * ни рекурсии, ни поправки. Инвариант держат валидатор контента и тест `I3-H4`.
 */
object DeterministicShuffler {

    private const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL // 0xCBF29CE484222325
    private const val FNV_PRIME = 0x100000001B3L

    private const val SPLITMIX_GAMMA = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
    private const val SPLITMIX_MIX_1 = -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
    private const val SPLITMIX_MIX_2 = -0x6b2fb644ecceee15L // 0x94D049BB133111EB

    /**
     * Перестановка [cardIds] — та же длина и то же мультимножество элементов.
     * Пустой список и список из одного элемента возвращаются как есть.
     */
    fun shuffle(puzzleId: String, cardIds: List<String>): List<String> {
        if (cardIds.size <= 1) return cardIds.toList()

        val result = cardIds.toMutableList()
        var state = seedOf(puzzleId)

        // Fisher–Yates сверху вниз.
        for (i in result.lastIndex downTo 1) {
            state = nextState(state)
            // ushr 1 убирает знак, не трогая младшие биты; остаток от неотрицательного
            // числа неотрицателен, поэтому floorMod и % совпадают.
            val j = (mix(state) ushr 1) % (i + 1)
            val index = j.toInt()
            val swap = result[i]
            result[i] = result[index]
            result[index] = swap
        }
        return result
    }

    /** Открыт для тестов и для валидатора контента итерации 4. */
    fun seedOf(puzzleId: String): Long {
        var hash = FNV_OFFSET_BASIS
        for (byte in puzzleId.toByteArray(Charsets.UTF_8)) {
            hash = (hash xor (byte.toLong() and 0xFF)) * FNV_PRIME // переполнение Long штатно
        }
        return hash
    }

    private fun nextState(state: Long): Long = state + SPLITMIX_GAMMA

    private fun mix(state: Long): Long {
        var z = state
        z = (z xor (z ushr 30)) * SPLITMIX_MIX_1
        z = (z xor (z ushr 27)) * SPLITMIX_MIX_2
        return z xor (z ushr 31)
    }
}
