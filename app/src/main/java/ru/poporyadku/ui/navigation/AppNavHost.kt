package ru.poporyadku.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
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
import ru.poporyadku.ui.components.rememberReportAvailability
import ru.poporyadku.ui.home.HomeEffect
import ru.poporyadku.ui.home.HomeScreen
import ru.poporyadku.ui.home.HomeViewModel
import ru.poporyadku.ui.platform.rememberExternalApps
import ru.poporyadku.ui.puzzle.PuzzleEffect
import ru.poporyadku.ui.puzzle.PuzzleScreen
import ru.poporyadku.ui.puzzle.PuzzleViewModel
import ru.poporyadku.ui.puzzleresult.PuzzleResultEffect
import ru.poporyadku.ui.puzzleresult.PuzzleResultScreen
import ru.poporyadku.ui.puzzleresult.PuzzleResultViewModel
import ru.poporyadku.ui.recap.DayRecapEffect
import ru.poporyadku.ui.recap.DayRecapScreen
import ru.poporyadku.ui.recap.DayRecapViewModel
import ru.poporyadku.ui.report.reportDraft
import ru.poporyadku.ui.settings.SettingsEffect
import ru.poporyadku.ui.settings.SettingsScreen
import ru.poporyadku.ui.settings.SettingsViewModel
import ru.poporyadku.ui.share.shareCardText
import ru.poporyadku.ui.sources.SourcesEffect
import ru.poporyadku.ui.sources.SourcesScreen
import ru.poporyadku.ui.sources.SourcesViewModel

/**
 * Граф приложения. Все экраны настоящие: `Home`, `Puzzle`, `PuzzleResult`, `DayRecap`,
 * `Archive` (PR 5B), `Settings` и её единственный подэкран `Sources` (PR 5C). Заглушек
 * итерации 1 не осталось.
 *
 * Внешние действия (письмо «Сообщить о неточности») выполняет коллектор эффектов
 * route-контейнера через `ExternalApps` — ни ViewModel, ни render-функция
 * (ITERATION_5_DESIGN.md, §6.11, §8.1).
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

        composable(Destinations.SETTINGS) { backStackEntry ->
            SettingsRoute(navController, backStackEntry)
        }

        composable(Destinations.SOURCES) { backStackEntry ->
            SourcesRoute(navController, backStackEntry)
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
    val externalApps = rememberExternalApps()
    // Ресурсы карточки читает контейнер, а не ViewModel; `resources` в ключах — чтобы
    // коллектор пересоздался при смене конфигурации, как у PuzzleRoute.
    val resources = LocalResources.current

    LaunchedEffect(viewModel, lifecycleOwner, externalApps, resources) {
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

                    // Карточка собирается здесь: ViewModel не читает Resources, экран
                    // ничего не запускает. Отказ запуска (Failed/Suppressed) экран не
                    // роняет и навигацию не меняет — пользователь остаётся на итоге.
                    is DayRecapEffect.Share -> {
                        externalApps.shareText(
                            text = resources.shareCardText(effect.input),
                            chooserTitle = resources.getString(R.string.share_chooser_title),
                        )
                    }
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
    val externalApps = rememberExternalApps()
    val resources = LocalResources.current
    // Почтовый клиент перепроверяется на каждом ON_START; без него действия нет в дереве.
    val isReportAvailable = rememberReportAvailability()

    LaunchedEffect(viewModel, lifecycleOwner, externalApps, resources) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is PuzzleResultEffect.NavigateBack -> {
                        if (!effect.isRedirect && !navController.isCurrent(entry)) return@collect
                        navController.popBackOrHome()
                    }

                    // Нажатие: только с текущей записи. Без клиента или при отказе
                    // запуска пользователь остаётся на этом экране.
                    is PuzzleResultEffect.ComposeReport -> {
                        if (!navController.isCurrent(entry)) return@collect
                        externalApps.composeEmail(resources.reportDraft(effect.context))
                    }

                    else -> navController.navigateFromPuzzleResult(effect, slotIndex, sessionDate)
                }
            }
        }
    }

    PuzzleResultScreen(
        state = state,
        onEvent = viewModel::onEvent,
        isReportAvailable = isReportAvailable,
    )
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

        // Архивный возврат и письмо выполняет route-контейнер до этой функции.
        is PuzzleResultEffect.NavigateBack -> popBackOrHome()
        is PuzzleResultEffect.ComposeReport -> Unit
    }
}

// --- Settings и Sources ------------------------------------------------------------------

/**
 * Route-контейнер настроек (ITERATION_5_DESIGN.md, §3.9, §4.5, §6.11): одна ViewModel,
 * ровно один lifecycle-aware коллектор эффектов. Все эффекты — нажатия, поэтому
 * выполняются только с текущей записи. Письмо открывается через `ExternalApps`; без
 * почтового клиента или при отказе запуска пользователь остаётся на экране.
 */
@Composable
private fun SettingsRoute(navController: NavHostController, entry: NavBackStackEntry) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val externalApps = rememberExternalApps()
    val resources = LocalResources.current
    val isReportAvailable = rememberReportAvailability()

    LaunchedEffect(viewModel, lifecycleOwner, externalApps, resources) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                if (!navController.isCurrent(entry)) return@collect
                when (effect) {
                    // Home лежит непосредственно ниже: возврат на существующий, не второй.
                    SettingsEffect.NavigateBack -> navController.popBackOrHome()

                    SettingsEffect.OpenSources -> navController.navigate(Destinations.SOURCES)

                    is SettingsEffect.ComposeReport ->
                        externalApps.composeEmail(resources.reportDraft(effect.context))
                }
            }
        }
    }

    SettingsScreen(
        state = state,
        isReportAvailable = isReportAvailable,
        onEvent = viewModel::onEvent,
    )
}

/** Route-контейнер источников: «Назад» — к настройкам, только с текущей записи. */
@Composable
private fun SourcesRoute(navController: NavHostController, entry: NavBackStackEntry) {
    val viewModel: SourcesViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                if (!navController.isCurrent(entry)) return@collect
                when (effect) {
                    SourcesEffect.NavigateBack -> navController.popBackOrHome()
                }
            }
        }
    }

    SourcesScreen(state = state, onEvent = viewModel::onEvent)
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

// --- Аргументы маршрутов -----------------------------------------------------

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
