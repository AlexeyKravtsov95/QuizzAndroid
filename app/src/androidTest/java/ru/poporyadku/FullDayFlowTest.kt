package ru.poporyadku

import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.poporyadku.core.model.Puzzle
import ru.poporyadku.core.model.SLOTS_PER_DAY
import ru.poporyadku.data.content.temporary.BundledPuzzles
import ru.poporyadku.debug.DebugGraphEntryPoint
import ru.poporyadku.domain.shuffle.DeterministicShuffler
import ru.poporyadku.ui.components.OrderableCardTestTags
import ru.poporyadku.ui.home.HomeTestTags
import ru.poporyadku.ui.navigation.AppNavHost
import ru.poporyadku.ui.navigation.Destinations
import ru.poporyadku.ui.puzzle.PuzzleTestTags
import ru.poporyadku.ui.puzzleresult.PuzzleResultTestTags
import ru.poporyadku.ui.recap.DayRecapTestTags
import ru.poporyadku.ui.theme.PoPoRyadkuTheme

/**
 * Сквозной день — ITERATION_3_DESIGN.md, `I3-E1` и `I3-E2`.
 *
 * `I3-E1` проходит день **пользовательским путём**: ни одна попытка не создаётся
 * тестом напрямую, каждая появляется от нажатия «Проверить» на настоящем экране.
 * Скрипт перемещений детерминирован: стартовый порядок задаёт `DeterministicShuffler`
 * по фактическому `puzzleId`, правильный — тот же временный контент, что видит
 * приложение, и оба известны на этапе компиляции.
 *
 * База очищается до и после каждого теста через `DebugGraphEntryPoint` — тот же
 * продуктовый граф, что у работающего приложения; новой тестовой инфраструктуры DI
 * не заводится.
 */
@RunWith(AndroidJUnit4::class)
class FullDayFlowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private lateinit var navController: TestNavHostController

    private val deps: DebugGraphEntryPoint by lazy {
        EntryPointAccessors.fromApplication(
            composeTestRule.activity.applicationContext,
            DebugGraphEntryPoint::class.java,
        )
    }

    @Before
    fun resetDatabase() = clearDatabase()

    @After
    fun cleanUp() = clearDatabase()

    /**
     * `I3-E1`. Чистая база → Home → три настоящие головоломки, каждая доведена
     * кнопками перемещения до правильного порядка → три `PuzzleResult` по «6 из 6» →
     * `DayRecap` «18 из 18» → «Готово» → Home в состоянии `Completed`.
     */
    @Test
    fun i3E1FullDayPlayedThroughTheUiEndsWith18Of18() {
        startApp()

        awaitHomeCta()
        composeTestRule.onNodeWithTag(HomeTestTags.PRIMARY_BUTTON).performClick()
        awaitRoute(Destinations.PUZZLE)

        dayOnePuzzles.forEachIndexed { slotIndex, puzzle ->
            awaitCards()
            assertEquals(slotIndex, currentSlotIndex())

            sortIntoCorrectOrder(puzzle)
            composeTestRule.onNodeWithTag(PuzzleTestTags.SUBMIT_BUTTON).performClick()

            awaitRoute(Destinations.PUZZLE_RESULT)
            assertEquals(slotIndex, currentSlotIndex())
            // Правильный порядок собран целиком: шесть пар из шести.
            composeTestRule.onNodeWithText(PERFECT_SLOT_SCORE).performScrollTo().assertExists()

            composeTestRule.onNodeWithTag(PuzzleResultTestTags.PRIMARY_BUTTON).performClick()
        }

        awaitRoute(Destinations.RECAP)
        composeTestRule.onNodeWithText(PERFECT_DAY_SCORE).assertExists()
        assertEquals("день закрыт ровно тремя попытками", SLOTS_PER_DAY, attemptCount())

        composeTestRule.onNodeWithTag(DayRecapTestTags.DONE_BUTTON).performClick()
        awaitRoute(Destinations.HOME)

        // Completed: основное действие Home — «Посмотреть итог», а не «Играть».
        awaitHomeCta()
        composeTestRule.onNodeWithText(HOME_CTA_VIEW_RECAP).assertExists()
    }

    /**
     * `I3-E2`. После первого результата системная «назад» ведёт на Home, а повторный
     * вход в уже закрытый слот немедленно показывает его результат — переиграть нельзя.
     *
     * Прямая навигация здесь — единственный способ воспроизвести «повторный вход в
     * маршрут»: из UI закрытый слот больше не открывается ничем.
     */
    @Test
    fun i3E2BackDoesNotAllowReplayingAClosedSlot() {
        startApp()

        awaitHomeCta()
        composeTestRule.onNodeWithTag(HomeTestTags.PRIMARY_BUTTON).performClick()
        awaitRoute(Destinations.PUZZLE)
        val sessionDate = currentDate()!!

        awaitCards()
        composeTestRule.onNodeWithTag(PuzzleTestTags.SUBMIT_BUTTON).performClick()
        awaitRoute(Destinations.PUZZLE_RESULT)

        Espresso.pressBackUnconditionally()
        awaitRoute(Destinations.HOME)

        composeTestRule.activity.runOnUiThread {
            navController.navigate("puzzle/0?date=$sessionDate")
        }
        awaitRoute(Destinations.PUZZLE_RESULT)

        assertEquals(0, currentSlotIndex())
        composeTestRule.onNodeWithTag(PuzzleTestTags.CARD_LIST).assertDoesNotExist()
        assertEquals("повторный вход не создаёт второй попытки", 1, attemptCount())
    }

    // --- Скрипт перемещений ---------------------------------------------------------

    /**
     * Доводит текущую головоломку до правильного порядка одними кнопками «вверх».
     *
     * Порядок карточек на экране известен заранее: `GetPuzzleUseCase` отдаёт
     * `DeterministicShuffler.shuffle(puzzleId, cardIds)`, а `SavedStateHandle` пуст.
     * Тест ведёт ту же модель списка, что и ViewModel, поэтому каждое нажатие
     * адресуется по `cardId`, а не по позиции на экране.
     */
    private fun sortIntoCorrectOrder(puzzle: Puzzle) {
        val current = DeterministicShuffler
            .shuffle(puzzle.puzzleId, puzzle.cards.map { it.cardId })
            .toMutableList()

        puzzle.correctOrder.forEachIndexed { targetIndex, cardId ->
            while (current.indexOf(cardId) > targetIndex) {
                val at = current.indexOf(cardId)
                // Список ленивый: при крупном системном шрифте нижние карточки ещё не
                // созданы, и до кнопки надо доскроллить, а не искать её в дереве.
                composeTestRule.onNodeWithTag(PuzzleTestTags.CARD_LIST)
                    .performScrollToNode(hasTestTag(OrderableCardTestTags.moveUp(cardId)))
                composeTestRule.onNodeWithTag(OrderableCardTestTags.moveUp(cardId)).performClick()
                composeTestRule.waitForIdle()
                current[at] = current[at - 1]
                current[at - 1] = cardId
            }
        }
        assertEquals("скрипт обязан привести список к правильному порядку", puzzle.correctOrder, current)
    }

    // --- Инфраструктура ---------------------------------------------------------------

    private fun clearDatabase() = runBlocking {
        withContext(Dispatchers.IO) { deps.database().clearAllTables() }
    }

    private fun attemptCount(): Int = runBlocking {
        withContext(Dispatchers.IO) { deps.database().attemptDao().observeAll().first().size }
    }

    private fun startApp() {
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

    private fun awaitRoute(route: String) {
        composeTestRule.waitUntil(TIMEOUT_MS) { currentRoute() == route }
        composeTestRule.waitForIdle()
    }

    /**
     * Home начинает с `Loading` и получает CTA только после чтения базы. Ждём именно
     * появления кнопки: при крупном системном шрифте первый кадр приходит заметно позже.
     */
    private fun awaitHomeCta() {
        composeTestRule.waitUntil(TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasTestTag(HomeTestTags.PRIMARY_BUTTON))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.waitForIdle()
    }

    private fun awaitCards() {
        composeTestRule.waitUntil(TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasTestTag(PuzzleTestTags.CARD_LIST))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.waitForIdle()
    }

    private companion object {
        /** Экраны читают базу; секунды хватает с запасом и на медленном эмуляторе. */
        const val TIMEOUT_MS = 5_000L

        const val PERFECT_SLOT_SCORE = "6 из 6"
        const val PERFECT_DAY_SCORE = "18 из 18"
        const val HOME_CTA_VIEW_RECAP = "Посмотреть итог"

        /**
         * Набор первого дня — `setIndex = 0` временного контента: география, история,
         * наука. Первый день чистой базы всегда получает именно его.
         */
        val dayOnePuzzles: List<Puzzle> = BundledPuzzles.sets
            .first { it.setIndex == 0 }
            .let { set -> listOf(set.puzzleId1, set.puzzleId2, set.puzzleId3) }
            .map { id -> BundledPuzzles.puzzles.first { it.puzzleId == id } }
    }
}
