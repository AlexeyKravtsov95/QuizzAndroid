package ru.poporyadku.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import ru.poporyadku.ui.theme.IconSizing
import ru.poporyadku.ui.theme.Sizing

/**
 * Ручка перетаскивания карточки (COMPONENTS.md, «DragHandle»; ITERATION_6_DESIGN.md,
 * §5.1, I6-D10, I6-D19).
 *
 * Зона захвата — `size.dragHandle.touchTarget` (48 × 48 dp), визуальный индикатор —
 * сетка точек `icon.size.dragHandleGlyph` (16 × 24 dp, 2 × 3), цвет `onSurfaceVariant`.
 *
 * **Семантики не создаёт.** Ни роли, ни описания, ни действия: `pointerInput` семантики
 * не добавляет, а глиф рисуется `Canvas` без `contentDescription`. Перестановка для
 * TalkBack выполняется только через custom actions карточки — ручка не тратит на себя
 * фокус-стоп и не читается вовсе (`I6-C1`).
 *
 * **Тактильной отдачи здесь нет** — ни системного отклика напрямую, ни через
 * Compose-локаль отдачи: захват подтверждает ViewModel эффектом `Feedback(CardGrabbed)` по
 * настройкам пользователя. Второй путь отдачи мимо `FeedbackPolicy` дал бы двойную
 * вибрацию, поэтому весь пакет `ui/puzzle` и `ui/components` обязан оставаться пустым для
 * проверки `I6-K4`.
 *
 * @param interactive принимает ли ручка жест. `false` (состояние `Submitting.Answer`) —
 * вид тот же, ввод не принимается: ключ `pointerInput` меняется, и активный жест
 * отменяется вместе с пересозданием обработчика.
 * @param onDragStart начало жеста; `false` означает «сессия уже открыта» — второй
 * указатель событий не порождает, и последующие [onDrag]/[onDragFinished] этого
 * указателя тоже.
 * @param onDrag вертикальное смещение указателя; горизонтальное не передаётся.
 * @param onDragFinished отпускание, отмена и потеря указателя — одинаково (I6-D8).
 */
@Composable
fun DragHandle(
    interactive: Boolean,
    onDragStart: () -> Boolean,
    onDrag: (deltaY: Float) -> Unit,
    onDragFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .size(Sizing.dragHandleTouchTarget)
            // Ключ — `interactive`: смена состояния пересоздаёт обработчик, и жест,
            // начатый в `Playing`, не продолжается в `Submitting`.
            .pointerInput(interactive) {
                if (!interactive) return@pointerInput
                // Порог — системный `ViewConfiguration.touchSlop` внутри
                // `detectDragGestures`: платформенная константа ввода, а не токен
                // дизайна. После порога жест потребляет изменения указателя, поэтому
                // `scrollable` родителя их уже не видит и список не прокручивается.
                // Свайп по карточке вне ручки до этого обработчика не доходит и
                // прокручивает список как обычно.
                var accepted = false
                detectDragGestures(
                    onDragStart = { _: Offset -> accepted = onDragStart() },
                    onDrag = { _, dragAmount -> if (accepted) onDrag(dragAmount.y) },
                    // Обе ветви одинаковы буквально: отпускание и отмена завершают жест
                    // на последнем подтверждённом порядке, отката нет (I6-D8).
                    onDragEnd = {
                        if (accepted) {
                            accepted = false
                            onDragFinished()
                        }
                    },
                    onDragCancel = {
                        if (accepted) {
                            accepted = false
                            onDragFinished()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(IconSizing.dragHandleGlyph)) {
            val columnStep = size.width / DOT_COLUMNS
            val rowStep = size.height / DOT_ROWS
            val radius = DOT_RADIUS.toPx()
            repeat(DOT_COLUMNS) { column ->
                repeat(DOT_ROWS) { row ->
                    drawCircle(
                        color = color,
                        radius = radius,
                        center = Offset(
                            x = columnStep * (column + CELL_CENTER),
                            y = rowStep * (row + CELL_CENTER),
                        ),
                    )
                }
            }
        }
    }
}

/** Сетка точек 2 × 3 (COMPONENTS.md, «DragHandle»). */
private const val DOT_COLUMNS = 2
private const val DOT_ROWS = 3

/** Центр ячейки сетки — половина шага. */
private const val CELL_CENTER = 0.5f

/** Точка сетки — половина шага ячейки по ширине, чтобы шесть точек читались как сетка. */
private const val DOT_RADIUS_DIVISOR = 2

/**
 * Радиус точки: половина `icon.strokeWidth.small` — та же плотность линии, что у иконок
 * 16 dp, поэтому собственного размерного токена сетка точек не вводит.
 */
private val DOT_RADIUS = IconSizing.strokeWidthSmall / DOT_RADIUS_DIVISOR
