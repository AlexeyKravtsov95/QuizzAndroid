package ru.poporyadku.ui.puzzle

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.collectLatest
import ru.poporyadku.R
import ru.poporyadku.core.model.Category
import ru.poporyadku.domain.usecase.PuzzleErrorKind
import ru.poporyadku.domain.usecase.Submission
import ru.poporyadku.ui.components.AppTopBar
import ru.poporyadku.ui.components.CategoryLabel
import ru.poporyadku.ui.components.DragHandle
import ru.poporyadku.ui.components.ErrorBlock
import ru.poporyadku.ui.components.OrderableCard
import ru.poporyadku.ui.components.OrderableCardControls
import ru.poporyadku.ui.components.OrderableCardSkeleton
import ru.poporyadku.ui.components.PrimaryButton
import ru.poporyadku.ui.components.SecondaryButton
import ru.poporyadku.ui.theme.PoPoRyadkuTheme
import ru.poporyadku.ui.theme.ProjectTextStyles
import ru.poporyadku.ui.theme.Sizing
import ru.poporyadku.ui.theme.Spacing
import ru.poporyadku.ui.theme.rememberMotionTokens

/** Стабильные testTag игрового экрана. */
object PuzzleTestTags {
    const val SCREEN = "puzzle_screen"
    const val CONTENT = "puzzle_content"
    const val CARD_LIST = "puzzle_card_list"
    const val SUBMIT_BUTTON = "puzzle_submit_button"
    const val SKIP_BUTTON = "puzzle_skip_button"
    const val RETRY_BUTTON = "puzzle_retry_button"
    const val ERROR_BLOCK = "puzzle_error_block"
    const val SKELETON = "puzzle_skeleton"

    /**
     * Ручка адресуется по `cardId`, никогда по позиции.
     *
     * Тег — не семантика: он не добавляет ни роли, ни описания, ни действия, и в
     * объединённом дереве карточки отдельного узла не создаёт (`I6-C1`).
     */
    fun dragHandle(cardId: String): String = "puzzle_drag_handle_$cardId"
}

/** Слотов в скелетоне ровно столько, сколько карточек появится после загрузки. */
private const val SKELETON_CARDS = 4

/**
 * Игровой экран (ITERATION_3_DESIGN.md, разделы 11 и 15).
 *
 * Stateless: `Screen(state, onEvent)`. ViewModel подключается только в `AppNavHost` —
 * это требование ARCHITECTURE.md §4 и одновременно условие, при котором Compose-тесты
 * экрана работают без Hilt (I3-D31).
 *
 * Системная «назад» проходит через [PuzzleEvent.BackPressed]: без `BackHandler` она
 * обошла бы запрет выхода из `Submitting`, где запись уже идёт.
 */
@Composable
fun PuzzleScreen(
    state: PuzzleUiState,
    onEvent: (PuzzleEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onEvent(PuzzleEvent.BackPressed) }

    val board = state.boardOrNull

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(PuzzleTestTags.SCREEN),
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isCompact = maxWidth < Sizing.compactWidthBreakpoint
            val margin = if (isCompact) Spacing.marginCompact else Spacing.marginDefault
            val isWideOrLandscape = maxWidth >= Sizing.mediumWidthBreakpoint || maxWidth > maxHeight
            val columnWidth = if (isWideOrLandscape) {
                Modifier.widthIn(max = Sizing.contentMaxWidth)
            } else {
                Modifier.fillMaxWidth()
            }

            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(modifier = columnWidth.fillMaxSize()) {
                    PuzzleTopBar(state = state, horizontalMargin = margin, onEvent = onEvent)

                    Box(
                        modifier = Modifier
                            .weight(WEIGHT_FILL)
                            .fillMaxWidth()
                            .testTag(PuzzleTestTags.CONTENT),
                    ) {
                        when (state) {
                            // Структурная ошибка маршрута/назначения не показывает
                            // собственного кадра: экран уже уходит на Home, и мигание
                            // текстом ошибки было бы видно пользователю (I3-D39).
                            is PuzzleUiState.Error -> if (state.retry == RetryAction.None) {
                                PuzzleSkeleton(margin)
                            } else {
                                PuzzleErrorContent(
                                    kind = state.kind,
                                    retry = state.retry,
                                    actionsEnabled = true,
                                    margin = margin,
                                    onEvent = onEvent,
                                )
                            }

                            // Та же композиция, с которой пришли, со всеми действиями
                            // disabled: скелетона и карточек здесь не появляется.
                            is PuzzleUiState.Submitting.Skip -> PuzzleErrorContent(
                                kind = state.sourceErrorKind,
                                retry = retryShapeOf(state.sourceErrorKind),
                                actionsEnabled = false,
                                margin = margin,
                                onEvent = onEvent,
                            )

                            PuzzleUiState.Loading -> PuzzleSkeleton(margin)

                            is PuzzleUiState.Playing,
                            is PuzzleUiState.Submitting.Answer,
                            -> PuzzleBoardContent(
                                board = requireNotNull(board),
                                interactive = state is PuzzleUiState.Playing,
                                margin = margin,
                                onEvent = onEvent,
                            )
                        }
                    }

                    if (state.showsSubmitButton) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .windowInsetsPadding(
                                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
                                )
                                .padding(horizontal = margin)
                                .padding(bottom = Spacing.section, top = Spacing.scale300),
                        ) {
                            PrimaryButton(
                                text = stringResource(R.string.puzzle_submit),
                                onClick = { onEvent(PuzzleEvent.Submit) },
                                enabled = (state as? PuzzleUiState.Playing)?.isSubmitEnabled == true,
                                modifier = Modifier.testTag(PuzzleTestTags.SUBMIT_BUTTON),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PuzzleTopBar(
    state: PuzzleUiState,
    horizontalMargin: Dp,
    onEvent: (PuzzleEvent) -> Unit,
) {
    val board = state.boardOrNull
    val title = if (board != null) {
        stringResource(R.string.puzzle_title, board.slotIndex + 1, board.totalSlots)
    } else {
        stringResource(R.string.puzzle_title_unknown)
    }

    AppTopBar(
        title = title,
        horizontalMargin = horizontalMargin,
        // Та же дорога, что и у системной «назад»: решение принимает ViewModel, и в
        // `Submitting` кнопка так же не действует.
        onBackClick = { onEvent(PuzzleEvent.BackPressed) },
        trailing = board?.let { { CategoryLabel(category = it.category) } },
    )
}

/**
 * Список карточек и всё, что читается над ним.
 *
 * Прокручиваемый контейнер — `LazyColumn`: он же даёт `key = cardId` (не позицию,
 * иначе перестановка пересоздавала бы узлы и анимация была бы неверной) и анимацию
 * перестановки на `motion.duration.long`. Формулировка и подпись направления едут
 * внутри того же списка, поэтому при 200% прокручивается **весь** контентный блок,
 * а кнопка «Проверить» остаётся закреплённой.
 *
 * Жест перетаскивания (ITERATION_6_DESIGN.md, §5): экран держит только визуальное
 * смещение пальца ([ReorderDragState]); порядок карточек приходит из [board] и меняется
 * исключительно подтверждениями ViewModel.
 */
@Composable
private fun PuzzleBoardContent(
    board: PuzzleBoard,
    interactive: Boolean,
    margin: Dp,
    onEvent: (PuzzleEvent) -> Unit,
) {
    val motion = rememberMotionTokens()
    val listState = rememberLazyListState()
    // Не `rememberSaveable` (I6-D9): пересоздание Activity не должно воскрешать
    // «поднятую» карточку — пальца на экране после него нет.
    val dragState = remember(listState) { ReorderDragState(listState) }
    val cardIds = board.cards.map { it.cardId }

    PuzzleDragEffects(
        dragState = dragState,
        cardIds = cardIds,
        interactive = interactive,
        onEvent = onEvent,
    )

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(PuzzleTestTags.CARD_LIST),
        contentPadding = PaddingValues(
            start = margin,
            end = margin,
            top = Spacing.section,
            bottom = Spacing.section,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.listGap),
    ) {
        item(key = "prompt") {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.scale200)) {
                Text(
                    text = board.prompt,
                    style = ProjectTextStyles.editorialTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = board.directionLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(items = board.cards, key = { card -> card.cardId }) { card ->
            val dragged = dragState.draggedCardId == card.cardId

            OrderableCard(
                cardId = card.cardId,
                position = card.position,
                totalPositions = board.cards.size,
                title = card.title,
                subtitle = card.subtitle,
                controls = OrderableCardControls(
                    canMoveUp = card.canMoveUp,
                    canMoveDown = card.canMoveDown,
                    enabled = interactive,
                    onMoveUp = { onEvent(PuzzleEvent.MoveUp(card.cardId)) },
                    onMoveDown = { onEvent(PuzzleEvent.MoveDown(card.cardId)) },
                    onMoveToTop = { onEvent(PuzzleEvent.MoveToTop(card.cardId)) },
                    onMoveToBottom = { onEvent(PuzzleEvent.MoveToBottom(card.cardId)) },
                ),
                dragging = dragged,
                dragHandle = {
                    DragHandle(
                        interactive = interactive,
                        onDragStart = { startDrag(dragState, card.cardId, onEvent) },
                        onDrag = { deltaY ->
                            dragState.drag(deltaY)
                            // Цель считается сразу по движению пальца, а не только в
                            // кадре авто-прокрутки: без этого перестановка ждала бы
                            // следующего кадра даже вдали от краевых зон.
                            dragState.emitTarget(cardIds, onEvent)
                        },
                        onDragFinished = { finishDrag(dragState, onEvent) },
                        modifier = Modifier.testTag(PuzzleTestTags.dragHandle(card.cardId)),
                    )
                },
                modifier = Modifier
                    .zIndex(if (dragged) DRAGGED_Z_INDEX else RESTING_Z_INDEX)
                    .animateItem(
                        // У поднятой карточки placement-анимации нет: иначе она
                        // «догоняла» бы палец, вместо того чтобы следовать за ним.
                        placementSpec = if (dragged) {
                            null
                        } else {
                            tween(
                                durationMillis = motion.durationLong,
                                easing = motion.easingStandard,
                            )
                        },
                    )
                    // Раскладка изменилась — подтверждённой перестановкой или прокруткой:
                    // компенсируем сдвиг слота, чтобы визуальный центр карточки не
                    // прыгнул и остался под пальцем (§5.3, шаг 4).
                    .onGloballyPositioned { if (dragged) dragState.syncSlotTop() }
                    .graphicsLayer { translationY = if (dragged) dragState.offsetY else NO_OFFSET },
            )
        }
    }
}

/**
 * Эффекты жеста: авто-прокрутка у краёв списка и закрытие жеста при уходе из `Playing`
 * (ITERATION_6_DESIGN.md, §5.4, I6-D11, I6-D12).
 *
 * Кадровый цикл живёт **только пока прокрутка действительно нужна**: пока карточка не в
 * краевой зоне или прокручивать некуда, скорость равна нулю и кадры не запрашиваются
 * вовсе. Короткий список поэтому цикл не запускает ни разу, а отпускание, отмена, уход из
 * `Playing` и уход из композиции отменяют корутину — «бесконечной корутины вне
 * композиции» здесь не существует.
 */
@Composable
private fun PuzzleDragEffects(
    dragState: ReorderDragState,
    cardIds: List<String>,
    interactive: Boolean,
    onEvent: (PuzzleEvent) -> Unit,
) {
    val density = LocalDensity.current
    val edgeZonePx = with(density) { Sizing.dragAutoScrollEdgeZone.toPx() }
    val maxVelocityPx = with(density) { Sizing.dragAutoScrollMaxVelocityDpPerSecond.dp.toPx() }
    // Цикл переживает подтверждённые перестановки, поэтому порядок читается через
    // `rememberUpdatedState`: захваченный при запуске список устарел бы после первой же.
    val confirmedOrder by rememberUpdatedState(cardIds)

    // Уход из `Playing` во время удержания: `pointerInput` пересоздаётся по ключу и
    // `onDragCancel` уже не придёт — жест закрывается здесь, тем же событием (I6-C7).
    LaunchedEffect(interactive) {
        if (!interactive) finishDrag(dragState, onEvent)
    }

    LaunchedEffect(dragState.draggedCardId, interactive) {
        if (dragState.draggedCardId == null || !interactive) return@LaunchedEffect

        snapshotFlow { dragState.autoScrollVelocity(edgeZonePx, maxVelocityPx) != NO_VELOCITY }
            .collectLatest { insideEdgeZone ->
                if (!insideEdgeZone) return@collectLatest

                var previousFrameNanos = NO_PREVIOUS_FRAME
                while (true) {
                    val elapsedSeconds = withFrameNanos { frameNanos ->
                        val elapsed = if (previousFrameNanos == NO_PREVIOUS_FRAME) {
                            NO_ELAPSED
                        } else {
                            (frameNanos - previousFrameNanos).toFloat() / NANOS_PER_SECOND
                        }
                        previousFrameNanos = frameNanos
                        elapsed
                    }

                    // Условие перепроверяется каждый кадр: выход из краевой зоны и
                    // достигнутый край списка останавливают прокрутку сразу, а не через
                    // подписку.
                    val velocity = dragState.autoScrollVelocity(edgeZonePx, maxVelocityPx)
                    if (velocity == NO_VELOCITY) break

                    // Фактический `dt`, а не константа кадра: пропущенный кадр не должен
                    // превращаться ни в рывок, ни в замедление прокрутки.
                    dragState.scrollBy(velocity * elapsedSeconds)
                    // Прокрутка сдвинула слот под карточкой — цель считается заново, и
                    // пройденные ею карточки подтверждаются как обычно.
                    dragState.emitTarget(confirmedOrder, onEvent)
                }
            }
    }
}

/** Новый жест: идентификатор выдаётся один раз и живёт до конца этого жеста (I6-D5). */
private fun startDrag(
    dragState: ReorderDragState,
    cardId: String,
    onEvent: (PuzzleEvent) -> Unit,
): Boolean {
    val gesture = DragGestureIds.next()
    if (!dragState.start(cardId, gesture)) return false
    onEvent(PuzzleEvent.DragStarted(cardId, gesture))
    return true
}

/** Отпускание, отмена, потеря указателя и уход из `Playing` — одно и то же (I6-D8). */
private fun finishDrag(dragState: ReorderDragState, onEvent: (PuzzleEvent) -> Unit) {
    val cardId = dragState.draggedCardId ?: return
    val gesture = dragState.gesture ?: return
    dragState.finish()
    onEvent(PuzzleEvent.DragFinished(cardId, gesture))
}

/** Цель пересчитана — отправляем её ViewModel; повтор той же цели там безвреден (I6-D3). */
private fun ReorderDragState.emitTarget(cardIds: List<String>, onEvent: (PuzzleEvent) -> Unit) {
    val cardId = draggedCardId ?: return
    val gesture = gesture ?: return
    val target = targetIndex(cardIds) ?: return
    onEvent(PuzzleEvent.DragMovedTo(cardId, target, gesture))
}

/**
 * Композиция ошибки. Два варианта, и текст одного никогда не появляется на другом:
 * `skippablePuzzle` — «Задание недоступно» и `SecondaryButton` «Пропустить»;
 * `retryable` — сообщение об отказе и `PrimaryButton` «Повторить» (I3-D27).
 */
@Composable
private fun PuzzleErrorContent(
    kind: PuzzleErrorKind,
    retry: RetryAction,
    actionsEnabled: Boolean,
    margin: Dp,
    onEvent: (PuzzleEvent) -> Unit,
) {
    val skippable = kind == PuzzleErrorKind.PuzzleNotFound || kind == PuzzleErrorKind.InvalidPuzzle
    val message = when {
        skippable -> stringResource(R.string.puzzle_unavailable)
        retry is RetryAction.Resubmit -> stringResource(R.string.puzzle_save_failed)
        else -> stringResource(R.string.puzzle_load_failed)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = margin, vertical = Spacing.section),
    ) {
        ErrorBlock(
            message = message,
            modifier = Modifier.testTag(PuzzleTestTags.ERROR_BLOCK),
        ) {
            if (skippable) {
                // На skippable «Повторить» не показывается: действие этого экрана —
                // переход дальше, а не повторная загрузка.
                SecondaryButton(
                    text = stringResource(R.string.puzzle_skip),
                    onClick = { onEvent(PuzzleEvent.SkipClicked) },
                    enabled = actionsEnabled,
                    modifier = Modifier.testTag(PuzzleTestTags.SKIP_BUTTON),
                )
            } else {
                PrimaryButton(
                    text = stringResource(R.string.puzzle_retry),
                    onClick = { onEvent(PuzzleEvent.RetryClicked) },
                    enabled = actionsEnabled,
                    modifier = Modifier.testTag(PuzzleTestTags.RETRY_BUTTON),
                )
            }
        }
    }
}

@Composable
private fun PuzzleSkeleton(margin: Dp) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = margin, vertical = Spacing.section)
            .testTag(PuzzleTestTags.SKELETON),
        verticalArrangement = Arrangement.spacedBy(Spacing.listGap),
    ) {
        repeat(SKELETON_CARDS) { OrderableCardSkeleton() }
    }
}

/** «Стол» есть ровно у трёх состояний; у остальных его не существует. */
private val PuzzleUiState.boardOrNull: PuzzleBoard?
    get() = when (this) {
        is PuzzleUiState.Playing -> board
        is PuzzleUiState.Submitting.Answer -> board
        is PuzzleUiState.Error -> board
        is PuzzleUiState.Submitting.Skip,
        PuzzleUiState.Loading,
        -> null
    }

/**
 * «Проверить» видна в `Loading` (disabled) и на «столе». В композиции ошибки её нет:
 * основное действие там другое.
 */
private val PuzzleUiState.showsSubmitButton: Boolean
    get() = this is PuzzleUiState.Loading ||
        this is PuzzleUiState.Playing ||
        this is PuzzleUiState.Submitting.Answer

/**
 * Какое действие несла бы ошибка этого вида: `Submitting.Skip` рисует ту же композицию,
 * что и состояние, из которого он запущен, и форму действия берёт отсюда.
 */
private fun retryShapeOf(kind: PuzzleErrorKind): RetryAction = when (kind) {
    PuzzleErrorKind.Storage -> RetryAction.Resubmit(Submission.Skip)
    else -> RetryAction.Reload
}

private const val WEIGHT_FILL = 1f

/**
 * Порядок отрисовки поднятой карточки — структурная константа рядом с [WEIGHT_FILL], а не
 * визуальное значение (ITERATION_6_DESIGN.md, 5.6): токеном «уровень выше соседей» не
 * является.
 */
private const val DRAGGED_Z_INDEX = 1f
private const val RESTING_Z_INDEX = 0f

/** Карточка не поднята — визуального смещения нет. */
private const val NO_OFFSET = 0f

/** Прокручивать не нужно или невозможно. */
private const val NO_VELOCITY = 0f

/** Первый кадр цикла: предыдущего времени ещё нет, поэтому `dt` равен нулю. */
private const val NO_PREVIOUS_FRAME = -1L
private const val NO_ELAPSED = 0f

/** `withFrameNanos` считает наносекундами, скорость задана в секундах. */
private const val NANOS_PER_SECOND = 1_000_000_000f

// --- Preview ---------------------------------------------------------------

private val previewCards = listOf(
    CardUi("c2", "Монблан", "Альпы, Франция и Италия", 1, canMoveUp = false, canMoveDown = true),
    CardUi("c1", "Эльбрус", "Кавказ, Россия", 2, canMoveUp = true, canMoveDown = true),
    CardUi("c3", "Килиманджаро", "Танзания", 3, canMoveUp = true, canMoveDown = true),
    CardUi("c4", "Аконкагуа", "Анды, Аргентина", 4, canMoveUp = true, canMoveDown = false),
)

private val previewBoard = PuzzleBoard(
    slotIndex = 1,
    totalSlots = 3,
    puzzleId = "tmp-geo-vysota-001",
    category = Category.GEOGRAPHY,
    prompt = "Расположите вершины от самой низкой к самой высокой",
    directionLabel = "Сверху — самая низкая",
    cards = previewCards,
)

private val previewPlaying = PuzzleUiState.Playing(previewBoard, isSubmitEnabled = true, showDragHint = false)

@Composable
private fun PreviewPuzzle(state: PuzzleUiState, darkTheme: Boolean = false) {
    PoPoRyadkuTheme(darkTheme = darkTheme) {
        PuzzleScreen(state = state, onEvent = {})
    }
}

@Preview(name = "Puzzle — Playing light 390×844", widthDp = 390, heightDp = 844)
@Composable
private fun PuzzlePlayingPreview() = PreviewPuzzle(previewPlaying)

@Preview(
    name = "Puzzle — Playing dark 390×844",
    widthDp = 390,
    heightDp = 844,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun PuzzlePlayingDarkPreview() = PreviewPuzzle(previewPlaying, darkTheme = true)

@Preview(name = "Puzzle — 320×844 @200%", widthDp = 320, heightDp = 844, fontScale = 2f)
@Composable
private fun PuzzleCompactLargeFontPreview() = PreviewPuzzle(previewPlaying)

@Preview(name = "Puzzle — длинное название", widthDp = 390, heightDp = 844)
@Composable
private fun PuzzleLongTitlePreview() = PreviewPuzzle(
    previewPlaying.copy(
        board = previewBoard.copy(
            cards = previewCards.mapIndexed { index, card ->
                if (index == 0) card.copy(title = "Килиманджаро", subtitle = "Танзания, Восточная Африка") else card
            },
        ),
    ),
)

@Preview(name = "Puzzle — Submitting 390×844", widthDp = 390, heightDp = 844)
@Composable
private fun PuzzleSubmittingPreview() = PreviewPuzzle(
    PuzzleUiState.Submitting.Answer(previewBoard, Submission.Answer(previewCards.map { it.cardId })),
)

@Preview(name = "Puzzle — Loading 390×844", widthDp = 390, heightDp = 844)
@Composable
private fun PuzzleLoadingPreview() = PreviewPuzzle(PuzzleUiState.Loading)

@Preview(name = "Puzzle — Error.skippablePuzzle 390×844", widthDp = 390, heightDp = 844)
@Composable
private fun PuzzleSkippableErrorPreview() = PreviewPuzzle(
    PuzzleUiState.Error(PuzzleErrorKind.PuzzleNotFound, RetryAction.Reload, null),
)
