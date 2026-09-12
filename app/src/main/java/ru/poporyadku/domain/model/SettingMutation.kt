package ru.poporyadku.domain.model

import ru.poporyadku.core.model.ThemeMode

/**
 * Настройка, чью запись можно подтвердить или провалить отдельно от других
 * (ITERATION_5_DESIGN.md, §3.9, §5.5, I5-D15). Ключ ошибки записи — ровно этот тип.
 */
enum class SettingKey {
    Sound,
    Vibration,
    Theme,
}

/**
 * Команда записи одной настройки. Одна команда — ровно один существующий сеттер
 * `UserPreferencesRepository` и один `edit` DataStore: это и есть атомарная граница
 * записи, поэтому ни пакетов, ни объединения команд нет.
 *
 * Доменный тип очереди, а не экранный: экран шлёт событие, ViewModel превращает его в
 * команду, исполняет её владелец очереди (`SettingsWriter`).
 */
sealed interface SettingMutation {
    val key: SettingKey

    data class Sound(val enabled: Boolean) : SettingMutation {
        override val key: SettingKey get() = SettingKey.Sound
    }

    data class Vibration(val enabled: Boolean) : SettingMutation {
        override val key: SettingKey get() = SettingKey.Vibration
    }

    data class Theme(val mode: ThemeMode) : SettingMutation {
        override val key: SettingKey get() = SettingKey.Theme
    }
}
