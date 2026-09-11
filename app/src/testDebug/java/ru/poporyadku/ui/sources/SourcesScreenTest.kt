package ru.poporyadku.ui.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import org.robolectric.annotation.GraphicsMode
import ru.poporyadku.core.model.Puzzle
import ru.poporyadku.ui.platform.FakeExternalApps
import ru.poporyadku.ui.platform.LocalExternalApps
import ru.poporyadku.ui.theme.PoPoRyadkuTheme
import ru.poporyadku.ui.theme.Sizing

/**
 * `SourcesScreen` — ITERATION_5_DESIGN.md, §3.11, §10.4: `I5-C14`, часть `I5-C16`.
 *
 * Внешние действия подставляются фейком через `LocalExternalApps`: проверяется само
 * правило трёх состояний `SourceRow`, а не набор приложений в образе Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
class SourcesScreenTest {

    @get:Rule
    val rule = createComposeRule()

    // --- I5-C14: четыре состояния ---------------------------------------------------------

    /** `I5-C14`. `Loading` — восемь skeleton-строк по силуэту `SourceRow`, шапка на месте. */
    @Test
    fun `I5-C14 loading shows eight source row skeletons`() {
        rule.setContent { Sources(SourcesState.Loading) }

        rule.onNodeWithTag(SourcesTestTags.LOADING).assertIsDisplayed()
        assertEquals(8, rule.onAllNodesWithTag(SourcesTestTags.ROW_SKELETON).fetchSemanticsNodes().size)
        rule.onNode(hasText(TITLE) and isHeading()).assertIsDisplayed()
    }

    /** `I5-C14`. `Empty` — одна строка без кнопки; единственное действие — «Назад». */
    @Test
    fun `I5-C14 empty shows the text without a call to action`() {
        rule.setContent { Sources(SourcesState.Empty) }

        rule.onNodeWithText(EMPTY).assertIsDisplayed()
        assertEquals(
            "нажимается только «Назад»",
            1,
            rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size,
        )
    }

    /** `I5-C14`. `Error` — «Не удалось прочитать источники» + «Повторить» → `RetryClicked`. */
    @Test
    fun `I5-C14 error offers retry`() {
        val events = mutableListOf<SourcesEvent>()
        rule.setContent { Sources(SourcesState.Error, onEvent = { events += it }) }

        rule.onNodeWithText(ERROR).assertIsDisplayed()
        rule.onNodeWithTag(SourcesTestTags.RETRY_BUTTON).assertIsDisplayed().performClick()
        rule.onNodeWithContentDescription(BACK).performClick()

        assertEquals(listOf(SourcesEvent.RetryClicked, SourcesEvent.BackClicked), events)
    }

    /**
     * `I5-C14`. `Content`: строка `link` (обработчик есть) — одна цель с ролью кнопки и
     * описанием с названием; нажатие вызывает `viewUrl` фейка ровно один раз.
     */
    @Test
    fun `I5-C14 a link row calls viewUrl exactly once`() {
        val apps = FakeExternalApps()
        rule.setContent { Sources(content(link), apps = apps) }

        val row = rule.onNodeWithContentDescription("${link.title}. Открыть источник в браузере")
        row.assertHasClickAction().assertHeightIsAtLeast(Sizing.touchTargetMin)
        assertEquals(Role.Button, row.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        row.performClick()

        assertEquals(listOf(link.url), apps.viewedUrls)
    }

    /**
     * `I5-C14`. `urlPlainText` (обработчика нет) — URL виден текстом, действия нет;
     * `referenceOnly` — только печатная ссылка, действия нет. Подпись вида — русская.
     */
    @Test
    fun `I5-C14 plain text and reference only rows have no action and a russian kind`() {
        val apps = FakeExternalApps(canView = { false })
        rule.setContent { Sources(content(link, reference), apps = apps) }

        rule.onNodeWithText(link.url!!).assertIsDisplayed()
        rule.onNodeWithText(link.title).assertHasNoClickAction()
        rule.onNodeWithText(reference.reference!!).assertIsDisplayed()
        rule.onNodeWithText(reference.title).assertHasNoClickAction()
        rule.onNodeWithContentDescription("${link.title}. Открыть источник в браузере").assertDoesNotExist()
        assertEquals(
            "нажимается только «Назад»",
            1,
            rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size,
        )
        assertEquals(2, rule.onAllNodes(hasText("Энциклопедия")).fetchSemanticsNodes().size)
        rule.onNodeWithText("encyclopedia").assertDoesNotExist()
        assertTrue(apps.viewedUrls.isEmpty())
    }

    /**
     * Доступность ссылки спрашивается лениво — только у строк, попавших в композицию:
     * при 500 источниках при открытии экрана система не опрашивается о каждом.
     */
    @Test
    fun `availability is checked only for composed rows`() {
        val apps = FakeExternalApps()
        val many = (1..500).map { index ->
            link.copy(sourceId = "s$index", title = "Источник %03d".format(index), url = "https://e.test/$index")
        }
        rule.setContent { Sources(SourcesState.Content(many.map { SourceItemUi("url:${it.url}", it) }), apps = apps) }

        assertTrue("спрошено ${apps.viewQueries.size} из 500", apps.viewQueries.size in 1 until 50)
        assertEquals("каждая строка спрошена один раз", apps.viewQueries.size, apps.viewQueries.toSet().size)
    }

    /** Строки в порядке состояния, ключ — ключ каталога, hairline между строками. */
    @Test
    fun `content rows keep the order and the catalog keys`() {
        rule.setContent { Sources(content(reference, link)) }

        val first = rule.onNodeWithTag(SourcesTestTags.row(keyOf(reference))).fetchSemanticsNode().positionInRoot.y
        val second = rule.onNodeWithTag(SourcesTestTags.row(keyOf(link))).fetchSemanticsNode().positionInRoot.y
        assertTrue(first < second)
    }

    // --- 320 dp / 200 %, темы, ландшафт ---------------------------------------------------

    @Test
    @Config(qualifiers = "w320dp-h844dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `I5-C14 at 320 dp and 200 percent rows wrap and nothing scrolls horizontally`() {
        rule.setContent { WithFontScale(FONT_SCALE_200) { Sources(content(link, reference)) } }

        rule.onNodeWithText(link.title).assertIsDisplayed()
        val list = rule.onNodeWithTag(SourcesTestTags.LIST).fetchSemanticsNode().boundsInRoot
        val title = rule.onNodeWithText(reference.title).fetchSemanticsNode().boundsInRoot
        assertTrue(title.right <= list.right + 1f)
        assertNoHorizontalScrolling()
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `I5-C14 the error retry stays visible at 320 dp and 200 percent`() {
        rule.setContent { WithFontScale(FONT_SCALE_200) { Sources(SourcesState.Error) } }

        rule.onNodeWithTag(SourcesTestTags.RETRY_BUTTON).assertIsDisplayed()
        assertNoHorizontalScrolling()
    }

    /** `I5-C16`. Светлая и тёмная темы; смена темы не теряет строки. */
    @Test
    fun `I5-C16 sources render in light and dark themes`() {
        var dark by mutableStateOf(false)
        rule.setContent {
            CompositionLocalProvider(LocalExternalApps provides FakeExternalApps()) {
                PoPoRyadkuTheme(darkTheme = dark) {
                    SourcesScreen(state = content(link, reference), onEvent = {})
                }
            }
        }
        rule.onNodeWithText(link.title).assertIsDisplayed()

        dark = true
        rule.onNodeWithText(link.title).assertIsDisplayed()
        rule.onNodeWithText(reference.title).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w844dp-h390dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `in landscape the column is limited to the content max width`() {
        rule.setContent { Sources(content(link, reference)) }

        val list = rule.onNodeWithTag(SourcesTestTags.LIST).fetchSemanticsNode()
        val maxWidth = with(rule.density) { Sizing.contentMaxWidth.roundToPx() }
        assertTrue("колонка ${list.size.width} ≤ $maxWidth", list.size.width <= maxWidth)
        assertNoHorizontalScrolling()
    }

    // --- Инфраструктура ------------------------------------------------------------------

    @Composable
    private fun Sources(
        state: SourcesState,
        apps: FakeExternalApps = FakeExternalApps(),
        onEvent: (SourcesEvent) -> Unit = {},
    ) {
        CompositionLocalProvider(LocalExternalApps provides apps) {
            PoPoRyadkuTheme(darkTheme = false) {
                SourcesScreen(state = state, onEvent = onEvent)
            }
        }
    }

    @Composable
    private fun WithFontScale(scale: Float, content: @Composable () -> Unit) {
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) { content() }
    }

    private fun content(vararg sources: Puzzle.Source) =
        SourcesState.Content(sources.map { SourceItemUi(keyOf(it), it) })

    private fun keyOf(source: Puzzle.Source) = source.url?.let { "url:$it" } ?: "ref:${source.sourceId}"

    private fun isHeading() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    private fun assertNoHorizontalScrolling() {
        assertTrue(
            rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    private companion object {
        const val FONT_SCALE_200 = 2f
        const val TITLE = "Источники"
        const val BACK = "Назад"
        const val EMPTY = "Источники появятся после первого сыгранного задания"
        const val ERROR = "Не удалось прочитать источники"

        val link = Puzzle.Source(
            sourceId = "s1",
            title = "Encyclopaedia Britannica, статьи о горных вершинах мира",
            kind = "encyclopedia",
            url = "https://www.britannica.com/",
            reference = null,
            accessedAt = "2026-08-20",
            note = null,
        )

        val reference = Puzzle.Source(
            sourceId = "s2",
            title = "Большая российская энциклопедия, том о географии и геологии",
            kind = "encyclopedia",
            url = null,
            reference = "БРЭ. Т. 35. М., 2017",
            accessedAt = "2026-08-20",
            note = null,
        )
    }
}
