package ru.poporyadku.ui.navigation

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.poporyadku.MainActivity
import ru.poporyadku.ui.home.HomeTestTags
import ru.poporyadku.ui.recap.DayRecapTestTags
import ru.poporyadku.ui.theme.PoPoRyadkuTheme

/**
 * Навигация — `UX_FLOW.md` §1 и ITERATION_3_DESIGN.md, `I3-N1`, `I3-N6`, `I3-N7`.
 *
 * После PR 3C `Home` и `DayRecap` — настоящие экраны с `hiltViewModel()`, поэтому
 * граф размещается внутри `MainActivity` (единственной `@AndroidEntryPoint`-активности
 * приложения): Hilt-граф берётся у настоящего `PoPoRyadkuApp`, и отдельная тестовая
 * инфраструктура DI не заводится.
 *
 * Пользовательский поток управляется исключительно кликами по реальному UI
 * (`performClick`); возврат — системной (`Espresso.pressBackUnconditionally`) или
 * экранной кнопкой «Назад». Ни один `@Test` не вызывает `navController.navigate(...)`
 * напрямую — `setUp()` лишь размещает `TestNavHostController` внутри `AppNavHost`.
 *
 * `NavController.currentBackStack` не используется — это `@RestrictTo(LIBRARY_GROUP)`
 * API. Единственное чтение состояния, которое допускают тесты, —
 * `currentBackStackEntry` (текущий route и его аргументы). Очистка предыдущих экранов
 * доказывается наблюдаемо: одно нажатие «назад» сразу приводит к ожидаемому экрану.
 *
 * **Предусловие цепочки заглушек.** Сценарии, начинающиеся с основной кнопки Home,
 * требуют устройства, на котором сегодняшний день ещё не завершён: у завершённого дня
 * та же кнопка подписана «Посмотреть итог» и ведёт в `recap`, а не в задание. Это
 * свойство продукта, а не теста; тесты `I3-N` выполняются вручную (`ARCHITECTURE.md` §9).
 */
@RunWith(AndroidJUnit4::class)
class AppNavHostTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private lateinit var navController: TestNavHostController

    @Before
    fun setUp() {
        composeTestRule.activity.runOnUiThread {
            navController = TestNavHostController(composeTestRule.activity).apply {
                navigatorProvider.addNavigator(ComposeNavigator())
            }
            composeTestRule.activity.setContent {
                PoPoRyadkuTheme {
                    AppNavHost(navController = navController)
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun currentRoute(): String? = navController.currentBackStackEntry?.destination?.route

    private fun currentSlotIndex(): Int? =
        navController.currentBackStackEntry?.arguments?.getInt(Destinations.ARG_SLOT_INDEX)

    private fun currentDate(): String? =
        navController.currentBackStackEntry?.arguments?.getString(Destinations.ARG_DATE)

    /** `I3-N1`. Стартовый экран — настоящий `Home`, а не заглушка итерации 1. */
    @Test
    fun startDestinationIsRealHome() {
        assertEquals(Destinations.HOME, currentRoute())
        composeTestRule.onNodeWithTag(HomeTestTags.SCREEN).assertExists()
    }

    /**
     * `Home` → `puzzle/{slotIndex}?date=` → `puzzle/{slotIndex}/result?date=` →
     * `puzzle/{slotIndex + 1}?date=`: аргументы доезжают неизменными.
     *
     * Настоящих игровых экранов PR 3C не добавляет — переходы идут по заглушкам.
     */
    @Test
    fun homeToPuzzle0ToResult0ToPuzzle1CarriesSlotIndexAndDate() {
        composeTestRule.onNodeWithTag(HomeTestTags.PRIMARY_BUTTON).performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.PUZZLE, currentRoute())

        val sessionDate = currentDate()
        assertNotNull("сессионная дата обязана доехать в маршрут", sessionDate)
        assertTrue(
            "дата маршрута — ISO yyyy-MM-dd, а не сентинел",
            ISO_DATE_REGEX.matches(sessionDate!!),
        )
        assertEquals(0, currentSlotIndex())

        composeTestRule.onNodeWithTag("puzzle_submit_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.PUZZLE_RESULT, currentRoute())
        assertEquals(0, currentSlotIndex())
        assertEquals("дата переносится в результат без изменений", sessionDate, currentDate())

        composeTestRule.onNodeWithTag("puzzle_result_next_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.PUZZLE, currentRoute())
        assertEquals(1, currentSlotIndex())
        assertEquals("дата переносится в следующий слот без изменений", sessionDate, currentDate())
    }

    /**
     * Puzzle следующего задания → Back → Home. Если бы `Puzzle(0)`/`PuzzleResult(0)`
     * оставались в стеке, одного системного «назад» не хватило бы.
     */
    @Test
    fun systemBackFromNextPuzzleLandsOnHome() {
        composeTestRule.onNodeWithTag(HomeTestTags.PRIMARY_BUTTON).performClick()
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

    /** `PuzzleResult` → Back → Home (UX_FLOW.md §5: вернуться в отвеченное задание нельзя). */
    @Test
    fun systemBackFromPuzzleResultLandsOnHome() {
        composeTestRule.onNodeWithTag(HomeTestTags.PRIMARY_BUTTON).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("puzzle_submit_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.PUZZLE_RESULT, currentRoute())

        Espresso.pressBackUnconditionally()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.HOME, currentRoute())
    }

    /**
     * Полная цепочка до `recap/{ISO}`, затем `recap` → Back → Home. Одно нажатие
     * «назад» доказывает, что весь граф сессии вычищен из стека.
     */
    @Test
    fun fullDayChainReachesRecapByIsoDateAndSystemBackLandsOnHome() {
        composeTestRule.onNodeWithTag(HomeTestTags.PRIMARY_BUTTON).performClick()
        composeTestRule.waitForIdle()
        val sessionDate = currentDate()

        repeat(3) { index ->
            composeTestRule.onNodeWithTag("puzzle_submit_button").performClick()
            composeTestRule.waitForIdle()
            assertEquals(Destinations.PUZZLE_RESULT, currentRoute())

            composeTestRule.onNodeWithTag("puzzle_result_next_button").performClick()
            composeTestRule.waitForIdle()

            if (index < 2) {
                assertEquals(Destinations.PUZZLE, currentRoute())
                assertEquals(index + 1, currentSlotIndex())
            }
        }

        assertEquals(Destinations.RECAP, currentRoute())
        // Сентинела today больше нет: recap всегда получает явную ISO-дату (I3-D23).
        assertEquals("дата сессии доезжает до итога", sessionDate, currentDate())

        Espresso.pressBackUnconditionally()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.HOME, currentRoute())
    }

    /** `I3-N6`. «Готово» на настоящем `recap` возвращает на существующий `Home`. */
    @Test
    fun doneButtonOnRecapReturnsToExistingHome() {
        composeTestRule.onNodeWithTag(HomeTestTags.PRIMARY_BUTTON).performClick()
        composeTestRule.waitForIdle()
        repeat(3) {
            composeTestRule.onNodeWithTag("puzzle_submit_button").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("puzzle_result_next_button").performClick()
            composeTestRule.waitForIdle()
        }
        assertEquals(Destinations.RECAP, currentRoute())

        composeTestRule.onNodeWithTag(DayRecapTestTags.DONE_BUTTON).performClick()
        composeTestRule.waitForIdle()

        assertEquals(Destinations.HOME, currentRoute())
        // Системная навигация не создала второго Home: экран тот же самый.
        composeTestRule.onNodeWithTag(HomeTestTags.SCREEN).assertExists()
    }

    /**
     * `I3-N7`. Переход из архива передаёт ISO-дату. Утверждение про сентинел `TODAY`
     * удалено вместе с самим сентинелом (I3-D23).
     */
    /**
     * Предусловие: иконка «Архив» видна только при `completedDayCount > 0`
     * (COMPONENTS.md), то есть на устройстве должен быть хотя бы один завершённый день.
     */
    @Test
    fun homeToArchiveToRecapByIsoDate() {
        composeTestRule.onNodeWithContentDescription(ARCHIVE).performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.ARCHIVE, currentRoute())

        composeTestRule.onNodeWithTag("archive_open_recap_row").performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.RECAP, currentRoute())

        val date = currentDate()
        assertTrue(
            "archive recap date must be ISO yyyy-MM-dd",
            date != null && ISO_DATE_REGEX.matches(date),
        )
    }

    @Test
    fun homeToSettingsToHome() {
        composeTestRule.onNodeWithContentDescription(SETTINGS).performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.SETTINGS, currentRoute())

        composeTestRule.onNodeWithTag("stub_generic_back_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.HOME, currentRoute())
    }

    private companion object {
        val ISO_DATE_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")
        const val ARCHIVE = "Архив"
        const val SETTINGS = "Настройки"
    }
}
