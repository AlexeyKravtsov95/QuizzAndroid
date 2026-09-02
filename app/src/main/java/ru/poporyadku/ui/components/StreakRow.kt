package ru.poporyadku.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ru.poporyadku.ui.theme.ProjectTextStyles
import ru.poporyadku.ui.theme.Spacing

/**
 * Строка «метка / значение» (COMPONENTS.md, «StreakRow»).
 *
 * Метка — `CapsLabel` (sans, не mono), значение — `labelMedium`. Ни иконки «огня»,
 * ни трофея: геймификация в духе Duolingo прямо запрещена (DESIGN_PRINCIPLES.md §11).
 * Тот же построчный вид переиспользуется строками статистики `DailyIssuePanel`
 * и [StatisticsBlock].
 */
@Composable
fun StreakRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label.uppercase(),
            style = ProjectTextStyles.capsLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = Spacing.statRowInner),
        )
    }
}
