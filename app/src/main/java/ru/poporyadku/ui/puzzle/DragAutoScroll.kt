package ru.poporyadku.ui.puzzle

/**
 * Скорость авто-прокрутки при перетаскивании у краёв списка (ITERATION_6_DESIGN.md, §5.4,
 * I6-D12, O6-6).
 *
 * Чистая функция: вся геометрия приходит числами в px, поэтому обе краевые зоны,
 * пропорциональность и ограничение максимума проверяются JVM-тестом `I6-R4`.
 *
 * Значения краевой зоны и максимальной скорости — токены `dragAutoScroll.edgeZone` и
 * `dragAutoScroll.maxVelocity` (`DESIGN_TOKENS.md` §6.5); собственных чисел здесь нет.
 */
object DragAutoScroll {

    /**
     * @param itemTop визуальный верх перетаскиваемой карточки (со смещением пальца), px.
     * @param itemBottom визуальный низ той же карточки, px.
     * @param viewportStart начало видимой области списка с учётом `contentPadding`, px.
     * @param viewportEnd конец видимой области списка с учётом `contentPadding`, px.
     * @param edgeZonePx ширина краевой зоны, px.
     * @param maxVelocityPx максимальный модуль скорости, px/с.
     * @param canScrollBackward можно ли прокрутить список вверх.
     * @param canScrollForward можно ли прокрутить список вниз.
     *
     * @return скорость прокрутки, px/с: отрицательная — к началу списка, положительная —
     * к концу, ноль — прокручивать не нужно или невозможно. Модуль растёт линейно с
     * глубиной захода в краевую зону и не превышает [maxVelocityPx].
     */
    @Suppress("LongParameterList")
    fun velocity(
        itemTop: Float,
        itemBottom: Float,
        viewportStart: Float,
        viewportEnd: Float,
        edgeZonePx: Float,
        maxVelocityPx: Float,
        canScrollBackward: Boolean,
        canScrollForward: Boolean,
    ): Float {
        if (edgeZonePx <= 0f) return NO_SCROLL

        val topDepth = (viewportStart + edgeZonePx) - itemTop
        if (topDepth > 0f) {
            // Невозможное направление — ноль, а не «скорость, которую проглотит список»:
            // иначе цикл кадров крутился бы у края вечно.
            if (!canScrollBackward) return NO_SCROLL
            return -maxVelocityPx * depthFraction(topDepth, edgeZonePx)
        }

        val bottomDepth = itemBottom - (viewportEnd - edgeZonePx)
        if (bottomDepth > 0f) {
            if (!canScrollForward) return NO_SCROLL
            return maxVelocityPx * depthFraction(bottomDepth, edgeZonePx)
        }

        return NO_SCROLL
    }

    /** Глубина захода ограничена самой зоной: за её пределами скорость уже максимальна. */
    private fun depthFraction(depth: Float, edgeZonePx: Float): Float =
        (depth / edgeZonePx).coerceAtMost(FULL_DEPTH)

    private const val NO_SCROLL = 0f
    private const val FULL_DEPTH = 1f
}
