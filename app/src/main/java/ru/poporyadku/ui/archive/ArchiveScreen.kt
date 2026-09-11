package ru.poporyadku.ui.archive

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import java.time.LocalDate
import ru.poporyadku.R
import ru.poporyadku.ui.components.AppTopBar
import ru.poporyadku.ui.components.ArchiveRow
import ru.poporyadku.ui.components.ArchiveRowSkeleton
import ru.poporyadku.ui.components.ErrorBlock
import ru.poporyadku.ui.components.PrimaryButton
import ru.poporyadku.ui.components.SkeletonLine
import ru.poporyadku.ui.components.StatisticItem
import ru.poporyadku.ui.components.StatisticsBlock
import ru.poporyadku.ui.theme.PoPoRyadkuTheme
import ru.poporyadku.ui.theme.Sizing
import ru.poporyadku.ui.theme.Spacing

/** Стабильные testTag экрана архива. */
object ArchiveTestTags {
    const val SCREEN = "archive_screen"
    const val LIST = "archive_list"
    const val STATISTICS = "archive_statistics"
    const val LOADING = "archive_loading"
    const val STATISTICS_SKELETON = "archive_statistics_skeleton"
    const val STATISTICS_SKELETON_TITLE = "archive_statistics_skeleton_title"
    const val STATISTICS_SKELETON_PAIR = "archive_statistics_skeleton_pair"
    const val ROW_SKELETON = "archive_row_skeleton"
    const val EMPTY = "archive_empty"
    const val EMPTY_CTA = "archive_empty_cta"
    const val ERROR = "archive_error"
    const val RETRY_BUTTON = "archive_retry_button"
    const val FOOTER_LOADING = "archive_footer_loading"
    const val FOOTER_ERROR = "archive_footer_error"
    const val FOOTER_RETRY_BUTTON = "archive_footer_retry_button"

    /** Строка дня — по ISO-дате, как ключ элемента списка. */
    fun row(localDate: LocalDate): String = "archive_row_$localDate"
}

/**
 * Архив и статистика (ITERATION_5_DESIGN.md, §3.6; UX_FLOW.md §7; COMPONENTS.md,
 * «Archive row», «Statistics block», «Loading skeleton», «Empty», «Error»).
 *
 * Stateless: `ArchiveScreen(state, onEvent)`, ни Hilt, ни ViewModel внутри.
 *
 * Весь экран — один `LazyColumn`: `AppTopBar` → `StatisticsBlock` → строки → футер.
 * Статистика — элемент списка, поэтому при 200 % и в ландшафте прокручивается вместе с
 * ним, а не отъедает высоту у строк. Порядок TalkBack совпадает с порядком элементов:
 * «Назад» → «Архив» → статистика → строки сверху вниз → футер.
 */
@Composable
fun ArchiveScreen(
    state: ArchiveState,
    onEvent: (ArchiveEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(ArchiveTestTags.SCREEN),
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // DESIGN_TOKENS.md §6.10: поле экрана и контентная колонка решаются на
            // границе экрана, а не в глубине компонентов.
            val isCompact = maxWidth < Sizing.compactWidthBreakpoint
            val margin = if (isCompact) Spacing.marginCompact else Spacing.marginDefault
            val isWideOrLandscape = maxWidth >= Sizing.mediumWidthBreakpoint || maxWidth > maxHeight
            val columnWidth = if (isWideOrLandscape) {
                Modifier.widthIn(max = Sizing.contentMaxWidth)
            } else {
                Modifier.fillMaxWidth()
            }
            // Нижний системный inset — последнему элементу списка, а не всему экрану.
            val bottomInset = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues()

            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = columnWidth
                        .fillMaxSize()
                        .testTag(ArchiveTestTags.LIST),
                    contentPadding = PaddingValues(
                        bottom = bottomInset.calculateBottomPadding() + Spacing.section,
                    ),
                ) {
                    item(key = KEY_TOP_BAR) {
                        AppTopBar(
                            title = stringResource(R.string.archive_title),
                            horizontalMargin = margin,
                            onBackClick = { onEvent(ArchiveEvent.BackClicked) },
                        )
                    }

                    when (state) {
                        ArchiveState.Loading -> loadingItems(margin)
                        ArchiveState.Empty -> emptyItems(margin, onEvent)
                        ArchiveState.Error -> errorItems(margin, onEvent)
                        is ArchiveState.Content -> contentItems(state, margin, onEvent)
                    }
                }
            }
        }
    }
}

// --- Состояния ---------------------------------------------------------------

/**
 * `Loading`: skeleton статистики (строка заголовка и пять отдельных полос — по одной на
 * пару) и шесть skeleton-строк архива. Одна произвольная карточка запрещена: каждый
 * skeleton повторяет силуэт своего компонента.
 */
private fun LazyListScope.loadingItems(margin: Dp) {
    item(key = KEY_LOADING) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = margin)
                .testTag(ArchiveTestTags.LOADING),
        ) {
            StatisticsSkeleton(modifier = Modifier.padding(vertical = Spacing.section))
            repeat(SKELETON_ROWS) {
                ArchiveRowSkeleton(modifier = Modifier.testTag(ArchiveTestTags.ROW_SKELETON))
            }
        }
    }
}

/** `Empty`: защитное состояние (I5-D7) — текст и «К заданию дня» на существующий Home. */
private fun LazyListScope.emptyItems(margin: Dp, onEvent: (ArchiveEvent) -> Unit) {
    item(key = KEY_EMPTY) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = margin, vertical = Spacing.section)
                .testTag(ArchiveTestTags.EMPTY),
            verticalArrangement = Arrangement.spacedBy(Spacing.section),
        ) {
            Text(
                text = stringResource(R.string.archive_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            PrimaryButton(
                text = stringResource(R.string.archive_empty_cta),
                onClick = { onEvent(ArchiveEvent.ToTodayClicked) },
                modifier = Modifier.testTag(ArchiveTestTags.EMPTY_CTA),
            )
        }
    }
}

/** `Error` — вариант `retryable`: «Не удалось прочитать историю» + «Повторить». */
private fun LazyListScope.errorItems(margin: Dp, onEvent: (ArchiveEvent) -> Unit) {
    item(key = KEY_ERROR) {
        RetryableError(
            buttonTag = ArchiveTestTags.RETRY_BUTTON,
            onEvent = onEvent,
            modifier = Modifier
                .padding(horizontal = margin, vertical = Spacing.section)
                .testTag(ArchiveTestTags.ERROR),
        )
    }
}

private fun LazyListScope.contentItems(
    state: ArchiveState.Content,
    margin: Dp,
    onEvent: (ArchiveEvent) -> Unit,
) {
    item(key = KEY_STATISTICS) {
        ArchiveStatisticsBlock(
            statistics = state.statistics,
            modifier = Modifier
                .padding(horizontal = margin, vertical = Spacing.section)
                .testTag(ArchiveTestTags.STATISTICS),
        )
    }

    items(items = state.items, key = { it.key }) { item ->
        ArchiveRow(
            dateText = rememberArchiveDate(item.localDate),
            dayNumber = item.dayNumber,
            totalScore = item.totalScore,
            completedCount = item.completedCount,
            isComplete = item.isComplete,
            onClick = { onEvent(ArchiveEvent.DayClicked(item.localDate)) },
            modifier = Modifier
                .padding(horizontal = margin)
                .testTag(ArchiveTestTags.row(item.localDate)),
        )
    }

    when (state.paging) {
        // Один и тот же элемент для обеих фаз: смена фазы не пересоздаёт футер и не
        // перезапускает его эффект — новый запрос даёт только новый размер списка.
        ArchivePaging.CanLoadMore, ArchivePaging.LoadingMore -> item(key = KEY_FOOTER_LOADING) {
            LoadMoreFooter(
                itemsCount = state.items.size,
                paging = state.paging,
                onEvent = onEvent,
                modifier = Modifier
                    .padding(horizontal = margin)
                    .testTag(ArchiveTestTags.FOOTER_LOADING),
            )
        }

        // Уже показанные строки остаются; «Повторить» — та же граница или переподписка.
        ArchivePaging.LoadMoreFailed, ArchivePaging.RefreshFailed -> item(key = KEY_FOOTER_ERROR) {
            RetryableError(
                buttonTag = ArchiveTestTags.FOOTER_RETRY_BUTTON,
                onEvent = onEvent,
                modifier = Modifier
                    .padding(horizontal = margin, vertical = Spacing.section)
                    .testTag(ArchiveTestTags.FOOTER_ERROR),
            )
        }

        ArchivePaging.EndReached -> Unit
    }
}

// --- Части экрана ------------------------------------------------------------

/**
 * Пять пар в порядке «Сыграно дней» → «Средний балл» → «Лучший день» → «Серия» →
 * «Лучшая серия». Метки — те же ресурсы, что у статистики Home.
 */
@Composable
private fun ArchiveStatisticsBlock(statistics: ArchiveStatistics, modifier: Modifier = Modifier) {
    StatisticsBlock(
        title = stringResource(R.string.statistics_title),
        items = listOf(
            StatisticItem(stringResource(R.string.home_stat_played_days), statistics.playedDays.toString()),
            StatisticItem(stringResource(R.string.archive_stat_average), averageText(statistics.averageTenths)),
            StatisticItem(
                stringResource(R.string.home_stat_best_day),
                stringResource(R.string.score_of_day, statistics.bestDayScore),
            ),
            StatisticItem(stringResource(R.string.home_stat_streak), streakText(statistics.currentStreak)),
            StatisticItem(stringResource(R.string.home_stat_best_streak), streakText(statistics.bestStreak)),
        ),
        modifier = modifier,
    )
}

/**
 * Skeleton блока статистики: полоса заголовка и пять отдельных полос — без формы-контейнера
 * вокруг, потому что у самого блока его нет (COMPONENTS.md, «Loading skeleton»).
 */
@Composable
private fun StatisticsSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ArchiveTestTags.STATISTICS_SKELETON),
        verticalArrangement = Arrangement.spacedBy(Spacing.scale400),
    ) {
        SkeletonLine(
            widthFraction = SKELETON_TITLE_FRACTION,
            modifier = Modifier.testTag(ArchiveTestTags.STATISTICS_SKELETON_TITLE),
        )
        repeat(STATISTIC_PAIRS) {
            SkeletonLine(
                widthFraction = SKELETON_PAIR_FRACTION,
                modifier = Modifier
                    .padding(top = Spacing.statRowInner)
                    .testTag(ArchiveTestTags.STATISTICS_SKELETON_PAIR),
            )
        }
    }
}

/**
 * Футер фаз `CanLoadMore` и `LoadingMore` — skeleton-строка архива. Его появление в
 * композиции (футер попал в видимую часть ленивого списка) просит следующую страницу —
 * ровно один раз на размер списка и только в `CanLoadMore`: смена фазы на `LoadingMore`
 * и обратно с тем же числом строк нового запроса не создаёт.
 */
@Composable
private fun LoadMoreFooter(
    itemsCount: Int,
    paging: ArchivePaging,
    onEvent: (ArchiveEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(itemsCount) {
        if (paging == ArchivePaging.CanLoadMore) onEvent(ArchiveEvent.EndReached)
    }
    ArchiveRowSkeleton(modifier = modifier)
}

@Composable
private fun RetryableError(
    buttonTag: String,
    onEvent: (ArchiveEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    ErrorBlock(
        message = stringResource(R.string.archive_error),
        modifier = modifier,
    ) {
        PrimaryButton(
            text = stringResource(R.string.home_error_retry),
            onClick = { onEvent(ArchiveEvent.RetryClicked) },
            modifier = Modifier.testTag(buttonTag),
        )
    }
}

@Composable
private fun streakText(days: Int): String =
    pluralStringResource(R.plurals.streak_days, days, days)

private const val KEY_TOP_BAR = "top_bar"
private const val KEY_LOADING = "loading"
private const val KEY_EMPTY = "empty"
private const val KEY_ERROR = "error"
private const val KEY_STATISTICS = "statistics"
private const val KEY_FOOTER_LOADING = "footer_loading"
private const val KEY_FOOTER_ERROR = "footer_error"

private const val SKELETON_ROWS = 6
private const val STATISTIC_PAIRS = 5
private const val SKELETON_TITLE_FRACTION = 0.35f
private const val SKELETON_PAIR_FRACTION = 0.5f

// --- Preview -----------------------------------------------------------------

private val previewStatistics = ArchiveStatistics(
    playedDays = 23,
    averageTenths = 137,
    bestDayScore = 17,
    currentStreak = 6,
    bestStreak = 9,
)

private val previewItems = listOf(
    ArchiveItem(LocalDate.of(2026, 8, 25), dayNumber = 12, totalScore = 15, completedCount = 3, isComplete = true),
    ArchiveItem(LocalDate.of(2026, 8, 24), dayNumber = 11, totalScore = 8, completedCount = 2, isComplete = false),
    ArchiveItem(LocalDate.of(2026, 8, 23), dayNumber = 10, totalScore = 18, completedCount = 3, isComplete = true),
)

@Composable
private fun PreviewArchive(state: ArchiveState, darkTheme: Boolean = false) {
    PoPoRyadkuTheme(darkTheme = darkTheme) {
        ArchiveScreen(state = state, onEvent = {})
    }
}

@Preview(name = "Archive — Content 390×844", widthDp = 390, heightDp = 844)
@Composable
private fun ArchiveContentPreview() = PreviewArchive(
    ArchiveState.Content(previewStatistics, previewItems, ArchivePaging.EndReached),
)

@Preview(
    name = "Archive — Content dark 390×844",
    widthDp = 390,
    heightDp = 844,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ArchiveContentDarkPreview() = PreviewArchive(
    ArchiveState.Content(previewStatistics, previewItems, ArchivePaging.CanLoadMore),
    darkTheme = true,
)

@Preview(name = "Archive — 320×844 @200%", widthDp = 320, heightDp = 844, fontScale = 2f)
@Composable
private fun ArchiveCompactLargeFontPreview() = PreviewArchive(
    ArchiveState.Content(previewStatistics.copy(averageTenths = null), previewItems, ArchivePaging.LoadMoreFailed),
)

@Preview(name = "Archive — landscape", widthDp = 844, heightDp = 390)
@Composable
private fun ArchiveLandscapePreview() = PreviewArchive(
    ArchiveState.Content(previewStatistics, previewItems, ArchivePaging.EndReached),
)

@Preview(name = "Archive — Loading", widthDp = 390, heightDp = 844)
@Composable
private fun ArchiveLoadingPreview() = PreviewArchive(ArchiveState.Loading)

@Preview(name = "Archive — Empty", widthDp = 390, heightDp = 844)
@Composable
private fun ArchiveEmptyPreview() = PreviewArchive(ArchiveState.Empty)

@Preview(name = "Archive — Error", widthDp = 390, heightDp = 844)
@Composable
private fun ArchiveErrorPreview() = PreviewArchive(ArchiveState.Error)
