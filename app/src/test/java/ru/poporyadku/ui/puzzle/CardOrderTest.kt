package ru.poporyadku.ui.puzzle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `I6-R1`, `I6-R2` — `CardOrder.move` (ITERATION_6_DESIGN.md, §4.2, I6-D4).
 *
 * Чистая функция, поэтому проверяется перечислением, а не примерами: все позиции × все
 * цели и свойство на **всех** 24 перестановках четырёх карточек.
 */
class CardOrderTest {

    private val order = listOf("c1", "c2", "c3", "c4")

    // --- I6-R1: применимые и неприменимые операции --------------------------------------

    /** `I6-R1`. Все позиции 0..3 × все цели: применимая операция ставит карточку на цель. */
    @Test
    fun `I6-R1 applicable targets put the card exactly on the target index`() {
        val expected = mapOf(
            ("c1" to MoveTarget.Down) to listOf("c2", "c1", "c3", "c4"),
            ("c1" to MoveTarget.Last) to listOf("c2", "c3", "c4", "c1"),
            ("c2" to MoveTarget.Up) to listOf("c2", "c1", "c3", "c4"),
            ("c2" to MoveTarget.First) to listOf("c2", "c1", "c3", "c4"),
            ("c2" to MoveTarget.Down) to listOf("c1", "c3", "c2", "c4"),
            ("c2" to MoveTarget.Last) to listOf("c1", "c3", "c4", "c2"),
            ("c3" to MoveTarget.Up) to listOf("c1", "c3", "c2", "c4"),
            ("c3" to MoveTarget.First) to listOf("c3", "c1", "c2", "c4"),
            ("c4" to MoveTarget.Up) to listOf("c1", "c2", "c4", "c3"),
            ("c4" to MoveTarget.First) to listOf("c4", "c1", "c2", "c3"),
        )

        expected.forEach { (intent, result) ->
            val (cardId, target) = intent
            assertEquals("$cardId → $target", result, CardOrder.move(order, cardId, target))
        }
    }

    /** `I6-R1`. Абсолютная цель для каждой карточки и каждого индекса 0..3. */
    @Test
    fun `I6-R1 absolute index moves the card to that very index`() {
        order.forEach { cardId ->
            order.indices.forEach { index ->
                val result = CardOrder.move(order, cardId, MoveTarget.Index(index))

                if (index == order.indexOf(cardId)) {
                    assertNull("$cardId уже на $index — операция неприменима", result)
                } else {
                    assertEquals(
                        "$cardId → Index($index)",
                        index,
                        requireNotNull(result).indexOf(cardId),
                    )
                }
            }
        }
    }

    /** `I6-R1`. Край списка: `Up` у первой и `Down` у последней неприменимы. */
    @Test
    fun `I6-R1 edges give null`() {
        assertNull(CardOrder.move(order, "c1", MoveTarget.Up))
        assertNull(CardOrder.move(order, "c4", MoveTarget.Down))
    }

    /** `I6-R1`. Цель, равная текущей позиции, ничего не меняет — для всех форм цели. */
    @Test
    fun `I6-R1 a target equal to the current index gives null`() {
        assertNull("c1 уже первая", CardOrder.move(order, "c1", MoveTarget.First))
        assertNull("c4 уже последняя", CardOrder.move(order, "c4", MoveTarget.Last))
        assertNull(CardOrder.move(order, "c2", MoveTarget.Index(1)))
    }

    /** `I6-R1`. Цель вне диапазона — неприменима; отрицательная и за последней. */
    @Test
    fun `I6-R1 out of range targets give null`() {
        assertNull(CardOrder.move(order, "c1", MoveTarget.Index(-1)))
        assertNull(CardOrder.move(order, "c1", MoveTarget.Index(order.size)))
        assertNull(CardOrder.move(order, "c1", MoveTarget.Index(Int.MAX_VALUE)))
    }

    /** `I6-R1`. Неизвестный `cardId` — неприменим для любой цели, включая `Index`. */
    @Test
    fun `I6-R1 unknown card id gives null for every target`() {
        allTargets().forEach { target ->
            assertNull("$target", CardOrder.move(order, "c9", target))
        }
    }

    /** `I6-R1`. Пустой список и список из одной карточки: двигать нечего. */
    @Test
    fun `I6-R1 degenerate orders give null`() {
        allTargets().forEach { target ->
            assertNull("пустой список, $target", CardOrder.move(emptyList(), "c1", target))
            assertNull("одна карточка, $target", CardOrder.move(listOf("c1"), "c1", target))
        }
    }

    // --- I6-R2: свойство на всех перестановках ------------------------------------------

    /**
     * `I6-R2`. Все 24 перестановки × 4 карточки × все цели: результат — перестановка
     * входа без потерь и дублей, карточка стоит на цели, относительный порядок остальных
     * сохранён, вход не изменён.
     */
    @Test
    fun `I6-R2 every applicable move is a permutation preserving the rest`() {
        permutations(order).forEach { source ->
            source.forEach { cardId ->
                allTargets().forEach { target ->
                    val input = source.toList()
                    val result = CardOrder.move(input, cardId, target) ?: return@forEach

                    val case = "$source, $cardId → $target"
                    assertEquals("$case: вход изменён", source, input)
                    assertNotSame("$case: вернулся тот же список", input, result)
                    assertEquals("$case: размер", input.size, result.size)
                    assertEquals("$case: множество ID", input.toSet(), result.toSet())
                    assertEquals("$case: дубли", result.size, result.toSet().size)
                    assertEquals(
                        "$case: карточка не на цели",
                        expectedIndex(input, cardId, target),
                        result.indexOf(cardId),
                    )
                    assertEquals(
                        "$case: относительный порядок остальных",
                        input.filterNot { it == cardId },
                        result.filterNot { it == cardId },
                    )
                }
            }
        }
    }

    /** `I6-R2`. Перестановка, не сдвинувшая карточку, невозможна: такой вызов даёт `null`. */
    @Test
    fun `I6-R2 a non-move never returns a new list`() {
        permutations(order).forEach { source ->
            source.forEachIndexed { index, cardId ->
                assertNull(
                    "$source: $cardId на своём месте",
                    CardOrder.move(source, cardId, MoveTarget.Index(index)),
                )
            }
        }
    }

    // --- Инфраструктура ------------------------------------------------------------------

    private fun allTargets(): List<MoveTarget> =
        listOf(MoveTarget.Up, MoveTarget.Down, MoveTarget.First, MoveTarget.Last) +
            order.indices.map { MoveTarget.Index(it) }

    private fun expectedIndex(order: List<String>, cardId: String, target: MoveTarget): Int {
        val index = order.indexOf(cardId)
        return when (target) {
            MoveTarget.Up -> index - 1
            MoveTarget.Down -> index + 1
            MoveTarget.First -> 0
            MoveTarget.Last -> order.lastIndex
            is MoveTarget.Index -> target.index
        }
    }

    /** Все перестановки списка — 24 для четырёх карточек. */
    private fun permutations(items: List<String>): List<List<String>> =
        if (items.size <= 1) {
            listOf(items)
        } else {
            items.flatMap { head ->
                permutations(items - head).map { tail -> listOf(head) + tail }
            }
        }
}
