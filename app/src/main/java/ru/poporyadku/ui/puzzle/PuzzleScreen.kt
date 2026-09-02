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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import ru.poporyadku.R
import ru.poporyadku.core.model.Category
import ru.poporyadku.domain.usecase.PuzzleErrorKind
import ru.poporyadku.domain.usecase.Submission
import ru.poporyadku.ui.components.AppTopBar
import ru.poporyadku.ui.components.CategoryLabel
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
 */
@Composable
private fun PuzzleBoardContent(
    board: PuzzleBoard,
    interactive: Boolean,
    margin: Dp,
    onEvent: (PuzzleEvent) -> Unit,
) {
    val motion = rememberMotionTokens()

    LazyColumn(
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
                modifier = Modifier.animateItem(
                    placementSpec = tween(
                        durationMillis = motion.durationLong,
                        easing = motion.easingStandard,
                    ),
                ),
            )
        }
    }
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
    draggedCardId = null,
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
