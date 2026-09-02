package ru.poporyadku.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import ru.poporyadku.core.model.SLOTS_PER_DAY
import ru.poporyadku.ui.theme.Sizing
import ru.poporyadku.ui.theme.Spacing

/**
 * Режим индикатора (COMPONENTS.md, «ThreeStepProgress»).
 *
 * Два независимых поля, а не одно: один и тот же `completedCount` рендерится по-разному
 * в разных местах использования. Состояние «текущая точка» существует только в
 * [ActiveDay] — в закрытом дне текущего задания больше нет.
 */
enum class ThreeStepProgressMode {
    /** `Home.InProgress`; `completedCount` — 0..2. */
    ActiveDay,

    /** `Archive row`; `completedCount` — 0..3, состояния «текущая» не бывает. */
    ClosedDay,
}

/**
 * Индикатор «задание N из 3» (COMPONENTS.md, «ThreeStepProgress»).
 *
 * Не самостоятельная точка фокуса: состояние всегда продублировано текстом рядом,
 * и озвучивается именно текст. Поэтому точки декоративны — `clearAndSetSemantics {}`,
 * иначе TalkBack прочитал бы одно и то же дважды.
 */
@Composable
fun ThreeStepProgress(
    mode: ThreeStepProgressMode,
    completedCount: Int,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier.clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(Spacing.scale100),
    ) {
        repeat(SLOTS_PER_DAY) { index ->
            val isDone = index < completedCount
            val isCurrent = index == completedCount && mode == ThreeStepProgressMode.ActiveDay
            Canvas(modifier = Modifier.size(Sizing.progressDotDiameter)) {
                val radius = size.minDimension / 2f
                when {
                    isDone -> drawCircle(color = colors.primary, radius = radius)
                    isCurrent -> {
                        val strokeWidth = Sizing.dividerThickness.toPx()
                        drawCircle(color = colors.primaryContainer, radius = radius - strokeWidth / 2f)
                        drawCircle(
                            color = colors.primary,
                            radius = radius - strokeWidth / 2f,
                            style = Stroke(width = strokeWidth),
                        )
                    }

                    else -> drawCircle(color = colors.outlineVariant, radius = radius)
                }
            }
        }
    }
}
