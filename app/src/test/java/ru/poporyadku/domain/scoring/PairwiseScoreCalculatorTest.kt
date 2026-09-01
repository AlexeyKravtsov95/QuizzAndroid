package ru.poporyadku.domain.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// ITERATION_3_DESIGN.md, §19: I3-S1 — I3-S9. Чистый JVM-тест, без Android и корутин.
class PairwiseScoreCalculatorTest {

    private val correct = listOf("c1", "c2", "c3", "c4")

    private fun permutations(items: List<String>): List<List<String>> =
        if (items.size <= 1) listOf(items)
        else items.flatMap { head ->
            permutations(items - head).map { tail -> listOf(head) + tail }
        }

    @Test
    fun `I3-S1 - distribution over all 24 permutations matches the reference`() {
        val all = permutations(correct)
        assertEquals(24, all.size)

        val byScore = all.groupingBy { PairwiseScoreCalculator.evaluate(it, correct).score }
            .eachCount()

        // ARCHITECTURE.md, §4: эталонное распределение 1, 3, 5, 6, 5, 3, 1 (по счёту 6..0).
        assertEquals(mapOf(6 to 1, 5 to 3, 4 to 5, 3 to 6, 2 to 5, 1 to 3, 0 to 1), byScore)
    }

    @Test
    fun `I3-S2 - correct order scores the maximum and reports no inverted pairs`() {
        val result = PairwiseScoreCalculator.evaluate(correct, correct)

        assertEquals(PairwiseScoreCalculator.MAX_PER_PUZZLE, result.score)
        assertEquals(emptyList<InvertedPair>(), result.invertedPairs)
    }

    @Test
    fun `I3-S3 - fully reversed order scores zero and reports six pairs`() {
        val result = PairwiseScoreCalculator.evaluate(correct.reversed(), correct)

        assertEquals(0, result.score)
        assertEquals(PairwiseScoreCalculator.MAX_PER_PUZZLE, result.invertedPairs.size)
    }

    @Test
    fun `I3-S4 - swapping neighbours costs exactly one point in any position`() {
        for (position in 0..2) {
            val submitted = correct.toMutableList()
            submitted[position] = correct[position + 1]
            submitted[position + 1] = correct[position]

            val result = PairwiseScoreCalculator.evaluate(submitted, correct)

            assertEquals("позиция $position", 5, result.score)
            assertEquals("позиция $position", 1, result.invertedPairs.size)
        }
    }

    @Test
    fun `I3-S5 - inverted pair count always completes the score to the maximum`() {
        for (submitted in permutations(correct)) {
            val result = PairwiseScoreCalculator.evaluate(submitted, correct)

            assertEquals(
                "$submitted",
                PairwiseScoreCalculator.MAX_PER_PUZZLE - result.score,
                result.invertedPairs.size,
            )
        }
    }

    @Test
    fun `I3-S6 - every reported pair is normalised by correct order and inverted in submitted`() {
        val rank = correct.withIndex().associate { (index, id) -> id to index }

        for (submitted in permutations(correct)) {
            val result = PairwiseScoreCalculator.evaluate(submitted, correct)
            val submittedRank = submitted.withIndex().associate { (index, id) -> id to index }

            for (pair in result.invertedPairs) {
                assertTrue(
                    "$submitted: пара не нормализована по correctOrder — $pair",
                    rank.getValue(pair.correctlyFirst) < rank.getValue(pair.correctlySecond),
                )
                assertTrue(
                    "$submitted: пара не инвертирована в submittedOrder — $pair",
                    submittedRank.getValue(pair.correctlySecond) <
                        submittedRank.getValue(pair.correctlyFirst),
                )
            }
            // Пары не дублируются: шесть пар делятся между score и invertedPairs.
            assertEquals(
                "$submitted",
                result.invertedPairs.size,
                result.invertedPairs.toSet().size,
            )
        }
    }

    @Test
    fun `I3-S7 - inverted pairs are sorted by position in correct order`() {
        val rank = correct.withIndex().associate { (index, id) -> id to index }

        for (submitted in permutations(correct)) {
            val pairs = PairwiseScoreCalculator.evaluate(submitted, correct).invertedPairs
            val keys = pairs.map { rank.getValue(it.correctlyFirst) to rank.getValue(it.correctlySecond) }

            assertEquals("$submitted", keys.sortedWith(compareBy({ it.first }, { it.second })), keys)
        }

        // Порядок детерминирован: повторный вызов даёт тот же список.
        val submitted = listOf("c4", "c3", "c2", "c1")
        assertEquals(
            PairwiseScoreCalculator.evaluate(submitted, correct).invertedPairs,
            PairwiseScoreCalculator.evaluate(submitted, correct).invertedPairs,
        )
        assertEquals(
            listOf(
                InvertedPair("c1", "c2"),
                InvertedPair("c1", "c3"),
                InvertedPair("c1", "c4"),
                InvertedPair("c2", "c3"),
                InvertedPair("c2", "c4"),
                InvertedPair("c3", "c4"),
            ),
            PairwiseScoreCalculator.evaluate(submitted, correct).invertedPairs,
        )
    }

    @Test
    fun `I3-S8 - invalid input is rejected by require`() {
        // Повторяющийся cardId.
        assertThrows(IllegalArgumentException::class.java) {
            PairwiseScoreCalculator.evaluate(listOf("c1", "c1", "c3", "c4"), correct)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairwiseScoreCalculator.evaluate(correct, listOf("c1", "c1", "c3", "c4"))
        }
        // Отсутствующий cardId: c4 в submittedOrder нет (на его месте посторонний c9).
        assertThrows(IllegalArgumentException::class.java) {
            PairwiseScoreCalculator.evaluate(listOf("c1", "c2", "c3", "c9"), correct)
        }
        // Лишний cardId: c5 не объявлен в correctOrder.
        assertThrows(IllegalArgumentException::class.java) {
            PairwiseScoreCalculator.evaluate(listOf("c1", "c2", "c3", "c5"), correct)
        }
        // Размер 3.
        assertThrows(IllegalArgumentException::class.java) {
            PairwiseScoreCalculator.evaluate(listOf("c1", "c2", "c3"), correct)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairwiseScoreCalculator.evaluate(correct, listOf("c1", "c2", "c3"))
        }
        // Размер 5.
        assertThrows(IllegalArgumentException::class.java) {
            PairwiseScoreCalculator.evaluate(listOf("c1", "c2", "c3", "c4", "c5"), correct)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairwiseScoreCalculator.evaluate(correct, listOf("c1", "c2", "c3", "c4", "c5"))
        }
        // Пустой вход — тот же дефект, а не отдельный доменный случай.
        assertThrows(IllegalArgumentException::class.java) {
            PairwiseScoreCalculator.evaluate(emptyList(), correct)
        }
    }

    @Test
    fun `I3-S9 - average score over all permutations is exactly three`() {
        val all = permutations(correct)
        val total = all.sumOf { PairwiseScoreCalculator.evaluate(it, correct).score }

        // PRODUCT.md, R-4: случайный порядок даёт ровно половину максимума.
        assertEquals(3 * all.size, total)
        assertEquals(PairwiseScoreCalculator.MAX_PER_PUZZLE, 2 * (total / all.size))
    }

    @Test
    fun `I3-S9 - day maximum is three puzzles worth of points`() {
        assertEquals(18, PairwiseScoreCalculator.MAX_PER_DAY)
        assertEquals(3 * PairwiseScoreCalculator.MAX_PER_PUZZLE, PairwiseScoreCalculator.MAX_PER_DAY)
    }
}
