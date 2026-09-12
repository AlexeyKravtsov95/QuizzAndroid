package ru.poporyadku.ui.theme

import app.cash.turbine.test
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import ru.poporyadku.core.model.ThemeMode
import ru.poporyadku.ui.settings.ControllablePreferences

/**
 * `I5-T2` (ITERATION_5_DESIGN.md, §3.9, §4.8, I5-D16): `AppThemeViewModel` отдаёт `null` до
 * первой эмиссии DataStore — корень в это время экраны не компонует, — затем режим; смена
 * других настроек режим не перевыдаёт.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppThemeViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `I5-T2 theme is null before the first emission then follows the stored mode`() = runTest(dispatcher) {
        val prefs = ControllablePreferences(initial = null)
        val viewModel = AppThemeViewModel(prefs)

        viewModel.themeMode.test {
            assertNull("до первой эмиссии DataStore", awaitItem())

            prefs.emit(ControllablePreferences.defaults(themeMode = ThemeMode.DARK))
            assertEquals(ThemeMode.DARK, awaitItem())

            // Другие ключи меняются — тема не перевыдаётся.
            prefs.emit(ControllablePreferences.defaults(themeMode = ThemeMode.DARK, soundEnabled = false))
            advanceUntilIdle()
            prefs.emit(
                ControllablePreferences.defaults(themeMode = ThemeMode.DARK).copy(lastSeenDate = LocalDate.of(2026, 9, 11)),
            )
            advanceUntilIdle()
            expectNoEvents()

            prefs.emit(ControllablePreferences.defaults(themeMode = ThemeMode.LIGHT))
            assertEquals(ThemeMode.LIGHT, awaitItem())
            prefs.emit(ControllablePreferences.defaults(themeMode = ThemeMode.SYSTEM))
            assertEquals(ThemeMode.SYSTEM, awaitItem())
            expectNoEvents()
        }
    }

    /** `Eagerly`: чтение начинается при создании, а не с первой подписки. */
    @Test
    fun `I5-T2 the mode is read eagerly without a subscriber`() = runTest(dispatcher) {
        val prefs = ControllablePreferences(ControllablePreferences.defaults(themeMode = ThemeMode.DARK))
        val viewModel = AppThemeViewModel(prefs)
        advanceUntilIdle()

        assertEquals(ThemeMode.DARK, viewModel.themeMode.value)
    }
}
