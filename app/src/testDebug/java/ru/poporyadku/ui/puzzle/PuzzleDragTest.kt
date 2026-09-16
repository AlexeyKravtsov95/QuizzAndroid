package ru.poporyadku.ui.puzzle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.poporyadku.core.model.Category
import ru.poporyadku.domain.usecase.Submission
import ru.poporyadku.ui.components.OrderableCard
import ru.poporyadku.ui.components.OrderableCardTestTags
import ru.poporyadku.ui.theme.PoPoRyadkuTheme

/**
 * `I6-C1`…`I6-C7` — жест перетаскивания на экране (ITERATION_6_DESIGN.md, §5, I6-D10,
 * I6-D11, I6-D12, I6-D19).
 *
 * Экран stateless, поэтому подтверждение перестановки здесь делает тот же алгоритм, что и
 * ViewModel — `CardOrder.move` ([Harness]): проверяется связка «жест → событие →
 * подтверждённый порядок → раскладка», а не UI в отрыве от неё.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
class PuzzleDragTest {

    @get:Rule
    val rule = createComposeRule()

    // --- I6-C1 -------------------------------------------------------------------------

    /**
     * `I6-C1`. У каждой карточки есть ручка со своим тестовым тегом; её область касания —
     * не меньше 48 × 48 dp; отдельного узла семантики она не создаёт; высота карточки при
     * 100% осталась 112 dp.
     */
    @Test
    fun `I6-C1 every card has a 48 dp silent handle and keeps the 112 dp card height`() {
        val harness = Harness()
        rule.setContent { harness.Content() }

        val minTouchTargetPx = with(rule.density) { MIN_TOUCH_TARGET.toPx() }
        val cardHeightPx = with(rule.density) { CARD_MIN_HEIGHT.toPx() }

        CARD_IDS.forEach { cardId ->
            val handle = rule.onNodeWithTag(PuzzleTestTags.dragHandle(cardId), useUnmergedTree = true)
                .fetchSemanticsNode()
            assertTrue(
                "$cardId: зона захвата меньше 48 dp — ${handle.size}",
                handle.size.width >= minTouchTargetPx && handle.size.height >= minTouchTargetPx,
            )

            // Ручка не добавляет ни роли, ни описания, ни действия: в объединённом дереве
            // её нет, перестановка для TalkBack идёт только через custom actions карточки.
            rule.onNodeWithTag(PuzzleTestTags.dragHandle(cardId)).assertDoesNotExist()

            val card = rule.onNodeWithTag(OrderableCardTestTags.card(cardId)).fetchSemanticsNode()
            assertEquals(
                "$cardId: вертикальный бюджет карточки вырос",
                cardHeightPx.toInt(),
                card.size.height,
            )
        }
    }

    // --- I6-C2 -------------------------------------------------------------------------

    /**
     * `I6-C2`. Жест за ручку: `DragStarted` → `DragMovedTo(цель)` → `DragFinished` именно в
     * этом порядке; порядок карточек подтверждён; список при этом не прокрутился —
     * указатель потреблён ручкой.
     */
    @Test
    fun `I6-C2 dragging the handle emits start, target and finish in order`() {
        val harness = Harness()
        rule.setContent { harness.Content() }
        val scrollBefore = scrollOffset()

        rule.onNodeWithTag(PuzzleTestTags.dragHandle("c1"), useUnmergedTree = true)
            .performTouchInput {
                down(center)
                moveBy(dragDown(positions = 1))
                up()
            }
        rule.waitForIdle()

        assertEquals(
            listOf(
                PuzzleEvent.DragStarted("c1", harness.gestureOf(0)),
                PuzzleEvent.DragMovedTo("c1", targetIndex = 1, gesture = harness.gestureOf(0)),
                PuzzleEvent.DragFinished("c1", harness.gestureOf(0)),
            ),
            harness.events,
        )
        assertEquals(listOf("c2", "c1", "c3", "c4"), harness.order())
        assertEquals("жест ручки не прокручивает список", scrollBefore, scrollOffset())
    }

    /**
     * `I6-C2`. Подтверждённая перестановка не сдвигает карточку под пальцем: слот уехал на
     * шаг списка, а визуальный центр остался там, куда его привёл палец (§5.3, шаг 4).
     *
     * Без компенсации сдвига слота карточка прыгнула бы ровно на шаг списка в момент
     * подтверждения — это самый заметный дефект жеста и самый незаметный в коде.
     */
    @Test
    fun `I6-C2 a confirmed reorder does not move the card under the finger`() {
        val harness = Harness()
        rule.setContent { harness.Content() }

        val before = cardTop("c1")
        val delta = with(rule.density) { (CARD_STEP + DRAG_MARGIN).toPx() }
        rule.onNodeWithTag(PuzzleTestTags.dragHandle("c1"), useUnmergedTree = true)
            .performTouchInput {
                down(center)
                moveBy(Offset(0f, delta))
            }
        rule.waitForIdle()

        assertEquals("перестановка обязана подтвердиться", listOf("c2", "c1", "c3", "c4"), harness.order())
        // Палец прошёл `delta`, из них `touch slop` съеден до первого onDrag — поэтому
        // сравнение с допуском в размер slop, а не побайтовое.
        val slopPx = with(rule.density) { DRAG_MARGIN.toPx() }
        val actual = cardTop("c1")
        assertTrue(
            "карточка прыгнула: было $before, стало $actual, палец прошёл $delta",
            kotlin.math.abs(actual - (before + delta)) <= slopPx,
        )
    }

    /**
     * `I6-C2`. Второй одновременный указатель на другой ручке не открывает вторую сессию и
     * не отправляет ни одного события.
     */
    @Test
    fun `I6-C2 a second simultaneous pointer sends no events`() {
        val harness = Harness()
        rule.setContent { harness.Content() }

        rule.onNodeWithTag(PuzzleTestTags.dragHandle("c1"), useUnmergedTree = true)
            .performTouchInput {
                down(FIRST_POINTER, center)
                moveBy(FIRST_POINTER, dragDown(positions = 1))
            }
        rule.onNodeWithTag(PuzzleTestTags.dragHandle("c3"), useUnmergedTree = true)
            .performTouchInput {
                down(SECOND_POINTER, center)
                moveBy(SECOND_POINTER, dragDown(positions = 1))
            }
        rule.waitForIdle()

        assertEquals(
            "вторая карточка событий не порождает",
            emptyList<String>(),
            harness.events.mapNotNull { event ->
                when (event) {
                    is PuzzleEvent.DragStarted -> event.cardId
                    is PuzzleEvent.DragMovedTo -> event.cardId
                    is PuzzleEvent.DragFinished -> event.cardId
                    else -> null
                }
            }.filterNot { it == "c1" },
        )
    }

    // --- I6-C3 -------------------------------------------------------------------------

    /**
     * `I6-C3`. Свайп по центральной зоне карточки (вне ручки) при 320 dp и шрифте 200 %
     * **прокручивает список** и **не отправляет ни одного события жеста**: захват
     * начинается только на ручке, а указатель вне её достаётся `LazyColumn` (I6-D10).
     *
     * Окно ниже 844 dp из спецификации намеренно: Robolectric не увеличивает метрики
     * шрифта вместе с `fontScale`, поэтому на 844 dp список здесь не прокручивается вовсе
     * и половина утверждения стала бы пустой. Прокрутка при 320 dp / 200 % на настоящем
     * экране проверена вручную (`I6-M1`).
     */
    @Test
    @Config(qualifiers = "w320dp-h400dp", fontScale = FONT_SCALE_200)
    fun `I6-C3 a swipe outside the handle scrolls the list and sends no gesture events`() {
        val harness = Harness()
        rule.setContent { harness.Content() }
        val scrollBefore = scrollOffset()

        rule.onNodeWithTag(OrderableCardTestTags.card("c2")).performTouchInput {
            down(center)
            moveBy(dragUp())
            up()
        }
        rule.waitForIdle()

        assertTrue("центральная зона карточки жест не начинает", harness.events.isEmpty())
        assertTrue("список обязан прокрутиться", scrollOffset() > scrollBefore)
        assertEquals("порядок карточек прокруткой не меняется", CARD_IDS, harness.order())
    }

    // --- I6-C4 -------------------------------------------------------------------------

    /** `I6-C4`. Отмена жеста даёт тот же `DragFinished`; дальнейших событий нет. */
    @Test
    fun `I6-C4 a cancelled gesture finishes exactly like a released one`() {
        val harness = Harness()
        rule.setContent { harness.Content() }

        rule.onNodeWithTag(PuzzleTestTags.dragHandle("c1"), useUnmergedTree = true)
            .performTouchInput {
                down(center)
                moveBy(dragDown(positions = 1))
                cancel()
            }
        rule.waitForIdle()

        assertEquals(
            PuzzleEvent.DragFinished("c1", harness.gestureOf(0)),
            harness.events.last(),
        )
        assertEquals("после отмены событий больше нет", 1, harness.events.count { it is PuzzleEvent.DragFinished })
        assertEquals("последний подтверждённый порядок сохранён", listOf("c2", "c1", "c3", "c4"), harness.order())
    }

    // --- I6-C5 -------------------------------------------------------------------------

    /** `I6-C5`. В `Submitting.Answer` ручка видна, но жест не отправляет ни одного события. */
    @Test
    fun `I6-C5 the handle accepts no gesture while submitting`() {
        val harness = Harness(initial = submitting())
        rule.setContent { harness.Content() }

        rule.onNodeWithTag(PuzzleTestTags.dragHandle("c1"), useUnmergedTree = true)
            .assertExists()
            .performTouchInput {
                down(center)
                moveBy(dragDown(positions = 1))
                up()
            }
        rule.waitForIdle()

        assertTrue("во время записи жест не принимается", harness.events.isEmpty())
    }

    /** `I6-C5`. Read-only карточка (`PuzzleResult`): ручки нет в дереве вовсе. */
    @Test
    fun `I6-C5 a read only card has no handle at all`() {
        rule.setContent {
            PoPoRyadkuTheme(darkTheme = false) {
                OrderableCard(
                    cardId = "c1",
                    position = 1,
                    totalPositions = CARD_IDS.size,
                    title = TITLES.first(),
                    subtitle = null,
                )
            }
        }

        rule.onNodeWithTag(PuzzleTestTags.dragHandle("c1"), useUnmergedTree = true)
            .assertDoesNotExist()
    }

    // --- I6-C6 -------------------------------------------------------------------------

    /**
     * `I6-C6`. Удержание карточки в нижней краевой зоне прокручивает список по кадрам и
     * подтверждает пройденные карточки; после отпускания прокрутка прекращается.
     *
     * Часы остановлены (`autoAdvance = false`): кадры выдаются тестом, поэтому проверяется
     * именно кадровый цикл, а не «успела ли анимация».
     */
    @Test
    @Config(qualifiers = "w320dp-h400dp")
    fun `I6-C6 holding the card at the bottom edge auto scrolls and keeps it under the finger`() {
        val harness = Harness()
        rule.setContent { harness.Content() }

        val topBeforeDrag = cardTop("c1")
        val scrollBefore = scrollOffset()
        val fingerDelta = with(rule.density) { BOTTOM_EDGE_DRAG.toPx() }

        // Палец уходит в нижнюю краевую зону и **останавливается**: всё дальнейшее
        // движение списка выполняет кадровый цикл авто-прокрутки, а не жест.
        rule.onNodeWithTag(PuzzleTestTags.dragHandle("c1"), useUnmergedTree = true)
            .performTouchInput {
                down(center)
                moveBy(Offset(0f, fingerDelta))
            }
        // Цикл крутится на часах теста и сам останавливается, когда список упирается в
        // конец: покадровая подача здесь не используется — при остановленных часах
        // Robolectric жест стартует, но цикл авто-прокрутки не запускается вовсе, и
        // проверка стала бы проверкой окружения. Настоящая покадровая динамика закрыта
        // `I6-N1`…`I6-N3` и ручной `I6-M1` на эмуляторе.
        rule.waitForIdle()

        val scrolled = scrollOffset()
        val scrolledToEnd = canScrollForward()
        val orderWhileHeld = harness.order()
        val topWhileHeld = cardTop("c1")

        rule.onNodeWithTag(PuzzleTestTags.dragHandle("c1"), useUnmergedTree = true)
            .performTouchInput { up() }
        rule.waitForIdle()
        val afterRelease = scrollOffset()
        rule.mainClock.advanceTimeBy(IDLE_WINDOW_MS)
        rule.waitForIdle()
        val afterIdle = scrollOffset()

        // Прокрутка действительно потреблена списком.
        assertTrue("авто-прокрутка не сдвинула список: $scrollBefore → $scrolled", scrolled > scrollBefore)
        // И она компенсирована в тех же кадрах: карточка стоит ровно там, куда её привёл
        // палец. Некомпенсированный кадр прокрутки сместил бы её на свою долю пути, а за
        // всю прокрутку — на её полную величину.
        assertEquals(
            "карточка ушла из-под пальца (прокручено $scrolled)",
            topBeforeDrag + fingerDelta,
            topWhileHeld,
            with(rule.density) { DRAG_MARGIN.toPx() },
        )
        // Последняя пройденная карточка подтверждена **до** остановки цикла у конца
        // списка: поднятая карточка стоит последней.
        assertFalse("список обязан дойти до конца", scrolledToEnd)
        assertEquals(listOf("c2", "c3", "c4", "c1"), orderWhileHeld)
        // После отпускания цикл остановлен: время идёт, список стоит.
        assertEquals("после отпускания цикл остановлен", afterRelease, afterIdle, CARD_TOLERANCE_PX)
    }

    // --- I6-C7 -------------------------------------------------------------------------

    /**
     * `I6-C7`. Переход в `Submitting.Answer` во время удержания отменяет жест: `DragFinished`
     * отправлен, авто-прокрутка остановлена.
     */
    @Test
    @Config(qualifiers = "w320dp-h400dp")
    fun `I6-C7 switching to submitting during a hold cancels the gesture`() {
        val harness = Harness()
        rule.setContent { harness.Content() }

        rule.onNodeWithTag(PuzzleTestTags.dragHandle("c1"), useUnmergedTree = true)
            .performTouchInput {
                down(center)
                moveBy(dragToBottomEdge())
            }
        rule.waitForIdle()

        harness.submit()
        rule.waitForIdle()

        assertTrue(
            "жест обязан завершиться событием",
            harness.events.any { it is PuzzleEvent.DragFinished },
        )

        val afterSubmit = scrollOffset()
        rule.mainClock.advanceTimeBy(IDLE_WINDOW_MS)
        rule.waitForIdle()
        assertEquals(
            "авто-прокрутка остановлена вместе с жестом",
            afterSubmit,
            scrollOffset(),
            CARD_TOLERANCE_PX,
        )
    }

    // --- Инфраструктура ------------------------------------------------------------------

    /** Может ли список прокручиваться дальше — из той же семантики, что и смещение. */
    private fun canScrollForward(): Boolean {
        val range = rule.onNodeWithTag(PuzzleTestTags.CARD_LIST)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.VerticalScrollAxisRange)
            ?: return false
        return range.value() < range.maxValue()
    }

    /** Визуальный верх карточки в корне — с учётом смещения поднятой карточки. */
    private fun cardTop(cardId: String): Float =
        rule.onNodeWithTag(OrderableCardTestTags.card(cardId))
            .fetchSemanticsNode()
            .positionInRoot
            .y

    /** Текущее смещение прокрутки списка — из семантики, без доступа к внутреннему состоянию. */
    private fun scrollOffset(): Float =
        rule.onNodeWithTag(PuzzleTestTags.CARD_LIST)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.VerticalScrollAxisRange)
            ?.value
            ?.invoke()
            ?: 0f

    /** Смещение вниз ровно на [positions] шагов списка плюс запас на системный touch slop. */
    private fun dragDown(positions: Int) = androidx.compose.ui.geometry.Offset(
        x = 0f,
        y = with(rule.density) { (CARD_STEP * positions + DRAG_MARGIN).toPx() },
    )

    private fun dragUp() = androidx.compose.ui.geometry.Offset(
        x = 0f,
        y = with(rule.density) { -(CARD_STEP + DRAG_MARGIN).toPx() },
    )

    /** Смещение, уводящее карточку в нижнюю краевую зону видимой области. */
    private fun dragToBottomEdge() = androidx.compose.ui.geometry.Offset(
        x = 0f,
        y = with(rule.density) { BOTTOM_EDGE_DRAG.toPx() },
    )

    @Composable
    private fun WithFontScale(scale: Float, content: @Composable () -> Unit) {
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) {
            content()
        }
    }

    /**
     * Стенд экрана: записывает события и подтверждает перестановку тем же алгоритмом, что
     * и ViewModel (`CardOrder.move`). Второй реализации порядка в тесте нет.
     */
    private class Harness(initial: PuzzleUiState = playing()) {

        val events = mutableListOf<PuzzleEvent>()

        private val state = mutableStateOf(initial)

        @Composable
        fun Content() {
            PoPoRyadkuTheme(darkTheme = false) {
                PuzzleScreen(state = state.value, onEvent = ::onEvent)
            }
        }

        fun order(): List<String> = boardOf(state.value).cards.map { it.cardId }

        /** Идентификатор жеста номер [index] среди принятых — генератор процессный. */
        fun gestureOf(index: Int): DragGestureId =
            events.filterIsInstance<PuzzleEvent.DragStarted>()[index].gesture

        fun submit() {
            state.value = PuzzleUiState.Submitting.Answer(
                board = boardOf(state.value),
                pending = Submission.Answer(order()),
            )
        }

        private fun onEvent(event: PuzzleEvent) {
            events += event
            if (event !is PuzzleEvent.DragMovedTo) return

            val playing = state.value as? PuzzleUiState.Playing ?: return
            val next = CardOrder.move(
                order = playing.board.cards.map { it.cardId },
                cardId = event.cardId,
                target = MoveTarget.Index(event.targetIndex),
            ) ?: return
            state.value = playing.copy(board = boardWith(next))
        }

        private fun boardOf(state: PuzzleUiState): PuzzleBoard = when (state) {
            is PuzzleUiState.Playing -> state.board
            is PuzzleUiState.Submitting.Answer -> state.board
            else -> error("состояние без «стола»")
        }
    }

    private companion object {
        val CARD_IDS = listOf("c1", "c2", "c3", "c4")
        val TITLES = listOf("Эльбрус", "Монблан", "Килиманджаро", "Аконкагуа")
        val SUBTITLES = listOf("Кавказ, Россия", "Альпы", "Танзания", "Анды")

        val MIN_TOUCH_TARGET = 48.dp
        val CARD_MIN_HEIGHT = 112.dp

        /** Высота карточки плюс `spacing.listGap`: шаг между центрами соседних карточек. */
        val CARD_STEP = 124.dp

        /** Запас на системный touch slop, который `detectDragGestures` съедает до первого onDrag. */
        val DRAG_MARGIN = 24.dp

        /** Заведомо уводит карточку в нижнюю краевую зону низкого окна. */
        val BOTTOM_EDGE_DRAG = 200.dp

        const val FIRST_POINTER = 0
        const val SECOND_POINTER = 1

        const val FONT_SCALE_200 = 2.0f

        /** Окно времени, за которое остановленный цикл проявил бы себя прокруткой. */
        const val IDLE_WINDOW_MS = 500L

        /** Допуск на округление позиции до целых пикселей при компенсации. */
        const val CARD_TOLERANCE_PX = 2f

        fun boardWith(order: List<String>): PuzzleBoard {
            val byId = CARD_IDS.withIndex().associate { (index, cardId) ->
                cardId to (TITLES[index] to SUBTITLES[index])
            }
            return PuzzleBoard(
                slotIndex = 1,
                totalSlots = 3,
                puzzleId = "tmp-geo-vysota-001",
                category = Category.GEOGRAPHY,
                prompt = "Расположите вершины от самой низкой к самой высокой",
                directionLabel = "Сверху — самая низкая",
                cards = order.mapIndexed { index, cardId ->
                    CardUi(
                        cardId = cardId,
                        title = byId.getValue(cardId).first,
                        subtitle = byId.getValue(cardId).second,
                        position = index + 1,
                        canMoveUp = index > 0,
                        canMoveDown = index < order.lastIndex,
                    )
                },
            )
        }

        fun playing() = PuzzleUiState.Playing(
            board = boardWith(CARD_IDS),
            isSubmitEnabled = true,
            showDragHint = false,
        )

        fun submitting() = PuzzleUiState.Submitting.Answer(
            board = boardWith(CARD_IDS),
            pending = Submission.Answer(CARD_IDS),
        )
    }
}
