package ru.poporyadku.ui.recap

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import ru.poporyadku.R
import ru.poporyadku.core.model.Category
import ru.poporyadku.ui.components.AppTopBar
import ru.poporyadku.ui.components.DayResultLeading
import ru.poporyadku.ui.components.DayResultList
import ru.poporyadku.ui.components.DayResultRowData
import ru.poporyadku.ui.components.ErrorBlock
import ru.poporyadku.ui.components.PrimaryButton
import ru.poporyadku.ui.components.ScoreBadge
import ru.poporyadku.ui.components.SkeletonLine
import ru.poporyadku.ui.components.StreakRow
import ru.poporyadku.ui.theme.PoPoRyadkuTheme
import ru.poporyadku.ui.theme.Sizing
import ru.poporyadku.ui.theme.Spacing

/** Стабильные testTag экрана итога дня. */
object DayRecapTestTags {
    const val SCREEN = "recap_screen"
    const val CONTENT = "recap_content"
    const val SCORE_BADGE = "recap_score_badge"
    const val RESULTS = "recap_results"
    const val STREAK = "recap_streak"
    const val BEST_STREAK = "recap_best_streak"
    const val DONE_BUTTON = "recap_done_button"
    const val NOT_FOUND = "recap_not_found"
}

/**
 * Итог дня (ITERATION_3_DESIGN.md, раздел 13; COMPONENTS.md, «DayRecapScreen»).
 *
 * Stateless: состояние и callbacks приходят параметрами.
 *
 * Порядок сверху вниз — заголовок → общий счёт → три результата → серия → «Готово»;
 * общий счёт крупнейший текстовый элемент экрана. «Поделиться», реклама и диалог
 * уведомлений на экране отсутствуют: они относятся к итерациям 5–7.
 */
@Composable
fun DayRecapScreen(
    state: DayRecapState,
    onEvent: (DayRecapEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(DayRecapTestTags.SCREEN),
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
                    // Заголовок «Сегодня» либо дата; leading-иконки «Назад» нет —
                    // граф сессии уже вычищен, и она вела бы туда же, куда «Готово».
                    AppTopBar(title = rememberTitle(state), horizontalMargin = margin)

                    Column(
                        modifier = Modifier
                            .weight(WEIGHT_FILL)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = margin, vertical = Spacing.section)
                            .testTag(DayRecapTestTags.CONTENT),
                        verticalArrangement = Arrangement.spacedBy(Spacing.section),
                    ) {
                        when (state) {
                            DayRecapState.Loading -> RecapSkeleton()
                            is DayRecapState.Content -> RecapContent(state)
                            DayRecapState.NotFound -> ErrorBlock(
                                // Без кнопки «Повторить»: повторная попытка ничего не
                                // изменит, данных за прошедший день больше нет.
                                message = stringResource(R.string.recap_not_found),
                                modifier = Modifier.testTag(DayRecapTestTags.NOT_FOUND),
                            )
                        }
                    }

                    if (state is DayRecapState.Content) {
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
                                text = stringResource(R.string.recap_done),
                                onClick = { onEvent(DayRecapEvent.DoneClicked) },
                                modifier = Modifier.testTag(DayRecapTestTags.DONE_BUTTON),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecapContent(state: DayRecapState.Content) {
    ScoreBadge(
        text = stringResource(R.string.score_of_day, state.totalScore),
        modifier = Modifier.testTag(DayRecapTestTags.SCORE_BADGE),
    )

    DayResultList(
        rows = state.slots.map { it.toRowData() },
        modifier = Modifier.testTag(DayRecapTestTags.RESULTS),
    )

    StreakRow(
        label = stringResource(R.string.recap_streak),
        value = streakText(state.currentStreak),
        modifier = Modifier.testTag(DayRecapTestTags.STREAK),
    )

    // Вторая строка появляется только тогда, когда ЭТОТ день установил рекорд;
    // цветом она не выделяется — только присутствием (I3-D46).
    if (state.isRecordUpdated) {
        StreakRow(
            label = stringResource(R.string.recap_best_streak),
            value = streakText(state.bestStreak),
            modifier = Modifier.testTag(DayRecapTestTags.BEST_STREAK),
        )
    }
}

/**
 * `when` по [SlotResultUi] исчерпывающий: `Unavailable` показывает «Задание N» вместо
 * `CategoryLabel` и **фактический** счёт, а не константный ноль.
 */
@Composable
private fun SlotResultUi.toRowData(): DayResultRowData = DayResultRowData(
    leading = when (this) {
        is SlotResultUi.Played -> DayResultLeading.CategoryOf(category)
        is SlotResultUi.Unavailable -> DayResultLeading.Label(
            stringResource(R.string.recap_slot_unavailable, slotIndex + 1),
        )
    },
    result = stringResource(R.string.score_of_slot, score),
)

@Composable
private fun RecapSkeleton() {
    SkeletonLine(widthFraction = SKELETON_SCORE_FRACTION, height = Spacing.scale700)
    repeat(SKELETON_ROWS) {
        SkeletonLine(widthFraction = SKELETON_ROW_FRACTION)
    }
}

@Composable
private fun rememberTitle(state: DayRecapState): String {
    val today = stringResource(R.string.recap_title_today)
    val date = (state as? DayRecapState.Content)?.title as? DayRecapTitle.Date
    return remember(date, today) {
        date?.localDate?.format(DATE_FORMATTER)?.replaceFirstChar { it.titlecase(RUSSIAN) } ?: today
    }
}

@Composable
private fun streakText(days: Int): String =
    pluralStringResource(R.plurals.streak_days, days, days)

private val RUSSIAN: Locale = Locale.forLanguageTag("ru")
private val DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM yyyy", RUSSIAN)
private const val WEIGHT_FILL = 1f
private const val SKELETON_ROWS = 3
private const val SKELETON_SCORE_FRACTION = 0.45f
private const val SKELETON_ROW_FRACTION = 0.85f

// --- Preview ---------------------------------------------------------------

private fun playedDay(total: Int, scores: List<Int>) = DayRecapState.Content(
    title = DayRecapTitle.Today,
    totalScore = total,
    slots = listOf(
        SlotResultUi.Played(slotIndex = 0, score = scores[0], category = Category.GEOGRAPHY),
        SlotResultUi.Played(slotIndex = 1, score = scores[1], category = Category.HISTORY),
        SlotResultUi.Played(slotIndex = 2, score = scores[2], category = Category.SCIENCE),
    ),
    currentStreak = 6,
    bestStreak = 9,
    isRecordUpdated = false,
)

@Composable
private fun PreviewRecap(state: DayRecapState, darkTheme: Boolean = false) {
    PoPoRyadkuTheme(darkTheme = darkTheme) {
        DayRecapScreen(state = state, onEvent = {})
    }
}

@Preview(name = "DayRecap — 18/18 light 390×844", widthDp = 390, heightDp = 844)
@Composable
private fun DayRecapPerfectPreview() = PreviewRecap(playedDay(18, listOf(6, 6, 6)))

@Preview(name = "DayRecap — 12/18 light 390×844", widthDp = 390, heightDp = 844)
@Composable
private fun DayRecapPartialPreview() = PreviewRecap(playedDay(12, listOf(6, 3, 3)))

@Preview(name = "DayRecap — 0/18 light 390×844", widthDp = 390, heightDp = 844)
@Composable
private fun DayRecapZeroPreview() = PreviewRecap(playedDay(0, listOf(0, 0, 0)))

/** День из трёх пропусков: три `Unavailable`, все «0 из 6». */
@Preview(name = "DayRecap — три пропуска 390×844", widthDp = 390, heightDp = 844)
@Composable
private fun DayRecapAllSkippedPreview() = PreviewRecap(
    DayRecapState.Content(
        title = DayRecapTitle.Today,
        totalScore = 0,
        slots = listOf(
            SlotResultUi.Unavailable(slotIndex = 0, score = 0),
            SlotResultUi.Unavailable(slotIndex = 1, score = 0),
            SlotResultUi.Unavailable(slotIndex = 2, score = 0),
        ),
        currentStreak = 1,
        bestStreak = 1,
        isRecordUpdated = true,
    ),
)

/** Смешанный: две `Played` и одна `Unavailable` с НЕнулевым счётом. */
@Preview(name = "DayRecap — смешанный 390×844", widthDp = 390, heightDp = 844)
@Composable
private fun DayRecapMixedPreview() = PreviewRecap(
    DayRecapState.Content(
        title = DayRecapTitle.Date(LocalDate.of(2026, 8, 25)),
        totalScore = 14,
        slots = listOf(
            SlotResultUi.Played(slotIndex = 0, score = 6, category = Category.NATURE),
            SlotResultUi.Unavailable(slotIndex = 1, score = 4),
            SlotResultUi.Played(slotIndex = 2, score = 4, category = Category.RUSSIA),
        ),
        currentStreak = 3,
        bestStreak = 9,
        isRecordUpdated = false,
    ),
)

@Preview(
    name = "DayRecap — dark 390×844",
    widthDp = 390,
    heightDp = 844,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun DayRecapDarkPreview() = PreviewRecap(playedDay(15, listOf(6, 5, 4)), darkTheme = true)

@Preview(name = "DayRecap — 320×844 @200%", widthDp = 320, heightDp = 844, fontScale = 2f)
@Composable
private fun DayRecapCompactLargeFontPreview() = PreviewRecap(playedDay(15, listOf(6, 5, 4)))
