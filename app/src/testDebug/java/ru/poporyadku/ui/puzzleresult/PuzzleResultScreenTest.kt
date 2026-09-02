package ru.poporyadku.ui.puzzleresult

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.poporyadku.core.model.Puzzle
import ru.poporyadku.domain.scoring.InvertedPair
import ru.poporyadku.ui.components.OrderableCardTestTags
import ru.poporyadku.ui.theme.PoPoRyadkuTheme

/**
 * `PuzzleResultScreen` — ITERATION_3_DESIGN.md, `I3-C7`–`I3-C9` и Result-части
 * `I3-C11`–`I3-C13`.
 *
 * Экран stateless: рендерится готовое состояние, Hilt не участвует (I3-D31).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
class PuzzleResultScreenTest {

    @get:Rule
    val rule = createComposeRule()

    // --- I3-C7 -------------------------------------------------------------------------

    /** `I3-C7`. При 6 из 6 перечня пар нет вовсе. */
    @Test
    fun `I3-C7 six of six has no inverted pair rows`() = assertPairRows(score = 6, expectedRows = 0)

    /** `I3-C7`. При 3 из 6 ровно три строки пар. */
    @Test
    fun `I3-C7 three of six has three inverted pair rows`() =
        assertPairRows(score = 3, expectedRows = 3)

    /** `I3-C7`. При 0 из 6 ровно шесть строк пар. */
    @Test
    fun `I3-C7 zero of six has six inverted pair rows`() = assertPairRows(score = 0, expectedRows = 6)

    /** `I3-C7`. Шесть перепутанных пар рисуются полностью, без красного и без обрезки. */
    @Test
    fun `I3-C7 all six inverted pairs are rendered`() {
        rule.setContent { Result(content(score = 0, pairs = allPairs)) }

        assertEquals(
            MAX_SCORE,
            rule.onNodeWithTag(PuzzleResultTestTags.INVERTED_PAIRS).fetchSemanticsNode().children.size,
        )
        rule.onNodeWithText(ALL_CORRECT).assertDoesNotExist()
    }

    /** `I3-C7`. 6 из 6 — одна нейтральная строка «Всё верно», без перечня пар. */
    @Test
    fun `I3-C7 a perfect score shows a single neutral line`() {
        rule.setContent { Result(content(score = MAX_SCORE, pairs = emptyList())) }

        rule.onNodeWithTag(PuzzleResultTestTags.ALL_CORRECT).assertExists()
        // Счёт живёт в прокручиваемой части экрана — прокрутка к нему часть проверки.
        rule.onNodeWithText("6 из 6").performScrollTo().assertIsDisplayed()
    }

    // --- I3-C8 -------------------------------------------------------------------------

    /** `I3-C8`. Read-only карточки не содержат `MoveButton` и custom actions в дереве. */
    @Test
    fun `I3-C8 read only cards have no move buttons and no custom actions`() {
        rule.setContent { Result(content(score = 3, pairs = allPairs.take(3))) }

        correctOrder.forEach { card ->
            rule.onNodeWithTag(OrderableCardTestTags.card(card.cardId)).assertExists()
            rule.onNodeWithTag(OrderableCardTestTags.moveUp(card.cardId)).assertDoesNotExist()
            rule.onNodeWithTag(OrderableCardTestTags.moveDown(card.cardId)).assertDoesNotExist()
        }

        val withActions = rule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions),
        ).fetchSemanticsNodes()
        assertTrue("на экране результата custom actions не предлагаются", withActions.isEmpty())
    }

    /** Правильный порядок показывается со значениями — это и делает экран обучающим. */
    @Test
    fun `correct order shows the display value of every card`() {
        rule.setContent { Result(content(score = 3, pairs = allPairs.take(3))) }

        correctOrder.forEach { card ->
            rule.onNodeWithText(card.displayValue).assertExists()
        }
    }

    // --- I3-C9 -------------------------------------------------------------------------

    /** `I3-C9`. `ScoringHint` показан только при `showScoringHint = true`. */
    @Test
    fun `I3-C9 scoring hint is shown only on the first result`() {
        rule.setContent { Result(content(score = 5, pairs = allPairs.take(1), hint = true)) }

        rule.onNodeWithTag(PuzzleResultTestTags.SCORING_HINT).assertExists()
        rule.onNodeWithText(HINT_TEXT).assertExists()
    }

    /** `I3-C9`. На следующем результате подсказки нет в дереве вовсе. */
    @Test
    fun `I3-C9 scoring hint is absent afterwards`() {
        rule.setContent { Result(content(score = 5, pairs = allPairs.take(1), hint = false)) }

        rule.onNodeWithTag(PuzzleResultTestTags.SCORING_HINT).assertDoesNotExist()
        rule.onNodeWithText(HINT_TEXT).assertDoesNotExist()
    }

    // --- CTA ----------------------------------------------------------------------------

    /** Слоты 0–1 — «Дальше», последний — «К итогу дня». */
    @Test
    fun `primary action label depends on the slot`() {
        rule.setContent { Result(content(score = 6, pairs = emptyList(), slotIndex = 0)) }
        rule.onNodeWithText(NEXT).assertIsDisplayed()

        rule.onNodeWithTag(PuzzleResultTestTags.PRIMARY_BUTTON).assertHasClickAction()
    }

    @Test
    fun `last slot leads to the day recap`() {
        val events = mutableListOf<PuzzleResultEvent>()
        rule.setContent {
            Result(content(score = 6, pairs = emptyList(), slotIndex = 2), onEvent = { events += it })
        }

        rule.onNodeWithText(TO_RECAP).assertIsDisplayed()
        rule.onNodeWithTag(PuzzleResultTestTags.PRIMARY_BUTTON).performClick()
        assertEquals(listOf(PuzzleResultEvent.PrimaryAction), events)
    }

    /** Кнопка «Назад» в шапке присутствует и ведёт на Home тем же событием. */
    @Test
    fun `top bar offers a back action`() {
        val events = mutableListOf<PuzzleResultEvent>()
        rule.setContent {
            Result(content(score = 6, pairs = emptyList()), onEvent = { events += it })
        }

        rule.onNodeWithContentDescription(BACK).performClick()
        assertEquals(listOf(PuzzleResultEvent.BackPressed), events)
    }

    /** Источники свёрнуты по умолчанию: строка источника не показана до раскрытия. */
    @Test
    fun `sources block is collapsed by default`() {
        rule.setContent { Result(content(score = 6, pairs = emptyList())) }

        rule.onNodeWithText(SOURCES).assertExists()
        rule.onNodeWithText(sources.first().title).assertDoesNotExist()

        rule.onNodeWithText(SOURCES).performScrollTo().performClick()
        rule.onNodeWithText(sources.first().title).assertExists()
    }

    // --- I3-C11 / I3-C12 / I3-C13, Result-часть -------------------------------------------

    /** `I3-C11`, Result-часть. На 320 dp нет горизонтальной прокрутки. */
    @Test
    @Config(qualifiers = "w320dp-h844dp")
    fun `I3-C11 result renders on 320 dp without horizontal scrolling`() {
        rule.setContent { Result(content(score = 3, pairs = allPairs.take(3))) }

        val screen = rule.onNodeWithTag(PuzzleResultTestTags.SCREEN).fetchSemanticsNode()
        val content = rule.onNodeWithTag(PuzzleResultTestTags.CONTENT).fetchSemanticsNode()
        assertTrue(
            "контент шире экрана: ${content.size.width} > ${screen.size.width}",
            content.size.width <= screen.size.width,
        )
        assertTrue(
            "на PuzzleResult не должно быть горизонтальной прокрутки",
            rule.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange),
            ).fetchSemanticsNodes().isEmpty(),
        )
    }

    /** `I3-C11`. Сокращённая форма строки пары на узком экране; TalkBack получает полную. */
    @Test
    @Config(qualifiers = "w320dp-h844dp")
    fun `I3-C11 compact width uses the short pair form and keeps the full one for TalkBack`() {
        val pair = allPairs.first()
        rule.setContent { Result(content(score = 5, pairs = listOf(pair))) }

        val full = "Карточка «${titleOf(pair.correctlySecond)}» должна располагаться " +
            "после карточки «${titleOf(pair.correctlyFirst)}»"
        val short = "«${titleOf(pair.correctlySecond)}» — после «${titleOf(pair.correctlyFirst)}»"

        rule.onNodeWithText(short).assertExists()
        rule.onNode(hasContentDescription(full)).assertExists()
    }

    /** `I3-C12`, Result-часть. При 200% основная кнопка видна и нажимается. */
    @Test
    @Config(qualifiers = "w320dp-h844dp")
    fun `I3-C12 primary button stays clickable at font scale 200 percent`() {
        val events = mutableListOf<PuzzleResultEvent>()
        rule.setContent {
            WithFontScale(FONT_SCALE_200) {
                Result(content(score = 3, pairs = allPairs.take(3)), onEvent = { events += it })
            }
        }

        rule.onNodeWithTag(PuzzleResultTestTags.PRIMARY_BUTTON).assertIsDisplayed()
        rule.onNodeWithTag(PuzzleResultTestTags.PRIMARY_BUTTON).performClick()
        assertEquals(listOf(PuzzleResultEvent.PrimaryAction), events)
    }

    /** `I3-C13`, Result-часть. Тёмная тема отрисовывается. */
    @Test
    fun `I3-C13 result renders in dark theme`() {
        rule.setContent {
            PoPoRyadkuTheme(darkTheme = true) {
                PuzzleResultScreen(
                    state = content(score = 3, pairs = allPairs.take(3)),
                    onEvent = {},
                )
            }
        }

        rule.onNodeWithTag(PuzzleResultTestTags.SCREEN).assertExists()
        rule.onNodeWithTag(PuzzleResultTestTags.SCORE_BADGE).assertExists()
        rule.onNodeWithTag(PuzzleResultTestTags.PRIMARY_BUTTON).assertIsDisplayed()
    }

    /** Порядок сверху вниз: правильный порядок → объяснение → счёт → подсказка → пары. */
    @Test
    fun `content order follows the approved hierarchy`() {
        rule.setContent { Result(content(score = 5, pairs = allPairs.take(1), hint = true)) }

        val tops = listOf(
            PuzzleResultTestTags.CORRECT_ORDER,
            PuzzleResultTestTags.EXPLANATION,
            PuzzleResultTestTags.SCORE_BADGE,
            PuzzleResultTestTags.SCORING_HINT,
            PuzzleResultTestTags.INVERTED_PAIRS,
            PuzzleResultTestTags.SOURCES,
        ).map { rule.onNodeWithTag(it).fetchSemanticsNode().positionInRoot.y }

        assertEquals("порядок блоков обязан быть возрастающим", tops.sorted(), tops)
    }

    // --- Инфраструктура -------------------------------------------------------------------

    @Composable
    private fun Result(
        state: PuzzleResultState,
        onEvent: (PuzzleResultEvent) -> Unit = {},
    ) {
        PoPoRyadkuTheme(darkTheme = false) {
            PuzzleResultScreen(state = state, onEvent = onEvent)
        }
    }

    @Composable
    private fun WithFontScale(scale: Float, content: @Composable () -> Unit) {
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) {
            content()
        }
    }

    /**
     * Число строк пар равно `6 − score`, и ни одна строка не содержит запрещённых слов
     * «выше»/«ниже» — их неоткуда взять: в шаблоне их физически нет.
     */
    private fun assertPairRows(score: Int, expectedRows: Int) {
        val pairs = allPairs.take(expectedRows)
        rule.setContent { Result(content(score = score, pairs = pairs)) }

        val rows = rule.onNodeWithTag(PuzzleResultTestTags.INVERTED_PAIRS)
            .fetchSemanticsNode()
            .children
        assertEquals(MAX_SCORE - score, expectedRows)
        assertEquals(if (expectedRows == 0) 1 else expectedRows, rows.size)

        pairs.forEach { pair ->
            val text = "Карточка «${titleOf(pair.correctlySecond)}» должна располагаться " +
                "после карточки «${titleOf(pair.correctlyFirst)}»"
            rule.onNodeWithText(text).assertExists()
            assertTrue("строка пары содержит запрещённое слово: $text", FORBIDDEN.none { it in text })
        }
    }

    private fun titleOf(cardId: String): String = correctOrder.first { it.cardId == cardId }.title

    private companion object {
        const val MAX_SCORE = 6
        const val FONT_SCALE_200 = 2f
        const val ALL_CORRECT = "Всё верно"
        const val NEXT = "Дальше"
        const val TO_RECAP = "К итогу дня"
        const val SOURCES = "Источники"
        const val BACK = "Назад"
        const val HINT_TEXT =
            "Баллы даются за каждую пару карточек в правильном порядке. У четырёх карточек шесть пар"
        val FORBIDDEN = listOf("выше", "ниже")

        val correctOrder = listOf(
            ResultCardUi("c2", 1, "Монблан", "Альпы", "4808 м"),
            ResultCardUi("c1", 2, "Эльбрус", "Кавказ", "5642 м"),
            ResultCardUi("c3", 3, "Килиманджаро", "Танзания", "5895 м"),
            ResultCardUi("c4", 4, "Аконкагуа", "Анды", "6961 м"),
        )

        val allPairs = listOf(
            InvertedPair("c2", "c1"),
            InvertedPair("c2", "c3"),
            InvertedPair("c2", "c4"),
            InvertedPair("c1", "c3"),
            InvertedPair("c1", "c4"),
            InvertedPair("c3", "c4"),
        )

        val sources = listOf(
            Puzzle.Source(
                sourceId = "s1",
                title = "Большая российская энциклопедия",
                kind = "encyclopedia",
                url = null,
                reference = "БРЭ. Т. 35. М., 2017",
                accessedAt = "2026-08-20",
                note = null,
            ),
        )

        fun content(
            score: Int,
            pairs: List<InvertedPair>,
            hint: Boolean = false,
            slotIndex: Int = 0,
        ) = PuzzleResultState.Content(
            slotIndex = slotIndex,
            totalSlots = 3,
            correctOrder = correctOrder,
            submittedOrder = listOf("c1", "c2", "c3", "c4"),
            score = score,
            invertedPairs = pairs,
            explanation = "Монблан уступает Эльбрусу, а рекорд держит Аконкагуа.",
            sources = sources,
            showScoringHint = hint,
            isLastSlot = slotIndex == 2,
            puzzleId = "tmp-geo-vysota-001",
        )
    }
}
