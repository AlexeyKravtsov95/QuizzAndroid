package ru.poporyadku.domain.model

import java.time.LocalTime
import ru.poporyadku.core.model.ThemeMode

/**
 * Настройка, чью запись можно подтвердить или провалить отдельно от других
 * (ITERATION_5_DESIGN.md, §3.9, §5.5, I5-D15). Ключ ошибки записи — ровно этот тип.
 */
enum class SettingKey {
    Sound,
    Vibration,
    Theme,

    /** Включение и выключение напоминания (ITERATION_6_DESIGN.md, I6-D39). */
    Reminder,

    /** Время напоминания — отдельный ключ: отказ времени не гасит строку переключателя. */
    ReminderTime,
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

    /**
     * Намерение пользователя показывать напоминание (ITERATION_6_DESIGN.md, §8.2,
     * I6-D39). Планировщик здесь не вызывается: работу ставит коллектор настроек,
     * увидевший записанное значение, — команда знает только про DataStore.
     */
    data class ReminderEnabled(val enabled: Boolean) : SettingMutation {
        override val key: SettingKey get() = SettingKey.Reminder
    }

    /** Время напоминания — по подтверждению выбора, а не на каждое изменение поля (I6-D38). */
    data class ReminderTime(val time: LocalTime) : SettingMutation {
        override val key: SettingKey get() = SettingKey.ReminderTime
    }
}
