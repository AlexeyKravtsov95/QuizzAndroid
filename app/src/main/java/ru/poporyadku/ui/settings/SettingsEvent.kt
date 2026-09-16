package ru.poporyadku.ui.settings

import java.time.LocalTime
import ru.poporyadku.core.model.ThemeMode

/**
 * События настроек (ITERATION_5_DESIGN.md, §4.5).
 *
 * Значение настройки в событии — **целевое**, вычисленное экраном из подтверждённого
 * состояния: два нажатия до прихода первой записи несут одно и то же значение.
 * События создаются только пользовательским действием — ни эмиссия DataStore, ни
 * перекомпозиция их не порождают.
 */
sealed interface SettingsEvent {
    data class SoundToggled(val enabled: Boolean) : SettingsEvent
    data class VibrationToggled(val enabled: Boolean) : SettingsEvent
    data class ThemeSelected(val mode: ThemeMode) : SettingsEvent

    /** Нажатие на строку «Напоминание» (ITERATION_6_DESIGN.md, §8.2). */
    data class ReminderToggled(val enabled: Boolean) : SettingsEvent

    /** Подтверждение выбора времени — одна команда записи, а не на каждое изменение поля. */
    data class ReminderTimeChosen(val time: LocalTime) : SettingsEvent

    /**
     * Система ответила на запрос разрешения. Булева результата у события нет намеренно:
     * решение принимается перечитанным статусом доступа (I6-D36).
     */
    data object NotificationPermissionResult : SettingsEvent

    /** «Открыть настройки уведомлений» под строкой напоминания. */
    data object OpenNotificationSettingsClicked : SettingsEvent
    data object SourcesClicked : SettingsEvent
    data object ReportClicked : SettingsEvent
    data object BackClicked : SettingsEvent
}
