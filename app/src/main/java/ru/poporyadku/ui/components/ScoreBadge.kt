package ru.poporyadku.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Счёт («N из 6» на `PuzzleResult`, «N из 18» на `DayRecap`) — COMPONENTS.md, «ScoreBadge».
 *
 * Роль `headlineSmall`, не `displaySmall`: счёт весомый, но по иерархии не крупнее
 * объяснения (DESIGN_PRINCIPLES.md §3). Собственного контейнера и заливки у компонента
 * нет — это текст на `surface`. Отдельного «праздничного» варианта для максимального
 * счёта не существует, и значение не анимируется «набегающим» счётчиком.
 */
@Composable
fun ScoreBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}
