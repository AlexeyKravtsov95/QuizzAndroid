package ru.poporyadku.core.model

import java.time.LocalDate
import java.time.LocalTime

// ITERATION_2_DESIGN.md, D-18: доменное значение в core/model — ни одного импорта из
// data. Видно domain и ui; оба видят core.model и не видят data.
data class UserPreferences(
    val soundEnabled: Boolean,
    val vibrationEnabled: Boolean,
    val reminderEnabled: Boolean,
    val reminderTime: LocalTime,
    val themeMode: ThemeMode,
    val storedContentVersion: Int,
    /** `sha256` байтов `manifest.json` установленного пакета, нижний регистр.
     *  `null` — отметки нет: ключ отсутствует, и импортёр обязан пройти полный путь
     *  (ITERATION_4_DESIGN.md, I4-D10). */
    val storedContentFingerprint: String?,
    val hasSeenDragHint: Boolean,
    val hasSeenScoringHint: Boolean,
    val hasCompletedFirstDay: Boolean,
    val notificationPromptShown: Boolean,
    val lastSeenDate: LocalDate?,
    val streakCache: StreakCache,
)
