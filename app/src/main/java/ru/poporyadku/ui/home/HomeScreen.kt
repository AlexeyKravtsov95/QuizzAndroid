package ru.poporyadku.ui.home

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import ru.poporyadku.R
import ru.poporyadku.domain.model.CompletedDaySummary
import ru.poporyadku.domain.model.TodayFailureKind
import ru.poporyadku.domain.model.TodayStats
import ru.poporyadku.domain.scoring.Streaks
import ru.poporyadku.ui.components.ErrorBlock
import ru.poporyadku.ui.components.DailyIssuePanelSkeleton
import ru.poporyadku.ui.components.PrimaryButton
import ru.poporyadku.ui.components.SecondaryButton
import ru.poporyadku.ui.components.StatisticItem
import ru.poporyadku.ui.components.StatisticsBlock
import ru.poporyadku.ui.theme.PoPoRyadkuTheme
import ru.poporyadku.ui.theme.Sizing
import ru.poporyadku.ui.theme.Spacing

/** Стабильные testTag экрана Home — для Compose- и navigation-тестов. */
object HomeTestTags {
    const val SCREEN = "home_screen"
    const val CONTENT = "home_content"
    const val DAILY_ISSUE_PANEL = "home_daily_issue_panel"
    const val STATISTICS_BLOCK = "home_statistics_block"
    const val PRIMARY_BUTTON = "home_primary_button"
    const val RETRY_BUTTON = "home_retry_button"
    const val RECOVERY_DIALOG = "home_recovery_dialog"
    const val RECOVERY_DIALOG_CONFIRM = "home_recovery_dialog_confirm"
    const val LOADING_SKELETON = "home_loading_skeleton"

    /** Кнопка восстановления конкретного действия. */
    fun recoveryAction(actionId: String): String = "home_recovery_action_$actionId"
}

/**
 * Главный экран (ITERATION_3_DESIGN.md, раздел 15; COMPONENTS.md).
 *
 * Stateless: состояние и callbacks приходят параметрами, ViewModel подключается только
 * в `AppNavHost`. Это требование `ARCHITECTURE.md` §4 и одновременно условие, при
 * котором Compose-тесты работают без Hilt (I3-D31).
 *
 * Девять композиций: `Loading`, `FirstRun`, `Ready`, `InProgress`, `Completed`,
 * `AwaitingNextDay`, `AwaitingFirstDay`, `ContentExhausted`, `Error`.
 *
 * Переходы в «Архив» и «Настройки» — чистая навигация и приходят отдельными
 * callback-параметрами, а не через [HomeEvent]: ViewModel в них не участвует.
 */
@Composable
fun HomeScreen(
    state: HomeState,
    countdown: Duration?,
    onEvent: (HomeEvent) -> Unit,
    onArchiveClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(HomeTestTags.SCREEN),
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

            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(modifier = columnWidth.fillMaxSize()) {
                    HomeHeader(
                        dateText = rememberFormattedDate(state.headerDate),
                        isArchiveVisible = state.isArchiveVisible,
                        onArchiveClick = onArchiveClick,
                        onSettingsClick = onSettingsClick,
                        horizontalMargin = margin,
                        intro = if (state is HomeState.FirstRun) {
                            stringResource(R.string.home_first_run_intro)
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
                            .testTag(HomeTestTags.CONTENT),
                        verticalArrangement = Arrangement.spacedBy(Spacing.section),
                    ) {
                        HomeBody(state = state, countdown = countdown, onEvent = onEvent)
                    }

                    PinnedCta(state = state, margin = margin, onEvent = onEvent)
                }
            }
        }
    }
}

// --- Тело экрана -----------------------------------------------------------

@Composable
private fun ColumnScope.HomeBody(
    state: HomeState,
    countdown: Duration?,
    onEvent: (HomeEvent) -> Unit,
) {
    when (state) {
        HomeState.Loading -> DailyIssuePanelSkeleton(
            modifier = Modifier.testTag(HomeTestTags.LOADING_SKELETON),
        )

        is HomeState.FirstRun -> DailyIssuePanel(
            dayNumber = state.dayNumber,
            contentDescription = stringResource(R.string.cd_issue_panel_first_run, state.dayNumber),
            modifier = Modifier.testTag(HomeTestTags.DAILY_ISSUE_PANEL),
        ) {
            Text(
                text = stringResource(R.string.home_first_run_caption),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is HomeState.Ready -> {
            val streak = streakText(state.stats.streaks.current)
            val bestDay = stringResource(R.string.score_of_day, state.stats.bestDayScore)
            DailyIssuePanel(
                dayNumber = state.dayNumber,
                contentDescription = stringResource(
                    R.string.cd_issue_panel_ready,
                    state.dayNumber,
                    streak,
                    bestDay,
                    state.stats.playedDayCount,
                ),
                modifier = Modifier.testTag(HomeTestTags.DAILY_ISSUE_PANEL),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.scale400)) {
                    IssueStatRow(stringResource(R.string.home_stat_streak), streak)
                    IssueStatRow(stringResource(R.string.home_stat_best_day), bestDay)
                    IssueStatRow(
                        stringResource(R.string.home_stat_played_days),
                        state.stats.playedDayCount.toString(),
                    )
                }
            }
        }

        is HomeState.InProgress -> DailyIssuePanel(
            dayNumber = state.dayNumber,
            contentDescription = stringResource(
                R.string.cd_issue_panel_in_progress,
                state.dayNumber,
                state.completedCount + 1,
            ),
            modifier = Modifier.testTag(HomeTestTags.DAILY_ISSUE_PANEL),
        ) {
            // Ни счёта, ни баллов — ни промежуточных, ни скрытых-но-отрендеренных.
            InProgressContent(completedCount = state.completedCount)
        }

        is HomeState.Completed -> {
            val score = stringResource(R.string.score_of_day, state.totalScore)
            val streak = streakText(state.streaks.current)
            val remaining = formatCountdown(countdown)
            DailyIssuePanel(
                dayNumber = state.dayNumber,
                contentDescription = stringResource(
                    R.string.cd_issue_panel_completed,
                    state.dayNumber,
                    score,
                    streak,
                    remaining,
                ),
                modifier = Modifier.testTag(HomeTestTags.DAILY_ISSUE_PANEL),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.scale400)) {
                    IssueStatRow(stringResource(R.string.home_today_score_label), score)
                    IssueStatRow(stringResource(R.string.home_stat_streak), streak)
                    IssueStatRow(
                        stringResource(R.string.home_next_issue_label),
                        stringResource(R.string.home_next_in, remaining),
                    )
                }
            }
        }

        is HomeState.AwaitingNextDay -> {
            val score = stringResource(R.string.score_of_day, state.lastCompleted.totalScore)
            DailyIssuePanel(
                // Номер ПОСЛЕДНЕГО завершённого дня: он не увеличивается.
                dayNumber = state.lastCompleted.dayNumber,
                contentDescription = stringResource(
                    R.string.cd_issue_panel_awaiting_next_day,
                    state.lastCompleted.dayNumber,
                    score,
                ),
                modifier = Modifier.testTag(HomeTestTags.DAILY_ISSUE_PANEL),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.scale400)) {
                    IssueStatRow(stringResource(R.string.home_total_score_label), score)
                    IssueStatRow(
                        stringResource(R.string.home_next_issue_label),
                        stringResource(R.string.home_next_tomorrow_value),
                    )
                }
            }
        }

        is HomeState.AwaitingFirstDay -> {
            // Ни DailyIssuePanel, ни PrimaryButton, ни фиктивной даты, ни «День 0»,
            // ни «0 из 18» (I3-D35): одна нейтральная строка и, при наличии истории,
            // статистика.
            Text(
                text = stringResource(R.string.home_next_tomorrow),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (state.stats.playedDayCount > 0) {
                HomeStatistics(state.stats)
            }
        }

        is HomeState.ContentExhausted -> {
            Text(
                text = stringResource(R.string.home_content_exhausted),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            HomeStatistics(state.stats)
        }

        is HomeState.Error -> ErrorContent(state = state, onEvent = onEvent)
    }
}

/**
 * `Error.retryable` + опциональное восстановление (I3-D47).
 *
 * Основная кнопка — «Повторить». Кнопка восстановления показывается **только** если
 * набор дескрипторов непуст: при `Generic` он отфильтрован по `isApplicableTo` и пуст,
 * а в release-сборке пуст всегда. Пока `runningRecoveryId != null`, обе кнопки
 * `disabled`, а диалог не показывается.
 */
@Composable
private fun ColumnScope.ErrorContent(
    state: HomeState.Error,
    onEvent: (HomeEvent) -> Unit,
) {
    // Локальный payload диалога копирует одновременно actionId и recomputeGeneration:
    // пока пользователь читает предупреждение, состояние могло смениться, и устаревший
    // диалог не должен получить поколение нового Error.
    var pending by remember { mutableStateOf<RecoveryDialogPayload?>(null) }
    val isRecovering = state.runningRecoveryId != null

    ErrorBlock(message = stringResource(R.string.home_error_message)) {
        PrimaryButton(
            text = stringResource(R.string.home_error_retry),
            onClick = { onEvent(HomeEvent.RetryClicked) },
            enabled = !isRecovering,
            modifier = Modifier.testTag(HomeTestTags.RETRY_BUTTON),
        )
        state.recoveryActions.forEach { action ->
            SecondaryButton(
                text = stringResource(action.labelRes),
                onClick = {
                    pending = RecoveryDialogPayload(
                        actionId = action.id,
                        generation = state.recomputeGeneration,
                        confirmationRes = action.confirmationRes,
                        labelRes = action.labelRes,
                    )
                },
                enabled = !isRecovering,
                modifier = Modifier.testTag(HomeTestTags.recoveryAction(action.id)),
            )
        }
    }

    // Статистика показывается, только если прогресс действительно прочитан.
    state.stats?.let { HomeStatistics(it) }

    val payload = pending
    if (payload != null && !isRecovering) {
        RecoveryConfirmationDialog(
            payload = payload,
            onDismiss = { pending = null },
            onConfirm = {
                pending = null
                onEvent(HomeEvent.RecoveryConfirmed(payload.actionId, payload.generation))
            },
        )
    }
}

/** Локальный payload диалога: экран поколение не интерпретирует, только копирует. */
private data class RecoveryDialogPayload(
    val actionId: String,
    val generation: Long,
    val confirmationRes: Int,
    val labelRes: Int,
)

@Composable
private fun RecoveryConfirmationDialog(
    payload: RecoveryDialogPayload,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(HomeTestTags.RECOVERY_DIALOG),
        title = { Text(stringResource(payload.labelRes)) },
        text = { Text(stringResource(payload.confirmationRes)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(HomeTestTags.RECOVERY_DIALOG_CONFIRM),
            ) {
                Text(stringResource(R.string.home_recovery_dialog_confirm))
            }
        },
        dismissButton = {
            // Отмена не отправляет события вовсе — экран остаётся в Error.
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_recovery_dialog_cancel))
            }
        },
    )
}

@Composable
private fun HomeStatistics(stats: TodayStats) {
    StatisticsBlock(
        title = stringResource(R.string.statistics_title),
        items = listOf(
            StatisticItem(
                stringResource(R.string.home_stat_played_days),
                stats.playedDayCount.toString(),
            ),
            StatisticItem(
                stringResource(R.string.home_stat_best_day),
                stringResource(R.string.score_of_day, stats.bestDayScore),
            ),
            StatisticItem(stringResource(R.string.home_stat_streak), streakText(stats.streaks.current)),
            StatisticItem(
                stringResource(R.string.home_stat_best_streak),
                streakText(stats.streaks.best),
            ),
        ),
        modifier = Modifier.testTag(HomeTestTags.STATISTICS_BLOCK),
    )
}

// --- Закреплённая CTA ------------------------------------------------------

/**
 * Нижний системный inset применяется к контейнеру закреплённой кнопки, а не ко всему
 * экрану (UI_REVIEW_CHECKLIST.md, «Edge-to-edge»).
 *
 * `AwaitingFirstDay` кнопки не имеет вовсе, `Loading` — тоже; на `Error` основное
 * действие («Повторить») живёт рядом с текстом ошибки, как требует state sheet.
 */
@Composable
private fun PinnedCta(
    state: HomeState,
    margin: Dp,
    onEvent: (HomeEvent) -> Unit,
) {
    val label = when (state) {
        is HomeState.FirstRun -> R.string.home_cta_start
        is HomeState.Ready -> R.string.home_cta_play
        is HomeState.InProgress -> R.string.home_cta_continue
        is HomeState.Completed -> R.string.home_cta_view_recap
        is HomeState.AwaitingNextDay -> R.string.home_cta_view_recap
        is HomeState.ContentExhausted -> R.string.home_cta_open_archive
        is HomeState.AwaitingFirstDay, HomeState.Loading, is HomeState.Error -> null
    } ?: return

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(horizontal = margin)
            .padding(bottom = Spacing.section),
    ) {
        PrimaryButton(
            text = stringResource(label),
            onClick = { onEvent(HomeEvent.PrimaryAction) },
            modifier = Modifier.testTag(HomeTestTags.PRIMARY_BUTTON),
        )
    }
}

// --- Форматирование --------------------------------------------------------

/** Дата шапки; у `Loading` и у отказа без прочитанной даты её нет. */
private val HomeState.headerDate: LocalDate?
    get() = when (this) {
        HomeState.Loading -> null
        is HomeState.FirstRun -> today
        is HomeState.Ready -> today
        is HomeState.InProgress -> today
        is HomeState.Completed -> today
        is HomeState.AwaitingNextDay -> today
        is HomeState.AwaitingFirstDay -> today
        is HomeState.ContentExhausted -> today
        is HomeState.Error -> today
    }

/**
 * «Суббота, 29 августа 2026»: русская локаль даёт «суббота», COMPONENTS.md показывает
 * первую букву заглавной. Форматирование — свойство представления, поэтому живёт здесь,
 * а не в домене.
 */
@Composable
private fun rememberFormattedDate(date: LocalDate?): String = remember(date) {
    date?.format(DATE_FORMATTER)?.replaceFirstChar { it.titlecase(RUSSIAN) }.orEmpty()
}

@Composable
private fun streakText(days: Int): String =
    pluralStringResource(R.plurals.streak_days, days, days)

@Composable
private fun formatCountdown(countdown: Duration?): String =
    if (countdown == null) {
        stringResource(R.string.home_countdown_pending)
    } else {
        stringResource(
            R.string.home_countdown_format,
            countdown.toHours().toInt(),
            countdown.toMinutes().toInt() % MINUTES_PER_HOUR,
        )
    }

private val RUSSIAN: Locale = Locale.forLanguageTag("ru")
private val DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", RUSSIAN)
private const val MINUTES_PER_HOUR = 60
private const val WEIGHT_FILL = 1f

// --- Preview ---------------------------------------------------------------
// Previews вызывают реальный экран, а не отдельную копию его разметки.

private val PreviewToday: LocalDate = LocalDate.of(2026, 8, 29)

private val PreviewStats = TodayStats(
    streaks = Streaks(current = 6, best = 9),
    bestDayScore = 17,
    playedDayCount = 23,
    completedDayCount = 21,
)

private val PreviewEmptyStats = TodayStats(
    streaks = Streaks(current = 0, best = 0),
    bestDayScore = 0,
    playedDayCount = 0,
    completedDayCount = 0,
)

@Composable
private fun PreviewHome(
    state: HomeState,
    darkTheme: Boolean = false,
    countdown: Duration? = Duration.ofMinutes(14 * 60 + 30),
) {
    PoPoRyadkuTheme(darkTheme = darkTheme) {
        HomeScreen(
            state = state,
            countdown = countdown,
            onEvent = {},
            onArchiveClick = {},
            onSettingsClick = {},
        )
    }
}

@Preview(name = "Home — FirstRun light 390×844", widthDp = 390, heightDp = 844)
@Composable
private fun HomeFirstRunPreview() =
    PreviewHome(HomeState.FirstRun(today = PreviewToday, dayNumber = 1))

@Preview(name = "Home — Ready light 390×844", widthDp = 390, heightDp = 844)
@Composable
private fun HomeReadyPreview() = PreviewHome(
    HomeState.Ready(
        today = PreviewToday,
        dayNumber = 24,
        stats = PreviewStats,
        isArchiveVisible = true,
    ),
)

@Preview(name = "Home — InProgress light 390×844", widthDp = 390, heightDp = 844)
@Composable
private fun HomeInProgressPreview() = PreviewHome(
    HomeState.InProgress(
        today = PreviewToday,
        sessionDate = PreviewToday,
        dayNumber = 24,
        completedCount = 1,
        isArchiveVisible = false,
    ),
)

@Preview(name = "Home — Completed light 390×844", widthDp = 390, heightDp = 844)
@Composable
private fun HomeCompletedPreview() = PreviewHome(
    HomeState.Completed(
        today = PreviewToday,
        sessionDate = PreviewToday,
        dayNumber = 24,
        totalScore = 15,
        streaks = PreviewStats.streaks,
        nextLocalDateStartsAt = Instant.EPOCH,
        isArchiveVisible = true,
    ),
)

@Preview(name = "Home — AwaitingNextDay light 390×844", widthDp = 390, heightDp = 844)
@Composable
private fun HomeAwaitingNextDayPreview() = PreviewHome(
    HomeState.AwaitingNextDay(
        today = PreviewToday,
        lastCompleted = CompletedDaySummary(
            localDate = PreviewToday.minusDays(1),
            dayNumber = 23,
            totalScore = 15,
        ),
        stats = PreviewStats,
        isArchiveVisible = true,
    ),
)

@Preview(name = "Home — AwaitingFirstDay light 390×844", widthDp = 390, heightDp = 844)
@Composable
private fun HomeAwaitingFirstDayPreview() = PreviewHome(
    HomeState.AwaitingFirstDay(
        today = PreviewToday,
        stats = PreviewEmptyStats,
        isArchiveVisible = false,
    ),
)

@Preview(name = "Home — ContentExhausted light 390×844", widthDp = 390, heightDp = 844)
@Composable
private fun HomeContentExhaustedPreview() = PreviewHome(
    HomeState.ContentExhausted(
        today = PreviewToday,
        stats = PreviewStats,
        isArchiveVisible = true,
    ),
)

@Preview(name = "Home — Error light 390×844", widthDp = 390, heightDp = 844)
@Composable
private fun HomeErrorPreview() = PreviewHome(
    HomeState.Error(
        today = PreviewToday,
        stats = PreviewStats,
        kind = TodayFailureKind.Generic,
        recoveryActions = emptyList(),
        runningRecoveryId = null,
        recomputeGeneration = 1L,
        isArchiveVisible = true,
    ),
)

@Preview(
    name = "Home — Ready dark 390×844",
    widthDp = 390,
    heightDp = 844,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun HomeReadyDarkPreview() = PreviewHome(
    state = HomeState.Ready(
        today = PreviewToday,
        dayNumber = 24,
        stats = PreviewStats,
        isArchiveVisible = true,
    ),
    darkTheme = true,
)

@Preview(name = "Home — Ready 320×844 @200%", widthDp = 320, heightDp = 844, fontScale = 2f)
@Composable
private fun HomeReadyCompactLargeFontPreview() = PreviewHome(
    HomeState.Ready(
        today = PreviewToday,
        dayNumber = 24,
        stats = PreviewStats,
        isArchiveVisible = true,
    ),
)
