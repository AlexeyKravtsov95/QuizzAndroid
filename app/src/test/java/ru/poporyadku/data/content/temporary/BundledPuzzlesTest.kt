package ru.poporyadku.data.content.temporary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.poporyadku.core.model.Card
import ru.poporyadku.core.model.ContentPack
import ru.poporyadku.core.model.Puzzle
import ru.poporyadku.core.model.SortDirection
import ru.poporyadku.core.model.isPlayable
import ru.poporyadku.core.model.puzzleIdAt
import ru.poporyadku.domain.shuffle.DeterministicShuffler

// ITERATION_3_DESIGN.md, §19: I3-U1 — валидность временного контента проверяется тестом,
// а не глазами. Чистый JVM-тест: BundledPuzzles — только литералы, ни базы, ни Android.
class BundledPuzzlesTest {

    private val puzzles: List<Puzzle> = BundledPuzzles.puzzles

    @Test
    fun `I3-U1 - exactly three puzzles of three distinct categories in the active pack`() {
        assertEquals(3, puzzles.size)
        assertEquals(3, puzzles.map { it.puzzleId }.toSet().size)
        assertEquals(3, puzzles.map { it.category }.toSet().size)
        assertTrue(puzzles.all { it.packId == ContentPack.CORE_RU })
    }

    @Test
    fun `I3-U1 - every puzzle has four unique cards and a matching correctOrder`() {
        for (puzzle in puzzles) {
            val cardIds = puzzle.cards.map(Card::cardId)

            assertEquals(puzzle.puzzleId, 4, puzzle.cards.size)
            assertEquals(puzzle.puzzleId, 4, cardIds.toSet().size)
            assertEquals(puzzle.puzzleId, listOf("c1", "c2", "c3", "c4"), cardIds)
            assertEquals(puzzle.puzzleId, 4, puzzle.correctOrder.size)
            assertEquals(puzzle.puzzleId, cardIds.toSet(), puzzle.correctOrder.toSet())
            assertTrue(puzzle.puzzleId, puzzle.isPlayable())
        }
    }

    @Test
    fun `I3-U1 - sortValues are pairwise distinct and agree with sortDirection`() {
        for (puzzle in puzzles) {
            val byId = puzzle.cards.associateBy(Card::cardId)
            val values = puzzle.cards.map(Card::sortValue)

            assertEquals(puzzle.puzzleId, values.size, values.toSet().size)

            // Правило 6 валидатора (CONTENT_MODEL.md, §8): correctOrder обязан совпадать
            // с порядком, вычисленным из sortValue и sortDirection.
            val computed = when (puzzle.sortDirection) {
                SortDirection.ASCENDING -> puzzle.cards.sortedBy(Card::sortValue)
                SortDirection.DESCENDING -> puzzle.cards.sortedByDescending(Card::sortValue)
            }.map(Card::cardId)
            assertEquals(puzzle.puzzleId, computed, puzzle.correctOrder)

            // Соседние значения строго упорядочены — на случай, если правило выше
            // когда-нибудь ослабят до «не хуже».
            val ordered = puzzle.correctOrder.map { byId.getValue(it).sortValue }
            for (i in 0 until ordered.lastIndex) {
                val strictlyOrdered = when (puzzle.sortDirection) {
                    SortDirection.ASCENDING -> ordered[i] < ordered[i + 1]
                    SortDirection.DESCENDING -> ordered[i] > ordered[i + 1]
                }
                assertTrue("${puzzle.puzzleId}: $ordered", strictlyOrdered)
            }
        }
    }

    @Test
    fun `I3-U1 - texts are non-empty and directionLabel avoids the forbidden words`() {
        for (puzzle in puzzles) {
            assertTrue(puzzle.puzzleId, puzzle.prompt.isNotBlank())
            assertTrue(puzzle.puzzleId, puzzle.explanation.isNotBlank())
            assertTrue(puzzle.puzzleId, puzzle.directionLabel.isNotBlank())
            assertTrue(puzzle.puzzleId, puzzle.sortKey.isNotBlank())

            // UX_FLOW.md, §5: слова «выше»/«ниже» означают позицию в списке и совпадают
            // с измеряемой величиной ровно там, где объяснение нужнее всего.
            val label = puzzle.directionLabel.lowercase()
            assertFalse(puzzle.directionLabel, label.contains("выше"))
            assertFalse(puzzle.directionLabel, label.contains("ниже"))

            for (card in puzzle.cards) {
                assertTrue("${puzzle.puzzleId}/${card.cardId}", card.title.isNotBlank())
                assertTrue("${puzzle.puzzleId}/${card.cardId}", card.displayValue.isNotBlank())
            }
        }
    }

    @Test
    fun `I3-U1 - sources are declared and cover every card`() {
        for (puzzle in puzzles) {
            assertTrue(puzzle.puzzleId, puzzle.sources.isNotEmpty())

            val declared = puzzle.sources.map { it.sourceId }.toSet()
            assertEquals(puzzle.puzzleId, puzzle.sources.size, declared.size)
            assertTrue(puzzle.puzzleId, puzzle.sources.all { it.title.isNotBlank() })

            for (card in puzzle.cards) {
                assertTrue("${puzzle.puzzleId}/${card.cardId}", card.sourceIds.isNotEmpty())
                assertTrue(
                    "${puzzle.puzzleId}/${card.cardId}: ${card.sourceIds} вне $declared",
                    declared.containsAll(card.sourceIds),
                )
            }
        }
    }

    @Test
    fun `I3-U1 - deterministic start order differs from the correct order`() {
        // I3-D8 / I3-H4: шаффлер совпадение не «чинит», инвариант обязан держаться сам.
        for (puzzle in puzzles) {
            val start = DeterministicShuffler.shuffle(puzzle.puzzleId, puzzle.cards.map(Card::cardId))

            assertNotEquals(puzzle.puzzleId, puzzle.correctOrder, start)
        }
    }

    @Test
    fun `I3-U1 - exactly three sets with setIndex 0, 1, 2 and no holes`() {
        val sets = BundledPuzzles.sets

        assertEquals(3, sets.size)
        assertEquals(listOf(0, 1, 2), sets.map { it.setIndex })
        assertTrue(sets.all { it.packId == ContentPack.CORE_RU })

        val knownIds = puzzles.map { it.puzzleId }.toSet()
        for (set in sets) {
            val slots = (0..2).map { set.puzzleIdAt(it) }

            assertEquals("set ${set.setIndex}", 3, slots.toSet().size)
            assertTrue("set ${set.setIndex}: $slots вне $knownIds", knownIds.containsAll(slots))
        }
    }

    @Test
    fun `I3-U1 - rotation matches the approved table`() {
        val geo = BundledPuzzles.GEOGRAPHY_PUZZLE_ID
        val hist = BundledPuzzles.HISTORY_PUZZLE_ID
        val sci = BundledPuzzles.SCIENCE_PUZZLE_ID

        assertEquals(
            listOf(
                listOf(geo, hist, sci),
                listOf(hist, sci, geo),
                listOf(sci, geo, hist),
            ),
            BundledPuzzles.sets.map { set -> (0..2).map { set.puzzleIdAt(it) } },
        )
    }
}
