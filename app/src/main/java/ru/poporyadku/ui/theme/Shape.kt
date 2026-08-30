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

// DESIGN_TOKENS.md §6.6. shape.none (0 dp) не используется ни одним компонентом продукта
// и в Material3.Shapes роли не имеет — не переносится.
val PoPoRyadkuShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),  // shape.extraSmall
    small = RoundedCornerShape(8.dp),       // shape.small
    medium = RoundedCornerShape(12.dp),     // shape.medium
    large = RoundedCornerShape(16.dp),      // shape.large
    extraLarge = RoundedCornerShape(28.dp), // shape.extraLarge — резерв
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

/** shape.medium (12 dp) — примитивное значение, определённое здесь же, в блоке PoPoRyadkuShapes выше. */
private val ShapeMediumRadius = 12.dp

/** Компонентный токен: OrderableCardCutCorner с размерами, буквально взятыми из токенов. */
val OrderableCardCutCorner: Shape = OrderableCardCutCornerShape(
    cornerRadius = ShapeMediumRadius, // shape.medium
    cutSize = Sizing.cutCorner,       // size.cutCorner
)
