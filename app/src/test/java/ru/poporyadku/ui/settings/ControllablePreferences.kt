package ru.poporyadku.ui.settings

import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import ru.poporyadku.core.model.StreakCache
import ru.poporyadku.core.model.ThemeMode
import ru.poporyadku.core.model.UserPreferences
import ru.poporyadku.domain.model.SettingKey
import ru.poporyadku.domain.model.SettingMutation
import ru.poporyadku.domain.repository.UserPreferencesRepository

/**
 * DataStore в памяти для тестов настроек и темы (ITERATION_5_DESIGN.md, §10.3).
 *
 * Модель та же, что у настоящего репозитория: сеттер завершается — хранилище эмитит новое
 * значение; сеттер бросает — эмиссии нет. Дополнительно умеет:
 * - не эмитить вовсе до [emit] (`initial = null`) — «DataStore ещё не прочитан»;
 * - задерживать запись ([holdWrites]/[releaseWrites]) — «команда принята, но не применена»;
 * - уронить следующую запись конкретного ключа ([failNext]);
 * - записывать каждый вызов сеттера ([calls]) и каждую отменённую запись ([cancelled]).
 */
internal class ControllablePreferences(
    initial: UserPreferences? = defaults(),
) : UserPreferencesRepository {

    private val state = MutableStateFlow(initial)

    override val preferences: Flow<UserPreferences> = state.filterNotNull()

    /** Каждый вызов сеттера настройки — в порядке вызова. */
    val calls = mutableListOf<SettingMutation>()

    /** Записи, прерванные отменой, пока ждали [releaseWrites]. */
    val cancelled = mutableListOf<SettingMutation>()

    private val failures = mutableMapOf<SettingKey, ArrayDeque<Exception>>()
    private var gate: CompletableDeferred<Unit>? = null

    val current: UserPreferences? get() = state.value

    /** Эмиссия DataStore без команды (другое приложение, другой ключ, первая загрузка). */
    fun emit(value: UserPreferences) {
        state.value = value
    }

    fun holdWrites() {
        gate = CompletableDeferred()
    }

    fun releaseWrites() {
        gate?.complete(Unit)
        gate = null
    }

    fun failNext(key: SettingKey, error: Exception = IllegalStateException("edit упал: $key")) {
        failures.getOrPut(key) { ArrayDeque() }.addLast(error)
    }

    override suspend fun setSoundEnabled(enabled: Boolean) =
        write(SettingMutation.Sound(enabled)) { it.copy(soundEnabled = enabled) }

    override suspend fun setVibrationEnabled(enabled: Boolean) =
        write(SettingMutation.Vibration(enabled)) { it.copy(vibrationEnabled = enabled) }

    override suspend fun setThemeMode(mode: ThemeMode) =
        write(SettingMutation.Theme(mode)) { it.copy(themeMode = mode) }

    private suspend fun write(mutation: SettingMutation, apply: (UserPreferences) -> UserPreferences) {
        calls += mutation
        try {
            gate?.await()
        } catch (e: CancellationException) {
            cancelled += mutation
            throw e
        }
        failures[mutation.key]?.removeFirstOrNull()?.let { throw it }
        state.value = apply(checkNotNull(state.value) { "запись до первой эмиссии" })
    }

    override suspend fun setReminderEnabled(enabled: Boolean) = unsupported()
    override suspend fun setReminderTime(time: LocalTime) = unsupported()
    override suspend fun setInstalledContent(contentVersion: Int, fingerprint: String) = unsupported()
    override suspend fun setHasSeenDragHint(seen: Boolean) = unsupported()
    override suspend fun setHasSeenScoringHint(seen: Boolean) = unsupported()
    override suspend fun setHasCompletedFirstDay(completed: Boolean) = unsupported()
    override suspend fun setNotificationPromptShown(shown: Boolean) = unsupported()
    override suspend fun setLastSeenDate(date: LocalDate?) = unsupported()
    override suspend fun updateStreakCache(current: Int, best: Int, date: LocalDate) = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("настройки этот ключ не пишут")

    companion object {
        fun defaults(
            soundEnabled: Boolean = true,
            vibrationEnabled: Boolean = true,
            themeMode: ThemeMode = ThemeMode.SYSTEM,
            contentVersion: Int = 1,
            fingerprint: String? = "fingerprint-1",
        ) = UserPreferences(
            soundEnabled = soundEnabled,
            vibrationEnabled = vibrationEnabled,
            reminderEnabled = false,
            reminderTime = LocalTime.of(9, 0),
            themeMode = themeMode,
            storedContentVersion = contentVersion,
            storedContentFingerprint = fingerprint,
            hasSeenDragHint = false,
            hasSeenScoringHint = false,
            hasCompletedFirstDay = false,
            notificationPromptShown = false,
            lastSeenDate = null,
            streakCache = StreakCache.EMPTY,
        )
    }
}
