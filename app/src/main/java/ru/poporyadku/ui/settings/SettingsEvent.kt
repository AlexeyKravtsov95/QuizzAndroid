package ru.poporyadku.ui.settings

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
    data object SourcesClicked : SettingsEvent
    data object ReportClicked : SettingsEvent
    data object BackClicked : SettingsEvent
}
