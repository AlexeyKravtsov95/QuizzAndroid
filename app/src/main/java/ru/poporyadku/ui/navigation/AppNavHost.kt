package ru.poporyadku.ui.navigation

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ru.poporyadku.R
import ru.poporyadku.ui.theme.PoPoRyadkuTheme
import ru.poporyadku.ui.theme.Sizing
import ru.poporyadku.ui.theme.Spacing

/**
 * Итерация 1: все шесть маршрутов из UX_FLOW.md §1 с рабочей навигацией между ними.
 * Игровое состояние, реальные данные и бизнес-логика — вне этой итерации.
 *
 * Правила бэкстека (UX_FLOW.md §1) реализованы стандартным `popUpTo` Navigation Compose,
 * без BackHandler: при навигации на конкретный `puzzle/{i}`/`puzzle/{i}/result` можно
 * `popUpTo` тем же конкретным (с заполненным аргументом) маршрутом — Navigation Compose
 * с версии 2.4 сопоставляет такой вызов с точным экземпляром бэкстека, а не с любым
 * узлом того же маршрута.
 */
@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = Destinations.HOME,
        modifier = modifier,
    ) {
        composable(Destinations.HOME) {
            HomeStubScreen(
                onPlayClick = { navController.navigate(Destinations.puzzle(0)) },
                onArchiveClick = { navController.navigate(Destinations.ARCHIVE) },
                onSettingsClick = { navController.navigate(Destinations.SETTINGS) },
            )
        }

        composable(
            route = Destinations.PUZZLE,
            arguments = listOf(navArgument(Destinations.ARG_PUZZLE_INDEX) { type = NavType.IntType }),
        ) { backStackEntry ->
            val puzzleIndex = backStackEntry.arguments?.getInt(Destinations.ARG_PUZZLE_INDEX) ?: 0
            PuzzleStubScreen(
                puzzleIndex = puzzleIndex,
                onBackClick = { navController.popBackStack() },
                onSubmitClick = {
                    // Puzzle(i) -> PuzzleResult(i): заменяет Puzzle(i) в стеке (UX_FLOW.md §1).
                    navController.navigate(Destinations.puzzleResult(puzzleIndex)) {
                        popUpTo(Destinations.puzzle(puzzleIndex)) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Destinations.PUZZLE_RESULT,
            arguments = listOf(navArgument(Destinations.ARG_PUZZLE_INDEX) { type = NavType.IntType }),
        ) { backStackEntry ->
            val puzzleIndex = backStackEntry.arguments?.getInt(Destinations.ARG_PUZZLE_INDEX) ?: 0
            PuzzleResultStubScreen(
                puzzleIndex = puzzleIndex,
                onNextClick = {
                    if (puzzleIndex < 2) {
                        // PuzzleResult(i) -> Puzzle(i+1): заменяет текущий PuzzleResult(i).
                        navController.navigate(Destinations.puzzle(puzzleIndex + 1)) {
                            popUpTo(Destinations.puzzleResult(puzzleIndex)) { inclusive = true }
                        }
                    } else {
                        // PuzzleResult(2) -> recap/today: вычищает dailySession, сохраняя Home.
                        navController.navigate(Destinations.recap(Destinations.TODAY)) {
                            popUpTo(Destinations.HOME) { inclusive = false }
                        }
                    }
                },
            )
        }

        composable(
            route = Destinations.RECAP,
            arguments = listOf(navArgument(Destinations.ARG_DATE) { type = NavType.StringType }),
        ) { backStackEntry ->
            val date = backStackEntry.arguments?.getString(Destinations.ARG_DATE).orEmpty()
            RecapStubScreen(
                date = date,
                onBackToArchiveClick = { navController.popBackStack() },
                onDoneClick = {
                    // Возврат на уже существующий Home, а не создание второго экземпляра.
                    navController.popBackStack(Destinations.HOME, inclusive = false)
                },
            )
        }

        composable(Destinations.ARCHIVE) {
            ArchiveStubScreen(
                onBackClick = { navController.popBackStack() },
                onOpenRecapClick = { navController.navigate(Destinations.recap(SampleArchiveDate)) },
            )
        }

        composable(Destinations.SETTINGS) {
            SettingsStubScreen(onBackClick = { navController.popBackStack() })
        }
    }
}

/**
 * Итерация 1 не имеет архивных данных — эта дата иллюстративна и служит только для
 * проверки перехода `Archive -> recap/{date}` и формата ISO `yyyy-MM-dd`. Реальный список
 * дней появится в итерации 5 вместе с `ArchiveScreen`/`GetArchiveUseCase`.
 */
private const val SampleArchiveDate = "2026-08-25"

@Composable
private fun StubScaffold(
    title: String,
    onBackClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.TopCenter,
        ) {
            // DESIGN_TOKENS.md §6.10 — поле экрана и контентная колонка на границе экрана,
            // не в глубине компонентов: BoxWithConstraints даёт maxWidth/maxHeight здесь же.
            val isCompactWidth = maxWidth < Sizing.compactWidthBreakpoint
            val horizontalMargin = if (isCompactWidth) Spacing.marginCompact else Spacing.marginDefault
            val isWideOrLandscape = maxWidth >= Sizing.mediumWidthBreakpoint || maxWidth > maxHeight
            val columnWidthModifier = if (isWideOrLandscape) {
                Modifier.widthIn(max = Sizing.contentMaxWidth)
            } else {
                Modifier.fillMaxWidth()
            }

            Column(
                modifier = columnWidthModifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = horizontalMargin, vertical = Spacing.section),
                verticalArrangement = Arrangement.spacedBy(Spacing.section),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                content()
                if (onBackClick != null) {
                    StubSecondaryButton(
                        text = stringResource(R.string.cd_back),
                        onClick = onBackClick,
                        modifier = Modifier.testTag(TestTags.GENERIC_BACK_BUTTON),
                    )
                }
            }
        }
    }
}

/**
 * Единственное основное действие экрана-заглушки. Соответствие PrimaryButton
 * (`COMPONENTS.md`): shape.small, size.button.height минимум, labelLarge, primary/onPrimary.
 * Полноценный переиспользуемый компонент PrimaryButton — задача итерации 3; здесь — только
 * применение утверждённых токенов к навигационным заглушкам итерации 1.
 */
@Composable
private fun StubPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Sizing.buttonHeight),
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Второстепенное действие экрана-заглушки. Соответствие SecondaryButton (`COMPONENTS.md`):
 * всегда outlined, никогда tonal-filled и никогда pill — shape.small, size.button.height
 * минимум, labelLarge, обводка outline/текст onSurface.
 */
@Composable
private fun StubSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Sizing.buttonHeight),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(
            width = ButtonDefaults.outlinedButtonBorder(enabled = true).width,
            color = MaterialTheme.colorScheme.outline,
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Стабильные testTag для UI-тестов навигации (`AppNavHostTest`) — не производственное поведение. */
private object TestTags {
    const val GENERIC_BACK_BUTTON = "stub_generic_back_button"
    const val HOME_PLAY_BUTTON = "home_play_button"
    const val HOME_ARCHIVE_BUTTON = "home_archive_button"
    const val HOME_SETTINGS_BUTTON = "home_settings_button"
    const val PUZZLE_SUBMIT_BUTTON = "puzzle_submit_button"
    const val PUZZLE_RESULT_NEXT_BUTTON = "puzzle_result_next_button"
    const val RECAP_PRIMARY_BUTTON = "recap_primary_button"
    const val RECAP_BACK_TO_ARCHIVE_BUTTON = "recap_back_to_archive_button"
    const val ARCHIVE_OPEN_RECAP_ROW = "archive_open_recap_row"
}

@Composable
private fun HomeStubScreen(
    onPlayClick: () -> Unit,
    onArchiveClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    StubScaffold(title = stringResource(R.string.stub_home_title)) {
        Text(
            text = stringResource(R.string.stub_placeholder_caption),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StubPrimaryButton(
            text = stringResource(R.string.stub_home_play),
            onClick = onPlayClick,
            modifier = Modifier.testTag(TestTags.HOME_PLAY_BUTTON),
        )
        StubSecondaryButton(
            text = stringResource(R.string.cd_archive),
            onClick = onArchiveClick,
            modifier = Modifier.testTag(TestTags.HOME_ARCHIVE_BUTTON),
        )
        StubSecondaryButton(
            text = stringResource(R.string.cd_settings),
            onClick = onSettingsClick,
            modifier = Modifier.testTag(TestTags.HOME_SETTINGS_BUTTON),
        )
    }
}

@Composable
private fun PuzzleStubScreen(
    puzzleIndex: Int,
    onBackClick: () -> Unit,
    onSubmitClick: () -> Unit,
) {
    StubScaffold(
        title = stringResource(R.string.stub_puzzle_title, puzzleIndex + 1),
        onBackClick = onBackClick,
    ) {
        Text(
            text = stringResource(R.string.stub_placeholder_caption),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StubPrimaryButton(
            text = stringResource(R.string.stub_puzzle_submit),
            onClick = onSubmitClick,
            modifier = Modifier.testTag(TestTags.PUZZLE_SUBMIT_BUTTON),
        )
    }
}

@Composable
private fun PuzzleResultStubScreen(
    puzzleIndex: Int,
    onNextClick: () -> Unit,
) {
    StubScaffold(title = stringResource(R.string.stub_puzzle_result_title, puzzleIndex + 1)) {
        Text(
            text = stringResource(R.string.stub_placeholder_caption),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val label = if (puzzleIndex < 2) {
            R.string.stub_puzzle_result_next
        } else {
            R.string.stub_puzzle_result_next_final
        }
        StubPrimaryButton(
            text = stringResource(label),
            onClick = onNextClick,
            modifier = Modifier.testTag(TestTags.PUZZLE_RESULT_NEXT_BUTTON),
        )
    }
}

/**
 * recap/{date} — один маршрут для сегодняшнего итога и итога из архива (UX_FLOW.md §1, §6;
 * COMPONENTS.md, таблица вариантов `AppTopBar` для `DayRecap`). Различие — только в
 * заголовке, наличии перехода «назад в Archive» и назначении основной кнопки; полноценный
 * `AppTopBar` с иконкой и правым слотом — компонент итерации 3, здесь только функциональные
 * различия двух вариантов одного маршрута.
 */
@Composable
private fun RecapStubScreen(
    date: String,
    onBackToArchiveClick: () -> Unit,
    onDoneClick: () -> Unit,
) {
    val isToday = date == Destinations.TODAY
    val title = if (isToday) stringResource(R.string.stub_recap_today_title) else date

    StubScaffold(title = title) {
        if (!isToday) {
            // Архивный вариант — leading "назад" (функциональный эквивалент AppTopBar).
            StubSecondaryButton(
                text = stringResource(R.string.cd_back),
                onClick = onBackToArchiveClick,
                modifier = Modifier.testTag(TestTags.RECAP_BACK_TO_ARCHIVE_BUTTON),
            )
        }
        Text(
            text = stringResource(R.string.stub_placeholder_caption),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StubPrimaryButton(
            text = stringResource(if (isToday) R.string.stub_recap_done else R.string.cd_back),
            onClick = if (isToday) onDoneClick else onBackToArchiveClick,
            modifier = Modifier.testTag(TestTags.RECAP_PRIMARY_BUTTON),
        )
    }
}

@Composable
private fun ArchiveStubScreen(
    onBackClick: () -> Unit,
    onOpenRecapClick: () -> Unit,
) {
    StubScaffold(
        title = stringResource(R.string.stub_archive_title),
        onBackClick = onBackClick,
    ) {
        Text(
            text = stringResource(R.string.stub_placeholder_caption),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StubSecondaryButton(
            text = stringResource(R.string.stub_archive_open_recap, SampleArchiveDate),
            onClick = onOpenRecapClick,
            modifier = Modifier.testTag(TestTags.ARCHIVE_OPEN_RECAP_ROW),
        )
    }
}

@Composable
private fun SettingsStubScreen(onBackClick: () -> Unit) {
    StubScaffold(
        title = stringResource(R.string.stub_settings_title),
        onBackClick = onBackClick,
    ) {
        Text(
            text = stringResource(R.string.stub_placeholder_caption),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- Preview: каркас приложения (Home) в трёх обязательных конфигурациях (итерация 1). ---
// Previews вызывают реальный экран, а не отдельную копию его разметки.

@Preview(name = "Home — light 390×844", widthDp = 390, heightDp = 844, fontScale = 1f)
@Composable
private fun HomeStubScreenLightPreview() {
    PoPoRyadkuTheme(darkTheme = false) {
        HomeStubScreen(onPlayClick = {}, onArchiveClick = {}, onSettingsClick = {})
    }
}

@Preview(
    name = "Home — dark 390×844",
    widthDp = 390,
    heightDp = 844,
    fontScale = 1f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun HomeStubScreenDarkPreview() {
    PoPoRyadkuTheme(darkTheme = true) {
        HomeStubScreen(onPlayClick = {}, onArchiveClick = {}, onSettingsClick = {})
    }
}

@Preview(name = "Home — light 320×844 @200%", widthDp = 320, heightDp = 844, fontScale = 2f)
@Composable
private fun HomeStubScreenCompactLargeFontPreview() {
    PoPoRyadkuTheme(darkTheme = false) {
        HomeStubScreen(onPlayClick = {}, onArchiveClick = {}, onSettingsClick = {})
    }
}
