package ru.poporyadku

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.poporyadku.core.model.ContentPack
import ru.poporyadku.core.model.SLOTS_PER_DAY
import ru.poporyadku.ui.home.HomeTestTags
import ru.poporyadku.ui.navigation.Destinations
import ru.poporyadku.ui.puzzle.PuzzleTestTags
import ru.poporyadku.ui.recap.DayRecapTestTags

/**
 * Сквозной день на НАСТОЯЩЕМ контенте — ITERATION_4_DESIGN.md, `I4-E1`, `I4-E2`
 * (продолжение `I3-E1`) и `I3-E2`.
 *
 * `I4-E1` проходит продуктовый путь целиком: чистая база → приложение само вызывает
 * установку при первом расчёте Home (`assets → ContentPackReader → ContentValidator →
 * ContentImporter → Room`) → три головоломки → итог дня. Импортёр тест не вызывает ни до
 * запуска, ни после — он только наблюдает результат через продуктовые репозитории.
 *
 * Отсутствие пакета ДО запуска намеренно не утверждается: `MainActivity` правила уже
 * запущена и сама пересчитывает Home после очистки базы в `@Before` — это тот же
 * продуктовый путь, но момент его завершения тесту не подконтролен.
 *
 * `I4-E2`: содержимое дня тест не знает заранее. Набор берётся из назначения, которое
 * создало приложение, головоломки — из `PuzzleRepository` (`DebugGraphEntryPoint`),
 * правильный порядок — `correctOrder` импортированной `Puzzle`, стартовый — настоящий
 * `DeterministicShuffler`. Литералов контента в тесте нет.
 *
 * База очищается до и после каждого теста через `DebugGraphEntryPoint` — тот же
 * продуктовый граф, что у работающего приложения.
 */
@RunWith(AndroidJUnit4::class)
class FullDayFlowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private val driver by lazy { DayFlowDriver(composeTestRule) }

    @Before
    fun resetDatabase() = driver.clearDatabase()

    @After
    fun cleanUp() = driver.clearDatabase()

    /**
     * `I4-E1` + `I4-E2`. Чистая база → настоящий импорт через запуск приложения → Home
     * `FirstRun` → три импортированные головоломки, каждая доведена кнопками до
     * правильного порядка → три `PuzzleResult` «6 из 6» → `DayRecap` «18 из 18» →
     * «Готово» → Home в состоянии `Completed`.
     */
    @Test
    fun i4E1CleanDatabaseImportsTheRealPackAndTheFirstDayEndsWith18Of18() {
        driver.startApp()
        driver.awaitHomeCta()

        // Импорт выполнило само приложение — на продуктовом пути первого расчёта Home —
        // и установлен весь пакет альфы, а не три набора временной фикстуры.
        assertNotNull("первый набор установлен", set(0))
        assertNotNull("последний набор пакета установлен", set(RELEASE_SET_COUNT - 1))
        assertNull("за последним набором пакета наборов нет", set(RELEASE_SET_COUNT))
        composeTestRule.onNodeWithText(HOME_CTA_START).assertExists()

        val setIndex = driver.playTodayThroughTheUi()
        assertEquals("чистая установка начинает с первого набора", 0, setIndex)
        assertEquals("день закрыт ровно тремя попытками", SLOTS_PER_DAY, driver.attemptCount())

        composeTestRule.onNodeWithTag(DayRecapTestTags.DONE_BUTTON).performClick()
        driver.awaitRoute(Destinations.HOME)

        // Completed: основное действие Home — «Посмотреть итог», а не «Играть».
        driver.awaitHomeCta()
        composeTestRule.onNodeWithText(HOME_CTA_VIEW_RECAP).assertExists()
    }

    /**
     * `I3-E2`. После первого результата системная «назад» ведёт на Home, а повторный
     * вход в уже закрытый слот немедленно показывает его результат — переиграть нельзя
     * и второй попытки не создаётся.
     *
     * Прямая навигация здесь — единственный способ воспроизвести «повторный вход в
     * маршрут»: из UI закрытый слот больше не открывается ничем.
     */
    @Test
    fun i3E2BackDoesNotAllowReplayingAClosedSlot() {
        driver.startApp()

        driver.awaitHomeCta()
        composeTestRule.onNodeWithTag(HomeTestTags.PRIMARY_BUTTON).performClick()
        driver.awaitRoute(Destinations.PUZZLE)
        val sessionDate = driver.currentDate()!!

        driver.awaitCards()
        composeTestRule.onNodeWithTag(PuzzleTestTags.SUBMIT_BUTTON).performClick()
        driver.awaitRoute(Destinations.PUZZLE_RESULT)

        Espresso.pressBackUnconditionally()
        driver.awaitRoute(Destinations.HOME)

        composeTestRule.activity.runOnUiThread {
            driver.navController.navigate("puzzle/0?date=$sessionDate")
        }
        driver.awaitRoute(Destinations.PUZZLE_RESULT)

        assertEquals(0, driver.currentSlotIndex())
        composeTestRule.onNodeWithTag(PuzzleTestTags.CARD_LIST).assertDoesNotExist()
        assertEquals("повторный вход не создаёт второй попытки", 1, driver.attemptCount())
    }

    private fun set(setIndex: Int) = runBlocking { driver.deps.sets().getSet(ContentPack.CORE_RU, setIndex) }

    private companion object {
        /** Критерий релиза пакета альфы (`I4-C4`); здесь — признак настоящего пакета. */
        const val RELEASE_SET_COUNT = 35

        const val HOME_CTA_START = "Начать"
        const val HOME_CTA_VIEW_RECAP = "Посмотреть итог"
    }
}
