package ru.poporyadku.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ru.poporyadku.ui.theme.Spacing

/**
 * Блок ошибки (COMPONENTS.md, state sheet «Error»).
 *
 * Общее для всех вариантов: текст нейтральный `bodyLarge`/`onSurface`, **без**
 * `error`-цвета — роли `ColorScheme.error`/`errorContainer` в MVP не задействуются,
 * чтобы не создавать тон тревоги в спокойном продукте.
 *
 * Действия приходят слотом, а не выбираются внутри компонента:
 * - `retryable` (Home, Archive) — слот содержит `PrimaryButton` «Повторить»;
 * - `recapMissing` (DayRecap) — слот пуст: повторять нечего, данных за прошедший день
 *   больше нет ни при каком количестве попыток, выход только системной «назад».
 */
@Composable
fun ErrorBlock(
    message: String,
    modifier: Modifier = Modifier,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.section),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (actions != null) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.scale300),
                content = actions,
            )
        }
    }
}
