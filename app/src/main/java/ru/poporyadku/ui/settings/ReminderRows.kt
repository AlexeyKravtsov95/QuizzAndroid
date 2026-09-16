package ru.poporyadku.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.time.LocalTime
import ru.poporyadku.R
import ru.poporyadku.ui.theme.Sizing
import ru.poporyadku.ui.theme.Spacing

/*
 * Строки напоминания (ITERATION_6_DESIGN.md, §8.1, §8.2; O6-3, O6-4, подтверждены
 * владельцем 2026-09-15). Визуальный язык — тот же, что у остальных строк настроек:
 * без карточек, hairline между строками, интерактивная строка — одна цель не ниже
 * `size.touchTarget.min`.
 */

/**
 * Строка значения времени: подпись слева, значение справа, вся строка — одна цель
 * раскрытия выбора.
 *
 * Описание для TalkBack — «Время напоминания, 9:00»: подпись и значение читаются одним
 * узлом, а не двумя фокус-стопами.
 */
@Composable
internal fun ReminderTimeRow(
    time: LocalTime,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val value = stringResource(R.string.settings_reminder_time_value, time.hour, time.minute)
    val description = stringResource(R.string.cd_settings_reminder_time, value)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Sizing.touchTargetMin)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(vertical = Spacing.scale200),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.scale400),
    ) {
        Text(
            text = stringResource(R.string.settings_reminder_time),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(WEIGHT_FILL),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Выбор времени — **встроенный** M3 `TimeInput` прямо под строкой «Время» (**O6-3**).
 *
 * Не диалог и не `TimePickerDialog`: у продукта ровно два компонента с тенью
 * (`OrderableCard.dragging` и `NotificationOptInDialog`, `DESIGN_PRINCIPLES.md` §6), и
 * третий появился бы только ради выбора часа. `TimeInput`, а не `TimePicker`-циферблат:
 * поля ввода помещаются на 320 dp при 200 % и работают с клавиатуры.
 *
 * Формат — 24 часа (`is24Hour = true`): продукт русскоязычный, и AM/PM здесь чужие.
 *
 * Значение подтверждается **явно**: событие записи уходит только по «Сохранить», а
 * «Отмена» закрывает выбор, не написав ничего (I6-D38).
 *
 * `ExperimentalMaterial3Api` — статус самого `TimeInput` в Material 3; согласованной
 * стабильной альтернативы для выбора времени в M3 нет, а собственный ввод часов и минут
 * означал бы свой визуальный язык вопреки **O6-3**. Opt-in точечный: он не
 * распространяется на другие файлы, и при смене API правка ограничена этой функцией.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReminderTimeInput(
    initial: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true,
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.scale200),
        verticalArrangement = Arrangement.spacedBy(Spacing.scale200),
    ) {
        TimeInput(state = state)
        // FlowRow: при 200 % и 320 dp кнопки складываются вертикально, сохраняя цели.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.scale200),
        ) {
            TextButton(
                onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) },
                modifier = Modifier
                    .heightIn(min = Sizing.touchTargetMin)
                    .testTag(SettingsTestTags.REMINDER_TIME_CONFIRM),
            ) {
                Text(stringResource(R.string.reminder_time_confirm))
            }
            TextButton(
                onClick = onCancel,
                modifier = Modifier
                    .heightIn(min = Sizing.touchTargetMin)
                    .testTag(SettingsTestTags.REMINDER_TIME_CANCEL),
            ) {
                Text(stringResource(R.string.reminder_time_cancel))
            }
        }
    }
}

/**
 * Подсказка недоступности и действие перехода в системные настройки (**O6-4**).
 *
 * Текст один на все три причины: пользователю не нужно различать разрешение,
 * уведомления приложения и канал — ему нужен путь туда, где это включается.
 */
@Composable
internal fun ReminderPermissionHint(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.scale200),
        verticalArrangement = Arrangement.spacedBy(Spacing.scale100),
    ) {
        Text(
            text = stringResource(R.string.settings_reminder_permission_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        TextButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .heightIn(min = Sizing.touchTargetMin)
                .testTag(SettingsTestTags.REMINDER_OPEN_SYSTEM),
        ) {
            Text(stringResource(R.string.settings_reminder_open_system))
        }
    }
}

private const val WEIGHT_FILL = 1f
