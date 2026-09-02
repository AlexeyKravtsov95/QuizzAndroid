package ru.poporyadku.ui.navigation

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import java.time.LocalDate
import ru.poporyadku.R
import ru.poporyadku.ui.home.HomeEffect
import ru.poporyadku.ui.home.HomeScreen
import ru.poporyadku.ui.home.HomeViewModel
import ru.poporyadku.ui.recap.DayRecapEffect
import ru.poporyadku.ui.recap.DayRecapScreen
import ru.poporyadku.ui.recap.DayRecapViewModel
import ru.poporyadku.ui.theme.Sizing
import ru.poporyadku.ui.theme.Spacing

/**
 * Граф приложения. После PR 3C `Home` и `DayRecap` — настоящие экраны; `Puzzle`,
 * `PuzzleResult`, `Archive` и `Settings` остаются заглушками итерации 1.
 *
 * ViewModel создаются **только здесь**, на route-уровне, через `hiltViewModel()`:
 * сами экраны stateless и в Compose-тестах работают без Hilt (I3-D31).
 *
 * Правила бэкстека (UX_FLOW.md §1) реализованы стандартным `popUpTo` Navigation
 * Compose, без BackHandler: при навигации на конкретный `puzzle/{i}` можно `popUpTo`
 * тем же конкретным маршрутом (с заполненными аргументами) — Navigation Compose
 * сопоставляет такой вызов с точным экземпляром бэкстека, а не с любым узлом того же
 * шаблона.
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
            HomeRoute(navController)
        }

        composable(
            route = Destinations.PUZZLE,
            arguments = puzzleArguments(),
        ) { backStackEntry ->
            val slotIndex = backStackEntry.arguments?.getInt(Destinations.ARG_SLOT_INDEX) ?: 0
            val date = backStackEntry.sessionDate()
            PuzzleStubScreen(
                slotIndex = slotIndex,
                onBackClick = { navController.popBackStack() },
                onSubmitClick = {
                    // Puzzle(i) -> PuzzleResult(i): заменяет Puzzle(i) в стеке; та же
                    // сессионная дата едет дальше без изменений.
                    navController.navigate(Destinations.puzzleResult(slotIndex, date)) {
                        popUpTo(Destinations.puzzle(slotIndex, date)) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Destinations.PUZZLE_RESULT,
            arguments = puzzleArguments(),
        ) { backStackEntry ->
            val slotIndex = backStackEntry.arguments?.getInt(Destinations.ARG_SLOT_INDEX) ?: 0
            val date = backStackEntry.sessionDate()
            PuzzleResultStubScreen(
                slotIndex = slotIndex,
                onNextClick = {
                    if (slotIndex < LAST_SLOT_INDEX) {
                        navController.navigate(Destinations.puzzle(slotIndex + 1, date)) {
                            popUpTo(Destinations.puzzleResult(slotIndex, date)) { inclusive = true }
                        }
                    } else {
                        // PuzzleResult(2) -> recap/{ISO}: вычищает граф сессии, сохраняя Home.
                        navController.navigate(Destinations.recap(date)) {
                            popUpTo(Destinations.HOME) { inclusive = false }
                        }
                    }
                },
            )
        }

        composable(
            route = Destinations.RECAP,
            arguments = listOf(navArgument(Destinations.ARG_DATE) { type = NavType.StringType }),
        ) {
            DayRecapRoute(navController)
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
 * Аргументы игровых маршрутов. `date` — структурно **не** обязательна
 * (`nullable = true`, без `defaultValue`): иначе маршрут без query-параметра не
 * сматчился бы вовсе, и обработать «даты нет» было бы негде (I3-D23). Семантическая
 * обязательность вводится в PR 3D.
 */
private fun puzzleArguments() = listOf(
    navArgument(Destinations.ARG_SLOT_INDEX) { type = NavType.IntType },
    navArgument(Destinations.ARG_DATE) {
        type = NavType.StringType
        nullable = true
    },
)

// --- Home ------------------------------------------------------------------

/**
 * Route-контейнер Home: единственное место, где создаётся `HomeViewModel`, где
 * состояние собирается lifecycle-aware и где живёт **ровно один** коллектор эффектов.
 */
@Composable
private fun HomeRoute(navController: NavHostController) {
    val viewModel: HomeViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val countdown by viewModel.countdown.collectAsStateWithLifecycle()

    // ON_START — не событие экрана, а вызов onScreenStarted() (I3-D14). За ПЕРВУЮ
    // эмиссию он не отвечает: она гарантирована конструкцией потока (I3-D38).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.onScreenStarted()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Ровно один коллектор эффектов, и он lifecycle-aware (I3-D25): ниже STARTED
    // сбор приостанавливается, поэтому навигационный эффект не может быть выполнен
    // на неактивном экране; Channel удержит его до возобновления.
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is HomeEffect.NavigateToPuzzle ->
                        navController.navigate(Destinations.puzzle(effect.slotIndex, effect.date))

                    is HomeEffect.NavigateToRecap ->
                        navController.navigate(Destinations.recap(effect.date))

                    HomeEffect.NavigateToArchive -> navController.navigate(Destinations.ARCHIVE)
                }
            }
        }
    }

    HomeScreen(
        state = state,
        countdown = countdown,
        onEvent = viewModel::onEvent,
        onArchiveClick = { navController.navigate(Destinations.ARCHIVE) },
        onSettingsClick = { navController.navigate(Destinations.SETTINGS) },
    )
}

// --- DayRecap --------------------------------------------------------------

@Composable
private fun DayRecapRoute(navController: NavHostController) {
    val viewModel: DayRecapViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    // «Готово» и системная «назад» дают один и тот же результат — Home,
                    // а не второй его экземпляр.
                    DayRecapEffect.NavigateHome ->
                        navController.popBackStack(Destinations.HOME, inclusive = false)
                }
            }
        }
    }

    DayRecapScreen(state = state, onEvent = viewModel::onEvent)
}

// --- Заглушки итерации 1 ---------------------------------------------------

/**
 * Итерация 1 не имеет архивных данных — эта дата иллюстративна и служит только для
 * проверки перехода `Archive -> recap/{date}`. Реальный список дней появится в
 * итерации 5 вместе с `ArchiveScreen`/`GetArchiveUseCase`.
 */
private val SampleArchiveDate: LocalDate = LocalDate.of(2026, 8, 25)

/** Последний слот дня; переход из него ведёт в итог дня, а не в следующий слот. */
private const val LAST_SLOT_INDEX = 2

/**
 * Сессионная дата заглушек. Структурно аргумент необязателен, поэтому у заглушки есть
 * запасной вариант — она ничего не пишет в базу и существует только чтобы переносить
 * дату дальше по цепочке. Семантическая обязательность и редирект на Home — PR 3D.
 */
private fun androidx.navigation.NavBackStackEntry.sessionDate(): LocalDate =
    arguments?.getString(Destinations.ARG_DATE)
        ?.let { runCatching { Destinations.parseDate(it) }.getOrNull() }
        ?: SampleArchiveDate

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

/** Стабильные testTag заглушек для `AppNavHostTest` — не производственное поведение. */
private object TestTags {
    const val GENERIC_BACK_BUTTON = "stub_generic_back_button"
    const val PUZZLE_SUBMIT_BUTTON = "puzzle_submit_button"
    const val PUZZLE_RESULT_NEXT_BUTTON = "puzzle_result_next_button"
    const val ARCHIVE_OPEN_RECAP_ROW = "archive_open_recap_row"
}

@Composable
private fun PuzzleStubScreen(
    slotIndex: Int,
    onBackClick: () -> Unit,
    onSubmitClick: () -> Unit,
) {
    StubScaffold(
        title = stringResource(R.string.stub_puzzle_title, slotIndex + 1),
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
    slotIndex: Int,
    onNextClick: () -> Unit,
) {
    StubScaffold(title = stringResource(R.string.stub_puzzle_result_title, slotIndex + 1)) {
        Text(
            text = stringResource(R.string.stub_placeholder_caption),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val label = if (slotIndex < LAST_SLOT_INDEX) {
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
            // Реальная ISO-дата, без сентинела (I3-D23).
            text = stringResource(
                R.string.stub_archive_open_recap,
                Destinations.serialize(SampleArchiveDate),
            ),
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
