package ru.poporyadku.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import ru.poporyadku.R
import ru.poporyadku.ui.theme.IconSizing
import ru.poporyadku.ui.theme.ProjectTextStyles
import ru.poporyadku.ui.theme.Sizing
import ru.poporyadku.ui.theme.Spacing

/**
 * Верхняя часть Home (COMPONENTS.md, «HomeHeader») — функциональный аналог `AppTopBar`
 * там, где `AppTopBar` не используется. Присутствует **во всех** состояниях Home,
 * включая `Loading` и `Error`.
 *
 * Единственный компонент Home, к которому применяется верхний системный inset
 * (UI_REVIEW_CHECKLIST.md, «Edge-to-edge») — **не** `DailyIssuePanel`.
 *
 * Видимость иконки «Архив» компонент не вычисляет: он получает уже готовый
 * [isArchiveVisible], посчитанный по `completedDayCount` и факту чтения прогресса
 * в [toHomeState]. Иконка «Настройки» видна всегда.
 *
 * @param intro одноразовая поясняющая строка `FirstRun`; принадлежит шапке, а не
 * `DailyIssuePanel`.
 */
@Composable
fun HomeHeader(
    dateText: String,
    isArchiveVisible: Boolean,
    onArchiveClick: () -> Unit,
    onSettingsClick: () -> Unit,
    horizontalMargin: Dp,
    modifier: Modifier = Modifier,
    intro: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(horizontal = horizontalMargin),
    ) {
        MastheadRow(
            isArchiveVisible = isArchiveVisible,
            onArchiveClick = onArchiveClick,
            onSettingsClick = onSettingsClick,
        )

        HorizontalDivider(
            modifier = Modifier.padding(top = Spacing.scale300),
            thickness = Sizing.dividerThickness,
            color = colors.outlineVariant,
        )

        Text(
            text = dateText,
            style = ProjectTextStyles.metadata,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.scale300),
        )

        if (intro != null) {
            Text(
                text = intro,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.scale300),
            )
        }
    }
}

/**
 * Мастхед и иконки на одной визуальной строке; при недостатке ширины иконки
 * переносятся на отдельную строку под мастхедом, выровненную по правому краю.
 *
 * Порядок разрешения конфликта фиксирован: мастхед остаётся первой строкой на всю
 * ширину, `size.headerIcon` (48 × 48 dp) не уменьшается ни при каком переносе —
 * сжимается компоновка, а не сенсорные цели.
 */
@Composable
private fun MastheadRow(
    isArchiveVisible: Boolean,
    onArchiveClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val iconGap = with(LocalDensity.current) { Spacing.scale200.roundToPx() }

    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            Text(
                text = stringResource(R.string.home_masthead).uppercase(),
                // Роль Masthead целиком, включая трекинг: literal letterSpacing
                // в компоненте запрещён (UI_REVIEW_CHECKLIST.md, «Типографика»).
                style = ProjectTextStyles.masthead,
                color = colors.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            HeaderIcons(
                isArchiveVisible = isArchiveVisible,
                onArchiveClick = onArchiveClick,
                onSettingsClick = onSettingsClick,
            )
        },
    ) { measurables, constraints ->
        val available = constraints.maxWidth
        val icons = measurables[1].measure(constraints.copy(minWidth = 0))
        val masthead = measurables[0].measure(constraints.copy(minWidth = 0))

        val inline = masthead.width + iconGap + icons.width <= available
        val rowHeight = maxOf(masthead.height, icons.height)
        val height = if (inline) rowHeight else masthead.height + iconGap + icons.height

        layout(available, height) {
            if (inline) {
                masthead.placeRelative(0, (rowHeight - masthead.height) / 2)
                icons.placeRelative(available - icons.width, (rowHeight - icons.height) / 2)
            } else {
                masthead.placeRelative(0, 0)
                icons.placeRelative(available - icons.width, masthead.height + iconGap)
            }
        }
    }
}

@Composable
private fun HeaderIcons(
    isArchiveVisible: Boolean,
    onArchiveClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val tint = MaterialTheme.colorScheme.onSurface
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.scale200),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isArchiveVisible) {
            HeaderIconButton(
                contentDescription = stringResource(R.string.cd_archive),
                onClick = onArchiveClick,
            ) { drawArchiveGlyph(tint) }
        }
        HeaderIconButton(
            contentDescription = stringResource(R.string.cd_settings),
            onClick = onSettingsClick,
        ) { drawSettingsGlyph(tint) }
    }
}

/**
 * Иконка шапки: визуальный глиф `icon.size.default` (24 dp) внутри прозрачной
 * сенсорной цели `size.headerIcon` (48 × 48 dp). Лишняя область — прозрачный padding,
 * а не декоративная заливка.
 */
@Composable
private fun HeaderIconButton(
    contentDescription: String,
    onClick: () -> Unit,
    glyph: DrawScope.() -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(Sizing.headerIcon)
            // Роль «кнопка» добавляет TalkBack сама — описание её не дублирует.
            .semantics { this.contentDescription = contentDescription },
    ) {
        Box(modifier = Modifier.size(IconSizing.default)) {
            Canvas(modifier = Modifier.size(IconSizing.default)) { glyph() }
        }
    }
}

/**
 * Глифы шапки перенесены из `tools/design-b2/svgkit.py` — единственного места, где
 * их геометрия зафиксирована и откуда отрисованы утверждённые артборды B2. Обе
 * построены из долей `icon.size.default` и линии `icon.strokeWidth.default`, поэтому
 * ни одного `.dp`-литерала здесь нет.
 *
 * `COMPONENTS.md` не называет для них конкретный Material Symbol — в отличие от семи
 * пиктограмм категорий; источником истины остаются артборды.
 */
private fun DrawScope.drawArchiveGlyph(tint: Color) {
    val s = size.minDimension
    val stroke = Stroke(width = IconSizing.strokeWidthDefault.toPx())
    val cx = size.width / 2f
    val cy = size.height / 2f

    drawRect(
        color = tint,
        topLeft = Offset(cx - s * ARCHIVE_LID_HALF_WIDTH, cy - s * ARCHIVE_LID_TOP),
        size = Size(s * ARCHIVE_LID_HALF_WIDTH * 2f, s * ARCHIVE_LID_HEIGHT),
        style = stroke,
    )
    drawRect(
        color = tint,
        topLeft = Offset(cx - s * ARCHIVE_BODY_HALF_WIDTH, cy - s * ARCHIVE_BODY_TOP),
        size = Size(s * ARCHIVE_BODY_HALF_WIDTH * 2f, s * ARCHIVE_BODY_HEIGHT),
        style = stroke,
    )
    drawLine(
        color = tint,
        start = Offset(cx - s * ARCHIVE_SLOT_HALF_WIDTH, cy + s * ARCHIVE_SLOT_OFFSET),
        end = Offset(cx + s * ARCHIVE_SLOT_HALF_WIDTH, cy + s * ARCHIVE_SLOT_OFFSET),
        strokeWidth = IconSizing.strokeWidthDefault.toPx(),
    )
}

private fun DrawScope.drawSettingsGlyph(tint: Color) {
    val s = size.minDimension
    val strokeWidth = IconSizing.strokeWidthDefault.toPx()
    val cx = size.width / 2f
    val cy = size.height / 2f

    SETTINGS_SLIDERS.forEach { (row, knob) ->
        val y = cy + s * row
        drawLine(
            color = tint,
            start = Offset(cx - s * SETTINGS_SLIDER_HALF_WIDTH, y),
            end = Offset(cx + s * SETTINGS_SLIDER_HALF_WIDTH, y),
            strokeWidth = strokeWidth,
        )
        drawCircle(
            color = tint,
            radius = s * SETTINGS_KNOB_RADIUS,
            center = Offset(cx + s * knob, y),
            style = Stroke(width = strokeWidth),
        )
    }
}

// Доли icon.size.default — геометрия артбордов B2 (`svgkit.archive_icon`).
private const val ARCHIVE_LID_HALF_WIDTH = 0.40f
private const val ARCHIVE_LID_TOP = 0.35f
private const val ARCHIVE_LID_HEIGHT = 0.18f
private const val ARCHIVE_BODY_HALF_WIDTH = 0.36f
private const val ARCHIVE_BODY_TOP = 0.17f
private const val ARCHIVE_BODY_HEIGHT = 0.52f
private const val ARCHIVE_SLOT_HALF_WIDTH = 0.12f
private const val ARCHIVE_SLOT_OFFSET = 0.06f

// `svgkit.settings_icon`: три ползунка, без мотива вращающейся шестерёнки.
private val SETTINGS_SLIDERS = listOf(-0.28f to 0.18f, 0.02f to -0.14f, 0.32f to 0.26f)
private const val SETTINGS_SLIDER_HALF_WIDTH = 0.38f
private const val SETTINGS_KNOB_RADIUS = 0.11f
