package ru.poporyadku.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import ru.poporyadku.core.model.StreakCache
import ru.poporyadku.core.model.ThemeMode
import ru.poporyadku.core.model.UserPreferences
import ru.poporyadku.domain.repository.UserPreferencesRepository

// ITERATION_2_DESIGN.md, D-18: единственное место в проекте, где встречается имя
// androidx.datastore, кроме di/PreferencesModule.kt.
class UserPreferencesRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : UserPreferencesRepository {

    // catch сужен до IOException: она означает "файл не прочитался" — восстановимую
    // ситуацию, где значения по умолчанию корректны. Всё остальное (IllegalStateException,
    // CancellationException и т. п.) — дефект кода, а не состояние диска, и пробрасывается.
    override val preferences: Flow<UserPreferences> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toUserPreferences() }

    override suspend fun setSoundEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferenceKeys.SOUND_ENABLED] = enabled }
    }

    override suspend fun setVibrationEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferenceKeys.VIBRATION_ENABLED] = enabled }
    }

    override suspend fun setReminderEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferenceKeys.REMINDER_ENABLED] = enabled }
    }

    override suspend fun setReminderTime(time: LocalTime) {
        val minutes = time.hour * 60 + time.minute
        require(minutes in 0..MAX_MINUTE_OF_DAY) { "время напоминания вне 0..$MAX_MINUTE_OF_DAY: $minutes" }
        dataStore.edit { it[PreferenceKeys.REMINDER_MINUTE_OF_DAY] = minutes }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[PreferenceKeys.THEME_MODE] = mode.name }
    }

    override suspend fun setStoredContentVersion(version: Int) {
        require(version >= 0) { "версия контента отрицательна: $version" }
        dataStore.edit { it[PreferenceKeys.STORED_CONTENT_VERSION] = version }
    }

    override suspend fun setHasSeenDragHint(seen: Boolean) {
        dataStore.edit { it[PreferenceKeys.HAS_SEEN_DRAG_HINT] = seen }
    }

    override suspend fun setHasSeenScoringHint(seen: Boolean) {
        dataStore.edit { it[PreferenceKeys.HAS_SEEN_SCORING_HINT] = seen }
    }

    override suspend fun setHasCompletedFirstDay(completed: Boolean) {
        dataStore.edit { it[PreferenceKeys.HAS_COMPLETED_FIRST_DAY] = completed }
    }

    override suspend fun setNotificationPromptShown(shown: Boolean) {
        dataStore.edit { it[PreferenceKeys.NOTIFICATION_PROMPT_SHOWN] = shown }
    }

    override suspend fun setLastSeenDate(date: LocalDate?) {
        dataStore.edit { prefs ->
            if (date == null) prefs.remove(PreferenceKeys.LAST_SEEN_DATE)
            else prefs[PreferenceKeys.LAST_SEEN_DATE] = date.toString()
        }
    }

    override suspend fun updateStreakCache(current: Int, best: Int, date: LocalDate) {
        require(current >= 0) { "текущая серия отрицательна: $current" }
        require(best >= current) { "лучшая серия $best меньше текущей $current" }
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.CACHED_CURRENT_STREAK] = current
            prefs[PreferenceKeys.CACHED_BEST_STREAK] = best
            prefs[PreferenceKeys.CACHED_STREAK_DATE] = date.toString()
        }
    }

    private fun Preferences.toUserPreferences(): UserPreferences = UserPreferences(
        soundEnabled = this[PreferenceKeys.SOUND_ENABLED] ?: true,
        vibrationEnabled = this[PreferenceKeys.VIBRATION_ENABLED] ?: true,
        reminderEnabled = this[PreferenceKeys.REMINDER_ENABLED] ?: false,
        reminderTime = readReminderTime(),
        themeMode = readThemeMode(),
        storedContentVersion = (this[PreferenceKeys.STORED_CONTENT_VERSION] ?: 0).coerceAtLeast(0),
        hasSeenDragHint = this[PreferenceKeys.HAS_SEEN_DRAG_HINT] ?: false,
        hasSeenScoringHint = this[PreferenceKeys.HAS_SEEN_SCORING_HINT] ?: false,
        hasCompletedFirstDay = this[PreferenceKeys.HAS_COMPLETED_FIRST_DAY] ?: false,
        notificationPromptShown = this[PreferenceKeys.NOTIFICATION_PROMPT_SHOWN] ?: false,
        lastSeenDate = readLastSeenDate(),
        streakCache = readStreakCache(),
    )

    private fun Preferences.readReminderTime(): LocalTime {
        val minutes = this[PreferenceKeys.REMINDER_MINUTE_OF_DAY]
        val effective = if (minutes != null && minutes in 0..MAX_MINUTE_OF_DAY) minutes else DEFAULT_REMINDER_MINUTE
        return LocalTime.of(effective / 60, effective % 60)
    }

    private fun Preferences.readThemeMode(): ThemeMode {
        val raw = this[PreferenceKeys.THEME_MODE] ?: return ThemeMode.SYSTEM
        return runCatching { ThemeMode.valueOf(raw) }.getOrDefault(ThemeMode.SYSTEM)
    }

    private fun Preferences.readLastSeenDate(): LocalDate? =
        this[PreferenceKeys.LAST_SEEN_DATE]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** Несогласованная тройка сбрасывается целиком, а не по полю — D-7/D-18.
     *  Сброс происходит только на чтении и ничего не пишет обратно в хранилище. */
    private fun Preferences.readStreakCache(): StreakCache {
        val current = this[PreferenceKeys.CACHED_CURRENT_STREAK] ?: 0
        val best = this[PreferenceKeys.CACHED_BEST_STREAK] ?: 0
        val rawDate = this[PreferenceKeys.CACHED_STREAK_DATE]
        val date = rawDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

        val consistent = current >= 0 &&
            best >= current &&
            (rawDate == null || date != null) &&
            (date != null || (current == 0 && best == 0))

        return if (consistent) StreakCache(current, best, date) else StreakCache.EMPTY
    }

    companion object {
        private const val DEFAULT_REMINDER_MINUTE = 540
        private const val MAX_MINUTE_OF_DAY = 1439
    }
}
