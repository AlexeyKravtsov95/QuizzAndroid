package ru.poporyadku.ui.puzzleresult

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import ru.poporyadku.R
import ru.poporyadku.core.model.Puzzle
import ru.poporyadku.domain.scoring.InvertedPair
import ru.poporyadku.domain.usecase.PuzzleErrorKind
import ru.poporyadku.ui.components.AppTopBar
import ru.poporyadku.ui.components.ErrorBlock
import ru.poporyadku.ui.components.InvertedPairRow
import ru.poporyadku.ui.components.OrderableCard
import ru.poporyadku.ui.components.PrimaryButton
import ru.poporyadku.ui.components.ScoreBadge
import ru.poporyadku.ui.components.ScoringHint
import ru.poporyadku.ui.components.SkeletonLine
import ru.poporyadku.ui.components.SourcesBlock
import ru.poporyadku.ui.theme.PoPoRyadkuTheme
import ru.poporyadku.ui.theme.ProjectTextStyles
import ru.poporyadku.ui.theme.Sizing
import ru.poporyadku.ui.theme.Spacing

/** Стабильные testTag экрана результата. */
object PuzzleResultTestTags {
    const val SCREEN = "puzzle_result_screen"
    const val CONTENT = "puzzle_result_content"
    const val CORRECT_ORDER = "puzzle_result_correct_order"
    const val EXPLANATION = "puzzle_result_explanation"
    const val SCORE_BADGE = "puzzle_result_score_badge"
    const val SCORING_HINT = "puzzle_result_scoring_hint"
    const val INVERTED_PAIRS = "puzzle_result_inverted_pairs"
    const val ALL_CORRECT = "puzzle_result_all_correct"
    const val SOURCES = "puzzle_result_sources"
    const val PRIMARY_BUTTON = "puzzle_result_primary_button"
    const val ERROR_BLOCK = "puzzle_result_error_block"
}

/**
 * Результат одной головоломки (ITERATION_3_DESIGN.md, разделы 12 и 15).
 *
 * Stateless: `Screen(state, onEvent)`.
 *
 * Порядок сверху вниз зафиксирован иерархией `DESIGN_PRINCIPLES.md` §3: правильный
 * порядок → объяснение → `ScoreBadge` → `ScoringHint` → перепутанные пары →
 * `SourcesBlock` → основная кнопка. Счёт — не самый заметный элемент экрана.
 *
 * Карточки правильного порядка — read-only: ни `MoveButton`, ни custom actions в дереве
 * семантики не существуют, а не показаны disabled (COMPONENTS.md).
 *
 * Вертикальная прокрутка здесь допустима и ожидаема — в отличие от `Puzzle`;
 * горизонтальной нет ни при какой ширине.
 */
@Composable
fun PuzzleResultScreen(
    state: PuzzleResultState,
    onEvent: (PuzzleResultEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(PuzzleResultTestTags.SCREEN),
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
                    AppTopBar(
                        title = resultTitle(state),
                        horizontalMargin = margin,
                        // Ведёт туда же, куда системная «назад», — на Home:
                        // вернуться в отвеченную головоломку нельзя.
                        onBackClick = { onEvent(PuzzleResultEvent.BackPressed) },
                    )

                    Column(
                        modifier = Modifier
                            .weight(WEIGHT_FILL)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = margin, vertical = Spacing.section)
                            .testTag(PuzzleResultTestTags.CONTENT),
                        verticalArrangement = Arrangement.spacedBy(Spacing.section),
                    ) {
                        when (state) {
                            PuzzleResultState.Loading -> ResultSkeleton()
                            is PuzzleResultState.Content -> ResultContent(state, compact = isCompact)
                            is PuzzleResultState.Error -> ErrorBlock(
                                message = stringResource(state.kind.messageRes),
                                modifier = Modifier.testTag(PuzzleResultTestTags.ERROR_BLOCK),
                            )
                        }
                    }

                    if (state is PuzzleResultState.Content) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .windowInsetsPadding(
                                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
                                )
                                .padding(horizontal = margin)
                                .padding(bottom = Spacing.section),
                        ) {
                            PrimaryButton(
                                text = stringResource(
                                    if (state.isLastSlot) R.string.result_to_recap else R.string.result_next,
                                ),
                                onClick = { onEvent(PuzzleResultEvent.PrimaryAction) },
                                modifier = Modifier.testTag(PuzzleResultTestTags.PRIMARY_BUTTON),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultContent(state: PuzzleResultState.Content, compact: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PuzzleResultTestTags.CORRECT_ORDER),
        verticalArrangement = Arrangement.spacedBy(Spacing.listGap),
    ) {
        Text(
            text = stringResource(R.string.result_correct_order),
            style = ProjectTextStyles.editorialTitle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        state.correctOrder.forEach { card ->
            OrderableCard(
                cardId = card.cardId,
                position = card.position,
                totalPositions = state.correctOrder.size,
                title = card.title,
                subtitle = card.subtitle,
                displayValue = card.displayValue,
                // controls = null: read-only. Кнопки и custom actions не существуют
                // в дереве, а не показаны disabled.
                controls = null,
            )
        }
    }

    Text(
        text = state.explanation,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag(PuzzleResultTestTags.EXPLANATION),
    )

    ScoreBadge(
        text = stringResource(R.string.score_of_slot, state.score),
        modifier = Modifier.testTag(PuzzleResultTestTags.SCORE_BADGE),
    )

    if (state.showScoringHint) {
        ScoringHint(modifier = Modifier.testTag(PuzzleResultTestTags.SCORING_HINT))
    }

    InvertedPairs(state = state, compact = compact)

    SourcesBlock(
        sources = state.sources,
        modifier = Modifier.testTag(PuzzleResultTestTags.SOURCES),
    )
}

/**
 * Перечень перепутанных пар либо одна нейтральная строка «Всё верно».
 *
 * Строк ровно `6 − score` — это свойство одного прохода калькулятора, а не совпадение.
 * Максимальный счёт отмечается сдержанно: без конфетти, свечения и смены цвета счёта.
 */
@Composable
private fun InvertedPairs(state: PuzzleResultState.Content, compact: Boolean) {
    val titleById = state.correctOrder.associate { it.cardId to it.title }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PuzzleResultTestTags.INVERTED_PAIRS),
        verticalArrangement = Arrangement.spacedBy(Spacing.scale300),
    ) {
        if (state.invertedPairs.isEmpty()) {
            Text(
                text = stringResource(R.string.result_all_correct),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag(PuzzleResultTestTags.ALL_CORRECT),
            )
        } else {
            state.invertedPairs.forEach { pair ->
                InvertedPairRow(
                    // Якорь — порядок пользователя: correctlySecond у него выше, и
                    // именно про неё говорится «должна располагаться после».
                    laterTitle = titleById[pair.correctlySecond].orEmpty(),
                    earlierTitle = titleById[pair.correctlyFirst].orEmpty(),
                    compact = compact,
                )
            }
        }
    }
}

@Composable
private fun ResultSkeleton() {
    SkeletonLine(widthFraction = SKELETON_TITLE_FRACTION, height = Spacing.scale700)
    repeat(SKELETON_ROWS) { SkeletonLine(widthFraction = SKELETON_ROW_FRACTION) }
}

@Composable
private fun resultTitle(state: PuzzleResultState): String {
    val slot = (state as? PuzzleResultState.Content)?.slotIndex
    return if (slot == null) {
        stringResource(R.string.result_title_unknown)
    } else {
        stringResource(R.string.result_title, slot + 1)
    }
}

/**
 * Сообщение ошибки экрана результата. Отказ чтения — «Повторить» здесь не предлагается:
 * экран открывается заново возвратом на Home, а не отдельной кнопкой.
 */
private val PuzzleErrorKind.messageRes: Int
    get() = when (this) {
        PuzzleErrorKind.PuzzleNotFound,
        PuzzleErrorKind.InvalidPuzzle,
        -> R.string.puzzle_unavailable

        PuzzleErrorKind.InvalidRoute,
        PuzzleErrorKind.SlotOutOfRange,
        PuzzleErrorKind.NoAssignment,
        PuzzleErrorKind.SetNotFound,
        PuzzleErrorKind.Storage,
        -> R.string.puzzle_load_failed
    }

private const val WEIGHT_FILL = 1f
private const val SKELETON_ROWS = 4
private const val SKELETON_TITLE_FRACTION = 0.55f
private const val SKELETON_ROW_FRACTION = 0.9f

// --- Preview ---------------------------------------------------------------

private val previewSources = listOf(
    Puzzle.Source(
        sourceId = "s1",
        title = "Encyclopaedia Britannica, статьи о горных вершинах",
        kind = "encyclopedia",
        url = "https://www.britannica.com/",
        reference = null,
        accessedAt = "2026-08-20",
        note = null,
    ),
    Puzzle.Source(
        sourceId = "s2",
        title = "Большая российская энциклопедия",
        kind = "encyclopedia",
        url = null,
        reference = "БРЭ. Т. 35. М., 2017",
        accessedAt = "2026-08-20",
        note = null,
    ),
)

private val previewOrder = listOf(
    ResultCardUi("c2", 1, "Монблан", "Альпы, Франция и Италия", "4808 м"),
    ResultCardUi("c1", 2, "Эльбрус", "Кавказ, Россия", "5642 м"),
    ResultCardUi("c3", 3, "Килиманджаро", "Танзания", "5895 м"),
    ResultCardUi("c4", 4, "Аконкагуа", "Анды, Аргентина", "6961 м"),
)

private fun previewContent(
    score: Int,
    invertedPairs: List<InvertedPair>,
    showScoringHint: Boolean = false,
    slotIndex: Int = 0,
) = PuzzleResultState.Content(
    slotIndex = slotIndex,
    totalSlots = 3,
    correctOrder = previewOrder,
    submittedOrder = listOf("c1", "c2", "c3", "c4"),
    score = score,
    invertedPairs = invertedPairs,
    explanation = "Монблан — высшая точка Альп, но уступает Эльбрусу. " +
        "Килиманджаро крупнее обоих, а рекорд среди четырёх держит Аконкагуа.",
    sources = previewSources,
    showScoringHint = showScoringHint,
    isLastSlot = slotIndex == 2,
    puzzleId = "tmp-geo-vysota-001",
)

private val allPairs = listOf(
    InvertedPair("c2", "c1"),
    InvertedPair("c2", "c3"),
    InvertedPair("c2", "c4"),
    InvertedPair("c1", "c3"),
    InvertedPair("c1", "c4"),
    InvertedPair("c3", "c4"),
)

@Composable
private fun PreviewResult(state: PuzzleResultState, darkTheme: Boolean = false) {
    PoPoRyadkuTheme(darkTheme = darkTheme) {
        PuzzleResultScreen(state = state, onEvent = {})
    }
}

@Preview(name = "PuzzleResult — 6 из 6", widthDp = 390, heightDp = 844)
@Composable
private fun ResultPerfectPreview() = PreviewResult(previewContent(6, emptyList()))

@Preview(name = "PuzzleResult — 3 из 6", widthDp = 390, heightDp = 844)
@Composable
private fun ResultPartialPreview() = PreviewResult(previewContent(3, allPairs.take(3)))

@Preview(name = "PuzzleResult — 0 из 6", widthDp = 390, heightDp = 844)
@Composable
private fun ResultZeroPreview() = PreviewResult(previewContent(0, allPairs))

@Preview(name = "PuzzleResult — первый в жизни", widthDp = 390, heightDp = 844)
@Composable
private fun ResultFirstEverPreview() =
    PreviewResult(previewContent(5, allPairs.take(1), showScoringHint = true))

@Preview(
    name = "PuzzleResult — dark",
    widthDp = 390,
    heightDp = 844,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ResultDarkPreview() = PreviewResult(previewContent(4, allPairs.take(2)), darkTheme = true)

@Preview(name = "PuzzleResult — 320×844 @200%", widthDp = 320, heightDp = 844, fontScale = 2f)
@Composable
private fun ResultCompactLargeFontPreview() = PreviewResult(previewContent(3, allPairs.take(3)))
