package ru.poporyadku.ui.puzzle

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Локальное состояние жеста перетаскивания (ITERATION_6_DESIGN.md, §5.3, I6-D6, I6-D9).
 *
 * Здесь живёт **только визуальное**: какая карточка поднята, идентификатор её жеста и
 * смещение под пальцем. Порядка карточек в этом классе нет — единственный источник
 * порядка и во время жеста, и после него — `PuzzleUiState.Playing.board.cards`, то есть
 * ViewModel. Поэтому потерять или задвоить карточку жест не может по построению.
 *
 * Состояние намеренно **не сохраняемое**: после поворота, пересоздания Activity или
 * смерти процесса пальца на экране нет, и восстановленная «поднятая» карточка означала бы
 * жест без жеста. Незакрытую сессию ViewModel закрывает сама — следующим [DragGestureId]
 * (I6-D9).
 */
@Stable
class ReorderDragState(private val listState: LazyListState) {

    var draggedCardId: String? by mutableStateOf(null)
        private set

    var gesture: DragGestureId? by mutableStateOf(null)
        private set

    /** Смещение карточки относительно её текущего слота, px. Только визуал. */
    var offsetY: Float by mutableFloatStateOf(0f)
        private set

    /**
     * Верх слота поднятой карточки в прошлом наблюдении, px.
     *
     * Нужен ровно для одного — компенсации сдвига слота (см. [syncSlotTop]).
     */
    private var lastSlotTop: Int? = null

    val isDragging: Boolean get() = draggedCardId != null

    /**
     * Начало жеста.
     *
     * @return `false`, если сессия уже открыта: второй указатель (мультитач) не открывает
     * вторую сессию и не отправляет ни одного события (I6-D10).
     */
    fun start(cardId: String, gesture: DragGestureId): Boolean {
        if (draggedCardId != null) return false
        draggedCardId = cardId
        this.gesture = gesture
        offsetY = 0f
        // Верх слота запоминается сразу, а не при первом изменении раскладки: первая же
        // подтверждённая перестановка сдвигает слот, и без исходного наблюдения
        // компенсировать этот сдвиг было бы не от чего — карточка прыгнула бы под пальцем
        // ровно на один шаг списка.
        lastSlotTop = itemOf(cardId)?.offset
        return true
    }

    /** Только вертикальная составляющая: горизонтальная игнорируется (I6-D10). */
    fun drag(deltaY: Float) {
        if (draggedCardId == null) return
        offsetY += deltaY
    }

    /** Отпускание, отмена и потеря указателя — одно и то же (I6-D8). */
    fun finish() {
        draggedCardId = null
        gesture = null
        offsetY = 0f
        lastSlotTop = null
    }

    /**
     * Компенсация сдвига слота: `offsetY -= (новый slotTop − прежний slotTop)` (§5.3).
     *
     * Одним выражением закрываются оба источника сдвига, и оба — с одним смыслом
     * «визуальный центр карточки не двигается сам по себе»:
     * - подтверждённая перестановка переставила слот под карточкой;
     * - авто-прокрутка сдвинула всё содержимое на `d` — тогда `slotTop` уменьшился на `d`,
     *   и `offsetY` увеличивается ровно на `d`, удерживая карточку под пальцем.
     *
     * Отдельного «добавить d после scrollBy» поэтому нет: два компенсатора одного и того
     * же сдвига сложились бы вдвое.
     */
    fun syncSlotTop() {
        val slotTop = draggedItem()?.offset
        if (slotTop == null) {
            // Карточка ушла из видимой области: её центр неизвестен, компенсировать
            // нечего. Прежнее наблюдение забывается, чтобы при возвращении карточка не
            // прыгнула на накопленную разницу.
            lastSlotTop = null
            return
        }
        lastSlotTop?.let { previous -> offsetY -= (slotTop - previous).toFloat() }
        lastSlotTop = slotTop
    }

    /**
     * Цель перестановки по единственному порогу «центр пересёк центр соседа» (§5.3).
     *
     * @param cardIds подтверждённый порядок из `PuzzleUiState` — единственный источник
     * индексов; собственного порядка это состояние не ведёт.
     * @return индекс цели или `null`, если поднятой карточки нет, её слот не виден или
     * цель совпадает с текущим индексом.
     */
    fun targetIndex(cardIds: List<String>): Int? {
        val cardId = draggedCardId ?: return null
        val index = cardIds.indexOf(cardId)
        if (index < 0) return null
        val dragged = draggedItem() ?: return null

        val draggedCenter = dragged.offset + offsetY + dragged.size / CENTER_DIVISOR
        val target = DragTargetResolver.targetIndex(
            i = index,
            lastIndex = cardIds.lastIndex,
            draggedCenter = draggedCenter,
            centerOf = { position ->
                itemOf(cardIds.getOrNull(position))
                    ?.let { item -> item.offset + item.size / CENTER_DIVISOR }
            },
        )
        return target.takeIf { it != index }
    }

    /**
     * Скорость авто-прокрутки для текущего кадра, px/с (§5.4).
     *
     * Геометрия — видимая область списка с учётом `contentPadding`: краевая зона
     * отсчитывается от того края, у которого пользователь реально видит содержимое.
     */
    fun autoScrollVelocity(edgeZonePx: Float, maxVelocityPx: Float): Float {
        val dragged = draggedItem() ?: return 0f
        val layout = listState.layoutInfo

        return DragAutoScroll.velocity(
            itemTop = dragged.offset + offsetY,
            itemBottom = dragged.offset + offsetY + dragged.size,
            viewportStart = (layout.viewportStartOffset + layout.beforeContentPadding).toFloat(),
            viewportEnd = (layout.viewportEndOffset - layout.afterContentPadding).toFloat(),
            edgeZonePx = edgeZonePx,
            maxVelocityPx = maxVelocityPx,
            canScrollBackward = listState.canScrollBackward,
            canScrollForward = listState.canScrollForward,
        )
    }

    /**
     * Прокрутка списка на [delta] px в рамках кадра жеста.
     *
     * Сама по себе карточку не сдвигает: положение под пальцем восстанавливает следующий
     * [syncSlotTop] — единственный компенсатор любого сдвига слота.
     */
    suspend fun scrollBy(delta: Float) {
        listState.scrollBy(delta)
    }

    private fun draggedItem(): LazyListItemInfo? = itemOf(draggedCardId)

    /**
     * Элемент списка по `cardId`.
     *
     * Ключ — всегда `cardId`, поэтому `prompt` (и будущая подсказка 6C) в расчёт индексов
     * карточек не попадают: их ключей нет среди идентификаторов карточек.
     */
    private fun itemOf(cardId: String?): LazyListItemInfo? {
        if (cardId == null) return null
        return listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == cardId }
    }

    private companion object {
        /** Центр элемента — половина его высоты от верха слота; делитель дробный, чтобы
         * нечётная высота не теряла половину пикселя при сравнении центров. */
        const val CENTER_DIVISOR = 2f
    }
}
