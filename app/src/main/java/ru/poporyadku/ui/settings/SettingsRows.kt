package ru.poporyadku.ui.settings

import android.content.ClipData
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import ru.poporyadku.R
import ru.poporyadku.ui.components.MoveDirection
import ru.poporyadku.ui.components.SkeletonLine
import ru.poporyadku.ui.components.rememberChevronIcon
import ru.poporyadku.ui.theme.IconSizing
import ru.poporyadku.ui.theme.Sizing
import ru.poporyadku.ui.theme.Spacing

/*
 * Строки настроек (COMPONENTS.md, state sheet «Settings row»; ITERATION_5_DESIGN.md,
 * §3.9, §3.10). Общее для всех: без карточек вокруг строк, строки разделены hairline
 * `outlineVariant`, интерактивная строка — одна сенсорная цель не ниже
 * `size.touchTarget.min`, текст переносится и строка растёт по высоте (320 dp, 200 %).
 */

/** Заголовок группы — `titleSmall`, `heading()` для навигации TalkBack по разделам. */
@Composable
internal fun SettingsGroupHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.section, bottom = Spacing.scale200)
            .semantics { heading() },
    )
}

/**
 * Строка-переключатель: заголовок `bodyLarge` слева, M3 `Switch` справа. Вся строка —
 * ОДНА цель `toggleable(role = Switch)`; сам `Switch` без обработчика, поэтому второй
 * независимой цели и второго фокус-стопа нет. [checked] — только подтверждённое значение.
 */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Sizing.touchTargetMin)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = Spacing.scale200),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.scale400),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(WEIGHT_FILL),
        )
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * Строка группы темы: заголовок слева, M3 `RadioButton` справа. Вся строка — одна цель
 * `selectable(role = RadioButton)`; контейнер группы — `selectableGroup()` у вызывающего.
 */
@Composable
internal fun SettingsThemeRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Sizing.touchTargetMin)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = Spacing.scale200),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.scale400),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(WEIGHT_FILL),
        )
        RadioButton(selected = selected, onClick = null)
    }
}

/**
 * Навигационная строка («Источники»): одна цель с ролью `Button`, шеврон справа —
 * указатель перехода на подэкран (COMPONENTS.md, «Settings row»).
 */
@Composable
internal fun SettingsNavigationRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Sizing.touchTargetMin)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = Spacing.scale200),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.scale400),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(WEIGHT_FILL),
        )
        // Шеврон «вниз», повёрнутый к концу строки: та же геометрия, что у MoveButton.
        Icon(
            imageVector = rememberChevronIcon(direction = MoveDirection.DOWN, tint = tint),
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(IconSizing.default)
                .rotate(CHEVRON_TO_END_DEGREES),
        )
    }
}

/**
 * Информационная пара «О приложении»: подпись и значение — один узел TalkBack, а не два
 * фокус-стопа. [label] может отсутствовать (название приложения, правило подсчёта).
 */
@Composable
internal fun SettingsInfoRow(
    value: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    valueStyle: ValueStyle = ValueStyle.Primary,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { }
            .padding(vertical = Spacing.scale300),
        verticalArrangement = Arrangement.spacedBy(Spacing.scale100),
    ) {
        if (label != null) SettingsLabel(label)
        Text(
            text = value,
            style = when (valueStyle) {
                ValueStyle.Primary -> MaterialTheme.typography.bodyLarge
                ValueStyle.Secondary -> MaterialTheme.typography.bodyMedium
            },
            color = when (valueStyle) {
                ValueStyle.Primary -> MaterialTheme.colorScheme.onSurface
                ValueStyle.Secondary -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

internal enum class ValueStyle { Primary, Secondary }

/**
 * Адрес для писем (ITERATION_5_DESIGN.md, §3.10, §8.4). Копируется независимо от наличия
 * почтового клиента:
 * - зрячему — длинным нажатием через системное меню `SelectionContainer`;
 * - TalkBack — действием «Скопировать адрес» у строки: адрес уходит в буфер обмена через
 *   Compose `LocalClipboard`; на API 26–32 следом объявляется «Адрес скопирован», на
 *   API 33+ подтверждение показывает сама система, и дублировать его нельзя.
 *
 * Буфер обмена только пишется, никогда не читается.
 */
@Composable
internal fun SettingsEmailRow(
    label: String,
    email: String,
    modifier: Modifier = Modifier,
    emailModifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboard.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val copyLabel = stringResource(R.string.about_copy_email)
    val copiedMessage = stringResource(R.string.about_email_copied)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                customActions = listOf(
                    CustomAccessibilityAction(copyLabel) {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, email)))
                            if (needsCopyAnnouncement(Build.VERSION.SDK_INT)) {
                                view.announceForAccessibility(copiedMessage)
                            }
                        }
                        true
                    },
                )
            }
            .padding(vertical = Spacing.scale300),
        verticalArrangement = Arrangement.spacedBy(Spacing.scale100),
    ) {
        SettingsLabel(label)
        SelectionContainer {
            Text(
                text = email,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = emailModifier,
            )
        }
    }
}

/**
 * Нужно ли собственное объявление после копирования: на API 33+ система сама показывает
 * подтверждение копирования, и второе было бы дублем.
 */
internal fun needsCopyAnnouncement(sdkInt: Int): Boolean = sdkInt < Build.VERSION_CODES.TIRAMISU

/** «Не удалось сохранить настройку» — под строкой своего ключа, без `error`-цвета. */
@Composable
internal fun SettingsWriteFailure(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.settings_write_failed),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.scale200),
    )
}

/** Skeleton строки настроек до первой эмиссии DataStore: полоса заголовка, hairline. */
@Composable
internal fun SettingsRowSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizing.touchTargetMin)
                .padding(vertical = Spacing.scale400),
        ) {
            SkeletonLine(widthFraction = SKELETON_ROW_FRACTION)
        }
        SettingsDivider()
    }
}

/** Skeleton одной строки текста — версия контента до чтения. */
@Composable
internal fun SettingsValueSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = Spacing.scale400)) {
        SkeletonLine(widthFraction = SKELETON_VALUE_FRACTION)
    }
}

/** Hairline между строками (`outlineVariant`, `size.divider.thickness`). */
@Composable
internal fun SettingsDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        thickness = Sizing.dividerThickness,
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = modifier,
    )
}

@Composable
private fun SettingsLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private const val WEIGHT_FILL = 1f
private const val CHEVRON_TO_END_DEGREES = -90f
private const val SKELETON_ROW_FRACTION = 0.5f
private const val SKELETON_VALUE_FRACTION = 0.45f
