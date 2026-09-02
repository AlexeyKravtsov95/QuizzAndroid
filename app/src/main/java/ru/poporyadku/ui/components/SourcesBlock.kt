package ru.poporyadku.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import ru.poporyadku.R
import ru.poporyadku.core.model.Puzzle
import ru.poporyadku.ui.theme.IconSizing
import ru.poporyadku.ui.theme.Sizing
import ru.poporyadku.ui.theme.Spacing
import ru.poporyadku.ui.theme.rememberMotionTokens

/**
 * Раскрывающийся блок источников (COMPONENTS.md, «SourcesBlock»).
 *
 * Свёрнут по умолчанию. Отдельного маршрута или экрана-списка источников не существует —
 * это единственная утверждённая форма и на `PuzzleResult`, и в будущих настройках.
 *
 * Раскрытие/сворачивание — `toggleable`, поэтому TalkBack объявляет состояние
 * «свёрнуто»/«развёрнуто» сам, без выдуманного `contentDescription`.
 *
 * Шеврон поворачивается на `motion.rotation.chevronExpand` за `motion.duration.short`;
 * при системной настройке «убрать анимацию» длительность приходит уже сокращённой из
 * [rememberMotionTokens] — компонент сам систему не опрашивает.
 */
@Composable
fun SourcesBlock(
    sources: List<Puzzle.Source>,
    modifier: Modifier = Modifier,
) {
    // Источника без единой строки не бывает: пустой заголовок раздела не показывается.
    if (sources.isEmpty()) return

    val motion = rememberMotionTokens()
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) motion.chevronExpandDegrees else NO_ROTATION,
        animationSpec = tween(motion.durationShort, easing = motion.easingStandard),
        label = "sources_chevron",
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.scale200),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = Sizing.touchTargetMin)
                .toggleable(value = expanded, onValueChange = { expanded = it }),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.scale200),
        ) {
            Text(
                text = stringResource(R.string.sources_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(WEIGHT_FILL),
            )
            Icon(
                imageVector = rememberChevronIcon(
                    direction = MoveDirection.DOWN,
                    tint = MaterialTheme.colorScheme.onSurface,
                ),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .size(IconSizing.default)
                    .rotate(rotation),
            )
        }

        if (expanded) {
            sources.forEach { source -> SourceRow(source = source) }
        }
    }
}

private const val NO_ROTATION = 0f
private const val WEIGHT_FILL = 1f
