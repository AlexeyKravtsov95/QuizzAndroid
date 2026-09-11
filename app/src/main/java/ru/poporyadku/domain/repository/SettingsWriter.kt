package ru.poporyadku.domain.repository

import kotlinx.coroutines.flow.StateFlow
import ru.poporyadku.domain.model.SettingKey
import ru.poporyadku.domain.model.SettingMutation

/**
 * Единственный путь записи настроек из UI (ITERATION_5_DESIGN.md, §5.5, I5-D15).
 *
 * Экран показывает только подтверждённое значение из `UserPreferencesRepository`, а
 * запись уходит сюда командой. Владелец очереди живёт дольше экрана: принятая команда
 * применяется и после ухода со Settings.
 */
interface SettingsWriter {

    /**
     * Ключи, последняя обработанная запись которых завершилась отказом. Ключ снимается
     * только успешной записью того же ключа — ни успех другого ключа, ни уход с экрана
     * его не снимают.
     */
    val failedKeys: StateFlow<Set<SettingKey>>

    /** Принять команду. Не `suspend` и не бросает: запись выполнит владелец очереди. */
    fun submit(mutation: SettingMutation)
}
