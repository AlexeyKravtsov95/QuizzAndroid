package ru.poporyadku.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ru.poporyadku.R
import ru.poporyadku.ui.platform.rememberExternalApps
import ru.poporyadku.ui.theme.Sizing
import ru.poporyadku.ui.theme.Spacing

/** Где стоит действие — от этого зависит только типографика (COMPONENTS.md). */
enum class ReportInaccuracyStyle {
    /** `PuzzleResult`, рядом с `SourcesBlock`: второстепенное действие, `labelSmall`. */
    Inline,

    /** Строка группы «Обратная связь» в настройках: рядовая строка, `bodyLarge`. */
    SettingsRow,
}

/**
 * «Сообщить о неточности» (COMPONENTS.md, «ReportInaccuracyAction»;
 * ITERATION_5_DESIGN.md, §3.12, I5-D18).
 *
 * `present` / `absent`: без почтового клиента действия **нет в дереве** — не disabled и не
 * приглушённое. Доступность спрашивает route-контейнер ([rememberReportAvailability]) и
 * передаёт флагом; компонент сам ни систему, ни ViewModel не опрашивает. Нажатие только
 * отправляет событие экрана: письмо собирает ViewModel, запускает — коллектор эффектов
 * route-контейнера через `ExternalApps`.
 *
 * TalkBack: видимый текст без слова «кнопка» — роль `Button` добавляет его сама. Вся
 * строка — одна цель не ниже `size.touchTarget.min`.
 */
@Composable
fun ReportInaccuracyAction(
    isAvailable: Boolean,
    style: ReportInaccuracyStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!isAvailable) return

    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Sizing.touchTargetMin)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = Spacing.scale200),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = stringResource(R.string.report_inaccuracy),
            style = when (style) {
                ReportInaccuracyStyle.Inline -> MaterialTheme.typography.labelSmall
                ReportInaccuracyStyle.SettingsRow -> MaterialTheme.typography.bodyLarge
            },
            color = when (style) {
                ReportInaccuracyStyle.Inline -> colors.onSurfaceVariant
                // В настройках — рядовая строка списка, тем же цветом, что соседние.
                ReportInaccuracyStyle.SettingsRow -> colors.onSurface
            },
        )
    }
}

/**
 * Есть ли почтовый клиент — для route-контейнера (ITERATION_5_DESIGN.md, §4.5).
 *
 * Спрашивается при первой композиции и заново на каждом `ON_START`: клиент могли
 * установить или удалить, пока приложение было в фоне. Значение не хранится ни в
 * состоянии экрана, ни во ViewModel: ViewModel о `PackageManager` не знает.
 */
@Composable
fun rememberReportAvailability(): Boolean {
    val externalApps = rememberExternalApps()
    val lifecycleOwner = LocalLifecycleOwner.current
    var available by remember(externalApps) { mutableStateOf(externalApps.canComposeEmail()) }

    DisposableEffect(lifecycleOwner, externalApps) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) available = externalApps.canComposeEmail()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return available
}
