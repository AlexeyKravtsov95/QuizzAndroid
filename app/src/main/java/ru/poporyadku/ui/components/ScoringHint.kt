package ru.poporyadku.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ru.poporyadku.R

/**
 * Однократное объяснение парного подсчёта (COMPONENTS.md, «ScoringHint»).
 *
 * Показывается только на **первом в жизни** экране результата — условие показа держит
 * вызывающий (`showScoringHint`), а не компонент. После первого показа компонент не
 * скрывается, а отсутствует в дереве.
 *
 * Без контейнера, без иконки, без цветной подложки: просто строка `bodyMedium`
 * `onSurfaceVariant` сразу под `ScoreBadge`. Терминология — «пара в правильном порядке»,
 * слово «инверсия» пользователю не показывается.
 */
@Composable
fun ScoringHint(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.scoring_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
