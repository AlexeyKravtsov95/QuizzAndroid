package ru.poporyadku.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import ru.poporyadku.ui.theme.IconSizing
import ru.poporyadku.ui.theme.Opacity
import ru.poporyadku.ui.theme.Sizing

/** Направление перемещения — единственный параметр формы кнопки. */
enum class MoveDirection { UP, DOWN }

/**
 * Кнопка перемещения карточки (COMPONENTS.md, «MoveButton»).
 *
 * Не «доступностная» альтернатива перетаскиванию, а обязательный способ упорядочивания:
 * кнопки постоянно видны и полезны зрячим пользователям (UX_FLOW.md §10).
 *
 * `disabled` различим **формой**, а не только цветом: enabled — заливка
 * `primaryContainer` плюс акцентная обводка `primary`; disabled — прозрачный фон и
 * блёклая обводка `outline`. Кнопка никогда не скрывается вместо перевода в disabled:
 * иначе список прыгал бы по ширине.
 *
 * Размер `size.moveButton` = 48 dp не уменьшается ни при 320 dp, ни при длинном
 * названии карточки, ни при масштабе шрифта 200% — он задан в dp, а не в sp.
 */
@Composable
fun MoveButton(
    direction: MoveDirection,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    val iconColor = if (enabled) {
        colors.onPrimaryContainer
    } else {
        colors.onSurface.copy(alpha = Opacity.disabledContent)
    }
    val border = if (enabled) {
        BorderStroke(
            Sizing.dividerThickness,
            colors.primary.copy(alpha = Opacity.moveButtonEnabledBorder),
        )
    } else {
        BorderStroke(
            Sizing.dividerThickness,
            colors.outline.copy(alpha = Opacity.disabledContent),
        )
    }

    Box(
        // Сенсорная цель 48 × 48 dp и она же — видимая форма: `shape.small`, а не круг.
        // Material-`IconButton` здесь не подходит: он клипует содержимое собственной
        // круглой формой, и квадратная обводка COMPONENTS.md обрезалась бы в дугу.
        modifier = modifier
            .size(Sizing.moveButton)
            .clip(shape)
            .background(
                // Заливка есть только у enabled — это и есть отличие по форме.
                color = if (enabled) colors.primaryContainer else Color.Transparent,
            )
            .border(border, shape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = rememberChevronIcon(direction, iconColor),
            contentDescription = contentDescription,
            tint = iconColor,
            modifier = Modifier.size(IconSizing.default),
        )
    }
}

/**
 * Шеврон — геометрия, а не глиф иконочного шрифта: ни один символ не адресуется кодом,
 * поэтому иконочная библиотека не подключается (тот же приём, что у `CategoryLabel`).
 *
 * Одна геометрия на всю систему: её же разворачивает `SourcesBlock` — второй иконки
 * «шеврон» в инвентаре не появляется.
 *
 * Толщина линии — `icon.strokeWidth.default` в единицах вьюпорта 24 × 24, то есть ровно
 * 2 dp при `icon.size.default`.
 */
@Composable
internal fun rememberChevronIcon(direction: MoveDirection, tint: Color): ImageVector =
    remember(direction, tint) {
        val path = when (direction) {
            MoveDirection.UP -> "M6 15 L12 9 L18 15"
            MoveDirection.DOWN -> "M6 9 L12 15 L18 9"
        }
        ImageVector.Builder(
            name = "chevron_$direction",
            defaultWidth = IconSizing.default,
            defaultHeight = IconSizing.default,
            viewportWidth = ICON_VIEWPORT,
            viewportHeight = ICON_VIEWPORT,
        )
            .addPath(
                pathData = PathParser().parsePathString(path).toNodes(),
                fill = null,
                stroke = SolidColor(tint),
                strokeLineWidth = STROKE_WIDTH,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()
    }

/** Коробка Material Symbols — 24 юнита, ровно `icon.size.default`. */
private const val ICON_VIEWPORT = 24f

/** `icon.strokeWidth.default` = 2 dp, выраженная в единицах вьюпорта 1:1. */
private const val STROKE_WIDTH = 2f
