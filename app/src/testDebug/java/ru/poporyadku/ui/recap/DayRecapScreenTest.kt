package ru.poporyadku.ui.recap

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.poporyadku.core.model.Category
import ru.poporyadku.ui.navigation.RouteOrigin
import ru.poporyadku.ui.theme.PoPoRyadkuTheme
import ru.poporyadku.ui.theme.Sizing

/**
 * `DayRecapScreen` — ITERATION_3_DESIGN.md, `I3-C10`, `I3-C15`, `I3-C17` и
 * Recap-части `I3-C11`–`I3-C13`; ITERATION_5_DESIGN.md, §10.4: `I5-C8`…`I5-C10`.
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
        rule.onNodeWithTag(DayRecapTestTags.PRIMARY_BUTTON).assertIsDisplayed()
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
                content(
                    slots = listOf(
                        SlotResultUi.Played(0, 6, Category.GEOGRAPHY, isOpenable = false),
                        SlotResultUi.Unavailable(1, 0),
                        SlotResultUi.Unavailable(2, 4),
                    ),
                    total = 10,
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
                content(
                    slots = listOf(
                        SlotResultUi.Played(0, 6, Category.GEOGRAPHY, isOpenable = false),
                        SlotResultUi.Unavailable(1, 0),
                        SlotResultUi.Played(2, 4, Category.CULTURE, isOpenable = false),
                    ),
                    total = 10,
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
        rule.setContent { Recap(DayRecapState.NotFound(RouteOrigin.Session)) }

        rule.onNodeWithTag(DayRecapTestTags.NOT_FOUND).assertIsDisplayed()
        rule.onNodeWithText(NOT_FOUND_TEXT).assertIsDisplayed()
        rule.onNodeWithText(RETRY).assertDoesNotExist()
        rule.onNodeWithTag(DayRecapTestTags.PRIMARY_BUTTON).assertDoesNotExist()
        // Сессионный recapMissing: выход — системная «назад», кнопки в шапке нет.
        rule.onNodeWithContentDescription(BACK).assertDoesNotExist()
    }

    /** Заголовок сегодняшнего итога — «Сегодня», leading-иконки «Назад» нет. */
    @Test
    fun `today recap has no leading back icon`() {
        rule.setContent { Recap(played(total = 15, scores = listOf(6, 5, 4))) }

        rule.onNodeWithText(TITLE_TODAY).assertIsDisplayed()
        rule.onNodeWithContentDescription(BACK).assertDoesNotExist()
    }

    /** Итог прошлого дня, открытый из сессии, показывает дату этого дня. */
    @Test
    fun `a past day shows the date of that day`() {
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
    fun `done button emits PrimaryClicked`() {
        val events = mutableListOf<DayRecapEvent>()
        rule.setContent {
            Recap(played(total = 15, scores = listOf(6, 5, 4)), onEvent = { events += it })
        }

        rule.onNodeWithTag(DayRecapTestTags.PRIMARY_BUTTON).assertTextEquals(DONE).performClick()

        assertEquals(listOf<DayRecapEvent>(DayRecapEvent.PrimaryClicked), events)
    }

    // --- I3-C11 / I3-C12 / I3-C13, Recap-часть ---------------------------------------

    /** `I3-C11`, Recap-часть. На 320 dp нет горизонтальной прокрутки. */
    @Test
    @Config(qualifiers = "w320dp-h844dp")
    fun `I3-C11 recap renders on 320 dp without horizontal scrolling`() {
        rule.setContent { Recap(played(total = 15, scores = listOf(6, 5, 4))) }

        assertNoHorizontalOverflow()
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

        // assertExists недостаточно: Compose кликает и по узлу, уехавшему за пределы
        // viewport, — кнопка обязана остаться ВИДИМОЙ.
        rule.onNodeWithTag(DayRecapTestTags.PRIMARY_BUTTON).assertIsDisplayed()
        rule.onNodeWithTag(DayRecapTestTags.PRIMARY_BUTTON).assertHasClickAction()
        rule.onNodeWithTag(DayRecapTestTags.PRIMARY_BUTTON).assertIsEnabled()
        rule.onNodeWithTag(DayRecapTestTags.PRIMARY_BUTTON).performClick()

        assertEquals(listOf<DayRecapEvent>(DayRecapEvent.PrimaryClicked), events)
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
        rule.onNodeWithTag(DayRecapTestTags.PRIMARY_BUTTON).assertIsDisplayed()
    }

    // --- I5-C8: архивный итог ----------------------------------------------------------

    /**
     * `I5-C8`. Архивный итог: «Назад» в шапке и внизу, дата в заголовке и для сегодняшнего
     * дня; незавершённый день — «День не завершён», строки «Задание N» / «не сыграно» без
     * «0 из 6», без `StreakRow`, без «Лучшей серии» и без «Поделиться».
     */
    @Test
    fun `I5-C8 an archived incomplete day`() {
        val today = LocalDate.of(2026, 9, 11)
        val events = mutableListOf<DayRecapEvent>()
        rule.setContent { Recap(archivedIncomplete(title = DayRecapTitle.Date(today)), onEvent = { events += it }) }

        rule.onNodeWithText("11 сентября 2026").assertIsDisplayed()
        rule.onNodeWithText(TITLE_TODAY).assertDoesNotExist()
        rule.onNodeWithTag(DayRecapTestTags.INCOMPLETE).assertIsDisplayed()
        rule.onNodeWithText(DAY_INCOMPLETE).assertIsDisplayed()

        // NotPlayed: «Задание N» слева, «не сыграно» справа — ни категории, ни «0 из 6».
        rule.onNodeWithText("Задание 2").assertIsDisplayed()
        rule.onNodeWithText("Задание 3").assertIsDisplayed()
        assertEquals(2, rule.onAllNodes(hasText(NOT_PLAYED)).fetchSemanticsNodes().size)
        rule.onNodeWithText("0 из 6").assertDoesNotExist()
        assertEquals(1, rule.onAllNodes(hasContentDescription(CATEGORY_CULTURE)).fetchSemanticsNodes().size)

        rule.onNodeWithTag(DayRecapTestTags.STREAK).assertDoesNotExist()
        rule.onNodeWithTag(DayRecapTestTags.BEST_STREAK).assertDoesNotExist()
        rule.onNodeWithText(SHARE).assertDoesNotExist()

        rule.onNodeWithContentDescription(BACK).performClick()
        rule.onNodeWithTag(DayRecapTestTags.PRIMARY_BUTTON).assertTextEquals(BACK).performClick()
        assertEquals(listOf(DayRecapEvent.BackClicked, DayRecapEvent.PrimaryClicked), events)
    }

    /** `I5-C8`. Завершённый архивный день показывает серию этого дня и рекорд тем же значением. */
    @Test
    fun `I5-C8 an archived complete day shows the streak of that day`() {
        rule.setContent {
            Recap(
                played(total = 15, scores = listOf(6, 5, 4), isRecordUpdated = true, origin = RouteOrigin.Archive)
                    .copy(title = DayRecapTitle.Date(LocalDate.of(2026, 8, 25)), streakDays = 5),
            )
        }

        rule.onNodeWithText("25 августа 2026").assertIsDisplayed()
        rule.onNodeWithContentDescription(BACK).assertIsDisplayed()
        rule.onNodeWithTag(DayRecapTestTags.INCOMPLETE).assertDoesNotExist()
        // Обе строки — «5 дней»: рекорд, установленный днём, равен его серии (O5-5).
        assertEquals(2, rule.onAllNodes(hasText("5 дней")).fetchSemanticsNodes().size)
    }

    /** `I5-C8`. Архивный recapMissing: «Назад» в шапке — единственный экранный выход. */
    @Test
    fun `I5-C8 an archived missing day keeps the top bar back button`() {
        val events = mutableListOf<DayRecapEvent>()
        rule.setContent { Recap(DayRecapState.NotFound(RouteOrigin.Archive), onEvent = { events += it }) }

        rule.onNodeWithText(NOT_FOUND_TEXT).assertIsDisplayed()
        rule.onNodeWithText(RETRY).assertDoesNotExist()
        rule.onNodeWithTag(DayRecapTestTags.PRIMARY_BUTTON).assertDoesNotExist()
        rule.onNodeWithContentDescription(BACK).performClick()
        assertEquals(listOf<DayRecapEvent>(DayRecapEvent.BackClicked), events)
    }

    // --- I5-C9: сессионный итог не изменился -------------------------------------------

    @Test
    fun `I5-C9 the session recap keeps no top bar button and Done`() {
        rule.setContent { Recap(played(total = 15, scores = listOf(6, 5, 4))) }

        rule.onNodeWithContentDescription(BACK).assertDoesNotExist()
        rule.onNodeWithTag(DayRecapTestTags.PRIMARY_BUTTON).assertTextEquals(DONE)
        rule.onNodeWithTag(DayRecapTestTags.INCOMPLETE).assertDoesNotExist()
        // Строки сессионного итога не нажимаются.
        assertTrue(rowNodes().none { SemanticsActions.OnClick in it.config })
    }

    // --- I5-C10: нажимается только Played архива ---------------------------------------

    @Test
    fun `I5-C10 only Played rows of the archive recap are clickable buttons`() {
        val events = mutableListOf<DayRecapEvent>()
        rule.setContent {
            Recap(
                content(
                    origin = RouteOrigin.Archive,
                    slots = listOf(
                        SlotResultUi.Played(0, 5, Category.GEOGRAPHY, isOpenable = true),
                        SlotResultUi.Unavailable(1, 3),
                        SlotResultUi.NotPlayed(2),
                    ),
                    total = 8,
                    isComplete = false,
                ),
                onEvent = { events += it },
            )
        }

        val rows = rowNodes()
        assertEquals(3, rows.size)
        val played = rows[0].config
        assertEquals(Role.Button, played.getOrNull(SemanticsProperties.Role))
        assertEquals(OPEN_RESULT, played.getOrNull(SemanticsActions.OnClick)?.label)
        val touchTarget = with(rule.density) { Sizing.touchTargetMin.roundToPx() }
        assertTrue("строка — одна цель не ниже 48 dp", rows[0].size.height >= touchTarget)
        assertNull("Unavailable без действия нажатия", rows[1].config.getOrNull(SemanticsActions.OnClick))
        assertNull("NotPlayed без действия нажатия", rows[2].config.getOrNull(SemanticsActions.OnClick))

        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button) and hasText("5 из 6"))
            .performClick()
        assertEquals(listOf<DayRecapEvent>(DayRecapEvent.SlotClicked(0)), events)
    }

    // --- I5-C15: «Поделиться» ----------------------------------------------------------

    /** `I5-C15`. Кнопка есть у завершённого сессионного итога и отправляет ровно одно событие. */
    @Test
    fun `I5-C15 the session recap of a completed day can be shared`() {
        val events = mutableListOf<DayRecapEvent>()
        rule.setContent {
            Recap(played(total = 15, scores = listOf(6, 5, 4)), onEvent = { events += it })
        }

        // Кнопка живёт в прокручиваемой части экрана — «есть» значит «доезжает и видна».
        rule.onNodeWithTag(DayRecapTestTags.SHARE_BUTTON).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(DayRecapTestTags.SHARE_BUTTON).assertTextEquals(SHARE)
        rule.onNodeWithTag(DayRecapTestTags.SHARE_BUTTON).assertIsEnabled()
        rule.onNodeWithTag(DayRecapTestTags.SHARE_BUTTON).performClick()

        assertEquals(listOf<DayRecapEvent>(DayRecapEvent.ShareClicked), events)
    }

    /** `I5-C15`. Та же кнопка у завершённого архивного итога — вариант её не меняет. */
    @Test
    fun `I5-C15 the archived recap of a completed day can be shared`() {
        val events = mutableListOf<DayRecapEvent>()
        rule.setContent {
            Recap(
                played(total = 15, scores = listOf(6, 5, 4), origin = RouteOrigin.Archive)
                    .copy(title = DayRecapTitle.Date(LocalDate.of(2026, 8, 25))),
                onEvent = { events += it },
            )
        }

        rule.onNodeWithTag(DayRecapTestTags.SHARE_BUTTON).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(DayRecapTestTags.SHARE_BUTTON).performClick()

        assertEquals(listOf<DayRecapEvent>(DayRecapEvent.ShareClicked), events)
    }

    /** `I5-C15`. У незавершённого дня кнопки нет: в карточке нет символа «не сыграно». */
    @Test
    fun `I5-C15 an incomplete day has no share button`() {
        rule.setContent { Recap(archivedIncomplete(title = DayRecapTitle.Date(LocalDate.of(2026, 9, 11)))) }

        rule.onNodeWithTag(DayRecapTestTags.SHARE_BUTTON).assertDoesNotExist()
        rule.onNodeWithText(SHARE).assertDoesNotExist()
    }

    /** `I5-C15`. Кнопка стоит после серии и рекорда и выше основной кнопки. */
    @Test
    fun `I5-C15 the share button sits between the streak and the primary button`() {
        rule.setContent {
            Recap(played(total = 18, scores = listOf(6, 6, 6), isRecordUpdated = true))
        }

        val streakBottom = rule.onNodeWithTag(DayRecapTestTags.BEST_STREAK).fetchSemanticsNode().boundsInRoot.bottom
        val share = rule.onNodeWithTag(DayRecapTestTags.SHARE_BUTTON).fetchSemanticsNode().boundsInRoot
        val primaryTop = rule.onNodeWithTag(DayRecapTestTags.PRIMARY_BUTTON).fetchSemanticsNode().boundsInRoot.top

        assertTrue("«Поделиться» ниже строки рекорда", share.top >= streakBottom)
        assertTrue("«Поделиться» выше основной кнопки", share.bottom <= primaryTop)
    }

    /** `I5-C15`. На 320 dp при масштабе 200% кнопка достижима и остаётся целью не ниже 48 dp. */
    @Test
    @Config(qualifiers = "w320dp-h844dp")
    fun `I5-C15 the share button stays reachable at 320 dp and font scale 200 percent`() {
        val events = mutableListOf<DayRecapEvent>()
        rule.setContent {
            WithFontScale(FONT_SCALE_200) {
                Recap(played(total = 15, scores = listOf(6, 5, 4)), onEvent = { events += it })
            }
        }

        // Кнопка живёт в прокручиваемой части: «достижима» — значит доезжает и видна.
        rule.onNodeWithTag(DayRecapTestTags.SHARE_BUTTON).performScrollTo().assertIsDisplayed()
        val touchTarget = with(rule.density) { Sizing.touchTargetMin.roundToPx() }
        assertTrue(
            "цель нажатия не ниже 48 dp",
            rule.onNodeWithTag(DayRecapTestTags.SHARE_BUTTON).fetchSemanticsNode().size.height >= touchTarget,
        )
        rule.onNodeWithTag(DayRecapTestTags.SHARE_BUTTON).performClick()

        assertEquals(listOf<DayRecapEvent>(DayRecapEvent.ShareClicked), events)
        assertNoHorizontalOverflow()
    }

    /**
     * Строка результата — один узел без собственного описания: TalkBack читает её части по
     * порядку, поэтому в узле есть и категория (или «Задание N»), и счёт (или «не сыграно»).
     */
    @Test
    fun `every result row exposes both of its parts`() {
        rule.setContent {
            Recap(
                content(
                    origin = RouteOrigin.Archive,
                    slots = listOf(
                        SlotResultUi.Played(0, 5, Category.GEOGRAPHY, isOpenable = true),
                        SlotResultUi.Unavailable(1, 3),
                        SlotResultUi.NotPlayed(2),
                    ),
                    total = 8,
                    isComplete = false,
                ),
            )
        }

        val rows = rowNodes()
        assertEquals(listOf(CATEGORY_GEOGRAPHY), rows[0].config.getOrNull(SemanticsProperties.ContentDescription))
        assertEquals(listOf("5 из 6"), rows[0].config.getOrNull(SemanticsProperties.Text)?.map { it.text })
        assertEquals(listOf("Задание 2", "3 из 6"), rows[1].config.getOrNull(SemanticsProperties.Text)?.map { it.text })
        assertEquals(listOf("Задание 3", NOT_PLAYED), rows[2].config.getOrNull(SemanticsProperties.Text)?.map { it.text })
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

    private fun assertNoHorizontalOverflow() {
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

    private fun played(
        total: Int,
        scores: List<Int>,
        isRecordUpdated: Boolean = false,
        origin: RouteOrigin = RouteOrigin.Session,
    ) = content(
        origin = origin,
        slots = listOf(
            SlotResultUi.Played(0, scores[0], Category.GEOGRAPHY, isOpenable = origin == RouteOrigin.Archive),
            SlotResultUi.Played(1, scores[1], Category.HISTORY, isOpenable = origin == RouteOrigin.Archive),
            SlotResultUi.Played(2, scores[2], Category.SCIENCE, isOpenable = origin == RouteOrigin.Archive),
        ),
        total = total,
        isRecordUpdated = isRecordUpdated,
    )

    private fun archivedIncomplete(title: DayRecapTitle) = content(
        origin = RouteOrigin.Archive,
        title = title,
        slots = listOf(
            SlotResultUi.Played(0, 5, Category.CULTURE, isOpenable = true),
            SlotResultUi.NotPlayed(1),
            SlotResultUi.NotPlayed(2),
        ),
        total = 5,
        isComplete = false,
    )

    private fun content(
        slots: List<SlotResultUi>,
        total: Int,
        origin: RouteOrigin = RouteOrigin.Session,
        title: DayRecapTitle = DayRecapTitle.Today,
        isComplete: Boolean = true,
        isRecordUpdated: Boolean = false,
    ) = DayRecapState.Content(
        origin = origin,
        title = title,
        dayNumber = 12,
        totalScore = total,
        isComplete = isComplete,
        slots = slots,
        streakDays = if (isComplete) 6 else null,
        isRecordUpdated = isRecordUpdated,
        canShare = isComplete,
    )

    private companion object {
        const val FONT_SCALE_200 = 2f

        /** Смешанная раскладка дала бы строки, отличающиеся примерно вдвое по высоте. */
        const val MIXED_LAYOUT_RATIO = 1.8

        const val TITLE_TODAY = "Сегодня"
        const val NOT_FOUND_TEXT = "Данные за этот день не сохранились"
        const val RETRY = "Повторить"
        const val BACK = "Назад"
        const val DONE = "Готово"
        const val SHARE = "Поделиться"
        const val DAY_INCOMPLETE = "День не завершён"
        const val NOT_PLAYED = "не сыграно"
        const val OPEN_RESULT = "Открыть результат"
        const val CATEGORY_GEOGRAPHY = "Категория: География"
        const val CATEGORY_CULTURE = "Категория: Культура"
    }
}
