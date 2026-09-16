package ru.poporyadku.ui.puzzle

import java.util.concurrent.atomic.AtomicLong

/**
 * Идентификатор одного жеста перетаскивания (ITERATION_6_DESIGN.md, §4.3, I6-D5).
 *
 * Уникален в пределах процесса, в том числе между экземплярами UI: после пересоздания
 * Activity новый экран не может повторить идентификатор прежнего жеста, поэтому
 * запоздалое событие старого жеста не совпадёт с новой сессией ViewModel.
 *
 * Одного `cardId` для этой защиты недостаточно: пользователь законно тянет ту же карточку
 * второй раз.
 */
@JvmInline
value class DragGestureId(val value: Long)

/**
 * Процессный монотонный генератор [DragGestureId] (I6-D5).
 *
 * Не Compose-состояние и не поле `ReorderDragState`: счётчик обязан пережить
 * пересоздание Activity, иначе второй экземпляр UI начал бы нумерацию заново и его первый
 * жест совпал бы по идентификатору с незавершённым жестом предыдущего.
 *
 * Переполнение `Long` в реальном времени приложения недостижимо и отдельно не
 * обрабатывается: при миллионе жестов в секунду счётчик исчерпается за 292 тысячи лет.
 */
object DragGestureIds {

    private val counter = AtomicLong(0)

    fun next(): DragGestureId = DragGestureId(counter.incrementAndGet())
}
