package ru.poporyadku.ui.navigation

import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.EntryPointAccessors
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.poporyadku.MainActivity
import ru.poporyadku.core.model.PuzzleAttempt
import ru.poporyadku.core.model.SLOTS_PER_DAY
import ru.poporyadku.debug.DebugGraphEntryPoint
import ru.poporyadku.ui.home.HomeTestTags
import ru.poporyadku.ui.puzzle.PuzzleTestTags
import ru.poporyadku.ui.puzzleresult.PuzzleResultTestTags
import ru.poporyadku.ui.recap.DayRecapTestTags
import ru.poporyadku.ui.theme.PoPoRyadkuTheme

/**
 * Навигация — `UX_FLOW.md` §1 и ITERATION_3_DESIGN.md, `I3-N1`–`I3-N8`.
 *
 * После PR 3D все четыре экрана игрового дня настоящие, поэтому цепочка проходится
 * реальными кнопками: «Начать» на Home, «Проверить» на `Puzzle`, «Дальше»/«К итогу дня»
 * на `PuzzleResult`. Заглушек с собственными testTag в цепочке не осталось.
 *
 * Граф размещается внутри `MainActivity` (единственной `@AndroidEntryPoint`-активности
 * приложения): Hilt-граф берётся у настоящего `PoPoRyadkuApp`, отдельная тестовая
 * инфраструктура DI не заводится — доступ к синглтонам даёт `DebugGraphEntryPoint`
 * из `src/debug`.
 *
 * **Изоляция состояния.** База — постоянная, и её содержимое напрямую определяет, что
 * покажет Home и что откроет его CTA. Поэтому каждый тест начинается с полной очистки
 * (`@Before`) и сам готовит ровно ту фикстуру, которая ему нужна, через **продуктовые**
 * репозитории. `@After` очищает базу снова, чтобы ни один тест не оставил состояние
 * следующему.
 *
 * Пользовательский поток управляется кликами по реальному UI; возврат — системной
 * (`Espresso.pressBackUnconditionally`) или экранной кнопкой «Назад». Единственное
 * исключение — [malformedPuzzleRouteWithoutDateReturnsHomeAndWritesNothing]: маршрут без
 * query-параметра из UI не построить, а проверять надо именно матчинг графа.
 *
 * `NavController.currentBackStack` не используется — это `@RestrictTo(LIBRARY_GROUP)`
 * API. Единственное чтение состояния, которое допускают тесты, — `currentBackStackEntry`.
 * Очистка предыдущих экранов доказывается наблюдаемо: одно нажатие «назад» сразу
 * приводит к ожидаемому экрану.
 */
@RunWith(AndroidJUnit4::class)
class AppNavHostTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private lateinit var navController: TestNavHostController

    private val deps: DebugGraphEntryPoint by lazy {
        EntryPointAccessors.fromApplication(
            composeTestRule.activity.applicationContext,
            DebugGraphEntryPoint::class.java,
        )
    }

    @Before
    fun resetDatabase() = clearDatabase()

    @After
    fun cleanUp() = clearDatabase()

    /**
     * Пустая база — состояние первого запуска: `Home.FirstRun`, CTA «Начать»,
     * иконка «Архив» скрыта. Именно от него отталкиваются сценарии игровой цепочки.
     */
    private fun clearDatabase() = runBlocking {
        withContext(Dispatchers.IO) { deps.database().clearAllTables() }
    }

    /**
     * Готовит ЗАВЕРШЁННЫЙ сегодняшний день продуктовым путём: назначение создаёт
     * `startSession()`, три попытки пишет `recordAttempt()`, а `day_results`
     * пересчитывается той же транзакцией, что и в приложении.
     *
     * Пустой `submittedOrder` — это пропуск: итог дня получает три строки
     * `SlotOutcome.Unavailable` и «0 из 18». Для навигационных проверок важен сам факт
     * завершённого дня, а не его счёт.
     */
    private fun seedCompletedToday(): LocalDate = runBlocking {
        deps.content().ensureInstalled()
        val date = deps.assignments().startSession().localDate
        repeat(SLOTS_PER_DAY) { slot ->
            deps.progress().recordAttempt(
                PuzzleAttempt(
                    id = 0L,
                    localDate = date,
                    slotIndex = slot,
                    puzzleId = "nav-test-slot-$slot",
                    submittedOrder = emptyList(),
                    score = 0,
                    // Игнорируется: фактическую метку ставит репозиторий из ClockProvider.
                    submittedAt = 0L,
                ),
            )
        }
        date
    }

    /** Сколько попыток записано во всей базе — за все даты сразу. */
    private fun attemptCount(): Int = runBlocking {
        withContext(Dispatchers.IO) { deps.database().attemptDao().observeAll().first().size }
    }

    /**
     * Размещает граф с [TestNavHostController]. Вызывается ПОСЛЕ подготовки базы,
     * поэтому первая же эмиссия `HomeViewModel` видит нужную фикстуру.
     */
    private fun startApp() {
        composeTestRule.activity.runOnUiThread {
            navController = TestNavHostController(composeTestRule.activity).apply {
                navigatorProvider.addNavigator(ComposeNavigator())
            }
            composeTestRule.activity.setContent {
                PoPoRyadkuTheme {
                    AppNavHost(navController = navController)
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun currentRoute(): String? = navController.currentBackStackEntry?.destination?.route

    private fun currentSlotIndex(): Int? =
        navController.currentBackStackEntry?.arguments?.getInt(Destinations.ARG_SLOT_INDEX)

    private fun currentDate(): String? =
        navController.currentBackStackEntry?.arguments?.getString(Destinations.ARG_DATE)

    /** Экраны загружаются из базы, поэтому ждём фактический маршрут, а не один кадр. */
    private fun awaitRoute(route: String) {
        composeTestRule.waitUntil(ROUTE_TIMEOUT_MS) { currentRoute() == route }
        composeTestRule.waitForIdle()
    }

    /**
     * Home начинает с `Loading` и получает CTA только после чтения базы. Ждём именно
     * появления кнопки: при крупном системном шрифте первый кадр приходит заметно позже.
     */
    private fun awaitHomeCta() {
        composeTestRule.waitUntil(ROUTE_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasTestTag(HomeTestTags.PRIMARY_BUTTON))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.waitForIdle()
    }

    /** Проходит текущую головоломку «как есть»: порядок не меняется, счёт неважен. */
    private fun submitCurrentPuzzle() {
        composeTestRule.waitUntil(ROUTE_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasTestTag(PuzzleTestTags.CARD_LIST))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag(PuzzleTestTags.SUBMIT_BUTTON).performClick()
    }

    /** `I3-N1`. Стартовый экран — настоящий `Home`, а не заглушка итерации 1. */
    @Test
    fun startDestinationIsRealHome() {
        startApp()

        assertEquals(Destinations.HOME, currentRoute())
        composeTestRule.onNodeWithTag(HomeTestTags.SCREEN).assertExists()
        composeTestRule.onNodeWithTag(HomeTestTags.DAILY_ISSUE_PANEL).assertExists()
    }

    /**
     * `I3-N2`. `Home` → `puzzle/0?date=` → `puzzle/0/result?date=` → `puzzle/1?date=`:
     * `slotIndex` растёт, а сессионная дата доезжает неизменной.
     */
    @Test
    fun homeToPuzzle0ToResult0ToPuzzle1CarriesSlotIndexAndDate() {
        startApp()

        awaitHomeCta()
        composeTestRule.onNodeWithTag(HomeTestTags.PRIMARY_BUTTON).performClick()
        awaitRoute(Destinations.PUZZLE)

        val sessionDate = currentDate()
        assertNotNull("сессионная дата обязана доехать в маршрут", sessionDate)
        assertTrue(
            "дата маршрута — ISO yyyy-MM-dd, а не сентинел",
            ISO_DATE_REGEX.matches(sessionDate!!),
        )
        assertEquals(0, currentSlotIndex())

        submitCurrentPuzzle()
        awaitRoute(Destinations.PUZZLE_RESULT)
        assertEquals(0, currentSlotIndex())
        assertEquals("дата переносится в результат без изменений", sessionDate, currentDate())

        composeTestRule.onNodeWithTag(PuzzleResultTestTags.PRIMARY_BUTTON).performClick()
        awaitRoute(Destinations.PUZZLE)
        assertEquals(1, currentSlotIndex())
        assertEquals("дата переносится в следующий слот без изменений", sessionDate, currentDate())
    }

    /**
     * `I3-N3`. Системная «назад» из `Puzzle(1)` ведёт на `Home` за одно нажатие: если бы
     * `Puzzle(0)`/`PuzzleResult(0)` оставались в стеке, одного не хватило бы.
     */
    @Test
    fun systemBackFromNextPuzzleLandsOnHome() {
        startApp()

        awaitHomeCta()
        composeTestRule.onNodeWithTag(HomeTestTags.PRIMARY_BUTTON).performClick()
        awaitRoute(Destinations.PUZZLE)
        submitCurrentPuzzle()
        awaitRoute(Destinations.PUZZLE_RESULT)
        composeTestRule.onNodeWithTag(PuzzleResultTestTags.PRIMARY_BUTTON).performClick()
        awaitRoute(Destinations.PUZZLE)
        assertEquals(1, currentSlotIndex())

        Espresso.pressBackUnconditionally()
        awaitRoute(Destinations.HOME)
    }

    /** `I3-N4`. `PuzzleResult` → Back → Home: вернуться в отвеченное задание нельзя. */
    @Test
    fun systemBackFromPuzzleResultLandsOnHome() {
        startApp()

        awaitHomeCta()
        composeTestRule.onNodeWithTag(HomeTestTags.PRIMARY_BUTTON).performClick()
        awaitRoute(Destinations.PUZZLE)
        submitCurrentPuzzle()
        awaitRoute(Destinations.PUZZLE_RESULT)

        Espresso.pressBackUnconditionally()
        awaitRoute(Destinations.HOME)
    }

    /**
     * `I3-N5`. Полная цепочка трёх заданий доводит до `recap/{ISO}` с настоящим итогом,
     * и одно «назад» оттуда возвращает на `Home` — весь граф сессии вычищен.
     */
    @Test
    fun fullChainReachesRecapByIsoDateAndSystemBackLandsOnHome() {
        startApp()

        awaitHomeCta()
        composeTestRule.onNodeWithTag(HomeTestTags.PRIMARY_BUTTON).performClick()
        awaitRoute(Destinations.PUZZLE)
        val sessionDate = currentDate()

        repeat(SLOTS_PER_DAY) { index ->
            submitCurrentPuzzle()
            awaitRoute(Destinations.PUZZLE_RESULT)
            assertEquals(index, currentSlotIndex())

            composeTestRule.onNodeWithTag(PuzzleResultTestTags.PRIMARY_BUTTON).performClick()
            if (index < SLOTS_PER_DAY - 1) {
                awaitRoute(Destinations.PUZZLE)
                assertEquals(index + 1, currentSlotIndex())
            }
        }

        awaitRoute(Destinations.RECAP)
        // Сентинела today больше нет: recap всегда получает явную ISO-дату (I3-D23).
        assertEquals("дата сессии доезжает до итога", sessionDate, currentDate())
        composeTestRule.onNodeWithTag(DayRecapTestTags.SCORE_BADGE).assertExists()
        assertEquals("день закрыт ровно тремя попытками", SLOTS_PER_DAY, attemptCount())

        Espresso.pressBackUnconditionally()
        awaitRoute(Destinations.HOME)
    }

    /**
     * `I3-N8`. Маршрут без query-параметра `date` **сматчился** графом, но экран
     * немедленно вернулся на Home и не сыграл на текущей дате: в `puzzle_attempts` нет
     * ни одной строки (I3-D39).
     *
     * Единственный тест, который навигирует напрямую: из UI такой маршрут не построить,
     * а проверяется здесь именно свойство графа, а не разбор аргументов (его закрывают
     * `I3-V22`/`I3-V23`).
     */
    @Test
    fun malformedPuzzleRouteWithoutDateReturnsHomeAndWritesNothing() {
        startApp()

        composeTestRule.activity.runOnUiThread { navController.navigate("puzzle/0") }
        awaitRoute(Destinations.HOME)

        composeTestRule.onNodeWithTag(HomeTestTags.SCREEN).assertExists()
        assertEquals("маршрут без даты не имеет права записать попытку", 0, attemptCount())
    }

    /**
     * `I3-N6`. «Готово» на настоящем `recap` возвращает на существующий `Home`.
     *
     * День готовится завершённым, поэтому Home открывается в `Completed`, его CTA —
     * «Посмотреть итог», а `DayRecap` получает реальный `Content` с кнопкой «Готово».
     */
    @Test
    fun doneButtonOnCompletedDayRecapReturnsToExistingHome() {
        val date = seedCompletedToday()
        startApp()

        assertEquals(Destinations.HOME, currentRoute())
        awaitHomeCta()
        composeTestRule.onNodeWithTag(HomeTestTags.PRIMARY_BUTTON).performClick()
        awaitRoute(Destinations.RECAP)

        assertEquals(Destinations.serialize(date), currentDate())
        composeTestRule.onNodeWithTag(DayRecapTestTags.SCORE_BADGE).assertExists()

        composeTestRule.onNodeWithTag(DayRecapTestTags.DONE_BUTTON).performClick()
        awaitRoute(Destinations.HOME)

        // Системная навигация не создала второго Home: экран тот же самый.
        composeTestRule.onNodeWithTag(HomeTestTags.SCREEN).assertExists()
    }

    /**
     * `I3-N7`. Переход из архива передаёт ISO-дату. Утверждение про сентинел `TODAY`
     * удалено вместе с самим сентинелом (I3-D23).
     */
    @Test
    fun homeToArchiveToRecapByIsoDate() {
        seedCompletedToday()
        startApp()

        composeTestRule.onNodeWithContentDescription(ARCHIVE).performClick()
        awaitRoute(Destinations.ARCHIVE)

        composeTestRule.onNodeWithTag(ARCHIVE_OPEN_RECAP).performClick()
        awaitRoute(Destinations.RECAP)

        val date = currentDate()
        assertTrue(
            "archive recap date must be ISO yyyy-MM-dd",
            date != null && ISO_DATE_REGEX.matches(date),
        )
    }

    /** Иконка «Архив» скрыта, пока не завершён ни один день (COMPONENTS.md). */
    @Test
    fun archiveIconIsHiddenWhenNoDayIsCompleted() {
        startApp()

        composeTestRule.onNodeWithContentDescription(ARCHIVE).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(SETTINGS).assertExists()
    }

    @Test
    fun homeToSettingsToHome() {
        startApp()

        composeTestRule.onNodeWithContentDescription(SETTINGS).performClick()
        awaitRoute(Destinations.SETTINGS)

        composeTestRule.onNodeWithTag(GENERIC_BACK).performClick()
        awaitRoute(Destinations.HOME)
    }

    private companion object {
        val ISO_DATE_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")

        /** Экраны читают базу; секунды хватает с запасом и на медленном эмуляторе. */
        const val ROUTE_TIMEOUT_MS = 5_000L

        const val ARCHIVE = "Архив"
        const val SETTINGS = "Настройки"

        // testTag заглушек итерации 1 — только у Archive и Settings, которые ими и остались.
        const val ARCHIVE_OPEN_RECAP = "archive_open_recap_row"
        const val GENERIC_BACK = "stub_generic_back_button"
    }
}
