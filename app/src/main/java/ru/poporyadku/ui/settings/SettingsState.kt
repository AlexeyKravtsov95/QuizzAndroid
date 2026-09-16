package ru.poporyadku.ui.settings

import java.time.LocalTime
import ru.poporyadku.core.model.ThemeMode
import ru.poporyadku.domain.model.InstalledContentVersion
import ru.poporyadku.domain.model.SettingKey
import ru.poporyadku.domain.reminder.NotificationAvailability

/**
 * Состояние настроек (ITERATION_5_DESIGN.md, §3.9, §4.5, I5-D15).
 *
 * `data class`, а не `sealed`: части независимы и комбинируются (ARCHITECTURE.md §4).
 * Доступность почтового клиента здесь не хранится — её узнаёт route-контейнер.
 */
data class SettingsState(
    /** `null` — DataStore ещё не эмитил: строки звука, вибрации и темы — skeleton. */
    val preferences: PreferencesUi?,
    /** `null` — DataStore ещё не эмитил: строки напоминания — skeleton. */
    val reminder: ReminderUi?,
    val about: AboutUi,
    /** Ключи, чья последняя завершённая запись упала (`SettingsWriter.failedKeys`). */
    val writeFailures: Set<SettingKey>,
)

/**
 * Напоминание на экране настроек (ITERATION_6_DESIGN.md, §8.1, I6-D37).
 *
 * @param enabledShown что показывает переключатель: `reminderEnabled && Allowed`.
 *  **Не** то же самое, что записанное намерение: доступ могли отозвать в системе, и
 *  тогда переключатель выключен, хотя в DataStore напоминание включено. Обратной записи
 *  при этом не происходит — пользователь его не выключал (I6-D37).
 * @param time подтверждённое DataStore время; показывается только при [enabledShown].
 * @param unavailability причина недоступности; `null` — доступ есть. Пользователю
 *  причина не показывается (подсказка одна на все три, **O6-4**) — она нужна только для
 *  выбора цели системных настроек.
 */
data class ReminderUi(
    val enabledShown: Boolean,
    val time: LocalTime,
    val unavailability: NotificationAvailability?,
    /**
     * Показывать ли подсказку и действие: доступа нет, а намерение выражено — либо уже
     * записано, либо ожидает выдачи доступа (`pendingEnable`).
     */
    val showPermissionHint: Boolean,
)

/** Только подтверждённые DataStore значения: оптимистичного локального состояния нет. */
data class PreferencesUi(
    val soundEnabled: Boolean,
    val vibrationEnabled: Boolean,
    val themeMode: ThemeMode,
)

/** «О приложении»: версия приложения известна сразу, версия контента — после чтения. */
data class AboutUi(
    val versionName: String,
    val versionCode: Long,
    /** `null` — ещё не прочитано. */
    val contentVersion: InstalledContentVersion?,
)
