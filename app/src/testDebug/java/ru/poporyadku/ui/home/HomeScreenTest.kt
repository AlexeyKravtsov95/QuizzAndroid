package ru.poporyadku.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.poporyadku.R
import ru.poporyadku.domain.model.CompletedDaySummary
import ru.poporyadku.domain.model.TodayFailureKind
import ru.poporyadku.domain.model.TodayStats
import ru.poporyadku.domain.scoring.Streaks
import ru.poporyadku.ui.theme.PoPoRyadkuTheme

/**
 * `HomeScreen` — ITERATION_3_DESIGN.md, `I3-C1`, `I3-C2`, `I3-C14`, `I3-C18`–`I3-C22`
 * и Home-части `I3-C11`–`I3-C13`.
 *
 * Экран stateless, поэтому Hilt в тестах не участвует (I3-D31): рендерится готовое
 * состояние. Ширина, масштаб шрифта и тема задаются Robolectric-квалификаторами
 * (`@Config(qualifiers = …)`), а не подменой внутренностей экрана.
 */
@RunWith(RobolectricTestRunner::class)
class HomeScreenTest {

    @get:Rule
    val rule = createComposeRule()

    // --- I3-C1: девять композиций ----------------------------------------------------

    /**
     * `I3-C1`. Все девять композиций рендерятся без падения (восемь состояний
     * `UX_FLOW.md` + `AwaitingFirstDay`).
     */
    @Test
    fun `I3-C1 renders all nine compositions`() {
        val state = mutableStateOf<HomeState>(HomeState.Loading)
        rule.setContent { Home(state.value) }

        allStates().forEach { (name, value) ->
            rule.runOnUiThread { state.value = value }
            rule.waitForIdle()
            rule.onNodeWithTag(HomeTestTags.SCREEN).assertExists("состояние $name не отрисовалось")
        }
    }

    /** `I3-C1` (продолжение). В `InProgress` на экране нет ни одного числа счёта. */
    @Test
    fun `I3-C1 in progress shows no score`() {
        rule.setContent { Home(inProgress()) }

        // DailyIssuePanel — ОДИН составной узел семантики, поэтому его содержимое
        // читается через составное описание, а не как отдельные фокус-стопы.
        rule.onNodeWithContentDescription(IN_PROGRESS_DESCRIPTION).assertExists()

        // Ни одного числа счёта: ни «из 18», ни «из 6», ни строк статистики.
        assertTrue(
            "в InProgress не должно быть счёта дня",
            rule.onAllNodes(hasText(OF_18, substring = true)).fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            "в InProgress не должно быть счёта задания",
            rule.onAllNodes(hasText(OF_6, substring = true)).fetchSemanticsNodes().isEmpty(),
        )
        rule.onNodeWithTag(HomeTestTags.STATISTICS_BLOCK).assertDoesNotExist()
    }

    // --- I3-C2: видимость «Архива» ---------------------------------------------------

    /**
     * `I3-C2`. Иконка «Архив» скрыта в `Loading` и при `completedDayCount == 0`,
     * видна при `> 0`. «Настройки» видны всегда.
     */
    @Test
    fun `I3-C2 archive icon follows completedDayCount and progress readiness`() {
        rule.setContent { Home(HomeState.Loading) }
        rule.onNodeWithContentDescription(ARCHIVE).assertDoesNotExist()
        rule.onNodeWithContentDescription(SETTINGS).assertIsDisplayed()
    }

    @Test
    fun `I3-C2 archive icon hidden when nothing is completed`() {
        rule.setContent {
            Home(
                HomeState.Ready(
                    today = TODAY,
                    dayNumber = 1,
                    stats = stats(completedDayCount = 0),
                    isArchiveVisible = false,
                ),
            )
        }
        rule.onNodeWithContentDescription(ARCHIVE).assertDoesNotExist()
        rule.onNodeWithContentDescription(SETTINGS).assertIsDisplayed()
    }

    @Test
    fun `I3-C2 archive icon visible when at least one day is completed`() {
        rule.setContent { Home(ready()) }
        rule.onNodeWithContentDescription(ARCHIVE).assertIsDisplayed()
        rule.onNodeWithContentDescription(SETTINGS).assertIsDisplayed()
    }

    /** `I3-C2` (продолжение). В `Error` с неизвестной статистикой «Архив» скрыт. */
    @Test
    fun `I3-C2 archive icon hidden when progress could not be read`() {
        rule.setContent { Home(error(stats = null)) }
        rule.onNodeWithContentDescription(ARCHIVE).assertDoesNotExist()
        rule.onNodeWithTag(HomeTestTags.STATISTICS_BLOCK).assertDoesNotExist()
    }

    // --- I3-C14: AwaitingFirstDay ----------------------------------------------------

    /**
     * `I3-C14`. `AwaitingFirstDay` не показывает ни `PrimaryButton`, ни
     * `DailyIssuePanel`, ни выдуманных чисел; при нулевой истории нет и
     * `StatisticsBlock`.
     */
    @Test
    fun `I3-C14 awaiting first day has no CTA and no issue panel`() {
        rule.setContent {
            Home(
                HomeState.AwaitingFirstDay(
                    today = TODAY,
                    stats = stats(completedDayCount = 0, playedDayCount = 0),
                    isArchiveVisible = false,
                ),
            )
        }

        rule.onNodeWithText(NEXT_TOMORROW).assertIsDisplayed()
        rule.onNodeWithTag(HomeTestTags.PRIMARY_BUTTON).assertDoesNotExist()
        rule.onNodeWithTag(HomeTestTags.DAILY_ISSUE_PANEL).assertDoesNotExist()
        rule.onNodeWithTag(HomeTestTags.STATISTICS_BLOCK).assertDoesNotExist()
        // Ни «День 0», ни «0 из 18».
        rule.onNodeWithText(SCORE_OF_DAY_0).assertDoesNotExist()
    }

    /** История есть — статистика показывается, CTA по-прежнему нет. */
    @Test
    fun `I3-C14 awaiting first day shows statistics when history exists`() {
        rule.setContent {
            Home(
                HomeState.AwaitingFirstDay(
                    today = TODAY,
                    stats = stats(completedDayCount = 0, playedDayCount = 2),
                    isArchiveVisible = false,
                ),
            )
        }

        rule.onNodeWithTag(HomeTestTags.STATISTICS_BLOCK).assertExists()
        rule.onNodeWithTag(HomeTestTags.PRIMARY_BUTTON).assertDoesNotExist()
    }

    // --- I3-C18 / I3-C19 / I3-C20: видимость recovery --------------------------------

    /** `I3-C18`. `ContentConflict` + непустой набор → действие сброса показано. */
    @Test
    fun `I3-C18 content conflict with a non-empty set shows the reset action`() {
        rule.setContent {
            Home(error(kind = TodayFailureKind.ContentConflict, actions = listOf(RESET_ACTION)))
        }

        rule.onNodeWithTag(HomeTestTags.RETRY_BUTTON).assertIsDisplayed()
        rule.onNodeWithTag(HomeTestTags.recoveryAction(RESET_ACTION.id)).assertIsDisplayed()
    }

    /** `I3-C19`. `Generic` при том же наборе (уже отфильтрованном) — действия нет. */
    @Test
    fun `I3-C19 generic failure does not show the reset action`() {
        rule.setContent {
            // Фильтрация по isApplicableTo выполняется во ViewModel: до экрана при
            // Generic доезжает пустой список — экран рисует ровно то, что получил.
            Home(error(kind = TodayFailureKind.Generic, actions = emptyList()))
        }

        rule.onNodeWithTag(HomeTestTags.RETRY_BUTTON).assertIsDisplayed()
        rule.onNodeWithTag(HomeTestTags.recoveryAction(RESET_ACTION.id)).assertDoesNotExist()
    }

    /** `I3-C20`. Пустой набор (release-конфигурация) скрывает восстановление и при конфликте. */
    @Test
    fun `I3-C20 empty release set hides recovery even on content conflict`() {
        rule.setContent {
            Home(error(kind = TodayFailureKind.ContentConflict, actions = emptyList()))
        }

        rule.onNodeWithTag(HomeTestTags.RETRY_BUTTON).assertIsDisplayed()
        rule.onNodeWithTag(HomeTestTags.recoveryAction(RESET_ACTION.id)).assertDoesNotExist()
    }

    // --- I3-C22: блокировка на время восстановления ----------------------------------

    /**
     * `I3-C22`. При `runningRecoveryId != null` и «Повторить», и кнопка восстановления
     * имеют семантику `disabled`, а диалог подтверждения не показан.
     */
    @Test
    fun `I3-C22 both buttons are disabled while recovery runs and no dialog is shown`() {
        rule.setContent {
            Home(
                error(
                    kind = TodayFailureKind.ContentConflict,
                    actions = listOf(RESET_ACTION),
                    runningRecoveryId = RESET_ACTION.id,
                ),
            )
        }

        rule.onNodeWithTag(HomeTestTags.RETRY_BUTTON).assertIsNotEnabled()
        rule.onNodeWithTag(HomeTestTags.recoveryAction(RESET_ACTION.id)).assertIsNotEnabled()
        rule.onNodeWithTag(HomeTestTags.RECOVERY_DIALOG).assertDoesNotExist()
    }

    /**
     * Диалог — локальное состояние Composable, а событие уносит одновременно
     * `actionId` и поколение того `Error`, на котором он открыт.
     */
    @Test
    fun `recovery dialog carries actionId and generation of its own error`() {
        val events = mutableListOf<HomeEvent>()
        rule.setContent {
            Home(
                state = error(
                    kind = TodayFailureKind.ContentConflict,
                    actions = listOf(RESET_ACTION),
                    generation = 7L,
                ),
                onEvent = { events += it },
            )
        }

        rule.onNodeWithTag(HomeTestTags.recoveryAction(RESET_ACTION.id)).performClick()
        rule.onNodeWithTag(HomeTestTags.RECOVERY_DIALOG).assertExists()

        rule.onNodeWithTag(HomeTestTags.RECOVERY_DIALOG_CONFIRM).performClick()

        assertEquals(
            listOf(HomeEvent.RecoveryConfirmed(RESET_ACTION.id, generation = 7L)),
            events,
        )
    }

    /** Основная кнопка `Error` — «Повторить», и она отправляет `RetryClicked`. */
    @Test
    fun `retry button emits RetryClicked`() {
        val events = mutableListOf<HomeEvent>()
        rule.setContent { Home(state = error(), onEvent = { events += it }) }

        rule.onNodeWithTag(HomeTestTags.RETRY_BUTTON).performClick()

        assertEquals(listOf(HomeEvent.RetryClicked), events)
    }

    /** CTA отправляет `PrimaryAction`, а не выполняет навигацию сама. */
    @Test
    fun `primary button emits PrimaryAction`() {
        val events = mutableListOf<HomeEvent>()
        rule.setContent { Home(state = ready(), onEvent = { events += it }) }

        rule.onNodeWithTag(HomeTestTags.PRIMARY_BUTTON).performClick()

        assertEquals(listOf(HomeEvent.PrimaryAction), events)
    }

    /** Архив и настройки — чистая навигация мимо ViewModel. */
    @Test
    fun `header icons call navigation callbacks and emit no events`() {
        val events = mutableListOf<HomeEvent>()
        var archive = 0
        var settings = 0
        rule.setContent {
            Home(
                state = ready(),
                onEvent = { events += it },
                onArchiveClick = { archive++ },
                onSettingsClick = { settings++ },
            )
        }

        rule.onNodeWithContentDescription(ARCHIVE).performClick()
        rule.onNodeWithContentDescription(SETTINGS).performClick()

        assertEquals(1, archive)
        assertEquals(1, settings)
        assertTrue(events.isEmpty())
    }

    // --- I3-C11 / I3-C12 / I3-C13, Home-часть ---------------------------------------

    /** `I3-C11`, Home-часть. На 320 dp экран рендерится без горизонтальной прокрутки. */
    @Test
    @Config(qualifiers = "w320dp-h844dp")
    fun `I3-C11 home renders on 320 dp without horizontal scrolling`() {
        rule.setContent { Home(ready()) }

        rule.onNodeWithTag(HomeTestTags.SCREEN).assertExists()
        val screen = rule.onNodeWithTag(HomeTestTags.SCREEN).fetchSemanticsNode()
        val content = rule.onNodeWithTag(HomeTestTags.CONTENT).fetchSemanticsNode()

        assertTrue(
            "контент шире экрана: ${content.size.width} > ${screen.size.width}",
            content.size.width <= screen.size.width,
        )
        // Ни один узел не предлагает горизонтальную прокрутку.
        assertTrue(
            "на Home не должно быть горизонтальной прокрутки",
            rule.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange),
            ).fetchSemanticsNodes().isEmpty(),
        )
    }

    /** `I3-C12`, Home-часть. При масштабе шрифта 200% CTA присутствует и нажимается. */
    @Test
    @Config(qualifiers = "w320dp-h844dp")
    fun `I3-C12 primary button stays clickable at font scale 200 percent`() {
        val events = mutableListOf<HomeEvent>()
        rule.setContent {
            WithFontScale(FONT_SCALE_200) {
                Home(state = ready(), onEvent = { events += it })
            }
        }

        // assertExists недостаточно: Compose кликает и по узлу, уехавшему за пределы
        // viewport, — кнопка обязана остаться ВИДИМОЙ.
        rule.onNodeWithTag(HomeTestTags.PRIMARY_BUTTON).assertIsDisplayed()
        rule.onNodeWithTag(HomeTestTags.PRIMARY_BUTTON).assertHasClickAction()
        rule.onNodeWithTag(HomeTestTags.PRIMARY_BUTTON).assertIsEnabled()
        rule.onNodeWithTag(HomeTestTags.PRIMARY_BUTTON).performClick()

        assertEquals(listOf(HomeEvent.PrimaryAction), events)
    }

    /** `I3-C13`, Home-часть. Тёмная тема отрисовывается. */
    @Test
    fun `I3-C13 home renders in dark theme`() {
        rule.setContent {
            PoPoRyadkuTheme(darkTheme = true) {
                HomeScreen(
                    state = ready(),
                    countdown = null,
                    onEvent = {},
                    onArchiveClick = {},
                    onSettingsClick = {},
                )
            }
        }

        rule.onNodeWithTag(HomeTestTags.SCREEN).assertExists()
        rule.onNodeWithTag(HomeTestTags.DAILY_ISSUE_PANEL).assertExists()
        rule.onNodeWithTag(HomeTestTags.PRIMARY_BUTTON).assertIsDisplayed()
    }

    // --- Инфраструктура --------------------------------------------------------------

    @Composable
    private fun Home(
        state: HomeState,
        onEvent: (HomeEvent) -> Unit = {},
        onArchiveClick: () -> Unit = {},
        onSettingsClick: () -> Unit = {},
    ) {
        PoPoRyadkuTheme(darkTheme = false) {
            HomeScreen(
                state = state,
                countdown = Duration.ofMinutes(COUNTDOWN_MINUTES),
                onEvent = onEvent,
                onArchiveClick = onArchiveClick,
                onSettingsClick = onSettingsClick,
            )
        }
    }

    private fun allStates(): List<Pair<String, HomeState>> = listOf(
        "Loading" to HomeState.Loading,
        "FirstRun" to HomeState.FirstRun(TODAY, dayNumber = 1),
        "Ready" to ready(),
        "InProgress" to inProgress(),
        "Completed" to HomeState.Completed(
            today = TODAY,
            sessionDate = TODAY,
            dayNumber = 24,
            totalScore = 15,
            streaks = Streaks(6, 9),
            nextLocalDateStartsAt = Instant.EPOCH,
            isArchiveVisible = true,
        ),
        "AwaitingNextDay" to HomeState.AwaitingNextDay(
            today = TODAY,
            lastCompleted = CompletedDaySummary(TODAY.minusDays(1), dayNumber = 23, totalScore = 15),
            stats = stats(completedDayCount = 21),
            isArchiveVisible = true,
        ),
        "AwaitingFirstDay" to HomeState.AwaitingFirstDay(
            today = TODAY,
            stats = stats(completedDayCount = 0, playedDayCount = 0),
            isArchiveVisible = false,
        ),
        "ContentExhausted" to HomeState.ContentExhausted(
            today = TODAY,
            stats = stats(completedDayCount = 35),
            isArchiveVisible = true,
        ),
        "Error" to error(),
    )

    private fun ready() = HomeState.Ready(
        today = TODAY,
        dayNumber = 24,
        stats = stats(completedDayCount = 21),
        isArchiveVisible = true,
    )

    private fun inProgress() = HomeState.InProgress(
        today = TODAY,
        sessionDate = TODAY,
        dayNumber = 24,
        completedCount = 1,
        isArchiveVisible = false,
    )

    private fun error(
        kind: TodayFailureKind = TodayFailureKind.Generic,
        actions: List<RecoveryActionUi> = emptyList(),
        runningRecoveryId: String? = null,
        generation: Long = 1L,
        stats: TodayStats? = stats(completedDayCount = 21),
    ) = HomeState.Error(
        today = TODAY,
        stats = stats,
        kind = kind,
        recoveryActions = actions,
        runningRecoveryId = runningRecoveryId,
        recomputeGeneration = generation,
        isArchiveVisible = stats != null && stats.completedDayCount > 0,
    )

    private fun stats(completedDayCount: Int, playedDayCount: Int = completedDayCount) = TodayStats(
        streaks = Streaks(current = 6, best = 9),
        bestDayScore = 17,
        playedDayCount = playedDayCount,
        completedDayCount = completedDayCount,
    )

    /** Масштаб шрифта задаётся плотностью, а не подменой внутренностей экрана. */
    @Composable
    private fun WithFontScale(scale: Float, content: @Composable () -> Unit) {
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) {
            content()
        }
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 8, 29)
        const val COUNTDOWN_MINUTES = 14L * 60 + 30
        const val FONT_SCALE_200 = 2f

        /**
         * Дескриптор доезжает до экрана уже отфильтрованным; строки взяты из `src/main`,
         * потому что тест общий для debug- и release-варианта, а настоящий вклад
         * (`TemporaryContentResetAction`) живёт только в `src/debug`.
         */
        val RESET_ACTION = RecoveryActionUi(
            id = "temporary_content_reset",
            labelRes = R.string.home_recovery_dialog_confirm,
            confirmationRes = R.string.home_error_message,
        )

        const val IN_PROGRESS_DESCRIPTION = "Выпуск 24. Задание 2 из 3"
        const val OF_18 = "из 18"
        const val OF_6 = "из 6"
        const val SCORE_OF_DAY_0 = "0 из 18"
        const val NEXT_TOMORROW = "Следующее задание — завтра"
        const val ARCHIVE = "Архив"
        const val SETTINGS = "Настройки"
    }
}
