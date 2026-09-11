package ru.poporyadku.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import ru.poporyadku.ui.theme.Spacing

/** Одна пара «метка → значение» блока статистики. */
data class StatisticItem(val label: String, val value: String)

/**
 * Сводка статистики (COMPONENTS.md, state sheet «Statistics block»).
 *
 * Заголовок `titleSmall` + построчные пары метка/значение — та же визуальная логика,
 * что у `DailyIssuePanel`, но **без spine**: spine закреплён за «сегодняшним выпуском»
 * Home и на сводку не распространяется.
 *
 * Компонент принимает уже готовые строки и ничего не знает ни про `HomeState`, ни про
 * доменные типы: он одинаково пригоден для `ContentExhausted`, `AwaitingFirstDay`,
 * `Error` и `Archive`.
 *
 * TalkBack: заголовок — `heading()` (заголовок секции, UI_REVIEW_CHECKLIST.md, «TalkBack»),
 * каждая пара метка/значение — один узел, а не два фокус-стопа
 * (ITERATION_5_DESIGN.md, §3.6).
 */
@Composable
fun StatisticsBlock(
    title: String,
    items: List<StatisticItem>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.scale400),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        items.forEach { item ->
            StreakRow(
                label = item.label,
                value = item.value,
                modifier = Modifier
                    .padding(top = Spacing.statRowInner)
                    .semantics(mergeDescendants = true) { },
            )
        }
    }
}
