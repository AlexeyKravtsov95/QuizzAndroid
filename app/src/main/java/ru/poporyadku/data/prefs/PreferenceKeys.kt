package ru.poporyadku.data.prefs

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

// ITERATION_2_DESIGN.md, раздел 7: ключи одного файла poporyadku_prefs.
// STORED_CONTENT_FINGERPRINT добавлен в PR 4B итерации 4 (ITERATION_4_DESIGN.md, I4-D10).
// Внутренняя деталь хранилища — виден только data/prefs (и тестам того же модуля).
internal object PreferenceKeys {
    val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
    val VIBRATION_ENABLED = booleanPreferencesKey("vibration_enabled")
    val REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")
    val REMINDER_MINUTE_OF_DAY = intPreferencesKey("reminder_minute_of_day")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val STORED_CONTENT_VERSION = intPreferencesKey("stored_content_version")
    val STORED_CONTENT_FINGERPRINT = stringPreferencesKey("stored_content_fingerprint")
    val HAS_SEEN_DRAG_HINT = booleanPreferencesKey("has_seen_drag_hint")
    val HAS_SEEN_SCORING_HINT = booleanPreferencesKey("has_seen_scoring_hint")
    val HAS_COMPLETED_FIRST_DAY = booleanPreferencesKey("has_completed_first_day")
    val NOTIFICATION_PROMPT_SHOWN = booleanPreferencesKey("notification_prompt_shown")
    val LAST_SEEN_DATE = stringPreferencesKey("last_seen_date")
    val CACHED_CURRENT_STREAK = intPreferencesKey("cached_current_streak")
    val CACHED_BEST_STREAK = intPreferencesKey("cached_best_streak")
    val CACHED_STREAK_DATE = stringPreferencesKey("cached_streak_date")
}
