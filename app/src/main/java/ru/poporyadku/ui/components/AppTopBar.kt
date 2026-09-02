package ru.poporyadku.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import ru.poporyadku.ui.theme.Sizing
import ru.poporyadku.ui.theme.Spacing

/**
 * Заголовок экрана (COMPONENTS.md, «AppTopBar»). На `Home` не используется — там
 * заголовок продукта не навигационный, его роль исполняет `HomeHeader`.
 *
 * Верхний системный inset применяется здесь: `AppTopBar` — самый верхний видимый
 * контейнер своих экранов (UI_REVIEW_CHECKLIST.md, «Edge-to-edge»).
 *
 * **Leading-иконки «Назад» нет.** Единственный потребитель компонента в PR 3C —
 * сегодняшний итог `DayRecap`, и там её отсутствие — решение COMPONENTS.md: граф
 * сессии к этому моменту вычищен из бэкстека, и кнопка вела бы туда же, куда «Готово».
 * Архивный вариант с кнопкой «Назад» вводится вместе с `Archive` (итерация 5), а
 * `Puzzle`/`PuzzleResult` — в PR 3D; заранее пустых слотов компонент не заводит.
 *
 * Высота — `size.button.height` минимум; при переносе длинного заголовка растёт.
 */
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    horizontalMargin: Dp = Spacing.marginDefault,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .defaultMinSize(minHeight = Sizing.buttonHeight)
            .padding(horizontal = horizontalMargin, vertical = Spacing.scale200),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
    }
}
