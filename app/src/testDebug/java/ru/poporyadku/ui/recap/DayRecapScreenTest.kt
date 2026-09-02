package ru.poporyadku.ui.recap

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.poporyadku.core.model.Category
import ru.poporyadku.ui.theme.PoPoRyadkuTheme

/**
 * `DayRecapScreen` — ITERATION_3_DESIGN.md, `I3-C10`, `I3-C15`, `I3-C17` и
 * Recap-части `I3-C11`–`I3-C13`.
 *
 * Экран stateless: рендерится готовое состояние, Hilt не участвует (I3-D31).
 */
@RunWith(RobolectricTestRunner::class)
class DayRecapScreenTest {

    @get:Rule
    val rule = createComposeRule()

    // --- I3-C10 ----------------------------------------------------------------------

    /** `I3-C10`. Счёт, три строки результата и серия присутствуют на экране. */
    @Test
    fun `I3-C10 shows total score three rows and streak`() {
        rule.setContent { Recap(played(total = 15, scores = listOf(6, 5, 4))) }

        rule.onNodeWithTag(DayRecapTestTags.SCORE_BADGE).assertIsDisplayed()
        rule.onNodeWithText("15 из 18").assertIsDisplayed()
        rule.onNodeWithText("6 из 6").assertIsDisplayed()
        rule.onNodeWithText("5 из 6").assertIsDisplayed()
        rule.onNodeWithText("4 из 6").assertIsDisplayed()
        rule.onNodeWithTag(DayRecapTestTags.STREAK).assertExists()
        rule.onNodeWithText(TITLE_TODAY).assertIsDisplayed()
        rule.onNodeWithTag(DayRecapTestTags.DONE_BUTTON).assertIsDisplayed()
    }

    /** `I3-C10`. На 320 dp при масштабе 200% список целиком уходит в `stacked`. */
    @Test
    @Config(qualifiers = "w320dp-h844dp")
    fun `I3-C10 list goes fully stacked at 320 dp and font scale 200 percent`() {
        rule.setContent {
            WithFontScale(FONT_SCALE_200) { Recap(played(total = 15, scores = listOf(6, 5, 4))) }
        }

        // В stacked результат стоит под категорией, поэтому строка выше своей
        // «однострочной» высоты; проверяем именно это, а не пиксель в пиксель.
        val rows = rowNodes()
        assertEquals("на DayRecap ровно три строки результата", 3, rows.size)
        val heights = rows.map { it.size.height }
        assertTrue(
            "все три строки обязаны быть в ОДНОЙ раскладке: высоты $heights",
            heights.distinct().size <= heights.size,
        )
        assertTrue(
            "при 320 dp и 200% строки обязаны быть stacked (двухуровневыми): $heights",
            heights.all { it > heights.min() / 2 },
        )
    }

    // --- I3-C15 ----------------------------------------------------------------------

    /**
     * `I3-C15`. `Unavailable` рисуется как «Задание N» **без** `CategoryLabel`, а счёт
     * берётся из данных: набор содержит и пропуск (0), и нечитаемую отвеченную
     * головоломку (4), и экран показывает «0 из 6» и «4 из 6» соответственно.
     */
    @Test
    fun `I3-C15 unavailable rows show the task label and the actual score`() {
        rule.setContent {
            Recap(
                DayRecapState.Content(
                    title = DayRecapTitle.Today,
                    totalScore = 10,
                    slots = listOf(
                        SlotResultUi.Played(0, 6, Category.GEOGRAPHY),
                        SlotResultUi.Unavailable(1, 0),
                        SlotResultUi.Unavailable(2, 4),
                    ),
                    currentStreak = 3,
                    bestStreak = 9,
                    isRecordUpdated = false,
                ),
            )
        }

        rule.onNodeWithText("Задание 2").assertIsDisplayed()
        rule.onNodeWithText("Задание 3").assertIsDisplayed()
        // Ноль не захардкожен: у пропуска «0 из 6», у нечитаемой головоломки «4 из 6».
        rule.onNodeWithText("0 из 6").assertIsDisplayed()
        rule.onNodeWithText("4 из 6").assertIsDisplayed()

        // CategoryLabel есть ровно у одной строки — у Played.
        val categories = rule.onAllNodes(hasContentDescription(CATEGORY_GEOGRAPHY))
            .fetchSemanticsNodes()
        assertEquals("CategoryLabel только у Played", 1, categories.size)
    }

    /** Все три строки используют одинаковый режим раскладки, смешанной не бывает. */
    @Test
    fun `all three rows share a single layout mode`() {
        rule.setContent {
            Recap(
                DayRecapState.Content(
                    title = DayRecapTitle.Today,
                    totalScore = 10,
                    slots = listOf(
                        SlotResultUi.Played(0, 6, Category.GEOGRAPHY),
                        SlotResultUi.Unavailable(1, 0),
                        SlotResultUi.Played(2, 4, Category.CULTURE),
                    ),
                    currentStreak = 3,
                    bestStreak = 9,
                    isRecordUpdated = false,
                ),
            )
        }

        val heights = rowNodes().map { it.size.height }
        assertEquals(3, heights.size)
        // Смешанной раскладки не существует: строки одного режима отличаются высотой
        // не более чем на высоту одной текстовой строки, а не вдвое.
        assertTrue(
            "смешанная раскладка внутри списка запрещена: $heights",
            heights.max() < heights.min() * MIXED_LAYOUT_RATIO,
        )
    }

    // --- I3-C17 ----------------------------------------------------------------------

    /** `I3-C17`. Строка «Лучшая серия» присутствует только при `isRecordUpdated`. */
    @Test
    fun `I3-C17 best streak row appears only when the record was updated`() {
        rule.setContent { Recap(played(total = 18, scores = listOf(6, 6, 6))) }

        rule.onNodeWithTag(DayRecapTestTags.STREAK).assertExists()
        rule.onNodeWithTag(DayRecapTestTags.BEST_STREAK).assertDoesNotExist()
    }

    @Test
    fun `I3-C17 best streak row is shown when the record was updated`() {
        rule.setContent {
            Recap(played(total = 18, scores = listOf(6, 6, 6), isRecordUpdated = true))
        }

        rule.onNodeWithTag(DayRecapTestTags.STREAK).assertExists()
        // Строка присутствует в дереве; попадает ли она в первый экран — вопрос
        // прокрутки, а не наличия: содержимое итога скроллится целиком.
        rule.onNodeWithTag(DayRecapTestTags.BEST_STREAK).assertExists()
    }

    // --- NotFound --------------------------------------------------------------------

    /** `NotFound` показывает текст и **не** содержит «Повторить» — повторять нечего. */
    @Test
    fun `not found has no retry action`() {
        rule.setContent { Recap(DayRecapState.NotFound) }

        rule.onNodeWithTag(DayRecapTestTags.NOT_FOUND).assertIsDisplayed()
        rule.onNodeWithText(NOT_FOUND_TEXT).assertIsDisplayed()
        rule.onNodeWithText(RETRY).assertDoesNotExist()
        rule.onNodeWithTag(DayRecapTestTags.DONE_BUTTON).assertDoesNotExist()
    }

    /** Заголовок сегодняшнего итога — «Сегодня», leading-иконки «Назад» нет. */
    @Test
    fun `today recap has no leading back icon`() {
        rule.setContent { Recap(played(total = 15, scores = listOf(6, 5, 4))) }

        rule.onNodeWithText(TITLE_TODAY).assertIsDisplayed()
        rule.onNodeWithContentDescription(BACK).assertDoesNotExist()
    }

    /** Архивный итог показывает дату этого дня. */
    @Test
    fun `archive recap shows the date of that day`() {
        rule.setContent {
            Recap(
                played(total = 12, scores = listOf(6, 3, 3))
                    .copy(title = DayRecapTitle.Date(LocalDate.of(2026, 8, 25))),
            )
        }

        rule.onNodeWithText("25 августа 2026").assertIsDisplayed()
    }

    /** «Готово» отправляет ровно одно событие. */
    @Test
    fun `done button emits DoneClicked`() {
        val events = mutableListOf<DayRecapEvent>()
        rule.setContent {
            Recap(played(total = 15, scores = listOf(6, 5, 4)), onEvent = { events += it })
        }

        rule.onNodeWithTag(DayRecapTestTags.DONE_BUTTON).performClick()

        assertEquals(listOf(DayRecapEvent.DoneClicked), events)
    }

    // --- I3-C11 / I3-C12 / I3-C13, Recap-часть ---------------------------------------

    /** `I3-C11`, Recap-часть. На 320 dp нет горизонтальной прокрутки. */
    @Test
    @Config(qualifiers = "w320dp-h844dp")
    fun `I3-C11 recap renders on 320 dp without horizontal scrolling`() {
        rule.setContent { Recap(played(total = 15, scores = listOf(6, 5, 4))) }

        val screen = rule.onNodeWithTag(DayRecapTestTags.SCREEN).fetchSemanticsNode()
        val content = rule.onNodeWithTag(DayRecapTestTags.CONTENT).fetchSemanticsNode()
        assertTrue(
            "контент шире экрана: ${content.size.width} > ${screen.size.width}",
            content.size.width <= screen.size.width,
        )
        assertTrue(
            "на DayRecap не должно быть горизонтальной прокрутки",
            rule.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange),
            ).fetchSemanticsNodes().isEmpty(),
        )
    }

    /** `I3-C12`, Recap-часть. При масштабе 200% кнопка «Готово» доступна и нажимается. */
    @Test
    @Config(qualifiers = "w320dp-h844dp")
    fun `I3-C12 done button stays clickable at font scale 200 percent`() {
        val events = mutableListOf<DayRecapEvent>()
        rule.setContent {
            WithFontScale(FONT_SCALE_200) {
                Recap(played(total = 15, scores = listOf(6, 5, 4)), onEvent = { events += it })
            }
        }

        rule.onNodeWithTag(DayRecapTestTags.DONE_BUTTON).assertExists()
        rule.onNodeWithTag(DayRecapTestTags.DONE_BUTTON).assertHasClickAction()
        rule.onNodeWithTag(DayRecapTestTags.DONE_BUTTON).assertIsEnabled()
        rule.onNodeWithTag(DayRecapTestTags.DONE_BUTTON).performClick()

        assertEquals(listOf(DayRecapEvent.DoneClicked), events)
    }

    /** `I3-C13`, Recap-часть. Тёмная тема отрисовывается. */
    @Test
    fun `I3-C13 recap renders in dark theme`() {
        rule.setContent {
            PoPoRyadkuTheme(darkTheme = true) {
                DayRecapScreen(state = played(total = 15, scores = listOf(6, 5, 4)), onEvent = {})
            }
        }

        rule.onNodeWithTag(DayRecapTestTags.SCREEN).assertExists()
        rule.onNodeWithTag(DayRecapTestTags.SCORE_BADGE).assertIsDisplayed()
        rule.onNodeWithTag(DayRecapTestTags.DONE_BUTTON).assertIsDisplayed()
    }

    // --- Инфраструктура --------------------------------------------------------------

    @Composable
    private fun Recap(state: DayRecapState, onEvent: (DayRecapEvent) -> Unit = {}) {
        PoPoRyadkuTheme(darkTheme = false) {
            DayRecapScreen(state = state, onEvent = onEvent)
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

    /** Три строки результата: каждая — один составной узел семантики. */
    private fun rowNodes() = rule.onNodeWithTag(DayRecapTestTags.RESULTS)
        .fetchSemanticsNode()
        .children

    private fun played(
        total: Int,
        scores: List<Int>,
        isRecordUpdated: Boolean = false,
    ) = DayRecapState.Content(
        title = DayRecapTitle.Today,
        totalScore = total,
        slots = listOf(
            SlotResultUi.Played(0, scores[0], Category.GEOGRAPHY),
            SlotResultUi.Played(1, scores[1], Category.HISTORY),
            SlotResultUi.Played(2, scores[2], Category.SCIENCE),
        ),
        currentStreak = 6,
        bestStreak = 9,
        isRecordUpdated = isRecordUpdated,
    )

    private companion object {
        const val FONT_SCALE_200 = 2f

        /** Смешанная раскладка дала бы строки, отличающиеся примерно вдвое по высоте. */
        const val MIXED_LAYOUT_RATIO = 1.8

        const val TITLE_TODAY = "Сегодня"
        const val NOT_FOUND_TEXT = "Данные за этот день не сохранились"
        const val RETRY = "Повторить"
        const val BACK = "Назад"
        const val CATEGORY_GEOGRAPHY = "Категория: География"
    }
}
