package ru.poporyadku.ui.navigation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.testing.TestNavHostController
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.poporyadku.ui.theme.PoPoRyadkuTheme

/**
 * Итерация 1, критерий приёмки: навигационный стек соответствует UX_FLOW.md §1.
 *
 * Пользовательский поток управляется исключительно кликами по реальному UI
 * (`performClick`) и системной кнопкой «назад» (`Espresso.pressBackUnconditionally`).
 * `navController.navigate(...)` вызывается только один раз в `setUp()`, чтобы разместить
 * `TestNavHostController` внутри дерева — это хостинг теста, а не имитация пользователя.
 * Чтение `currentBackStackEntry`/`currentBackStack` используется только для проверок.
 */
@RunWith(AndroidJUnit4::class)
class AppNavHostTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var navController: TestNavHostController

    @Before
    fun setUp() {
        composeTestRule.setContent {
            navController = TestNavHostController(InstrumentationRegistry.getInstrumentation().targetContext)
            navController.navigatorProvider.addNavigator(
                androidx.navigation.compose.ComposeNavigator(),
            )
            PoPoRyadkuTheme {
                AppNavHost(navController = navController as NavHostController)
            }
        }
    }

    private fun currentRoute(): String? = navController.currentBackStackEntry?.destination?.route

    private fun currentBackStackRoutes(): List<String?> =
        navController.currentBackStack.value.mapNotNull { it.destination.route }

    @Test
    fun startDestinationIsHome() {
        assertEquals(Destinations.HOME, currentRoute())
    }

    @Test
    fun homeToPuzzle0ToResult0ToPuzzle1_leavesOnlyHomeAndCurrentPuzzleInStack() {
        composeTestRule.onNodeWithTag("home_play_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.PUZZLE, currentRoute())

        composeTestRule.onNodeWithTag("puzzle_submit_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.PUZZLE_RESULT, currentRoute())

        composeTestRule.onNodeWithTag("puzzle_result_next_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.PUZZLE, currentRoute())

        val stack = currentBackStackRoutes()
        assertEquals(listOf(Destinations.HOME, Destinations.PUZZLE), stack)
    }

    @Test
    fun answeredPuzzleIsRemovedFromBackStack() {
        composeTestRule.onNodeWithTag("home_play_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("puzzle_submit_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("puzzle_result_next_button").performClick()
        composeTestRule.waitForIdle()

        val stack = currentBackStackRoutes()
        assertFalse("puzzle/0 must not remain reachable", stack.contains(Destinations.PUZZLE) && stack.size > 2)
        assertFalse("puzzle/0/result must be gone after moving to the next puzzle", stack.contains(Destinations.PUZZLE_RESULT))
        assertEquals(2, stack.size)
    }

    @Test
    fun systemBackFromPuzzleResultLandsOnHome() {
        composeTestRule.onNodeWithTag("home_play_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("puzzle_submit_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.PUZZLE_RESULT, currentRoute())

        Espresso.pressBackUnconditionally()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.HOME, currentRoute())
    }

    @Test
    fun fullDayChainReachesRecapTodayAndSystemBackLandsOnHome() {
        composeTestRule.onNodeWithTag("home_play_button").performClick()
        composeTestRule.waitForIdle()

        // Puzzle(0) -> Result(0) -> Puzzle(1) -> Result(1) -> Puzzle(2) -> Result(2) -> recap/today
        repeat(3) { index ->
            composeTestRule.onNodeWithTag("puzzle_submit_button").performClick()
            composeTestRule.waitForIdle()
            assertEquals(Destinations.PUZZLE_RESULT, currentRoute())

            composeTestRule.onNodeWithTag("puzzle_result_next_button").performClick()
            composeTestRule.waitForIdle()

            if (index < 2) {
                assertEquals(Destinations.PUZZLE, currentRoute())
            }
        }

        assertEquals(Destinations.RECAP, currentRoute())
        assertEquals(listOf(Destinations.HOME, Destinations.RECAP), currentBackStackRoutes())

        Espresso.pressBackUnconditionally()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.HOME, currentRoute())
    }

    @Test
    fun doneButtonOnTodayRecapReturnsToExistingHomeInstance() {
        composeTestRule.onNodeWithTag("home_play_button").performClick()
        composeTestRule.waitForIdle()
        repeat(3) {
            composeTestRule.onNodeWithTag("puzzle_submit_button").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("puzzle_result_next_button").performClick()
            composeTestRule.waitForIdle()
        }
        assertEquals(Destinations.RECAP, currentRoute())

        composeTestRule.onNodeWithTag("recap_primary_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals(Destinations.HOME, currentRoute())
        // Ровно один Home в стеке — кнопка "Готово" не создаёт второй экземпляр.
        assertEquals(1, currentBackStackRoutes().count { it == Destinations.HOME })
    }

    @Test
    fun homeToArchiveToRecapByIsoDate_backReturnsToArchive() {
        composeTestRule.onNodeWithTag("home_archive_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.ARCHIVE, currentRoute())

        composeTestRule.onNodeWithTag("archive_open_recap_row").performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.RECAP, currentRoute())

        val date = navController.currentBackStackEntry
            ?.arguments
            ?.getString(Destinations.ARG_DATE)
        assertTrue("archive recap date must be ISO yyyy-MM-dd", date != null && ISO_DATE_REGEX.matches(date))
        assertFalse("archive recap date must not be the today sentinel", date == Destinations.TODAY)

        assertEquals(listOf(Destinations.HOME, Destinations.ARCHIVE, Destinations.RECAP), currentBackStackRoutes())

        // Экранная кнопка "Назад" возвращает в Archive без пересоздания экземпляра.
        composeTestRule.onNodeWithTag("recap_back_to_archive_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.ARCHIVE, currentRoute())
    }

    @Test
    fun systemBackFromArchiveRecapReturnsToArchive() {
        composeTestRule.onNodeWithTag("home_archive_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("archive_open_recap_row").performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.RECAP, currentRoute())

        Espresso.pressBackUnconditionally()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.ARCHIVE, currentRoute())
    }

    @Test
    fun homeToSettingsToHome() {
        composeTestRule.onNodeWithTag("home_settings_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.SETTINGS, currentRoute())

        composeTestRule.onNodeWithTag("stub_generic_back_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.HOME, currentRoute())
    }

    private companion object {
        val ISO_DATE_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}
