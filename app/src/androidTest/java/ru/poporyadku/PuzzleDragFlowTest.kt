package ru.poporyadku

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.poporyadku.core.model.Puzzle
import ru.poporyadku.domain.shuffle.DeterministicShuffler
import ru.poporyadku.ui.components.OrderableCardTestTags
import ru.poporyadku.ui.feedback.FeedbackCue
import ru.poporyadku.ui.home.HomeTestTags
import ru.poporyadku.ui.navigation.Destinations
import ru.poporyadku.ui.puzzle.PuzzleTestTags

/**
 * `I6-N1`…`I6-N3` — перетаскивание на настоящем приложении (ITERATION_6_DESIGN.md, §14.1).
 *
 * Выполняются вручную на эмуляторе; в CI только компилируются (раздел 2.8 дизайна).
 * Событий `PuzzleEvent` тест не отправляет ни разу: он касается ручки, ведёт палец и
 * отпускает — остальное делают `DragHandle`, `ReorderDragState` и `PuzzleViewModel`.
 */
@RunWith(AndroidJUnit4::class)
class PuzzleDragFlowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private val driver by lazy { DayFlowDriver(composeTestRule) }

    @Before
    fun resetDatabase() = driver.clearDatabase()

    @After
    fun cleanUp() = driver.clearDatabase()

    /**
     * `I6-N1`. Настоящий жест на одну и на три позиции: итоговый порядок на экране, и в
     * записи отдачи `CardGrabbed` = числу захватов, `CardMoved` = числу подтверждённых
     * перестановок.
     */
    @Test
    fun i6N1RealDragMovesOneAndThreePositionsWithExactFeedback() {
        val feedback = DayFlowDriver.RecordingFeedbackPlayer()
        driver.startApp(feedback)
        driver.awaitHomeCta()

        val puzzle = openFirstPuzzle()
        val order = startOrderOf(puzzle).toMutableList()

        // Одна позиция вниз: верхняя карточка проходит центр второй.
        val first = order.first()
        dragByPositions(first, positions = 1)
        order.removeAt(0)
        order.add(1, first)
        assertOrderOnScreen(order)

        // Три позиции вверх одним жестом: карточка с конца встаёт первой.
        val last = order.last()
        dragByPositions(last, positions = -(order.size - 1))
        order.removeAt(order.lastIndex)
        order.add(0, last)
        assertOrderOnScreen(order)

        assertEquals("по одному захвату на жест", GRABS, feedback.countOf(FeedbackCue.CardGrabbed))
        assertEquals(
            "по одной отдаче на подтверждённую перестановку",
            CONFIRMED_REORDERS,
            feedback.countOf(FeedbackCue.CardMoved),
        )
    }

    /**
     * `I6-N2`, часть 1. Пересоздание Activity посреди жеста не порождает отдачи, а новый
     * жест той же карточки даёт ровно один `CardGrabbed`.
     *
     * Здесь содержимое ставит драйвер (ради подставленного исполнителя отдачи), поэтому
     * навигация после `recreate()` начинается заново — восстановление порядка проверяет
     * часть 2.
     */
    @Test
    fun i6N2RecreatingTheActivityMidDragProducesNoExtraFeedback() {
        val feedback = DayFlowDriver.RecordingFeedbackPlayer()
        driver.startApp(feedback)
        driver.awaitHomeCta()

        val puzzle = openFirstPuzzle()
        val moved = startOrderOf(puzzle).first()

        // Палец остаётся на экране: `up()` не отправляется.
        holdDrag(moved)
        val grabsBeforeRecreate = feedback.countOf(FeedbackCue.CardGrabbed)
        val movesBeforeRecreate = feedback.countOf(FeedbackCue.CardMoved)

        composeTestRule.activityRule.scenario.recreate()
        driver.startApp(feedback)
        driver.awaitHomeCta()

        assertEquals(
            "пересоздание отдачи не порождает",
            grabsBeforeRecreate to movesBeforeRecreate,
            feedback.countOf(FeedbackCue.CardGrabbed) to feedback.countOf(FeedbackCue.CardMoved),
        )

        // Новый жест той же карточки в новом UI: ровно один новый захват.
        openFirstPuzzle()
        dragByPositions(moved, positions = 1)
        assertEquals(
            "новый жест той же карточки — один захват",
            grabsBeforeRecreate + 1,
            feedback.countOf(FeedbackCue.CardGrabbed),
        )
    }

    /**
     * `I6-N2`, часть 2. На **собственном** содержимом приложения (навигация и
     * `SavedStateHandle` восстанавливаются по-настоящему): частичный жест без отпускания,
     * затем `recreate()` — карточка не «прилипла», порядок равен последнему подтверждению.
     */
    @Test
    fun i6N2RecreatingTheActivityMidDragLeavesNoStuckCardAndKeepsTheOrder() {
        driver.awaitHomeCta()
        composeTestRule.onNodeWithTag(HomeTestTags.PRIMARY_BUTTON).performClick()
        driver.awaitCards()

        val before = visibleCardOrder()
        holdDrag(before.first())

        val confirmed = visibleCardOrder()
        assertEquals("жест обязан подтвердить одну перестановку", listOf(before[1], before[0]), confirmed.take(2))

        composeTestRule.activityRule.scenario.recreate()
        driver.awaitCards()

        assertEquals("порядок — последнее подтверждение", confirmed, visibleCardOrder())
        assertNoStuckCard()
    }

    /** `I6-N3`. Полный день только жестами: итог 18 из 18. */
    @Test
    fun i6N3AFullDayPlayedOnlyByDraggingEndsWith18Of18() {
        driver.startApp()
        driver.awaitHomeCta()

        driver.playTodayThroughTheUi(byDragging = true)

        assertTrue("день закрыт тремя попытками", driver.attemptCount() == SLOTS_IN_DAY)
    }

    // --- Инфраструктура ------------------------------------------------------------------

    private fun openFirstPuzzle(): Puzzle {
        composeTestRule.onNodeWithTag(HomeTestTags.PRIMARY_BUTTON).performClick()
        driver.awaitRoute(Destinations.PUZZLE)
        driver.awaitCards()
        val (_, puzzles) = driver.importedDay(LocalDate.parse(requireNotNull(driver.currentDate())))
        return puzzles.first()
    }

    private fun startOrderOf(puzzle: Puzzle): List<String> =
        DeterministicShuffler.shuffle(puzzle.puzzleId, puzzle.cards.map { it.cardId })

    /** Высота карточки плюс запас на системный touch slop — шаг одной позиции, px. */
    private fun stepFor(cardId: String): Float {
        composeTestRule.onNodeWithTag(PuzzleTestTags.CARD_LIST)
            .performScrollToNode(hasTestTag(OrderableCardTestTags.card(cardId)))
        composeTestRule.waitForIdle()
        val card = composeTestRule.onNodeWithTag(OrderableCardTestTags.card(cardId))
            .fetchSemanticsNode()
        return card.size.height + with(composeTestRule.density) { SLOP_MARGIN.dp.toPx() }
    }

    /** Жест на одну позицию вниз **без отпускания**: палец остаётся на экране. */
    private fun holdDrag(cardId: String) {
        val step = stepFor(cardId)
        composeTestRule.onNodeWithTag(PuzzleTestTags.dragHandle(cardId), useUnmergedTree = true)
            .performTouchInput {
                down(center)
                moveBy(Offset(0f, step))
            }
        composeTestRule.waitForIdle()
    }

    /** Порядок карточек **на экране**: по фактическому вертикальному положению узлов. */
    private fun visibleCardOrder(): List<String> =
        composeTestRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.TestTag))
            .fetchSemanticsNodes()
            .mapNotNull { node ->
                val tag = node.config.getOrNull(SemanticsProperties.TestTag) ?: return@mapNotNull null
                if (!tag.startsWith(CARD_TAG_PREFIX)) return@mapNotNull null
                val cardId = tag.removePrefix(CARD_TAG_PREFIX)
                if (cardId.startsWith(CONTROL_TAG_INFIX)) null else cardId to node.positionInRoot.y
            }
            .sortedBy { it.second }
            .map { it.first }

    /**
     * Ни одна карточка не «прилипла»: зазор между соседними карточками везде одинаков.
     *
     * Сравнивается именно **зазор** (низ предыдущей → верх следующей), а не шаг между
     * верхами: карточки законно бывают разной высоты, когда уточняющая строка переносится,
     * и равный шаг у них не обязан получаться. Остаточное смещение поднятой карточки, в
     * отличие от разной высоты, ломает ровно зазор.
     */
    private fun assertNoStuckCard() {
        val bounds = visibleCardOrder().map { cardId ->
            val node = composeTestRule.onNodeWithTag(OrderableCardTestTags.card(cardId))
                .fetchSemanticsNode()
            node.positionInRoot.y to node.size.height
        }
        val gaps = bounds.zipWithNext { (top, height), (nextTop, _) -> nextTop - (top + height) }
        gaps.forEach { gap ->
            assertTrue(
                "неравный зазор списка — карточка осталась поднятой: $gaps",
                kotlin.math.abs(gap - gaps.first()) <= STEP_TOLERANCE_PX,
            )
        }
    }

    /** Один жест на [positions] позиций: отрицательное значение — вверх. */
    private fun dragByPositions(cardId: String, positions: Int) {
        val step = stepFor(cardId)
        composeTestRule.onNodeWithTag(PuzzleTestTags.dragHandle(cardId), useUnmergedTree = true)
            .performTouchInput {
                down(center)
                moveBy(Offset(0f, step * positions))
                up()
            }
        composeTestRule.waitForIdle()
    }

    private fun assertOrderOnScreen(expected: List<String>) {
        val actual = expected.associateWith { cardId ->
            composeTestRule.onNodeWithTag(OrderableCardTestTags.card(cardId))
                .fetchSemanticsNode()
                .positionInRoot.y
        }
        val onScreen = actual.entries.sortedBy { it.value }.map { it.key }

        assertEquals("порядок на экране: $actual", expected, onScreen)
    }

    private companion object {
        /** Два жеста теста `I6-N1`. */
        const val GRABS = 2

        /** Одна перестановка первым жестом и одна — вторым (обе цели абсолютные). */
        const val CONFIRMED_REORDERS = 2

        const val SLOTS_IN_DAY = 3

        /** Запас на системный touch slop и зазор списка. */
        const val SLOP_MARGIN = 40

        /** Префикс тестового тега карточки; у кнопок перемещения он же плюс `move_`. */
        const val CARD_TAG_PREFIX = "orderable_card_"
        const val CONTROL_TAG_INFIX = "move_"

        /** Допуск на округление зазора при переводе dp в px. */
        const val STEP_TOLERANCE_PX = 2f
    }
}
