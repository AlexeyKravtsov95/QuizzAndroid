package ru.poporyadku.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import ru.poporyadku.R
import ru.poporyadku.ui.theme.IconSizing
import ru.poporyadku.ui.theme.Sizing
import ru.poporyadku.ui.theme.Spacing

/**
 * Заголовок экрана (COMPONENTS.md, «AppTopBar»). На `Home` не используется — там
 * заголовок продукта не навигационный, его роль исполняет `HomeHeader`.
 *
 * Верхний системный inset применяется здесь: `AppTopBar` — самый верхний видимый
 * контейнер своих экранов (UI_REVIEW_CHECKLIST.md, «Edge-to-edge»).
 *
 * **Leading-иконка «Назад»** присутствует на `Puzzle`, `PuzzleResult`, `Archive` и
 * `Settings` и отсутствует на сегодняшнем итоге `DayRecap`: там граф сессии уже вычищен
 * из бэкстека, и кнопка вела бы туда же, куда «Готово». Отсутствие выражено отсутствием
 * [onBackClick], а не пустым слотом.
 *
 * **Правый слот** занимает только `CategoryLabel` и только на `Puzzle`. При недостатке
 * ширины метка **переносится на вторую строку** шапки и выравнивается по левому краю
 * заголовка — она не уменьшается ни кеглем, ни отступами, а кнопка «Назад» сохраняет
 * `size.backButton` при любом переносе. Порядок обхода TalkBack от переноса не зависит:
 * «Назад» → заголовок → категория.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    horizontalMargin: Dp = Spacing.marginDefault,
    onBackClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .defaultMinSize(minHeight = Sizing.buttonHeight)
            .padding(horizontal = horizontalMargin, vertical = Spacing.scale200),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.scale200),
    ) {
        if (onBackClick != null) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier.size(Sizing.backButton),
            ) {
                Icon(
                    imageVector = rememberBackIcon(MaterialTheme.colorScheme.onSurface),
                    contentDescription = stringResource(R.string.cd_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(IconSizing.default),
                )
            }
        }

        // FlowRow, а не Row: перенос метки на вторую строку — это компоновка, которая
        // обязана срабатывать по фактической ширине заголовка и метки при текущем
        // масштабе шрифта, а не по заранее выбранному брейкпоинту.
        FlowRow(
            modifier = Modifier.weight(WEIGHT_FILL),
            horizontalArrangement = Arrangement.spacedBy(Spacing.scale300),
            verticalArrangement = Arrangement.spacedBy(Spacing.scale200),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            trailing?.invoke()
        }
    }
}

/**
 * Стрелка «Назад» — геометрия, а не глиф иконочного шрифта (тот же приём, что у
 * `CategoryLabel` и `MoveButton`): дополнительная иконочная библиотека не подключается.
 */
@Composable
private fun rememberBackIcon(tint: Color): ImageVector = remember(tint) {
    ImageVector.Builder(
        name = "arrow_back",
        defaultWidth = IconSizing.default,
        defaultHeight = IconSizing.default,
        viewportWidth = ICON_VIEWPORT,
        viewportHeight = ICON_VIEWPORT,
    )
        .addPath(
            pathData = PathParser().parsePathString("M20 12 H4 M10 6 L4 12 L10 18").toNodes(),
            fill = null,
            stroke = SolidColor(tint),
            strokeLineWidth = STROKE_WIDTH,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        .build()
}

private const val ICON_VIEWPORT = 24f

/** `icon.strokeWidth.default` = 2 dp, выраженная в единицах вьюпорта 1:1. */
private const val STROKE_WIDTH = 2f
private const val WEIGHT_FILL = 1f
