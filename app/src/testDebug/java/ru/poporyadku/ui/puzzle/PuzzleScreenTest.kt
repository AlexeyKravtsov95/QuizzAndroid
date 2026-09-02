package ru.poporyadku.ui.puzzle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.poporyadku.core.model.Category
import ru.poporyadku.domain.usecase.PuzzleErrorKind
import ru.poporyadku.domain.usecase.Submission
import ru.poporyadku.ui.components.OrderableCardTestTags
import ru.poporyadku.ui.theme.PoPoRyadkuTheme

/**
 * `PuzzleScreen` — ITERATION_3_DESIGN.md, `I3-C3`–`I3-C6`, `I3-C16`, `I3-C21` и
 * Puzzle-части `I3-C11`–`I3-C13`.
 *
 * Экран stateless: рендерится готовое состояние, Hilt не участвует (I3-D31).
 */
@RunWith(RobolectricTestRunner::class)
// Эталонная ширина макетов — 390 dp; на ней все четыре карточки помещаются на экране
// без прокрутки, как того требует UX_FLOW.md §4. Тесты 320 dp / 200% переопределяют
// эти квалификаторы у себя.
@Config(qualifiers = "w390dp-h844dp")
class PuzzleScreenTest {

    @get:Rule
    val rule = createComposeRule()

    // --- I3-C3 -------------------------------------------------------------------------

    /**
     * `I3-C3`. Четыре карточки; у первой недоступен ход вверх, у последней — вниз;
     * нажатие `MoveButton` отправляет нужное событие.
     */
    @Test
    fun `I3-C3 four cards with disabled edge buttons emit move events`() {
        val events = mutableListOf<PuzzleEvent>()
        rule.setContent { Puzzle(playing(), onEvent = { events += it }) }

        cards.forEach { rule.onNodeWithTag(OrderableCardTestTags.card(it)).assertExists() }

        rule.onNodeWithTag(OrderableCardTestTags.moveUp("c1")).assertIsNotEnabled()
        rule.onNodeWithTag(OrderableCardTestTags.moveDown("c1")).assertIsEnabled()
        rule.onNodeWithTag(OrderableCardTestTags.moveDown("c4")).assertIsNotEnabled()
        rule.onNodeWithTag(OrderableCardTestTags.moveUp("c4")).assertIsEnabled()

        rule.onNodeWithTag(OrderableCardTestTags.moveDown("c1")).performClick()
        rule.onNodeWithTag(OrderableCardTestTags.moveUp("c3")).performClick()

        assertEquals(listOf(PuzzleEvent.MoveDown("c1"), PuzzleEvent.MoveUp("c3")), events)
    }

    // --- I3-C4 -------------------------------------------------------------------------

    /**
     * `I3-C4`. У каждой карточки есть составное описание «Позиция N из 4. {Название}» и
     * только те custom actions, которые реально меняют её позицию.
     */
    @Test
    fun `I3-C4 cards expose position semantics and only applicable custom actions`() {
        rule.setContent { Puzzle(playing()) }

        rule.onNodeWithContentDescription("Позиция 1 из 4. Эльбрус").assertExists()
        rule.onNodeWithContentDescription("Позиция 4 из 4. Аконкагуа").assertExists()

        // Первая карточка: только «вниз» и «в конец».
        assertEquals(
            listOf("Переместить вниз", "Переместить в конец"),
            customActionLabels("Позиция 1 из 4. Эльбрус"),
        )
        // Последняя: только «вверх» и «в начало».
        assertEquals(
            listOf("Переместить вверх", "Переместить в начало"),
            customActionLabels("Позиция 4 из 4. Аконкагуа"),
        )
        // Средняя: все четыре.
        assertEquals(
            listOf(
                "Переместить вверх",
                "Переместить в начало",
                "Переместить вниз",
                "Переместить в конец",
            ),
            customActionLabels("Позиция 2 из 4. Монблан"),
        )
    }

    /** Custom action действительно отправляет событие, а не просто присутствует. */
    @Test
    fun `custom action performs the move`() {
        val events = mutableListOf<PuzzleEvent>()
        rule.setContent { Puzzle(playing(), onEvent = { events += it }) }

        val node = rule.onNodeWithContentDescription("Позиция 4 из 4. Аконкагуа")
            .fetchSemanticsNode()
        val action = node.config[SemanticsActions.CustomActions]
            .first { it.label == "Переместить в начало" }
        rule.runOnUiThread { action.action?.invoke() }

        assertEquals(listOf(PuzzleEvent.MoveToTop("c4")), events)
    }

    // --- I3-C5 / I3-C16 ------------------------------------------------------------------

    /** `I3-C5`. В `Submitting` кнопка «Проверить» имеет семантику `disabled`. */
    @Test
    fun `I3-C5 submit button is disabled while submitting`() {
        rule.setContent { Puzzle(submitting()) }

        rule.onNodeWithTag(PuzzleTestTags.SUBMIT_BUTTON).assertIsNotEnabled()
    }

    /**
     * `I3-C16`. Во время записи на экране те же четыре карточки в том же порядке, обе
     * `MoveButton` каждой disabled, custom actions **отсутствуют** в дереве семантики.
     */
    @Test
    fun `I3-C16 submitting keeps the board and removes custom actions`() {
        rule.setContent { Puzzle(submitting()) }

        cards.forEachIndexed { index, cardId ->
            rule.onNodeWithTag(OrderableCardTestTags.card(cardId)).assertExists()
            rule.onNodeWithTag(OrderableCardTestTags.moveUp(cardId)).assertIsNotEnabled()
            rule.onNodeWithTag(OrderableCardTestTags.moveDown(cardId)).assertIsNotEnabled()
            assertTrue(
                "custom actions во время записи обязаны отсутствовать",
                customActionLabels("Позиция ${index + 1} из 4. ${titles[index]}").isEmpty(),
            )
        }
        rule.onNodeWithTag(PuzzleTestTags.SUBMIT_BUTTON).assertIsNotEnabled()
    }

    // --- I3-C6 -------------------------------------------------------------------------

    /** `I3-C6`. `DragHandle` отсутствует в дереве семантики — его нет и в коде (I3-D24). */
    @Test
    fun `I3-C6 there is no drag handle anywhere in the tree`() {
        rule.setContent { Puzzle(playing()) }

        rule.onNodeWithContentDescription(DRAG_HANDLE).assertDoesNotExist()
        rule.onNodeWithText(DRAG_HINT, substring = true).assertDoesNotExist()
    }

    // --- I3-C21 ------------------------------------------------------------------------

    /**
     * `I3-C21`. `Submitting.Skip` рисует ТУ ЖЕ композицию `skippablePuzzle`, что и
     * исходная ошибка: тот же текст, ни скелетона, ни карточек; «Пропустить» disabled.
     */
    @Test
    fun `I3-C21 skip while submitting keeps the same error composition disabled`() {
        rule.setContent { Puzzle(PuzzleUiState.Submitting.Skip(PuzzleErrorKind.PuzzleNotFound)) }

        // Тот же текст, что и в исходном Error(PuzzleNotFound) — см. соседний тест.
        rule.onNodeWithText(UNAVAILABLE).assertIsDisplayed()
        rule.onNodeWithTag(PuzzleTestTags.SKIP_BUTTON).assertIsNotEnabled()
        rule.onNodeWithTag(PuzzleTestTags.RETRY_BUTTON).assertDoesNotExist()
        rule.onNodeWithTag(PuzzleTestTags.SKELETON).assertDoesNotExist()
        cards.forEach { rule.onNodeWithTag(OrderableCardTestTags.card(it)).assertDoesNotExist() }
    }

    /** `I3-C21`. Исходная композиция: тот же текст, «Пропустить» активна. */
    @Test
    fun `I3-C21 skippable error offers an enabled skip action`() {
        rule.setContent {
            Puzzle(PuzzleUiState.Error(PuzzleErrorKind.PuzzleNotFound, RetryAction.Reload, null))
        }

        rule.onNodeWithText(UNAVAILABLE).assertIsDisplayed()
        rule.onNodeWithTag(PuzzleTestTags.SKIP_BUTTON).assertIsEnabled()
        rule.onNodeWithTag(PuzzleTestTags.RETRY_BUTTON).assertDoesNotExist()
    }

    /** Отказ записи показывает «Не удалось сохранить ответ» и «Повторить». */
    @Test
    fun `write failure shows the retryable composition`() {
        rule.setContent {
            Puzzle(
                PuzzleUiState.Error(
                    kind = PuzzleErrorKind.Storage,
                    retry = RetryAction.Resubmit(Submission.Answer(cards)),
                    board = board,
                ),
            )
        }

        rule.onNodeWithText(SAVE_FAILED).assertIsDisplayed()
        rule.onNodeWithTag(PuzzleTestTags.RETRY_BUTTON).assertIsEnabled()
        rule.onNodeWithTag(PuzzleTestTags.SKIP_BUTTON).assertDoesNotExist()
    }

    /** Структурная ошибка не мигает отдельным кадром перед возвратом на Home. */
    @Test
    fun `structural error shows no error frame`() {
        rule.setContent {
            Puzzle(PuzzleUiState.Error(PuzzleErrorKind.InvalidRoute, RetryAction.None, null))
        }

        rule.onNodeWithTag(PuzzleTestTags.ERROR_BLOCK).assertDoesNotExist()
        rule.onNodeWithTag(PuzzleTestTags.SKELETON).assertExists()
    }

    /** `Loading` — четыре скелетона формы карточки и отключённая «Проверить». */
    @Test
    fun `loading shows four card skeletons and a disabled submit button`() {
        rule.setContent { Puzzle(PuzzleUiState.Loading) }

        rule.onNodeWithTag(PuzzleTestTags.SKELETON).assertExists()
        rule.onNodeWithTag(PuzzleTestTags.SUBMIT_BUTTON).assertIsNotEnabled()
    }

    // --- I3-C11 / I3-C12 / I3-C13, Puzzle-часть ------------------------------------------

    /** `I3-C11`, Puzzle-часть. На 320 dp нет горизонтальной прокрутки. */
    @Test
    @Config(qualifiers = "w320dp-h844dp")
    fun `I3-C11 puzzle renders on 320 dp without horizontal scrolling`() {
        rule.setContent { Puzzle(playing()) }

        val screen = rule.onNodeWithTag(PuzzleTestTags.SCREEN).fetchSemanticsNode()
        val content = rule.onNodeWithTag(PuzzleTestTags.CONTENT).fetchSemanticsNode()
        assertTrue(
            "контент шире экрана: ${content.size.width} > ${screen.size.width}",
            content.size.width <= screen.size.width,
        )
        assertTrue(
            "на Puzzle не должно быть горизонтальной прокрутки",
            rule.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange),
            ).fetchSemanticsNodes().isEmpty(),
        )
    }

    /** `I3-C12`, Puzzle-часть. При 320 dp и масштабе 200% «Проверить» видна и нажимается. */
    @Test
    @Config(qualifiers = "w320dp-h844dp")
    fun `I3-C12 submit button stays visible at font scale 200 percent`() {
        val events = mutableListOf<PuzzleEvent>()
        rule.setContent {
            WithFontScale(FONT_SCALE_200) { Puzzle(playing(), onEvent = { events += it }) }
        }

        // assertExists недостаточно: Compose кликает и по узлу за пределами viewport.
        rule.onNodeWithTag(PuzzleTestTags.SUBMIT_BUTTON).assertIsDisplayed()
        rule.onNodeWithTag(PuzzleTestTags.SUBMIT_BUTTON).assertHasClickAction()
        rule.onNodeWithTag(PuzzleTestTags.SUBMIT_BUTTON).performClick()

        assertEquals(listOf(PuzzleEvent.Submit), events)
    }

    /** `I3-C12`. Индексная зона и `MoveButton` не уменьшаются при 200%. */
    @Test
    @Config(qualifiers = "w320dp-h844dp")
    fun `I3-C12 move buttons keep their touch target at font scale 200 percent`() {
        rule.setContent { WithFontScale(FONT_SCALE_200) { Puzzle(playing()) } }

        val density = rule.density
        val button = rule.onNodeWithTag(OrderableCardTestTags.moveDown("c1")).fetchSemanticsNode()
        val minPx = with(density) { MIN_TOUCH_TARGET_DP.toDp().toPx() }
        assertTrue(
            "MoveButton уменьшилась: ${button.size}",
            button.size.width >= minPx && button.size.height >= minPx,
        )
    }

    /** `I3-C13`, Puzzle-часть. Тёмная тема отрисовывается. */
    @Test
    fun `I3-C13 puzzle renders in dark theme`() {
        rule.setContent {
            PoPoRyadkuTheme(darkTheme = true) {
                PuzzleScreen(state = playing(), onEvent = {})
            }
        }

        rule.onNodeWithTag(PuzzleTestTags.SCREEN).assertExists()
        rule.onNodeWithTag(PuzzleTestTags.CARD_LIST).assertExists()
        rule.onNodeWithTag(PuzzleTestTags.SUBMIT_BUTTON).assertIsDisplayed()
    }

    /** Список объявляет себя live region — перестановки озвучиваются без потери фокуса. */
    @Test
    fun `card list is a polite live region`() {
        rule.setContent { Puzzle(playing()) }

        val list = rule.onNodeWithTag(PuzzleTestTags.CARD_LIST).fetchSemanticsNode()
        assertEquals(
            LiveRegionMode.Polite,
            list.config.getOrNull(SemanticsProperties.LiveRegion),
        )
    }

    // --- Инфраструктура ------------------------------------------------------------------

    @Composable
    private fun Puzzle(state: PuzzleUiState, onEvent: (PuzzleEvent) -> Unit = {}) {
        PoPoRyadkuTheme(darkTheme = false) {
            PuzzleScreen(state = state, onEvent = onEvent)
        }
    }

    /** Масштаб шрифта задаётся плотностью, а не подменой внутренностей экрана. */
    @Composable
    private fun WithFontScale(scale: Float, content: @Composable () -> Unit) {
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) {
            content()
        }
    }

    private fun customActionLabels(contentDescription: String): List<String> =
        rule.onNodeWithContentDescription(contentDescription)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.CustomActions)
            ?.map { it.label }
            .orEmpty()

    private companion object {
        val cards = listOf("c1", "c2", "c3", "c4")
        val titles = listOf("Эльбрус", "Монблан", "Килиманджаро", "Аконкагуа")
        val subtitles = listOf("Кавказ, Россия", "Альпы", "Танзания", "Анды")

        const val FONT_SCALE_200 = 2f
        const val MIN_TOUCH_TARGET_DP = 48
        const val UNAVAILABLE = "Задание недоступно"
        const val SAVE_FAILED = "Не удалось сохранить ответ"
        const val DRAG_HANDLE = "Перетащить"
        const val DRAG_HINT = "Перетащите"

        val board = PuzzleBoard(
            slotIndex = 1,
            totalSlots = 3,
            puzzleId = "tmp-geo-vysota-001",
            category = Category.GEOGRAPHY,
            prompt = "Расположите вершины от самой низкой к самой высокой",
            directionLabel = "Сверху — самая низкая",
            cards = cards.mapIndexed { index, cardId ->
                CardUi(
                    cardId = cardId,
                    title = titles[index],
                    subtitle = subtitles[index],
                    position = index + 1,
                    canMoveUp = index > 0,
                    canMoveDown = index < cards.lastIndex,
                )
            },
            draggedCardId = null,
        )

        fun playing() = PuzzleUiState.Playing(board, isSubmitEnabled = true, showDragHint = false)

        fun submitting() =
            PuzzleUiState.Submitting.Answer(board, Submission.Answer(cards))
    }
}
