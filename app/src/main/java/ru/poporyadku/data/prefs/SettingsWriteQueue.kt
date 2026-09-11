package ru.poporyadku.data.prefs

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.poporyadku.di.ApplicationScope
import ru.poporyadku.domain.model.SettingKey
import ru.poporyadku.domain.model.SettingMutation
import ru.poporyadku.domain.repository.SettingsWriter
import ru.poporyadku.domain.repository.UserPreferencesRepository

/**
 * Очередь записи настроек (ITERATION_5_DESIGN.md, §3.9, §5.5, I5-D15).
 *
 * - **Одна очередь, один worker на процесс.** Команды применяются строго в порядке
 *   [submit], по одной: две записи никогда не идут одновременно, и исход каждой
 *   применяется только к своему ключу. `failures` меняет только worker, поэтому поздний
 *   исход не может перетереть более ранний другого ключа.
 * - **Атомарная граница — один `edit` DataStore одного ключа** на команду: DataStore
 *   пишет её целиком или не пишет вовсе. Команды не объединяются и не переставляются —
 *   последний `edit` в любом случае последний.
 * - **Время жизни — scope приложения**, а не ViewModel: пользователь, выключивший звук и
 *   сразу нажавший «Назад», не должен найти звук включённым. Отмена scope приложения
 *   (конец процесса, тесты) пробрасывается как `CancellationException` и ошибкой ключа
 *   не становится; команды, не дошедшие до `edit` к смерти процесса, теряются, и экран
 *   после перезапуска честно показывает прежнее значение из DataStore.
 * - Очередь без ограничения ёмкости, но заполняется только нажатиями пользователя, а
 *   worker обрабатывает их за миллисекунды.
 */
@Singleton
class SettingsWriteQueue @Inject constructor(
    private val preferences: UserPreferencesRepository,
    @ApplicationScope scope: CoroutineScope,
) : SettingsWriter {

    private val commands = Channel<SettingMutation>(Channel.UNLIMITED)
    private val failures = MutableStateFlow<Set<SettingKey>>(emptySet())

    override val failedKeys: StateFlow<Set<SettingKey>> = failures.asStateFlow()

    init {
        // ОДИН worker: команды обрабатываются строго в порядке поступления.
        scope.launch {
            for (mutation in commands) apply(mutation)
        }
    }

    override fun submit(mutation: SettingMutation) {
        // UNLIMITED: не отказывает и не приостанавливает, пока очередь открыта, а
        // закрывать её некому — она живёт столько же, сколько процесс.
        commands.trySend(mutation)
    }

    private suspend fun apply(mutation: SettingMutation) {
        try {
            // Ровно один сеттер — ровно один edit DataStore.
            when (mutation) {
                is SettingMutation.Sound -> preferences.setSoundEnabled(mutation.enabled)
                is SettingMutation.Vibration -> preferences.setVibrationEnabled(mutation.enabled)
                is SettingMutation.Theme -> preferences.setThemeMode(mutation.mode)
            }
            // Успех снимает ошибку ТОЛЬКО своего ключа.
            failures.update { it - mutation.key }
        } catch (e: CancellationException) {
            // Отмена scope приложения — не ошибка ключа.
            throw e
        } catch (e: Exception) {
            // Чужие ключи не трогаются.
            failures.update { it + mutation.key }
        }
    }
}
