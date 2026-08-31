package ru.poporyadku.domain.repository

import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import ru.poporyadku.core.model.ThemeMode
import ru.poporyadku.core.model.UserPreferences

// ITERATION_2_DESIGN.md, D-18: импортирует только core.model и kotlinx.coroutines.flow —
// ни одного импорта из data или из хранилища настроек, которое эти данные подкрепляет.
interface UserPreferencesRepository {
    val preferences: Flow<UserPreferences>

    suspend fun setSoundEnabled(enabled: Boolean)
    suspend fun setVibrationEnabled(enabled: Boolean)
    suspend fun setReminderEnabled(enabled: Boolean)
    suspend fun setReminderTime(time: LocalTime)
    suspend fun setThemeMode(mode: ThemeMode)

    /** version >= 0; contentVersion не бывает отрицательной (CONTENT_MODEL.md §7). */
    suspend fun setStoredContentVersion(version: Int)

    suspend fun setHasSeenDragHint(seen: Boolean)
    suspend fun setHasSeenScoringHint(seen: Boolean)
    suspend fun setHasCompletedFirstDay(completed: Boolean)
    suspend fun setNotificationPromptShown(shown: Boolean)

    /** null удаляет ключ — «даты нет», а не «дата пустая строка». */
    suspend fun setLastSeenDate(date: LocalDate?)

    /** Единственная операция записи кэша серии. Отдельных сеттеров трёх ключей нет. */
    suspend fun updateStreakCache(current: Int, best: Int, date: LocalDate)
}
