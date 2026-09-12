package ru.poporyadku

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.ext.junit.rules.ActivityScenarioRule
import dagger.hilt.android.EntryPointAccessors
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import ru.poporyadku.core.model.Puzzle
import ru.poporyadku.core.model.SLOTS_PER_DAY
import ru.poporyadku.core.model.puzzleIdAt
import ru.poporyadku.debug.DebugGraphEntryPoint
import ru.poporyadku.domain.shuffle.DeterministicShuffler
import ru.poporyadku.ui.components.OrderableCardTestTags
import ru.poporyadku.ui.feedback.FeedbackCue
import ru.poporyadku.ui.feedback.FeedbackPlayer
import ru.poporyadku.ui.feedback.FeedbackRequest
import ru.poporyadku.ui.feedback.LocalFeedbackPlayerOverride
import ru.poporyadku.ui.home.HomeTestTags
import ru.poporyadku.ui.navigation.AppNavHost
import ru.poporyadku.ui.navigation.Destinations
import ru.poporyadku.ui.puzzle.PuzzleTestTags
import ru.poporyadku.ui.puzzleresult.PuzzleResultTestTags
import ru.poporyadku.ui.theme.PoPoRyadkuTheme

/**
 * Общие шаги сквозных тестов на настоящем приложении — `FullDayFlowTest` и
 * `HardCutoverTest` (ITERATION_4_DESIGN.md, `I4-E1`–`I4-E3`).
 *
 * Граф размещается внутри `MainActivity`, доступ к продуктовым синглтонам даёт
 * `DebugGraphEntryPoint` из `src/debug`: новой тестовой инфраструктуры DI не заводится.
 * Содержимое дня тест не знает заранее — он читает его из того, что приложение само
 * импортировало из `assets`, через продуктовые `DailySetRepository`/`PuzzleRepository`
 * (`I4-E2`). Литералов контента здесь нет.
 */
internal class DayFlowDriver(
    private val rule: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>,
) {

    lateinit var navController: TestNavHostController
        private set

    /**
     * Сколько фактических перестановок выполнил тест нажатиями «вверх» (`I5-N8`).
     *
     * Считается по совершённым нажатиям, а не по формуле: с этим числом сравнивается
     * число `CardMoved`, дошедших до исполнителя отдачи.
     */
    var performedReorders: Int = 0
        private set

    val deps: DebugGraphEntryPoint by lazy {
        EntryPointAccessors.fromApplication(
            rule.activity.applicationContext,
            DebugGraphEntryPoint::class.java,
        )
    }

    fun clearDatabase() = runBlocking {
        withContext(Dispatchers.IO) { deps.database().clearAllTables() }
    }

    fun attemptCount(): Int = runBlocking {
        withContext(Dispatchers.IO) { deps.database().attemptDao().observeAll().first().size }
    }

    /**
     * @param feedbackPlayer подставленный исполнитель отдачи (`I5-N8`): настоящий звук и
     * тактильная отдача в сквозном тесте не нужны, а проверять надо вызовы. `null` —
     * поведение по умолчанию: `PuzzleRoute` строит `AndroidFeedbackPlayer`, а звуков в
     * этой композиции нет, потому что `LocalSoundCues` здесь никто не предоставляет.
     */
    fun startApp(feedbackPlayer: FeedbackPlayer? = null) {
        rule.activity.runOnUiThread {
            navController = TestNavHostController(rule.activity).apply {
                navigatorProvider.addNavigator(ComposeNavigator())
            }
            rule.activity.setContent {
                PoPoRyadkuTheme {
                    CompositionLocalProvider(LocalFeedbackPlayerOverride provides feedbackPlayer) {
                        AppNavHost(navController = navController)
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    fun currentRoute(): String? = navController.currentBackStackEntry?.destination?.route

    fun currentSlotIndex(): Int? =
        navController.currentBackStackEntry?.arguments?.getInt(Destinations.ARG_SLOT_INDEX)

    fun currentDate(): String? =
        navController.currentBackStackEntry?.arguments?.getString(Destinations.ARG_DATE)

    fun awaitRoute(route: String) {
        rule.waitUntil(ROUTE_TIMEOUT_MS) { currentRoute() == route }
        rule.waitForIdle()
    }

    /**
     * Home начинает с `Loading` и получает CTA только после расчёта, а на чистой базе
     * расчёт включает полный импорт пакета — поэтому потолок ожидания шире, чем у
     * переходов между экранами. Это потолок, а не пауза: ожидание заканчивается, как
     * только кнопка появилась.
     */
    fun awaitHomeCta() = awaitTag(HomeTestTags.PRIMARY_BUTTON, HOME_TIMEOUT_MS)

    fun awaitCards() = awaitTag(PuzzleTestTags.CARD_LIST, ROUTE_TIMEOUT_MS)

    fun awaitTag(tag: String, timeoutMs: Long = HOME_TIMEOUT_MS) {
        rule.waitUntil(timeoutMs) {
            rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
    }

    /**
     * Три головоломки дня в порядке слотов — из НАСТОЯЩЕГО назначения на [date]
     * и импортированного пакета, через продуктовые репозитории (`I4-E2`).
     */
    fun importedDay(date: LocalDate): Pair<Int, List<Puzzle>> = runBlocking {
        val assignment = requireNotNull(deps.assignments().getAssignment(date)) {
            "на $date нет назначения"
        }
        val set = requireNotNull(deps.sets().getSet(assignment.packId, assignment.setIndex)) {
            "набор ${assignment.setIndex} не установлен"
        }
        assignment.setIndex to (0 until SLOTS_PER_DAY).map { slot ->
            val puzzleId = set.puzzleIdAt(slot)
            requireNotNull(deps.puzzles().getPuzzle(puzzleId)) { "'$puzzleId' не установлена" }
        }
    }

    /**
     * Home (CTA уже виден) → три головоломки, каждая доведена кнопками до правильного
     * порядка → три `PuzzleResult` «6 из 6» → `DayRecap` «18 из 18». Ни одна попытка не
     * создаётся напрямую: каждая появляется от нажатия «Проверить» на настоящем экране.
     *
     * @return индекс сыгранного набора — из назначения, которое создало приложение.
     */
    fun playTodayThroughTheUi(): Int {
        rule.onNodeWithTag(HomeTestTags.PRIMARY_BUTTON).performClick()
        awaitRoute(Destinations.PUZZLE)
        val (setIndex, puzzles) = importedDay(LocalDate.parse(requireNotNull(currentDate())))

        puzzles.forEachIndexed { slotIndex, puzzle ->
            awaitCards()
            assertEquals(slotIndex, currentSlotIndex())
            // На экране — именно эта импортированная головоломка.
            rule.onNodeWithText(puzzle.prompt).assertExists()

            sortIntoCorrectOrder(puzzle)
            rule.onNodeWithTag(PuzzleTestTags.SUBMIT_BUTTON).performClick()

            awaitRoute(Destinations.PUZZLE_RESULT)
            assertEquals(slotIndex, currentSlotIndex())
            // Правильный порядок собран целиком: шесть пар из шести.
            rule.onNodeWithText(PERFECT_SLOT_SCORE).performScrollTo().assertExists()

            rule.onNodeWithTag(PuzzleResultTestTags.PRIMARY_BUTTON).performClick()
        }

        awaitRoute(Destinations.RECAP)
        rule.onNodeWithText(PERFECT_DAY_SCORE).assertExists()
        return setIndex
    }

    /**
     * Доводит текущую головоломку до правильного порядка одними кнопками «вверх».
     *
     * Стартовый порядок на экране известен заранее: `GetPuzzleUseCase` отдаёт
     * `DeterministicShuffler.shuffle(puzzleId, cardIds)`, а `SavedStateHandle` пуст.
     * Правильный порядок — `correctOrder` импортированной головоломки. Тест ведёт ту же
     * модель списка, что и ViewModel, поэтому каждое нажатие адресуется по `cardId`.
     */
    fun sortIntoCorrectOrder(puzzle: Puzzle) {
        val current = DeterministicShuffler
            .shuffle(puzzle.puzzleId, puzzle.cards.map { it.cardId })
            .toMutableList()

        puzzle.correctOrder.forEachIndexed { targetIndex, cardId ->
            while (current.indexOf(cardId) > targetIndex) {
                val at = current.indexOf(cardId)
                // Список ленивый: при крупном системном шрифте нижние карточки ещё не
                // созданы, и до кнопки надо доскроллить, а не искать её в дереве.
                rule.onNodeWithTag(PuzzleTestTags.CARD_LIST)
                    .performScrollToNode(hasTestTag(OrderableCardTestTags.moveUp(cardId)))
                rule.onNodeWithTag(OrderableCardTestTags.moveUp(cardId)).performClick()
                rule.waitForIdle()
                current[at] = current[at - 1]
                current[at - 1] = cardId
                performedReorders++
            }
        }
        assertEquals("скрипт обязан привести список к правильному порядку", puzzle.correctOrder, current)
    }

    /**
     * Исполнитель отдачи, который только записывает запросы (`I5-N8`).
     *
     * Ни `SoundPool`, ни `performHapticFeedback`: тест утверждает, сколько раз и что
     * именно подтверждалось, а не то, что устройство издало звук.
     */
    class RecordingFeedbackPlayer : FeedbackPlayer {

        private val requests = mutableListOf<FeedbackRequest>()

        override fun play(request: FeedbackRequest) {
            synchronized(requests) { requests += request }
        }

        fun countOf(cue: FeedbackCue): Int = synchronized(requests) { requests.count { it.cue == cue } }
    }

    companion object {
        /** Переходы между экранами читают базу; секунд с запасом хватает и эмулятору. */
        const val ROUTE_TIMEOUT_MS = 5_000L

        /** Первый расчёт Home на чистой базе включает полный импорт пакета. */
        const val HOME_TIMEOUT_MS = 20_000L

        const val PERFECT_SLOT_SCORE = "6 из 6"
        const val PERFECT_DAY_SCORE = "18 из 18"
    }
}
