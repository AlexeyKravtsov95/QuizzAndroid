package ru.poporyadku.ui.puzzle

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `I6-R3` — единственный порог перестановки (ITERATION_6_DESIGN.md, §5.3, I6-D7).
 *
 * Порог один: визуальный центр перетаскиваемой карточки **строго** пересекает центр
 * соседней. Здесь он проверяется на числах в px — без Compose, эмулятора и реальной
 * раскладки, поэтому «карточка ушла не туда» становится воспроизводимым JVM-падением.
 */
class DragTargetResolverTest {

    // --- Движение вниз -------------------------------------------------------------------

    /** `I6-R3`. Центр ниже центра соседа снизу на 1 px — цель +1. */
    @Test
    fun `I6-R3 one pixel past the neighbour center moves one position down`() {
        val layout = uniformLayout(count = 4)

        assertEquals(1, layout.targetFor(index = 0, center = layout.center(1) + ONE_PIXEL))
    }

    /** `I6-R3`. Ровно на центре соседа перестановки нет: равенство порога не даёт. */
    @Test
    fun `I6-R3 exactly on the neighbour center changes nothing`() {
        val layout = uniformLayout(count = 4)

        assertEquals(0, layout.targetFor(index = 0, center = layout.center(1)))
        assertEquals(3, layout.targetFor(index = 3, center = layout.center(2)))
    }

    /** `I6-R3`. Два центра, пройденных за один сэмпл, дают одну цель +2, а не две. */
    @Test
    fun `I6-R3 passing two centers in a single sample gives a single target`() {
        val layout = uniformLayout(count = 4)

        assertEquals(2, layout.targetFor(index = 0, center = layout.center(2) + ONE_PIXEL))
    }

    /** `I6-R3`. Ниже последней карточки цель не уходит за `lastIndex`. */
    @Test
    fun `I6-R3 the target never exceeds the last index`() {
        val layout = uniformLayout(count = 4)

        assertEquals(3, layout.targetFor(index = 0, center = layout.center(3) + HUGE_OVERSHOOT))
    }

    // --- Движение вверх ------------------------------------------------------------------

    /** `I6-R3`. Симметрично вверх: на 1 px выше центра соседа — цель −1. */
    @Test
    fun `I6-R3 one pixel above the neighbour center moves one position up`() {
        val layout = uniformLayout(count = 4)

        assertEquals(2, layout.targetFor(index = 3, center = layout.center(2) - ONE_PIXEL))
    }

    /** `I6-R3`. Два центра вверх за сэмпл — одна цель −2; выше первой карточки цель 0. */
    @Test
    fun `I6-R3 upwards the target never goes below zero`() {
        val layout = uniformLayout(count = 4)

        assertEquals(1, layout.targetFor(index = 3, center = layout.center(1) - ONE_PIXEL))
        assertEquals(0, layout.targetFor(index = 3, center = layout.center(0) - HUGE_OVERSHOOT))
    }

    // --- Карточки разной высоты -----------------------------------------------------------

    /**
     * `I6-R3`. Карточки высотой 112 и 200 px: порог считается по **собственному** центру
     * каждой соседки, а не по усреднённому шагу списка.
     */
    @Test
    fun `I6-R3 cards of different heights use their own centers`() {
        // 112, 200, 112, 200 — чередование, чтобы ни один шаг не совпал с другим.
        val layout = layoutOf(listOf(SHORT_CARD, TALL_CARD, SHORT_CARD, TALL_CARD))

        // До центра высокой соседки — цель прежняя, хотя короткую карточку той же
        // высоты она бы уже прошла.
        assertEquals(0, layout.targetFor(index = 0, center = layout.center(1) - ONE_PIXEL))
        assertEquals(1, layout.targetFor(index = 0, center = layout.center(1) + ONE_PIXEL))

        // Вверх — то же самое от низкой соседки.
        assertEquals(3, layout.targetFor(index = 3, center = layout.center(2) + ONE_PIXEL))
        assertEquals(2, layout.targetFor(index = 3, center = layout.center(2) - ONE_PIXEL))
    }

    // --- Невидимый сосед -------------------------------------------------------------------

    /**
     * `I6-R3`. Невидимая соседка останавливает расчёт: её центр неизвестен, и
     * «перепрыгнуть» через неё по догадке нельзя — цель дождётся следующего кадра.
     */
    @Test
    fun `I6-R3 an invisible neighbour stops the search`() {
        val layout = uniformLayout(count = 4)
        val withoutSecond = layout.hiding(index = 1)

        assertEquals(
            "цель за невидимой соседкой не берётся",
            0,
            withoutSecond.targetFor(index = 0, center = layout.center(3) + ONE_PIXEL),
        )
        // Видимая соседка за видимой — проходится как обычно.
        assertEquals(
            2,
            layout.hiding(index = 3).targetFor(index = 0, center = layout.center(2) + ONE_PIXEL),
        )
    }

    // --- Отсутствие дребезга ----------------------------------------------------------------

    /**
     * `I6-R3`. Последовательность сэмплов около порога не даёт чередования перестановок
     * (доказательство §5.3): после перестановки вниз обратная цель требует смещения вверх
     * не меньше `hD + g` от точки пересечения, а не одного пикселя.
     */
    @Test
    fun `I6-R3 crossing back requires more than a pixel, so there is no chatter`() {
        val heights = listOf(SHORT_CARD, TALL_CARD, SHORT_CARD, TALL_CARD)
        val before = layoutOf(heights)

        // Порог вниз пройден на 1 px: карточка 0 встаёт на позицию 1.
        val crossingCenter = before.center(1) + ONE_PIXEL
        assertEquals(1, before.targetFor(index = 0, center = crossingCenter))

        // Подтверждённый порядок: D и N поменялись местами. Визуальный центр C не
        // изменился — его удерживает компенсация сдвига слота (`ReorderDragState`).
        val after = layoutOf(listOf(TALL_CARD, SHORT_CARD, SHORT_CARD, TALL_CARD))

        assertEquals(
            "тот же центр сразу после перестановки не возвращает карточку назад",
            1,
            after.targetFor(index = 1, center = crossingCenter),
        )

        // Дрожание пальца у порога: ±1 px в обе стороны цель не меняют.
        listOf(-ONE_PIXEL, ONE_PIXEL).forEach { jitter ->
            assertEquals(
                "дрожание $jitter px не переставляет карточку",
                1,
                after.targetFor(index = 1, center = crossingCenter + jitter),
            )
        }

        // Возврат назад наступает только пройдя центр соседки в её НОВОМ слоте.
        assertEquals(0, after.targetFor(index = 1, center = after.center(0) - ONE_PIXEL))
    }

    // --- Инфраструктура ----------------------------------------------------------------------

    /**
     * Раскладка списка: верх слота и высота каждой карточки, зазор `spacing.listGap`
     * между ними (в px — тест на JVM, плотность здесь роли не играет).
     */
    private class Layout(private val heights: List<Int>, private val hidden: Set<Int> = emptySet()) {

        fun center(index: Int): Float = slotTop(index) + heights[index] / 2f

        fun hiding(index: Int) = Layout(heights, hidden + index)

        fun targetFor(index: Int, center: Float): Int = DragTargetResolver.targetIndex(
            i = index,
            lastIndex = heights.lastIndex,
            draggedCenter = center,
            centerOf = { position -> if (position in hidden) null else center(position) },
        )

        private fun slotTop(index: Int): Int =
            (0 until index).sumOf { heights[it] + GAP }
    }

    private fun uniformLayout(count: Int) = layoutOf(List(count) { SHORT_CARD })

    private fun layoutOf(heights: List<Int>) = Layout(heights)

    private companion object {
        /** `size.orderableCard.minHeight` при плотности 1: карточка обычной высоты. */
        const val SHORT_CARD = 112

        /** Карточка с перенесённым названием — вдвое выше обычной. */
        const val TALL_CARD = 200

        /** `spacing.listGap` при плотности 1. */
        const val GAP = 12

        const val ONE_PIXEL = 1f

        /** Заведомо больше любой раскладки: проверка ограничения краями. */
        const val HUGE_OVERSHOOT = 10_000f
    }
}
