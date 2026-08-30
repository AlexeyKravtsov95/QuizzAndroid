package ru.poporyadku.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * DESIGN_TOKENS.md §6.6 — примитивные радиусы шкалы shapes. Единственный источник значений:
 * [PoPoRyadkuShapes] и [OrderableCardCutCorner] ссылаются на эти константы, не дублируют
 * литералы. `none` не имеет поля в `Material3.Shapes` (там нет роли `none`) и не используется
 * ни одним компонентом продукта, но зафиксирован здесь для полноты шкалы токенов.
 */
object ShapeRadius {
    /** shape.none — не используется ни одним компонентом продукта. */
    val none = 0.dp

    /** shape.extraSmall */
    val extraSmall = 4.dp

    /** shape.small */
    val small = 8.dp

    /** shape.medium */
    val medium = 12.dp

    /** shape.large */
    val large = 16.dp

    /** shape.extraLarge — резерв. */
    val extraLarge = 28.dp
}

val PoPoRyadkuShapes = Shapes(
    extraSmall = RoundedCornerShape(ShapeRadius.extraSmall),
    small = RoundedCornerShape(ShapeRadius.small),
    medium = RoundedCornerShape(ShapeRadius.medium),
    large = RoundedCornerShape(ShapeRadius.large),
    extraLarge = RoundedCornerShape(ShapeRadius.extraLarge),
)

/**
 * DESIGN_TOKENS.md §6.6 «Custom shape: OrderableCardCutCorner» — единственная нестандартная
 * форма в системе. Три обычных угла скруглены [cornerRadius] (= shape.medium), верхний правый
 * заменён прямым диагональным срезом [cutSize] (= size.cutCorner). Оба параметра — константы,
 * не масштабируются шириной/высотой карточки (DESIGN_TOKENS.md §6.6).
 *
 * Применяется ровно к одному компоненту продукта — OrderableCard (и его Loading-skeleton) —
 * см. DESIGN_PRINCIPLES.md §6 и COMPONENTS.md, «Ошибки, которые нельзя допускать».
 */
class OrderableCardCutCornerShape(
    private val cornerRadius: Dp,
    private val cutSize: Dp,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val radiusPx = with(density) { cornerRadius.toPx() }
        val cutPx = with(density) { cutSize.toPx() }

        val roundedRect = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(Offset.Zero, size),
                    cornerRadius = CornerRadius(radiusPx, radiusPx),
                ),
            )
        }
        val topRightCutAway = Path().apply {
            moveTo(size.width - cutPx, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, cutPx)
            close()
        }
        val result = Path()
        result.op(roundedRect, topRightCutAway, PathOperation.Difference)
        return Outline.Generic(result)
    }
}

/** Компонентный токен: OrderableCardCutCorner с размерами, буквально взятыми из токенов. */
val OrderableCardCutCorner: Shape = OrderableCardCutCornerShape(
    cornerRadius = ShapeRadius.medium, // shape.medium — тот же источник, что и PoPoRyadkuShapes.medium
    cutSize = Sizing.cutCorner,        // size.cutCorner
)
