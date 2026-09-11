package ru.poporyadku.ui.sources

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import ru.poporyadku.R
import ru.poporyadku.core.model.Puzzle
import ru.poporyadku.ui.components.AppTopBar
import ru.poporyadku.ui.components.ErrorBlock
import ru.poporyadku.ui.components.PrimaryButton
import ru.poporyadku.ui.components.SkeletonLine
import ru.poporyadku.ui.components.SourceRow
import ru.poporyadku.ui.theme.PoPoRyadkuTheme
import ru.poporyadku.ui.theme.Sizing
import ru.poporyadku.ui.theme.Spacing

/** Стабильные testTag экрана источников. */
object SourcesTestTags {
    const val SCREEN = "sources_screen"
    const val LIST = "sources_list"
    const val LOADING = "sources_loading"
    const val ROW_SKELETON = "sources_row_skeleton"
    const val EMPTY = "sources_empty"
    const val ERROR = "sources_error"
    const val RETRY_BUTTON = "sources_retry_button"

    fun row(key: String): String = "sources_row_$key"
}

/**
 * Источники сыгранных головоломок (ITERATION_5_DESIGN.md, §3.11; COMPONENTS.md,
 * «SourceRow», «Loading skeleton», «Error»).
 *
 * Stateless: `SourcesScreen(state, onEvent)`. Весь экран — один `LazyColumn`: шапка —
 * его первый элемент, строки `SourceRow` с ключом дедупликации и hairline между ними.
 * Доступность обработчика ссылки каждая строка спрашивает сама, только попав в
 * композицию, — список из сотен ссылок при открытии систему не опрашивает.
 */
@Composable
fun SourcesScreen(
    state: SourcesState,
    onEvent: (SourcesEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(SourcesTestTags.SCREEN),
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
            val bottomInset = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues()

            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = columnWidth
                        .fillMaxSize()
                        .testTag(SourcesTestTags.LIST),
                    contentPadding = PaddingValues(
                        bottom = bottomInset.calculateBottomPadding() + Spacing.section,
                    ),
                ) {
                    item(key = KEY_TOP_BAR) {
                        AppTopBar(
                            title = stringResource(R.string.sources_title),
                            horizontalMargin = margin,
                            onBackClick = { onEvent(SourcesEvent.BackClicked) },
                        )
                    }

                    when (state) {
                        SourcesState.Loading -> loadingItems(margin)
                        SourcesState.Empty -> emptyItems(margin)
                        SourcesState.Error -> errorItems(margin, onEvent)
                        is SourcesState.Content -> contentItems(state, margin)
                    }
                }
            }
        }
    }
}

/** Восемь skeleton-строк по силуэту `SourceRow`: название 70 %, подпись 40 %, hairline. */
private fun LazyListScope.loadingItems(margin: Dp) {
    item(key = KEY_LOADING) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = margin)
                .testTag(SourcesTestTags.LOADING),
        ) {
            repeat(SKELETON_ROWS) {
                SourceRowSkeleton(modifier = Modifier.testTag(SourcesTestTags.ROW_SKELETON))
            }
        }
    }
}

/** `Empty` — одна строка без кнопки: действие здесь не нужно, выход — «Назад». */
private fun LazyListScope.emptyItems(margin: Dp) {
    item(key = KEY_EMPTY) {
        Text(
            text = stringResource(R.string.sources_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = margin, vertical = Spacing.section)
                .testTag(SourcesTestTags.EMPTY),
        )
    }
}

/** `Error` — вариант `retryable`: «Не удалось прочитать источники» + «Повторить». */
private fun LazyListScope.errorItems(margin: Dp, onEvent: (SourcesEvent) -> Unit) {
    item(key = KEY_ERROR) {
        ErrorBlock(
            message = stringResource(R.string.sources_error),
            modifier = Modifier
                .padding(horizontal = margin, vertical = Spacing.section)
                .testTag(SourcesTestTags.ERROR),
        ) {
            PrimaryButton(
                text = stringResource(R.string.home_error_retry),
                onClick = { onEvent(SourcesEvent.RetryClicked) },
                modifier = Modifier.testTag(SourcesTestTags.RETRY_BUTTON),
            )
        }
    }
}

private fun LazyListScope.contentItems(state: SourcesState.Content, margin: Dp) {
    items(items = state.items, key = { it.key }) { item ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = margin)
                .testTag(SourcesTestTags.row(item.key)),
        ) {
            SourceRow(source = item.source)
            HorizontalDivider(thickness = Sizing.dividerThickness, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** Skeleton `SourceRow` — тот же силуэт без формы-контейнера вокруг. */
@Composable
private fun SourceRowSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.scale300),
            verticalArrangement = Arrangement.spacedBy(Spacing.scale200),
        ) {
            SkeletonLine(widthFraction = SKELETON_TITLE_FRACTION)
            SkeletonLine(widthFraction = SKELETON_CAPTION_FRACTION)
        }
        HorizontalDivider(thickness = Sizing.dividerThickness, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private const val KEY_TOP_BAR = "top_bar"
private const val KEY_LOADING = "loading"
private const val KEY_EMPTY = "empty"
private const val KEY_ERROR = "error"

private const val SKELETON_ROWS = 8
private const val SKELETON_TITLE_FRACTION = 0.70f
private const val SKELETON_CAPTION_FRACTION = 0.40f

// --- Preview -----------------------------------------------------------------------------

private val previewItems = listOf(
    SourceItemUi(
        key = "ref:12:БРЭ. Т. 35.Большая российская энциклопедия",
        source = Puzzle.Source(
            sourceId = "s2",
            title = "Большая российская энциклопедия",
            kind = "encyclopedia",
            url = null,
            reference = "БРЭ. Т. 35.",
            accessedAt = "2026-08-20",
            note = null,
        ),
    ),
    SourceItemUi(
        key = "url:https://www.britannica.com/",
        source = Puzzle.Source(
            sourceId = "s1",
            title = "Encyclopaedia Britannica",
            kind = "encyclopedia",
            url = "https://www.britannica.com/",
            reference = null,
            accessedAt = "2026-08-20",
            note = null,
        ),
    ),
)

@Composable
private fun PreviewSources(state: SourcesState, darkTheme: Boolean = false) {
    PoPoRyadkuTheme(darkTheme = darkTheme) {
        SourcesScreen(state = state, onEvent = {})
    }
}

@Preview(name = "Sources — Content 390×844", widthDp = 390, heightDp = 844)
@Composable
private fun SourcesContentPreview() = PreviewSources(SourcesState.Content(previewItems))

@Preview(
    name = "Sources — Content dark 390×844",
    widthDp = 390,
    heightDp = 844,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SourcesContentDarkPreview() = PreviewSources(SourcesState.Content(previewItems), darkTheme = true)

@Preview(name = "Sources — Loading", widthDp = 390, heightDp = 844)
@Composable
private fun SourcesLoadingPreview() = PreviewSources(SourcesState.Loading)

@Preview(name = "Sources — Empty 320×844 @200%", widthDp = 320, heightDp = 844, fontScale = 2f)
@Composable
private fun SourcesEmptyPreview() = PreviewSources(SourcesState.Empty)

@Preview(name = "Sources — Error", widthDp = 390, heightDp = 844)
@Composable
private fun SourcesErrorPreview() = PreviewSources(SourcesState.Error)
