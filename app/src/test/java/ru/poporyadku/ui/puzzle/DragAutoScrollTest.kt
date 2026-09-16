package ru.poporyadku.ui.puzzle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `I6-R4` — скорость авто-прокрутки при перетаскивании (ITERATION_6_DESIGN.md, §5.4,
 * I6-D12).
 *
 * Чистая функция от геометрии в px: обе краевые зоны, пропорциональность, ограничение
 * максимума и невозможное направление проверяются на JVM.
 */
class DragAutoScrollTest {

    // --- Вне краевых зон -----------------------------------------------------------------

    /** `I6-R4`. Карточка в середине видимой области — прокручивать нечего. */
    @Test
    fun `I6-R4 outside the edge zones the velocity is zero`() {
        val velocity = velocityOf(itemTop = MIDDLE_TOP, itemBottom = MIDDLE_TOP + CARD_HEIGHT)

        assertEquals(0f, velocity, PRECISION)
    }

    /** `I6-R4`. Ровно на границе зоны прокрутка ещё не начинается. */
    @Test
    fun `I6-R4 exactly at the zone boundary the velocity is zero`() {
        assertEquals(
            0f,
            velocityOf(itemTop = VIEWPORT_START + EDGE_ZONE, itemBottom = VIEWPORT_END - EDGE_ZONE),
            PRECISION,
        )
    }

    // --- Верхняя зона ---------------------------------------------------------------------

    /** `I6-R4`. В верхней зоне скорость отрицательная и растёт с глубиной захода. */
    @Test
    fun `I6-R4 the top zone gives a negative velocity proportional to the depth`() {
        val quarter = velocityOf(itemTop = VIEWPORT_START + EDGE_ZONE * QUARTER_REMAINDER)
        val half = velocityOf(itemTop = VIEWPORT_START + EDGE_ZONE / HALF_DIVISOR)

        assertEquals(-MAX_VELOCITY * QUARTER, quarter, PRECISION)
        assertEquals(-MAX_VELOCITY * HALF, half, PRECISION)
        assertTrue("глубже — быстрее", half < quarter)
    }

    /** `I6-R4`. Модуль скорости не превышает максимума, как бы далеко ни зашла карточка. */
    @Test
    fun `I6-R4 the top velocity is capped at the maximum`() {
        val atEdge = velocityOf(itemTop = VIEWPORT_START)
        val farBeyond = velocityOf(itemTop = VIEWPORT_START - HUGE_OVERSHOOT)

        assertEquals(-MAX_VELOCITY, atEdge, PRECISION)
        assertEquals("за краем скорость та же, не больше", -MAX_VELOCITY, farBeyond, PRECISION)
    }

    // --- Нижняя зона -----------------------------------------------------------------------

    /** `I6-R4`. В нижней зоне скорость положительная и так же пропорциональна заходу. */
    @Test
    fun `I6-R4 the bottom zone gives a positive velocity proportional to the depth`() {
        val bottom = VIEWPORT_END - EDGE_ZONE / HALF_DIVISOR
        val velocity = velocityOf(itemTop = bottom - CARD_HEIGHT, itemBottom = bottom)

        assertEquals(MAX_VELOCITY * HALF, velocity, PRECISION)
    }

    /** `I6-R4`. Нижняя зона тоже ограничена максимумом. */
    @Test
    fun `I6-R4 the bottom velocity is capped at the maximum`() {
        val velocity = velocityOf(
            itemTop = VIEWPORT_END,
            itemBottom = VIEWPORT_END + HUGE_OVERSHOOT,
        )

        assertEquals(MAX_VELOCITY, velocity, PRECISION)
    }

    // --- Невозможное направление ------------------------------------------------------------

    /**
     * `I6-R4`. Список уже у своего края: прокручивать некуда — ноль, а не скорость,
     * которую список проглотит. Иначе кадровый цикл крутился бы у края вечно.
     */
    @Test
    fun `I6-R4 an impossible scroll direction gives zero`() {
        assertEquals(
            "вверх прокручивать некуда",
            0f,
            velocityOf(itemTop = VIEWPORT_START, canScrollBackward = false),
            PRECISION,
        )
        assertEquals(
            "вниз прокручивать некуда",
            0f,
            velocityOf(
                itemTop = VIEWPORT_END,
                itemBottom = VIEWPORT_END + CARD_HEIGHT,
                canScrollForward = false,
            ),
            PRECISION,
        )
    }

    /** `I6-R4`. Короткий список (обе прокрутки невозможны) не прокручивается ни у одного края. */
    @Test
    fun `I6-R4 a short list never scrolls`() {
        val tops = listOf(VIEWPORT_START, MIDDLE_TOP, VIEWPORT_END)

        tops.forEach { top ->
            assertEquals(
                "top=$top",
                0f,
                velocityOf(
                    itemTop = top,
                    itemBottom = top + CARD_HEIGHT,
                    canScrollBackward = false,
                    canScrollForward = false,
                ),
                PRECISION,
            )
        }
    }

    /** `I6-R4`. Нулевая краевая зона не делит на ноль и не прокручивает. */
    @Test
    fun `I6-R4 a zero edge zone gives zero`() {
        val velocity = DragAutoScroll.velocity(
            itemTop = VIEWPORT_START,
            itemBottom = VIEWPORT_START + CARD_HEIGHT,
            viewportStart = VIEWPORT_START,
            viewportEnd = VIEWPORT_END,
            edgeZonePx = 0f,
            maxVelocityPx = MAX_VELOCITY,
            canScrollBackward = true,
            canScrollForward = true,
        )

        assertEquals(0f, velocity, PRECISION)
    }

    // --- Инфраструктура ------------------------------------------------------------------------

    private fun velocityOf(
        itemTop: Float,
        itemBottom: Float = itemTop + CARD_HEIGHT,
        canScrollBackward: Boolean = true,
        canScrollForward: Boolean = true,
    ): Float = DragAutoScroll.velocity(
        itemTop = itemTop,
        itemBottom = itemBottom,
        viewportStart = VIEWPORT_START,
        viewportEnd = VIEWPORT_END,
        edgeZonePx = EDGE_ZONE,
        maxVelocityPx = MAX_VELOCITY,
        canScrollBackward = canScrollBackward,
        canScrollForward = canScrollForward,
    )

    private companion object {
        /** Видимая область списка с учётом `contentPadding`, px. */
        const val VIEWPORT_START = 0f
        const val VIEWPORT_END = 800f

        /** `dragAutoScroll.edgeZone` = `size.touchTarget.min` при плотности 1. */
        const val EDGE_ZONE = 48f

        /** `dragAutoScroll.maxVelocity` ≈ 413 dp/с при плотности 1. */
        const val MAX_VELOCITY = 413f

        const val CARD_HEIGHT = 112f
        const val MIDDLE_TOP = 300f

        const val HALF = 0.5f
        const val HALF_DIVISOR = 2f
        const val QUARTER = 0.25f

        /** Заход на четверть зоны: карточка не дошла до края на три четверти зоны. */
        const val QUARTER_REMAINDER = 0.75f

        const val HUGE_OVERSHOOT = 10_000f
        const val PRECISION = 0.001f
    }
}
