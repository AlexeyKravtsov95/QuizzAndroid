package ru.poporyadku.domain.shuffle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// ITERATION_3_DESIGN.md, §19: I3-H1 — I3-H7. Чистый JVM-тест.
//
// Литералы в I3-H2 и I3-H4 — ЧАСТЬ КОНТРАКТА: после принятия реализации они не меняются
// без явного решения. Если алгоритм когда-нибудь изменится, эти тесты покажут это первыми.
class DeterministicShufflerTest {

    private val cardIds = listOf("c1", "c2", "c3", "c4")

    // Три временные головоломки (data/content/temporary/BundledPuzzles.kt). Идентификаторы
    // выписаны литералами намеренно: domain про data не знает, и тест домена — тоже.
    private val geographyId = "tmp-geo-vysota-001"
    private val historyId = "tmp-hist-izobreteniya-002"
    private val scienceId = "tmp-sci-otkrytiya-003"

    @Test
    fun `I3-H1 - repeated calls with the same puzzleId give an identical result`() {
        val expected = DeterministicShuffler.shuffle(geographyId, cardIds)

        repeat(100) {
            assertEquals(expected, DeterministicShuffler.shuffle(geographyId, cardIds))
        }
        // Два разных экземпляра списка с тем же содержимым — тот же результат.
        assertEquals(expected, DeterministicShuffler.shuffle(geographyId, listOf("c1", "c2", "c3", "c4")))
        assertEquals(expected, DeterministicShuffler.shuffle(geographyId, ArrayList(cardIds)))
    }

    @Test
    fun `I3-H2 - literal test vectors`() {
        assertEquals(listOf("c1", "c2", "c4", "c3"), DeterministicShuffler.shuffle(geographyId, cardIds))
        assertEquals(listOf("c1", "c3", "c2", "c4"), DeterministicShuffler.shuffle(historyId, cardIds))
        assertEquals(listOf("c3", "c4", "c2", "c1"), DeterministicShuffler.shuffle(scienceId, cardIds))

        // Seed — тоже контракт: он открыт для валидатора контента итерации 4.
        assertEquals(8056761665564835395L, DeterministicShuffler.seedOf(geographyId))
        assertEquals(-1313988808359401040L, DeterministicShuffler.seedOf(historyId))
        assertEquals(-5719328115881941861L, DeterministicShuffler.seedOf(scienceId))
    }

    @Test
    fun `I3-H3 - the result is a permutation of the input`() {
        for (puzzleId in listOf(geographyId, historyId, scienceId, "", "x", "любая строка")) {
            val shuffled = DeterministicShuffler.shuffle(puzzleId, cardIds)

            assertEquals(puzzleId, cardIds.size, shuffled.size)
            assertEquals(puzzleId, cardIds.sorted(), shuffled.sorted())
            assertEquals(puzzleId, cardIds.groupingBy { it }.eachCount(), shuffled.groupingBy { it }.eachCount())
        }

        // Более длинный вход — то же свойство, без предположений о размере 4.
        val long = (1..12).map { "c$it" }
        assertEquals(long.sorted(), DeterministicShuffler.shuffle(geographyId, long).sorted())
    }

    @Test
    fun `I3-H4 - no temporary puzzle starts in its correct order`() {
        // correctOrder трёх временных головоломок — литералы BundledPuzzles.
        val puzzles = mapOf(
            geographyId to listOf("c2", "c1", "c3", "c4"),
            historyId to listOf("c2", "c4", "c1", "c3"),
            scienceId to listOf("c3", "c2", "c4", "c1"),
        )

        for ((puzzleId, correctOrder) in puzzles) {
            // I3-D8: шаффлер не «чинит» совпадение — инвариант обязан держаться сам.
            assertNotEquals(puzzleId, correctOrder, DeterministicShuffler.shuffle(puzzleId, cardIds))
        }
    }

    @Test
    fun `I3-H5 - different puzzleIds give different seeds and generally different orders`() {
        val ids = listOf(geographyId, historyId, scienceId)
        val seeds = ids.map { DeterministicShuffler.seedOf(it) }

        assertEquals("seedOf инъективна на временных id", ids.size, seeds.toSet().size)
        assertEquals(
            "стартовые порядки трёх временных головоломок различны",
            ids.size,
            ids.map { DeterministicShuffler.shuffle(it, cardIds) }.toSet().size,
        )

        // На широкой выборке идентификаторов результат тоже расходится, а не залипает.
        val orders = (1..200).map { DeterministicShuffler.shuffle("tmp-probe-%03d".format(it), cardIds) }
        assertTrue("порядки не должны схлопываться в один", orders.toSet().size > 10)
    }

    @Test
    fun `I3-H6 - seed depends on puzzleId bytes only, not on packId, date or input order`() {
        val seed = DeterministicShuffler.seedOf(geographyId)

        // Ни packId, ни дата, ни setIndex в seed не входят: любая такая примесь дала бы
        // другое значение, а контракт I3-H2 фиксирует именно это.
        assertNotEquals(seed, DeterministicShuffler.seedOf("core-ru:$geographyId"))
        assertNotEquals(seed, DeterministicShuffler.seedOf("$geographyId:2026-09-01"))
        assertNotEquals(seed, DeterministicShuffler.seedOf(geographyId.uppercase()))

        // Порядок карточек во входном списке на перестановку не влияет: применяется одна
        // и та же последовательность обменов, что бы ни лежало в списке.
        val letters = listOf("a", "b", "c", "d")
        val permutationOfCards = DeterministicShuffler.shuffle(geographyId, cardIds)
            .map { cardIds.indexOf(it) }
        val permutationOfLetters = DeterministicShuffler.shuffle(geographyId, letters)
            .map { letters.indexOf(it) }
        assertEquals(permutationOfCards, permutationOfLetters)

        val reversedInput = cardIds.reversed()
        val permutationOfReversed = DeterministicShuffler.shuffle(geographyId, reversedInput)
            .map { reversedInput.indexOf(it) }
        assertEquals(permutationOfCards, permutationOfReversed)
    }

    @Test
    fun `I3-H7 - empty and single element lists are returned as is`() {
        assertEquals(emptyList<String>(), DeterministicShuffler.shuffle(geographyId, emptyList()))
        assertEquals(listOf("c1"), DeterministicShuffler.shuffle(geographyId, listOf("c1")))
        assertEquals(listOf("c1"), DeterministicShuffler.shuffle("", listOf("c1")))
    }
}
