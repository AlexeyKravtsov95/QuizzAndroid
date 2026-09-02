package ru.poporyadku.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import ru.poporyadku.ui.theme.Sizing
import ru.poporyadku.ui.theme.Spacing

/**
 * Плейсхолдеры загрузки (COMPONENTS.md, state sheet «Loading skeleton»).
 *
 * Правило одно: каждый skeleton повторяет **силуэт целевого компонента**, а не общую
 * прямоугольную заглушку. Заливка — `surfaceContainer`, форма — `shape.medium`.
 *
 * Анимации пульсации нет и не появляется: `UX_FLOW.md` её не описывает ни разу, а без
 * явного разрешения на анимацию `Loading` показывается статичным плейсхолдером. По этой
 * же причине компонент не обращается к motion-токенам — анимировать здесь нечего.
 */
@Composable
fun SkeletonLine(
    modifier: Modifier = Modifier,
    widthFraction: Float = FULL_WIDTH,
    height: Dp = Spacing.scale400,
) {
    Box(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium),
    )
}

/**
 * Skeleton `DailyIssuePanel` на `Home.Loading`: тот же силуэт со `spine` слева и
 * теми же hairline-разделителями, но без текста внутри.
 *
 * Spine тянется на всю высоту блока, который он помечает, — как и в самом
 * `DailyIssuePanel`, а не на произвольно заданную высоту.
 */
@Composable
fun DailyIssuePanelSkeleton(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clearAndSetSemantics { },
    ) {
        Box(
            modifier = Modifier
                .width(Spacing.spineWidth)
                .fillMaxHeight()
                .background(colors.primary),
        )
        Column(
            modifier = Modifier
                .padding(start = Spacing.spineContentIndent)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.scale400),
        ) {
            SkeletonLine(widthFraction = LABEL_FRACTION)
            SkeletonLine(widthFraction = TITLE_FRACTION, height = Spacing.scale700)
            HorizontalDivider(thickness = Sizing.dividerThickness, color = colors.outlineVariant)
            SkeletonLine(widthFraction = LABEL_FRACTION)
            SkeletonLine(widthFraction = VALUE_FRACTION)
            HorizontalDivider(thickness = Sizing.dividerThickness, color = colors.outlineVariant)
        }
    }
}

private const val FULL_WIDTH = 1f
private const val LABEL_FRACTION = 0.35f
private const val TITLE_FRACTION = 0.60f
private const val VALUE_FRACTION = 0.45f
