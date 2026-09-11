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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import java.time.LocalDate
import ru.poporyadku.R
import ru.poporyadku.ui.archive.ArchiveEffect
import ru.poporyadku.ui.archive.ArchiveScreen
import ru.poporyadku.ui.archive.ArchiveViewModel
import ru.poporyadku.ui.home.HomeEffect
import ru.poporyadku.ui.home.HomeScreen
import ru.poporyadku.ui.home.HomeViewModel
import ru.poporyadku.ui.puzzle.PuzzleEffect
import ru.poporyadku.ui.puzzle.PuzzleScreen
import ru.poporyadku.ui.puzzle.PuzzleViewModel
import ru.poporyadku.ui.puzzleresult.PuzzleResultEffect
import ru.poporyadku.ui.puzzleresult.PuzzleResultScreen
import ru.poporyadku.ui.puzzleresult.PuzzleResultViewModel
import ru.poporyadku.ui.recap.DayRecapEffect
import ru.poporyadku.ui.recap.DayRecapScreen
import ru.poporyadku.ui.recap.DayRecapViewModel
import ru.poporyadku.ui.theme.Sizing
import ru.poporyadku.ui.theme.Spacing

/**
 * Граф приложения. Настоящие экраны — `Home`, `Puzzle`, `PuzzleResult`, `DayRecap` и
 * (с PR 5B) `Archive`; заглушкой итерации 1 остаётся только `Settings` (PR 5C).
 *
 * `DayRecap` и `PuzzleResult` существуют в двух вариантах по аргументу `origin`
 * (ITERATION_5_DESIGN.md, §3.7, §7): сессионный — игровая цепочка, архивный — бэкстек
 * `home → archive → recap/{D}?origin=archive → puzzle/{i}/result?date={D}&origin=archive`.
 * Ни один архивный путь не строит маршрут `puzzle/{slotIndex}?date=`.
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
            PuzzleRoute(
                navController = navController,
                slotIndex = backStackEntry.slotIndex(),
                sessionDate = backStackEntry.sessionDateOrNull(),
            )
        }

        composable(
            route = Destinations.PUZZLE_RESULT,
            arguments = puzzleArguments() + originArgument(),
        ) { backStackEntry ->
            PuzzleResultRoute(
                navController = navController,
                entry = backStackEntry,
                slotIndex = backStackEntry.slotIndex(),
                sessionDate = backStackEntry.sessionDateOrNull(),
            )
        }

        composable(
            route = Destinations.RECAP,
            arguments = listOf(
                navArgument(Destinations.ARG_DATE) { type = NavType.StringType },
                originArgument(),
            ),
        ) { backStackEntry ->
            DayRecapRoute(navController, backStackEntry)
        }

        composable(Destinations.ARCHIVE) { backStackEntry ->
            ArchiveRoute(navController, backStackEntry)
        }

        composable(Destinations.SETTINGS) {
            SettingsStubScreen(onBackClick = { navController.popBackStack() })
        }
    }
}

/**
 * Аргументы игровых маршрутов. `date` — структурно **не** обязательна
 * (`nullable = true`, без значения по умолчанию): иначе маршрут без query-параметра не
 * сматчился бы вовсе, и обработать «даты нет» было бы негде (I3-D23). Семантическая
 * обязательность живёт в `readPuzzleRoute()`: отсутствующая или неразбираемая дата даёт
 * `Error(InvalidRoute)` и немедленный возврат на Home, а не подстановку «сегодня».
 * Значение по умолчанию не добавляется ни при каких условиях — это была бы та же
 * подстановка под другим именем.
 */
private fun puzzleArguments() = listOf(
    navArgument(Destinations.ARG_SLOT_INDEX) { type = NavType.IntType },
    navArgument(Destinations.ARG_DATE) {
        type = NavType.StringType
        nullable = true
    },
)

/**
 * `origin` итога и результата (ITERATION_5_DESIGN.md, §7.1, I5-D8) — тем же приёмом,
 * что `date`: `nullable = true` без `defaultValue`. Отсутствие означает сессию, а
 * значение по умолчанию было бы тихой подстановкой; разбор и отказ неизвестного
 * значения — во ViewModel экрана.
 */
private fun originArgument() = navArgument(Destinations.ARG_ORIGIN) {
    type = NavType.StringType
    nullable = true
}

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

// --- Archive ---------------------------------------------------------------

/**
 * Route-контейнер архива (ITERATION_5_DESIGN.md, §6.11): одна ViewModel, ровно один
 * lifecycle-aware коллектор эффектов, `ON_START` — вызов `onScreenStarted()`, как у Home.
 */
@Composable
private fun ArchiveRoute(navController: NavHostController, entry: NavBackStackEntry) {
    val viewModel: ArchiveViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.onScreenStarted()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                // Все эффекты архива — нажатия: второе быстрое нажатие, успевшее попасть в
                // канал, после первого перехода находит другую запись и отбрасывается.
                if (!navController.isCurrent(entry)) return@collect
                when (effect) {
                    is ArchiveEffect.OpenDay ->
                        navController.navigate(Destinations.archivedRecap(effect.localDate))

                    ArchiveEffect.NavigateBack -> navController.popBackOrHome()

                    // «К заданию дня» из Empty — существующий Home, а не второй.
                    ArchiveEffect.NavigateHome -> navController.leaveToHome()
                }
            }
        }
    }

    ArchiveScreen(state = state, onEvent = viewModel::onEvent)
}

// --- DayRecap --------------------------------------------------------------

/**
 * Один экран, два варианта по `origin` (I5-D8). Сессионный выход — существующий Home
 * (`popBackStack(HOME, false)`), архивный — `popBackStack()` к архиву, лежащему
 * непосредственно ниже; системная «назад» даёт то же самое сама. Все эффекты итога —
 * нажатия, поэтому выполняются только с текущей записи.
 */
@Composable
private fun DayRecapRoute(navController: NavHostController, entry: NavBackStackEntry) {
    val viewModel: DayRecapViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                if (!navController.isCurrent(entry)) return@collect
                when (effect) {
                    // «Готово» и системная «назад» дают один и тот же результат — Home,
                    // а не второй его экземпляр.
                    DayRecapEffect.NavigateHome -> navController.leaveToHome()

                    DayRecapEffect.NavigateBack -> navController.popBackOrHome()

                    // Только архив и только Played: исторический результат, а не игра.
                    is DayRecapEffect.OpenResult -> navController.navigate(
                        Destinations.archivedPuzzleResult(effect.slotIndex, effect.localDate),
                    )
                }
            }
        }
    }

    DayRecapScreen(state = state, onEvent = viewModel::onEvent)
}

// --- Puzzle ----------------------------------------------------------------

/**
 * Route-контейнер игрового экрана: единственное место, где создаётся [PuzzleViewModel],
 * где состояние собирается lifecycle-aware и где живёт **ровно один** коллектор
 * эффектов. Второй коллектор увёл бы часть эффектов мимо экрана: `receiveAsFlow()`
 * раздаёт элемент одному из подписчиков, а какому именно — не определено (I3-D25).
 */
@Composable
private fun PuzzleRoute(
    navController: NavHostController,
    slotIndex: Int,
    sessionDate: LocalDate?,
) {
    val viewModel: PuzzleViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    // LocalResources, а не LocalContext.getString: чтение ресурсов обязано
    // инвалидироваться при смене конфигурации, иначе объявление TalkBack осталось бы на
    // старой локали. По той же причине `resources` входит в ключи LaunchedEffect —
    // коллектор пересоздаётся, а недоставленный эффект удержит Channel.
    val resources = LocalResources.current

    LaunchedEffect(viewModel, lifecycleOwner, resources) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    // Локализованную фразу собирает контейнер: ViewModel не держит ни
                    // Context, ни Resources и структурой эффекта их не подменяет.
                    is PuzzleEffect.AnnounceCardMoved -> view.announceForAccessibility(
                        resources.getString(
                            R.string.puzzle_card_moved,
                            effect.cardTitle,
                            effect.position,
                            effect.totalPositions,
                        ),
                    )

                    else -> navController.navigateFromPuzzle(effect, slotIndex, sessionDate)
                }
            }
        }
    }

    PuzzleScreen(state = state, onEvent = viewModel::onEvent)
}

/**
 * Правила бэкстека игрового экрана (UX_FLOW.md §1, раздел 14 ITERATION_3_DESIGN.md).
 *
 * `popUpTo` вызывается с **конкретным** маршрутом, с подставленными аргументами: так
 * Navigation Compose снимает именно тот экземпляр, а не любой узел того же шаблона.
 */
private fun NavHostController.navigateFromPuzzle(
    effect: PuzzleEffect,
    slotIndex: Int,
    sessionDate: LocalDate?,
) {
    // Даты нет только на невалидном маршруте — там единственный исход Home.
    val date = sessionDate ?: return leaveToHome()

    when (effect) {
        is PuzzleEffect.NavigateToResult ->
            navigate(Destinations.puzzleResult(effect.slotIndex, date)) {
                popUpTo(Destinations.puzzle(slotIndex, date)) { inclusive = true }
            }

        // Пропуск: следующий слот заменяет текущую головоломку, минуя результат.
        is PuzzleEffect.NavigateToNextSlot ->
            navigate(Destinations.puzzle(effect.slotIndex, date)) {
                popUpTo(Destinations.puzzle(slotIndex, date)) { inclusive = true }
            }

        PuzzleEffect.NavigateToRecap ->
            navigate(Destinations.recap(date)) {
                popUpTo(Destinations.HOME) { inclusive = false }
            }

        PuzzleEffect.NavigateHome -> leaveToHome()

        is PuzzleEffect.AnnounceCardMoved -> Unit
    }
}

// --- PuzzleResult ----------------------------------------------------------

/**
 * Тот же контракт, что у [PuzzleRoute]: одна ViewModel, один коллектор эффектов.
 *
 * Архивный режим отдаёт только `NavigateBack`: по нажатию он выполняется лишь с текущей
 * записи (второе быстрое нажатие отбрасывается), а редирект загрузки без кадра
 * (`Skipped`/`NoAttempt`) этой проверкой не ограничивается (ITERATION_5_DESIGN.md, §6.11).
 * Сессионные переходы сохраняют поведение итерации 3.
 */
@Composable
private fun PuzzleResultRoute(
    navController: NavHostController,
    entry: NavBackStackEntry,
    slotIndex: Int,
    sessionDate: LocalDate?,
) {
    val viewModel: PuzzleResultViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                if (effect is PuzzleResultEffect.NavigateBack) {
                    if (!effect.isRedirect && !navController.isCurrent(entry)) return@collect
                    navController.popBackOrHome()
                } else {
                    navController.navigateFromPuzzleResult(effect, slotIndex, sessionDate)
                }
            }
        }
    }

    PuzzleResultScreen(state = state, onEvent = viewModel::onEvent)
}

/** Сессионные переходы результата — правила бэкстека итерации 3 без изменений. */
private fun NavHostController.navigateFromPuzzleResult(
    effect: PuzzleResultEffect,
    slotIndex: Int,
    sessionDate: LocalDate?,
) {
    val date = sessionDate ?: return leaveToHome()

    when (effect) {
        is PuzzleResultEffect.NavigateToNextSlot ->
            navigate(Destinations.puzzle(effect.slotIndex, date)) {
                popUpTo(Destinations.puzzleResult(slotIndex, date)) { inclusive = true }
            }

        // Последний результат -> итог дня: вычищает граф сессии, сохраняя Home.
        PuzzleResultEffect.NavigateToRecap ->
            navigate(Destinations.recap(date)) {
                popUpTo(Destinations.HOME) { inclusive = false }
            }

        // Попытки нет: слот ещё не сыгран, возвращаемся в саму головоломку.
        is PuzzleResultEffect.NavigateToPuzzle ->
            navigate(Destinations.puzzle(effect.slotIndex, date)) {
                popUpTo(Destinations.puzzleResult(slotIndex, date)) { inclusive = true }
            }

        PuzzleResultEffect.NavigateHome -> leaveToHome()

        // Архивный возврат выполняет route-контейнер до этой функции.
        is PuzzleResultEffect.NavigateBack -> popBackOrHome()
    }
}

/** Возврат на существующий Home, а не создание его второго экземпляра. */
private fun NavHostController.leaveToHome() {
    popBackStack(Destinations.HOME, false)
}

/**
 * Возврат на экран ниже. Если снимать нечего (стек повреждён), — существующий Home: он
 * всегда в основании стека (ITERATION_5_DESIGN.md, §7.2).
 */
private fun NavHostController.popBackOrHome() {
    if (!popBackStack()) leaveToHome()
}

/**
 * Пользовательская навигация выполняется, только пока запись экрана — текущая (I5-D24):
 * эффект второго быстрого нажатия, успевший попасть в канал, после первого перехода
 * находит другую запись и отбрасывается.
 */
private fun NavHostController.isCurrent(entry: NavBackStackEntry): Boolean =
    currentBackStackEntry?.id == entry.id

// --- Аргументы маршрутов и заглушка итерации 1 -------------------------------

/**
 * Сессионная дата маршрута — **без** запасного варианта: подмены на «сегодня» или на
 * любую другую дату здесь нет ни на одном уровне (I3-D39).
 *
 * `null` возможен только на невалидном маршруте, а он даёт единственный эффект —
 * возврат на Home; строить из него следующий маршрут просто не из чего.
 */
private fun NavBackStackEntry.sessionDateOrNull(): LocalDate? =
    arguments?.getString(Destinations.ARG_DATE)
        ?.let { raw -> runCatching { Destinations.parseDate(raw) }.getOrNull() }

/** Слот маршрута; невалидное значение отсекает уже `readPuzzleRoute()` во ViewModel. */
private fun NavBackStackEntry.slotIndex(): Int =
    arguments?.getInt(Destinations.ARG_SLOT_INDEX) ?: 0

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
