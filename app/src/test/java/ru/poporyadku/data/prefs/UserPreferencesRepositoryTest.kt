package ru.poporyadku.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import app.cash.turbine.test
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.poporyadku.core.model.StreakCache
import ru.poporyadku.core.model.ThemeMode
import ru.poporyadku.core.model.UserPreferences

// ITERATION_2_DESIGN.md, D-18, раздел 4 (T1–T10). Robolectric + TemporaryFolder +
// настоящий Preferences DataStore во временном каталоге + Turbine.
@RunWith(RobolectricTestRunner::class)
class UserPreferencesRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: UserPreferencesRepositoryImpl

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(tempFolder.newFolder(), "test.preferences_pb")
        }
        repository = UserPreferencesRepositoryImpl(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun defaultPreferences() = UserPreferences(
        soundEnabled = true,
        vibrationEnabled = true,
        reminderEnabled = false,
        reminderTime = LocalTime.of(9, 0),
        themeMode = ThemeMode.SYSTEM,
        storedContentVersion = 0,
        hasSeenDragHint = false,
        hasSeenScoringHint = false,
        hasCompletedFirstDay = false,
        notificationPromptShown = false,
        lastSeenDate = null,
        streakCache = StreakCache.EMPTY,
    )

    // ---------- значения по умолчанию на пустом хранилище ----------

    @Test
    fun `empty store yields full defaults`() = runTest {
        repository.preferences.test {
            assertEquals(defaultPreferences(), awaitItem())
        }
    }

    // ---------- запись и чтение каждого из 14 ключей ----------

    @Test
    fun `sound_enabled is written and read back`() = runTest {
        repository.setSoundEnabled(false)
        repository.preferences.test {
            assertEquals(false, awaitItem().soundEnabled)
        }
    }

    @Test
    fun `vibration_enabled is written and read back`() = runTest {
        repository.setVibrationEnabled(false)
        repository.preferences.test {
            assertEquals(false, awaitItem().vibrationEnabled)
        }
    }

    @Test
    fun `reminder_enabled is written and read back`() = runTest {
        repository.setReminderEnabled(true)
        repository.preferences.test {
            assertEquals(true, awaitItem().reminderEnabled)
        }
    }

    @Test
    fun `reminder_minute_of_day is written and read back as LocalTime`() = runTest {
        repository.setReminderTime(LocalTime.of(21, 30))
        repository.preferences.test {
            assertEquals(LocalTime.of(21, 30), awaitItem().reminderTime)
        }
    }

    @Test
    fun `theme_mode is written and read back`() = runTest {
        repository.setThemeMode(ThemeMode.DARK)
        repository.preferences.test {
            assertEquals(ThemeMode.DARK, awaitItem().themeMode)
        }
    }

    @Test
    fun `stored_content_version is written and read back`() = runTest {
        repository.setStoredContentVersion(3)
        repository.preferences.test {
            assertEquals(3, awaitItem().storedContentVersion)
        }
    }

    @Test
    fun `has_seen_drag_hint is written and read back`() = runTest {
        repository.setHasSeenDragHint(true)
        repository.preferences.test {
            assertEquals(true, awaitItem().hasSeenDragHint)
        }
    }

    @Test
    fun `has_seen_scoring_hint is written and read back`() = runTest {
        repository.setHasSeenScoringHint(true)
        repository.preferences.test {
            assertEquals(true, awaitItem().hasSeenScoringHint)
        }
    }

    @Test
    fun `has_completed_first_day is written and read back`() = runTest {
        repository.setHasCompletedFirstDay(true)
        repository.preferences.test {
            assertEquals(true, awaitItem().hasCompletedFirstDay)
        }
    }

    @Test
    fun `notification_prompt_shown is written and read back`() = runTest {
        repository.setNotificationPromptShown(true)
        repository.preferences.test {
            assertEquals(true, awaitItem().notificationPromptShown)
        }
    }

    @Test
    fun `last_seen_date is written and read back`() = runTest {
        repository.setLastSeenDate(LocalDate.of(2026, 8, 30))
        repository.preferences.test {
            assertEquals(LocalDate.of(2026, 8, 30), awaitItem().lastSeenDate)
        }
    }

    @Test
    fun `streak cache triple is written and read back via updateStreakCache`() = runTest {
        repository.updateStreakCache(current = 4, best = 6, date = LocalDate.of(2026, 8, 30))
        repository.preferences.test {
            assertEquals(StreakCache(4, 6, LocalDate.of(2026, 8, 30)), awaitItem().streakCache)
        }
    }

    // ---------- доставка изменений через Flow ----------

    @Test
    fun `changes are delivered through the flow`() = runTest {
        repository.preferences.test {
            assertEquals(true, awaitItem().soundEnabled)

            repository.setSoundEnabled(false)
            assertEquals(false, awaitItem().soundEnabled)

            repository.setSoundEnabled(true)
            assertEquals(true, awaitItem().soundEnabled)
        }
    }

    // ---------- устойчивость чтения (D-18) ----------

    @Test
    fun `unknown theme name reads as SYSTEM`() = runTest {
        dataStore.edit { it[PreferenceKeys.THEME_MODE] = "NOT_A_THEME" }
        repository.preferences.test {
            assertEquals(ThemeMode.SYSTEM, awaitItem().themeMode)
        }
    }

    @Test
    fun `invalid ISO date reads as null`() = runTest {
        dataStore.edit { it[PreferenceKeys.LAST_SEEN_DATE] = "not-a-date" }
        repository.preferences.test {
            assertNull(awaitItem().lastSeenDate)
        }
    }

    @Test
    fun `reminder_minute_of_day = -1 reads as default 540`() = runTest {
        dataStore.edit { it[PreferenceKeys.REMINDER_MINUTE_OF_DAY] = -1 }
        repository.preferences.test {
            assertEquals(LocalTime.of(9, 0), awaitItem().reminderTime)
        }
    }

    @Test
    fun `reminder_minute_of_day = 1440 reads as default 540`() = runTest {
        dataStore.edit { it[PreferenceKeys.REMINDER_MINUTE_OF_DAY] = 1440 }
        repository.preferences.test {
            assertEquals(LocalTime.of(9, 0), awaitItem().reminderTime)
        }
    }

    // ---------- T1–T10 ----------

    @Test
    fun `T1 - negative current streak is rejected and storage is unchanged`() = runTest {
        repository.updateStreakCache(current = 3, best = 5, date = LocalDate.of(2026, 8, 1))

        assertThrows<IllegalArgumentException> {
            repository.updateStreakCache(current = -1, best = 0, date = LocalDate.of(2026, 8, 2))
        }

        repository.preferences.test {
            assertEquals(StreakCache(3, 5, LocalDate.of(2026, 8, 1)), awaitItem().streakCache)
        }
    }

    @Test
    fun `T2 - best less than current is rejected and storage is unchanged`() = runTest {
        repository.updateStreakCache(current = 3, best = 5, date = LocalDate.of(2026, 8, 1))

        assertThrows<IllegalArgumentException> {
            repository.updateStreakCache(current = 5, best = 3, date = LocalDate.of(2026, 8, 2))
        }

        repository.preferences.test {
            assertEquals(StreakCache(3, 5, LocalDate.of(2026, 8, 1)), awaitItem().streakCache)
        }
    }

    @Test
    fun `T3 - inconsistent triple reads as EMPTY entirely`() = runTest {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.CACHED_CURRENT_STREAK] = 5
            prefs[PreferenceKeys.CACHED_BEST_STREAK] = 3
            prefs[PreferenceKeys.CACHED_STREAK_DATE] = LocalDate.of(2026, 8, 1).toString()
        }

        repository.preferences.test {
            assertEquals(StreakCache.EMPTY, awaitItem().streakCache)
        }
    }

    @Test
    fun `T4 - unparseable streak date reads as EMPTY and storage is untouched by the read`() = runTest {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.CACHED_CURRENT_STREAK] = 4
            prefs[PreferenceKeys.CACHED_BEST_STREAK] = 4
            prefs[PreferenceKeys.CACHED_STREAK_DATE] = "не-дата"
        }

        repository.preferences.test {
            assertEquals(StreakCache.EMPTY, awaitItem().streakCache)
        }

        // Сброс происходит только в возвращённом значении — чтение не пишет в DataStore.
        dataStore.data.test {
            val raw = awaitItem()
            assertEquals(4, raw[PreferenceKeys.CACHED_CURRENT_STREAK])
            assertEquals(4, raw[PreferenceKeys.CACHED_BEST_STREAK])
            assertEquals("не-дата", raw[PreferenceKeys.CACHED_STREAK_DATE])
        }
    }

    @Test
    fun `T5 - negative stored content version reads as 0`() = runTest {
        dataStore.edit { it[PreferenceKeys.STORED_CONTENT_VERSION] = -3 }
        repository.preferences.test {
            assertEquals(0, awaitItem().storedContentVersion)
        }
    }

    @Test
    fun `T6 - IOException from the data flow yields defaults`() = runTest {
        val throwing = ThrowingDataStore(IOException("disk error"))
        val repo = UserPreferencesRepositoryImpl(throwing)

        repo.preferences.test {
            assertEquals(defaultPreferences(), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `T7 - a non-IOException from the data flow is rethrown, not swallowed`() = runTest {
        val throwing = ThrowingDataStore(RuntimeException("boom"))
        val repo = UserPreferencesRepositoryImpl(throwing)

        repo.preferences.test {
            val error = awaitError()
            assertEquals("boom", error.message)
        }
    }

    @Test
    fun `T8 - updateStreakCache is atomic, no intermediate state is ever emitted`() = runTest {
        repository.updateStreakCache(current = 1, best = 1, date = LocalDate.of(2026, 8, 1))

        repository.preferences.test {
            assertEquals(StreakCache(1, 1, LocalDate.of(2026, 8, 1)), awaitItem().streakCache)

            repository.updateStreakCache(current = 5, best = 5, date = LocalDate.of(2026, 8, 30))

            assertEquals(StreakCache(5, 5, LocalDate.of(2026, 8, 30)), awaitItem().streakCache)
        }
    }

    @Test
    fun `T9 - negative stored content version on write is rejected, previous value is kept`() = runTest {
        repository.setStoredContentVersion(2)

        assertThrows<IllegalArgumentException> {
            repository.setStoredContentVersion(-1)
        }

        repository.preferences.test {
            assertEquals(2, awaitItem().storedContentVersion)
        }
    }

    @Test
    fun `T10 - setLastSeenDate(null) removes the key`() = runTest {
        repository.setLastSeenDate(LocalDate.of(2026, 8, 30))
        repository.setLastSeenDate(null)

        repository.preferences.test {
            assertNull(awaitItem().lastSeenDate)
        }
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected ${T::class.java.name} to be thrown, nothing was")
        } catch (e: Throwable) {
            if (e !is T) throw e
        }
    }

    /** Тестовый двойник DataStore, поток data которого сразу бросает заданную ошибку —
     *  единственный способ подставить IOException/RuntimeException из потока без реальной
     *  порчи диска. updateData не задействуется ни одним тестом T6/T7. */
    private class ThrowingDataStore(private val error: Throwable) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw error }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            throw UnsupportedOperationException("не вызывается тестами T6/T7")
        }
    }
}
