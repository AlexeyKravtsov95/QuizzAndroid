package ru.poporyadku.ui.navigation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.testing.TestNavHostController
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
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
 * `TestNavHostController` внутри дерева — это хостинг теста, а не имитация пользователя;
 * ни один `@Test` не вызывает `navigate()` напрямую.
 *
 * `NavController.currentBackStack` не используется — это `@RestrictTo(LIBRARY_GROUP)` API.
 * Единственное чтение состояния, которое допускают тесты, — `currentBackStackEntry`
 * (текущий route и его аргументы), и то только для проверки, а не для того, чтобы
 * заглянуть во внутренний список записей. Очистка предыдущих экранов из бэкстека
 * доказывается наблюдаемо: одно нажатие системного «назад» (или экранной кнопки) сразу
 * приводит к ожидаемому экрану — если бы промежуточные записи оставались в стеке, потребовалось
 * бы больше одного нажатия или результат отличался бы от ожидаемого.
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

    @Test
    fun startDestinationIsHome() {
        assertEquals(Destinations.HOME, currentRoute())
    }

    @Test
    fun homeToPuzzle0ToResult0ToPuzzle1() {
        composeTestRule.onNodeWithTag("home_play_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.PUZZLE, currentRoute())

        composeTestRule.onNodeWithTag("puzzle_submit_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.PUZZLE_RESULT, currentRoute())

        composeTestRule.onNodeWithTag("puzzle_result_next_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.PUZZLE, currentRoute())
    }

    /**
     * Puzzle следующего задания -> Back -> Home. Если бы Puzzle(0)/PuzzleResult(0) оставались
     * в стеке под Puzzle(1), одного системного «назад» не хватило бы, чтобы попасть на Home.
     */
    @Test
    fun systemBackFromNextPuzzleLandsOnHome() {
        composeTestRule.onNodeWithTag("home_play_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("puzzle_submit_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("puzzle_result_next_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.PUZZLE, currentRoute())

        Espresso.pressBackUnconditionally()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.HOME, currentRoute())
    }

    /** PuzzleResult -> Back -> Home (UX_FLOW.md §5: «Вернуться в отвеченную головоломку нельзя»). */
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

    /**
     * Полная цепочка до recap/today, затем recap/today -> Back -> Home. Одно нажатие «назад»
     * доказывает, что весь `dailySession` (Puzzle/PuzzleResult трёх заданий) вычищен из стека —
     * иначе системный back вернул бы на PuzzleResult(2), а не на Home.
     */
    @Test
    fun fullDayChainReachesRecapTodayAndSystemBackLandsOnHome() {
        composeTestRule.onNodeWithTag("home_play_button").performClick()
        composeTestRule.waitForIdle()

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

        Espresso.pressBackUnconditionally()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.HOME, currentRoute())
    }

    @Test
    fun doneButtonOnTodayRecapReturnsToHome() {
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
    }

    @Test
    fun homeToArchiveToRecapByIsoDate() {
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
        assertTrue("archive recap date must not be the today sentinel", date != Destinations.TODAY)
    }

    /** recap архивной даты -> Back (экранная кнопка) -> Archive. */
    @Test
    fun onScreenBackFromArchiveRecapReturnsToArchive() {
        composeTestRule.onNodeWithTag("home_archive_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("archive_open_recap_row").performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.RECAP, currentRoute())

        composeTestRule.onNodeWithTag("recap_back_to_archive_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.ARCHIVE, currentRoute())
    }

    /** recap архивной даты -> Back (системная кнопка) -> Archive. */
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
