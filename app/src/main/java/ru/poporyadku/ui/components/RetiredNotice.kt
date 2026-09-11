package ru.poporyadku.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ru.poporyadku.R

/**
 * Пометка отозванной головоломки на `PuzzleResult` (COMPONENTS.md, state sheet
 * «RetiredNotice»; ITERATION_5_DESIGN.md, §3.8, I5-D11, I5-D30).
 *
 * Одна строка текста `bodyMedium`, `onSurface` — без контейнера, иконки и цветной
 * подложки, и без ролей `error`/`errorContainer`: отзыв — факт о головоломке, а не
 * тревога. Экран ставит пометку первым элементом контента, до «Правильный порядок»:
 * пользователь узнаёт о неточности раньше, чем прочитает порядок, а TalkBack читает её
 * сразу после заголовка экрана.
 */
@Composable
fun RetiredNotice(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.result_retired_notice),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}
