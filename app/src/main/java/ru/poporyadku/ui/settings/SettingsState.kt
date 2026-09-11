package ru.poporyadku.ui.settings

import ru.poporyadku.core.model.ThemeMode
import ru.poporyadku.domain.model.InstalledContentVersion
import ru.poporyadku.domain.model.SettingKey

/**
 * Состояние настроек (ITERATION_5_DESIGN.md, §3.9, §4.5, I5-D15).
 *
 * `data class`, а не `sealed`: части независимы и комбинируются (ARCHITECTURE.md §4).
 * Доступность почтового клиента здесь не хранится — её узнаёт route-контейнер.
 */
data class SettingsState(
    /** `null` — DataStore ещё не эмитил: строки звука, вибрации и темы — skeleton. */
    val preferences: PreferencesUi?,
    val about: AboutUi,
    /** Ключи, чья последняя завершённая запись упала (`SettingsWriter.failedKeys`). */
    val writeFailures: Set<SettingKey>,
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
