package ru.poporyadku.ui.archive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.poporyadku.ui.components.ArchiveRowTestTags
import ru.poporyadku.ui.theme.PoPoRyadkuTheme
import ru.poporyadku.ui.theme.Sizing

/**
 * `ArchiveScreen` — ITERATION_5_DESIGN.md, §3.6, §10.4: `I5-C1`…`I5-C7`.
 *
 * Экран stateless: рендерится готовое состояние, Hilt не участвует.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
class ArchiveScreenTest {

    @get:Rule
    val rule = createComposeRule()

    // --- I5-C1 ---------------------------------------------------------------------------

    @Test
    fun `I5-C1 Empty shows the text and the CTA back to the task of the day`() {
        val events = mutableListOf<ArchiveEvent>()
        rule.setContent { Archive(ArchiveState.Empty, onEvent = { events += it }) }

        rule.onNodeWithText(EMPTY_TEXT).assertIsDisplayed()
        rule.onNodeWithTag(ArchiveTestTags.STATISTICS).assertDoesNotExist()
        rule.onNodeWithText(EMPTY_CTA).assertIsDisplayed().performClick()
        rule.onNodeWithContentDescription(BACK).performClick()

        assertEquals(listOf(ArchiveEvent.ToTodayClicked, ArchiveEvent.BackClicked), events)
    }

    // --- I5-C2 ---------------------------------------------------------------------------

    @Test
    fun `I5-C2 Content shows five statistic pairs in order and the archive rows`() {
        rule.setContent { Archive(content(averageTenths = 137)) }

        // Заголовки экрана и секции — heading().
        rule.onNode(hasText(TITLE) and isHeading()).assertIsDisplayed()
        rule.onNode(hasText(STATISTICS) and isHeading()).assertIsDisplayed()

        // Пять пар, каждая — один узел, в порядке сверху вниз.
        val block = rule.onNodeWithTag(ArchiveTestTags.STATISTICS).fetchSemanticsNode()
        assertEquals("заголовок и пять пар", 6, block.children.size)
        val tops = PAIR_LABELS.map { label -> rule.onNode(hasText(label)).fetchSemanticsNode().positionInRoot.y }
        assertEquals("порядок пар статистики", tops.sorted(), tops)

        rule.onNode(hasText("СЫГРАНО ДНЕЙ") and hasText("23")).assertExists()
        rule.onNode(hasText("СРЕДНИЙ БАЛЛ") and hasText("13,7 из 18")).assertExists()
        rule.onNode(hasText("ЛУЧШИЙ ДЕНЬ") and hasText("17 из 18")).assertExists()
        rule.onNode(hasText("СЕРИЯ") and hasText("6 дней")).assertExists()
        rule.onNode(hasText("ЛУЧШАЯ СЕРИЯ") and hasText("9 дней")).assertExists()

        rule.onNode(hasContentDescription("25 августа 2026. День 12. 15 из 18. Завершён")).assertIsDisplayed()
        rule.onNode(hasContentDescription(INCOMPLETE_DESCRIPTION)).assertIsDisplayed()
    }

    @Test
    fun `I5-C2 an absent average is a dash, and one decimal is always shown`() {
        rule.setContent { Archive(content(averageTenths = null)) }
        rule.onNode(hasText("СРЕДНИЙ БАЛЛ") and hasText("—")).assertExists()
    }

    @Test
    fun `I5-C2 a whole average keeps its decimal`() {
        rule.setContent { Archive(content(averageTenths = 150)) }
        rule.onNode(hasText("СРЕДНИЙ БАЛЛ") and hasText("15,0 из 18")).assertExists()
    }

    // --- I5-C3 ---------------------------------------------------------------------------

    @Test
    fun `I5-C3 an incomplete row is one clickable target with a composite description`() {
        val events = mutableListOf<ArchiveEvent>()
        rule.setContent { Archive(content(), onEvent = { events += it }) }

        val row = rowTarget(INCOMPLETE_DATE).fetchSemanticsNode()
        assertEquals(listOf(INCOMPLETE_DESCRIPTION), row.config.getOrNull(SemanticsProperties.ContentDescription))
        assertEquals(Role.Button, row.config.getOrNull(SemanticsProperties.Role))
        assertEquals(OPEN_DAY, row.config.getOrNull(SemanticsActions.OnClick)?.label)
        val touchTarget = with(rule.density) { Sizing.touchTargetMin.roundToPx() }
        assertTrue("высота строки ≥ 48 dp: ${row.size.height}", row.size.height >= touchTarget)
        // Один узел: ни тексты, ни точки не читаются отдельно — ни дублем после описания,
        // ни собственными фокус-стопами.
        assertTrue(row.children.isEmpty())
        assertEquals(null, row.config.getOrNull(SemanticsProperties.Text))

        rowTarget(INCOMPLETE_DATE).performScrollTo().performClick()
        assertEquals(listOf<ArchiveEvent>(ArchiveEvent.DayClicked(INCOMPLETE_DATE)), events)
    }

    @Test
    fun `I5-C3 a complete row has its own description and no incomplete mark`() {
        rule.setContent { Archive(content()) }

        val row = rowTarget(COMPLETE_DATE).fetchSemanticsNode()
        assertEquals(
            listOf("25 августа 2026. День 12. 15 из 18. Завершён"),
            row.config.getOrNull(SemanticsProperties.ContentDescription),
        )
    }

    // --- I5-C4 ---------------------------------------------------------------------------

    @Test
    fun `I5-C4 Loading shows the statistics skeleton and six archive row skeletons`() {
        rule.setContent { Archive(ArchiveState.Loading) }

        rule.onNodeWithText(TITLE).assertIsDisplayed()
        rule.onNodeWithTag(ArchiveTestTags.STATISTICS_SKELETON).assertExists()
        assertEquals(1, rule.onAllNodesWithTag(ArchiveTestTags.STATISTICS_SKELETON_TITLE).fetchSemanticsNodes().size)
        assertEquals(5, rule.onAllNodesWithTag(ArchiveTestTags.STATISTICS_SKELETON_PAIR).fetchSemanticsNodes().size)
        assertEquals(6, rule.onAllNodesWithTag(ArchiveTestTags.ROW_SKELETON).fetchSemanticsNodes().size)
        // У каждой строки — своя полоса даты и своя полоса второй строки, а не одна карточка.
        assertEquals(6, rule.onAllNodesWithTag(ArchiveRowTestTags.SKELETON_DATE).fetchSemanticsNodes().size)
        assertEquals(6, rule.onAllNodesWithTag(ArchiveRowTestTags.SKELETON_SECOND_LINE).fetchSemanticsNodes().size)
        rule.onNodeWithTag(ArchiveTestTags.STATISTICS).assertDoesNotExist()
        rule.onNodeWithTag(ArchiveTestTags.ERROR).assertDoesNotExist()
    }

    // --- I5-C5 ---------------------------------------------------------------------------

    @Test
    fun `I5-C5 Error shows the retryable block`() {
        val events = mutableListOf<ArchiveEvent>()
        rule.setContent { Archive(ArchiveState.Error, onEvent = { events += it }) }

        rule.onNodeWithText(ERROR_TEXT).assertIsDisplayed()
        rule.onNodeWithTag(ArchiveTestTags.RETRY_BUTTON).assertIsDisplayed().performClick()
        assertEquals(listOf<ArchiveEvent>(ArchiveEvent.RetryClicked), events)
    }

    @Test
    fun `I5-C5 a failed next page keeps the rows and offers retry in the footer`() {
        val events = mutableListOf<ArchiveEvent>()
        rule.setContent { Archive(content(paging = ArchivePaging.LoadMoreFailed), onEvent = { events += it }) }

        rule.onNodeWithTag(ArchiveTestTags.row(COMPLETE_DATE)).assertExists()
        rule.onNodeWithTag(ArchiveTestTags.row(INCOMPLETE_DATE)).assertExists()
        rule.onNodeWithTag(ArchiveTestTags.FOOTER_ERROR).assertExists()
        rule.onNodeWithTag(ArchiveTestTags.FOOTER_LOADING).assertDoesNotExist()
        rule.onNodeWithTag(ArchiveTestTags.FOOTER_RETRY_BUTTON).performScrollTo().performClick()
        assertEquals(listOf<ArchiveEvent>(ArchiveEvent.RetryClicked), events)
    }

    @Test
    fun `I5-C5 RefreshFailed and EndReached footers`() {
        var state: ArchiveState by mutableStateOf(content(paging = ArchivePaging.RefreshFailed))
        rule.setContent { Archive(state) }

        rule.onNodeWithTag(ArchiveTestTags.FOOTER_ERROR).assertExists()
        rule.onNodeWithTag(ArchiveTestTags.row(COMPLETE_DATE)).assertExists()

        state = content(paging = ArchivePaging.EndReached)
        rule.waitForIdle()
        rule.onNodeWithTag(ArchiveTestTags.FOOTER_ERROR).assertDoesNotExist()
        rule.onNodeWithTag(ArchiveTestTags.FOOTER_LOADING).assertDoesNotExist()
    }

    /**
     * `I5-C5`. Появление футера `CanLoadMore` отправляет `EndReached` ровно один раз: ни
     * перекомпозиция, ни переход в `LoadingMore` и обратно с тем же числом строк нового
     * запроса не создают; новый — только когда строк стало больше.
     */
    @Test
    fun `I5-C5 the CanLoadMore footer asks for the next page once per list size`() {
        val events = mutableListOf<ArchiveEvent>()
        var state: ArchiveState by mutableStateOf(content(paging = ArchivePaging.CanLoadMore))
        rule.setContent { Archive(state, onEvent = { events += it }) }

        rule.onNodeWithTag(ArchiveTestTags.FOOTER_LOADING).assertExists()
        assertEquals(listOf<ArchiveEvent>(ArchiveEvent.EndReached), events)

        state = content(paging = ArchivePaging.LoadingMore)
        rule.waitForIdle()
        rule.onNodeWithTag(ArchiveTestTags.FOOTER_LOADING).assertExists()
        state = content(paging = ArchivePaging.CanLoadMore)
        rule.waitForIdle()
        assertEquals("тот же размер списка — нового запроса нет", 1, events.size)

        state = content(paging = ArchivePaging.CanLoadMore, extraDays = 1)
        rule.waitForIdle()
        assertEquals(listOf<ArchiveEvent>(ArchiveEvent.EndReached, ArchiveEvent.EndReached), events)
    }

    // --- I5-C6 ---------------------------------------------------------------------------

    /**
     * Перенос проверяется на настоящих метриках шрифта: в режиме графики Robolectric по
     * умолчанию текст почти не имеет ширины, и `FlowRow` там не переносит ничего.
     */
    @Test
    @Config(qualifiers = "w320dp-h844dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `I5-C6 at 320 dp and 200 percent the second line wraps without horizontal scrolling`() {
        rule.setContent { WithFontScale(FONT_SCALE_200) { Archive(content()) } }

        // Завершённая строка — две текстовые строки. Незавершённая при 320 dp и 200 %
        // выше: пометка «не завершён» не помещается рядом и переносится целиком на третью.
        val complete = rowTarget(COMPLETE_DATE).fetchSemanticsNode()
        val incomplete = rowTarget(INCOMPLETE_DATE).fetchSemanticsNode()
        assertTrue(
            "вторая строка переносится: ${incomplete.size.height} > ${complete.size.height}",
            incomplete.size.height > complete.size.height,
        )
        // Ни одна строка не выходит за ширину колонки.
        val list = rule.onNodeWithTag(ArchiveTestTags.LIST).fetchSemanticsNode()
        assertTrue(incomplete.boundsInRoot.right <= list.boundsInRoot.right + 1f)
        assertNoHorizontalScrolling()
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `I5-C6 the Empty CTA stays visible at 320 dp and 200 percent`() {
        rule.setContent { WithFontScale(FONT_SCALE_200) { Archive(ArchiveState.Empty) } }

        rule.onNodeWithTag(ArchiveTestTags.EMPTY_CTA).assertIsDisplayed()
        assertNoHorizontalScrolling()
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `I5-C6 the Error retry stays visible at 320 dp and 200 percent`() {
        rule.setContent { WithFontScale(FONT_SCALE_200) { Archive(ArchiveState.Error) } }

        rule.onNodeWithTag(ArchiveTestTags.RETRY_BUTTON).assertIsDisplayed()
        assertNoHorizontalScrolling()
    }

    // --- I5-C7 ---------------------------------------------------------------------------

    @Test
    fun `I5-C7 the archive renders in dark theme`() {
        rule.setContent {
            PoPoRyadkuTheme(darkTheme = true) {
                ArchiveScreen(state = content(paging = ArchivePaging.CanLoadMore), onEvent = {})
            }
        }

        rule.onNodeWithTag(ArchiveTestTags.STATISTICS).assertIsDisplayed()
        rule.onNodeWithTag(ArchiveTestTags.row(COMPLETE_DATE)).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w844dp-h390dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `I5-C7 in landscape the column is limited to the content max width`() {
        rule.setContent { Archive(content()) }

        val list = rule.onNodeWithTag(ArchiveTestTags.LIST).fetchSemanticsNode()
        val maxWidth = with(rule.density) { Sizing.contentMaxWidth.roundToPx() }
        assertTrue("колонка ${list.size.width} ≤ $maxWidth", list.size.width <= maxWidth)
        rule.onNodeWithTag(ArchiveTestTags.STATISTICS).assertIsDisplayed()
        assertNoHorizontalScrolling()
    }

    // --- Инфраструктура ------------------------------------------------------------------

    @Composable
    private fun Archive(state: ArchiveState, onEvent: (ArchiveEvent) -> Unit = {}) {
        PoPoRyadkuTheme(darkTheme = false) {
            ArchiveScreen(state = state, onEvent = onEvent)
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

    private fun isHeading() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    /**
     * Сенсорная цель строки дня: тег стоит на обёртке строки вместе с её hairline, а
     * нажимаемый узел с описанием — единственный кликабельный потомок.
     */
    private fun rowTarget(date: LocalDate) =
        rule.onNode(hasClickAction() and hasAnyAncestor(hasTestTag(ArchiveTestTags.row(date))))

    private fun assertNoHorizontalScrolling() {
        assertTrue(
            "в архиве не должно быть горизонтальной прокрутки",
            rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    private fun content(
        averageTenths: Int? = 137,
        paging: ArchivePaging = ArchivePaging.EndReached,
        extraDays: Int = 0,
    ) = ArchiveState.Content(
        statistics = ArchiveStatistics(
            playedDays = 23,
            averageTenths = averageTenths,
            bestDayScore = 17,
            currentStreak = 6,
            bestStreak = 9,
        ),
        items = listOf(
            ArchiveItem(COMPLETE_DATE, dayNumber = 12, totalScore = 15, completedCount = 3, isComplete = true),
            ArchiveItem(INCOMPLETE_DATE, dayNumber = 11, totalScore = 8, completedCount = 2, isComplete = false),
        ) + (1..extraDays).map { offset ->
            ArchiveItem(INCOMPLETE_DATE.minusDays(offset.toLong()), 11 - offset, 18, 3, isComplete = true)
        },
        paging = paging,
    )

    private companion object {
        val COMPLETE_DATE: LocalDate = LocalDate.of(2026, 8, 25)
        val INCOMPLETE_DATE: LocalDate = LocalDate.of(2026, 8, 24)

        const val FONT_SCALE_200 = 2f
        const val TITLE = "Архив"
        const val STATISTICS = "Статистика"
        const val BACK = "Назад"
        const val EMPTY_TEXT = "Здесь появятся ваши результаты"
        const val EMPTY_CTA = "К заданию дня"
        const val ERROR_TEXT = "Не удалось прочитать историю"
        const val INCOMPLETE = "не завершён"
        const val INCOMPLETE_DESCRIPTION = "24 августа 2026. День 11. 8 из 18. Не завершён, пройдено заданий: 2 из 3"
        const val OPEN_DAY = "Открыть итог дня"
        val PAIR_LABELS = listOf("СЫГРАНО ДНЕЙ", "СРЕДНИЙ БАЛЛ", "ЛУЧШИЙ ДЕНЬ", "СЕРИЯ", "ЛУЧШАЯ СЕРИЯ")
    }
}
