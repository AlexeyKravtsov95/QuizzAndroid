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
import androidx.compose.material3.Text
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
import ru.poporyadku.ui.components.DayResultTrailing
import ru.poporyadku.ui.components.ErrorBlock
import ru.poporyadku.ui.components.PrimaryButton
import ru.poporyadku.ui.components.ScoreBadge
import ru.poporyadku.ui.components.SkeletonLine
import ru.poporyadku.ui.components.StreakRow
import ru.poporyadku.ui.navigation.RouteOrigin
import ru.poporyadku.ui.theme.PoPoRyadkuTheme
import ru.poporyadku.ui.theme.Sizing
import ru.poporyadku.ui.theme.Spacing

/** Стабильные testTag экрана итога дня. */
object DayRecapTestTags {
    const val SCREEN = "recap_screen"
    const val CONTENT = "recap_content"
    const val SCORE_BADGE = "recap_score_badge"
    const val INCOMPLETE = "recap_day_incomplete"
    const val RESULTS = "recap_results"
    const val STREAK = "recap_streak"
    const val BEST_STREAK = "recap_best_streak"
    const val PRIMARY_BUTTON = "recap_primary_button"
    const val NOT_FOUND = "recap_not_found"
}

/**
 * Итог дня (ITERATION_3_DESIGN.md, раздел 13; ITERATION_5_DESIGN.md, §3.7;
 * COMPONENTS.md, «AppTopBar», «DayResultRow»).
 *
 * Stateless: состояние и callbacks приходят параметрами.
 *
 * Порядок сверху вниз — заголовок → общий счёт → («День не завершён») → три результата →
 * серия → основная кнопка; общий счёт крупнейший текстовый элемент экрана.
 *
 * **Два варианта по происхождению** (I5-D8). Сессионный: заголовок «Сегодня» либо дата,
 * leading-иконки «Назад» нет (граф сессии уже вычищен, и она вела бы туда же, куда
 * «Готово»), строки не нажимаются, внизу «Готово». Архивный: заголовок — всегда дата,
 * «Назад» в шапке и внизу, строка `Played` открывает исторический результат.
 * «Поделиться» (PR 5D), реклама и диалог уведомлений на экране отсутствуют.
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
                    AppTopBar(
                        title = rememberTitle(state),
                        horizontalMargin = margin,
                        // «Назад» в шапке — только у архивного варианта, в том числе у
                        // recapMissing: там это единственный экранный выход.
                        onBackClick = if (state.origin() == RouteOrigin.Archive) {
                            { onEvent(DayRecapEvent.BackClicked) }
                        } else {
                            null
                        },
                    )

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
                            is DayRecapState.Content -> RecapContent(state, onEvent)
                            is DayRecapState.NotFound -> ErrorBlock(
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
                                text = stringResource(
                                    when (state.origin) {
                                        RouteOrigin.Session -> R.string.recap_done
                                        RouteOrigin.Archive -> R.string.recap_back
                                    },
                                ),
                                onClick = { onEvent(DayRecapEvent.PrimaryClicked) },
                                modifier = Modifier.testTag(DayRecapTestTags.PRIMARY_BUTTON),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecapContent(state: DayRecapState.Content, onEvent: (DayRecapEvent) -> Unit) {
    ScoreBadge(
        text = stringResource(R.string.score_of_day, state.totalScore),
        modifier = Modifier.testTag(DayRecapTestTags.SCORE_BADGE),
    )

    if (!state.isComplete) {
        Text(
            text = stringResource(R.string.recap_day_incomplete),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(DayRecapTestTags.INCOMPLETE),
        )
    }

    DayResultList(
        rows = state.slots.map { it.toRowData(onEvent) },
        modifier = Modifier.testTag(DayRecapTestTags.RESULTS),
    )

    // Незавершённый день серию не продолжал: StreakRow нет вовсе.
    val streakDays = state.streakDays
    if (streakDays != null) {
        StreakRow(
            label = stringResource(R.string.recap_streak),
            value = streakText(streakDays),
            modifier = Modifier.testTag(DayRecapTestTags.STREAK),
        )

        // Вторая строка появляется только тогда, когда ЭТОТ день установил рекорд, и
        // показывает то же значение: рекорд, установленный днём, равен его серии (O5-5).
        // Цветом строка не выделяется — только присутствием (I3-D46).
        if (state.isRecordUpdated) {
            StreakRow(
                label = stringResource(R.string.recap_best_streak),
                value = streakText(streakDays),
                modifier = Modifier.testTag(DayRecapTestTags.BEST_STREAK),
            )
        }
    }
}

/**
 * `when` по [SlotResultUi] исчерпывающий, без `else`: `Unavailable` показывает «Задание N»
 * вместо `CategoryLabel` и **фактический** счёт, `NotPlayed` — «Задание N» и «не сыграно»
 * без счёта. Нажимается только `Played` архивного итога.
 */
@Composable
private fun SlotResultUi.toRowData(onEvent: (DayRecapEvent) -> Unit): DayResultRowData = when (this) {
    is SlotResultUi.Played -> DayResultRowData(
        leading = DayResultLeading.CategoryOf(category),
        trailing = DayResultTrailing.Score(stringResource(R.string.score_of_slot, score)),
        onClick = if (isOpenable) {
            { onEvent(DayRecapEvent.SlotClicked(slotIndex)) }
        } else {
            null
        },
        onClickLabel = if (isOpenable) stringResource(R.string.recap_slot_open) else null,
    )

    is SlotResultUi.Unavailable -> DayResultRowData(
        leading = DayResultLeading.Label(stringResource(R.string.recap_slot_unavailable, slotIndex + 1)),
        trailing = DayResultTrailing.Score(stringResource(R.string.score_of_slot, score)),
    )

    is SlotResultUi.NotPlayed -> DayResultRowData(
        leading = DayResultLeading.Label(stringResource(R.string.recap_slot_unavailable, slotIndex + 1)),
        trailing = DayResultTrailing.NotPlayed(stringResource(R.string.recap_slot_not_played)),
    )
}

@Composable
private fun RecapSkeleton() {
    SkeletonLine(widthFraction = SKELETON_SCORE_FRACTION, height = Spacing.scale700)
    repeat(SKELETON_ROWS) {
        SkeletonLine(widthFraction = SKELETON_ROW_FRACTION)
    }
}

/** Вариант, известный состоянию: у `Loading` его нет — там нет и кнопок. */
private fun DayRecapState.origin(): RouteOrigin? = when (this) {
    DayRecapState.Loading -> null
    is DayRecapState.Content -> origin
    is DayRecapState.NotFound -> origin
}

/**
 * Заголовок показывает только то, что известно: «Сегодня» или дату у `Content`.
 * У `Loading` и `NotFound` даты в состоянии нет, и заголовок пуст — иначе архивный день
 * на мгновение назывался бы «Сегодня».
 */
@Composable
private fun rememberTitle(state: DayRecapState): String {
    val title = (state as? DayRecapState.Content)?.title
    val today = stringResource(R.string.recap_title_today)
    return remember(title, today) {
        when (title) {
            DayRecapTitle.Today -> today
            is DayRecapTitle.Date ->
                title.localDate.format(DATE_FORMATTER).replaceFirstChar { it.titlecase(RUSSIAN) }
            null -> ""
        }
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

private fun playedDay(
    total: Int,
    scores: List<Int>,
    origin: RouteOrigin = RouteOrigin.Session,
    title: DayRecapTitle = DayRecapTitle.Today,
) = DayRecapState.Content(
    origin = origin,
    title = title,
    dayNumber = 12,
    totalScore = total,
    isComplete = true,
    slots = listOf(
        SlotResultUi.Played(0, scores[0], Category.GEOGRAPHY, isOpenable = origin == RouteOrigin.Archive),
        SlotResultUi.Played(1, scores[1], Category.HISTORY, isOpenable = origin == RouteOrigin.Archive),
        SlotResultUi.Played(2, scores[2], Category.SCIENCE, isOpenable = origin == RouteOrigin.Archive),
    ),
    streakDays = 6,
    isRecordUpdated = false,
    canShare = true,
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
        origin = RouteOrigin.Session,
        title = DayRecapTitle.Today,
        dayNumber = 3,
        totalScore = 0,
        isComplete = true,
        slots = listOf(
            SlotResultUi.Unavailable(slotIndex = 0, score = 0),
            SlotResultUi.Unavailable(slotIndex = 1, score = 0),
            SlotResultUi.Unavailable(slotIndex = 2, score = 0),
        ),
        streakDays = 1,
        isRecordUpdated = true,
        canShare = true,
    ),
)

/** Смешанный: две `Played` и одна `Unavailable` с НЕнулевым счётом. */
@Preview(name = "DayRecap — смешанный 390×844", widthDp = 390, heightDp = 844)
@Composable
private fun DayRecapMixedPreview() = PreviewRecap(
    DayRecapState.Content(
        origin = RouteOrigin.Session,
        title = DayRecapTitle.Date(LocalDate.of(2026, 8, 25)),
        dayNumber = 11,
        totalScore = 14,
        isComplete = true,
        slots = listOf(
            SlotResultUi.Played(slotIndex = 0, score = 6, category = Category.NATURE, isOpenable = false),
            SlotResultUi.Unavailable(slotIndex = 1, score = 4),
            SlotResultUi.Played(slotIndex = 2, score = 4, category = Category.RUSSIA, isOpenable = false),
        ),
        streakDays = 3,
        isRecordUpdated = false,
        canShare = true,
    ),
)

/** Архивный незавершённый день: «День не завершён», две строки «не сыграно», без серии. */
@Preview(name = "DayRecap — архив, не завершён 390×844", widthDp = 390, heightDp = 844)
@Composable
private fun DayRecapArchivedIncompletePreview() = PreviewRecap(
    DayRecapState.Content(
        origin = RouteOrigin.Archive,
        title = DayRecapTitle.Date(LocalDate.of(2026, 8, 24)),
        dayNumber = 11,
        totalScore = 5,
        isComplete = false,
        slots = listOf(
            SlotResultUi.Played(slotIndex = 0, score = 5, category = Category.CULTURE, isOpenable = true),
            SlotResultUi.NotPlayed(slotIndex = 1),
            SlotResultUi.NotPlayed(slotIndex = 2),
        ),
        streakDays = null,
        isRecordUpdated = false,
        canShare = false,
    ),
)

@Preview(name = "DayRecap — архив 390×844", widthDp = 390, heightDp = 844)
@Composable
private fun DayRecapArchivedPreview() = PreviewRecap(
    playedDay(
        total = 15,
        scores = listOf(6, 5, 4),
        origin = RouteOrigin.Archive,
        title = DayRecapTitle.Date(LocalDate.of(2026, 8, 25)),
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
