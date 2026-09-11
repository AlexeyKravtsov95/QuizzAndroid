package ru.poporyadku.ui.navigation

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.EntryPointAccessors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.poporyadku.MainActivity
import ru.poporyadku.core.model.ContentPack
import ru.poporyadku.core.model.PuzzleAttempt
import ru.poporyadku.core.model.SLOTS_PER_DAY
import ru.poporyadku.core.model.puzzleIdAt
import ru.poporyadku.data.db.entity.DayAssignmentEntity
import ru.poporyadku.debug.DebugGraphEntryPoint
import ru.poporyadku.ui.archive.ArchiveTestTags
import ru.poporyadku.ui.home.HomeTestTags
import ru.poporyadku.ui.puzzle.PuzzleTestTags
import ru.poporyadku.ui.puzzleresult.PuzzleResultTestTags
import ru.poporyadku.ui.recap.DayRecapTestTags
import ru.poporyadku.ui.theme.PoPoRyadkuTheme

/**
 * Навигация — `UX_FLOW.md` §1 и ITERATION_3_DESIGN.md, `I3-N1`–`I3-N8`;
 * ITERATION_5_DESIGN.md, §7, §10.6: архив, архивный итог и исторический результат —
 * `I5-N1`–`I5-N3`, `I5-N5`–`I5-N7`.
 *
 * Все экраны цепочек настоящие, поэтому они проходятся реальными кнопками: «Начать» на
 * Home, «Проверить» на `Puzzle`, «Дальше»/«К итогу дня» на `PuzzleResult`, строки архива
 * и итога.
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
 * (`Espresso.pressBackUnconditionally`) или экранной кнопкой «Назад». Исключения —
 * маршруты, которые из UI не построить: [malformedPuzzleRouteWithoutDateReturnsHomeAndWritesNothing]
 * и [archivedRecapOfAMissingDayOffersBackToArchive].
 *
 * `NavController.currentBackStack` не используется — это `@RestrictTo(LIBRARY_GROUP)`
 * API. Состояние читается через `currentBackStackEntry` и `previousBackStackEntry`.
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
     *
     * `puzzleId` каждой попытки — головоломка своего слота в выданном наборе, как пишет
     * её `SubmitAnswerUseCase`: сыгранный идентификатор, чужой своему слоту, настоящий
     * импортёр справедливо объявил бы конфликтом (ITERATION_4_DESIGN.md, I4-D4, C).
     */
    private fun seedCompletedToday(): LocalDate = runBlocking {
        deps.content().ensureInstalled()
        val date = deps.assignments().startSession().localDate
        val assignment = requireNotNull(deps.assignments().getAssignment(date))
        val set = requireNotNull(deps.sets().getSet(assignment.packId, assignment.setIndex))
        repeat(SLOTS_PER_DAY) { slot ->
            deps.progress().recordAttempt(
                PuzzleAttempt(
                    id = 0L,
                    localDate = date,
                    slotIndex = slot,
                    puzzleId = set.puzzleIdAt(slot),
                    submittedOrder = emptyList(),
                    score = 0,
                    // Игнорируется: фактическую метку ставит репозиторий из ClockProvider.
                    submittedAt = 0L,
                ),
            )
        }
        date
    }

    /**
     * Готовит ЗАВЕРШЁННЫЙ вчерашний день с тремя ОТВЕЧЕННЫМИ заданиями — строками `Played`
     * архивного итога. Сегодня назначения нет, поэтому Home — `Ready` со следующим
     * набором, а иконка «Архив» видна (`completedDayCount > 0`).
     */
    private fun seedCompletedYesterday(): LocalDate = seedPastDay(daysAgo = 1, setIndex = 0, answered = SLOTS_PER_DAY)

    /**
     * Прошлый день [daysAgo] дней назад с набором [setIndex] и [answered] отвеченными
     * слотами подряд: попытки пишет `recordAttempt()`, `day_results` пересчитывается той
     * же транзакцией.
     *
     * Назначение на прошлую дату пишется DAO напрямую: продуктовый путь создаёт назначения
     * только на сегодня, а архиву нужен именно прошлый день. `puzzleId` каждой попытки —
     * головоломка своего слота, как пишет её `SubmitAnswerUseCase`, поэтому импортёр такую
     * историю принимает без конфликта (ITERATION_4_DESIGN.md, I4-D4).
     */
    private fun seedPastDay(daysAgo: Long, setIndex: Int, answered: Int): LocalDate = runBlocking {
        deps.content().ensureInstalled()
        val date = deps.assignments().peek().localDate.minusDays(daysAgo)
        withContext(Dispatchers.IO) {
            deps.database().assignmentDao().insert(
                DayAssignmentEntity(date.toString(), ContentPack.CORE_RU, setIndex, assignedAt = 0L),
            )
        }
        val set = requireNotNull(deps.sets().getSet(ContentPack.CORE_RU, setIndex))
        repeat(answered) { slot -> recordAnswer(date, slot, set.puzzleIdAt(slot)) }
        date
    }

    /** Правильный ответ — порядок импортированной головоломки, 6 из 6. */
    private suspend fun recordAnswer(date: LocalDate, slot: Int, puzzleId: String) {
        val puzzle = requireNotNull(deps.puzzles().getPuzzle(puzzleId))
        deps.progress().recordAttempt(
            PuzzleAttempt(
                id = 0L,
                localDate = date,
                slotIndex = slot,
                puzzleId = puzzleId,
                submittedOrder = puzzle.correctOrder,
                score = MAX_SLOT_SCORE,
                // Игнорируется: фактическую метку ставит репозиторий из ClockProvider.
                submittedAt = 0L,
            ),
        )
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

    private fun currentOrigin(): String? =
        navController.currentBackStackEntry?.arguments?.getString(Destinations.ARG_ORIGIN)

    /** Экраны загружаются из базы, поэтому ждём фактический маршрут, а не один кадр. */
    private fun awaitRoute(route: String) {
        composeTestRule.waitUntil(ROUTE_TIMEOUT_MS) { currentRoute() == route }
        composeTestRule.waitForIdle()
    }

    /**
     * Home начинает с `Loading` и получает CTA только после чтения базы. Ждём именно
     * появления кнопки: при крупном системном шрифте первый кадр приходит заметно позже,
     * а на чистой базе первый расчёт включает полный импорт пакета.
     */
    private fun awaitHomeCta() {
        composeTestRule.waitUntil(HOME_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasTestTag(HomeTestTags.PRIMARY_BUTTON))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.waitForIdle()
    }

    /** Узел появляется после чтения базы: ждём его, а не один кадр. */
    private fun awaitTag(tag: String, timeoutMs: Long = ROUTE_TIMEOUT_MS) {
        composeTestRule.waitUntil(timeoutMs) {
            composeTestRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
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

    /**
     * Home → иконка «Архив» → строка дня [date] → архивный итог этого дня. Иконка
     * появляется только после расчёта Home, строка — после чтения окна архива.
     */
    private fun openArchivedRecap(date: LocalDate) {
        awaitHomeCta()
        composeTestRule.onNodeWithContentDescription(ARCHIVE).performClick()
        awaitTag(ArchiveTestTags.row(date))
        composeTestRule.onNodeWithTag(ArchiveTestTags.row(date)).performClick()
        awaitTag(DayRecapTestTags.RESULTS)
    }

    /** Строки результата итога, у которых есть действие нажатия, — только `Played` архива. */
    private fun openableRows() =
        composeTestRule.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(DayRecapTestTags.RESULTS)))

    private fun titleOf(date: LocalDate): String = date.format(TITLE_FORMAT)

    /** `I3-N1`. Стартовый экран — настоящий `Home`, а не заглушка итерации 1. */
    @Test
    fun startDestinationIsRealHome() {
        startApp()
        awaitHomeCta()

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
        assertNull("сессионный результат — без origin", currentOrigin())

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
        assertNull("сессионный итог — без origin", currentOrigin())
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
     * Навигирует напрямую: из UI такой маршрут не построить, а проверяется здесь именно
     * свойство графа, а не разбор аргументов (его закрывают `I3-V22`/`I3-V23`).
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
     * `I3-N6`, `I5-N5`. «Готово» на настоящем сессионном `recap` возвращает на
     * существующий `Home` — регресс итерации 3 после появления `origin`.
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
        assertNull("сессионный итог — без origin", currentOrigin())
        awaitTag(DayRecapTestTags.PRIMARY_BUTTON)
        composeTestRule.onNodeWithTag(DayRecapTestTags.SCORE_BADGE).assertExists()
        composeTestRule.onNodeWithContentDescription(BACK).assertDoesNotExist()

        composeTestRule.onNodeWithTag(DayRecapTestTags.PRIMARY_BUTTON).assertTextEquals(DONE).performClick()
        awaitRoute(Destinations.HOME)

        // Системная навигация не создала второго Home: экран тот же самый, и под ним пусто.
        composeTestRule.onNodeWithTag(HomeTestTags.SCREEN).assertExists()
        assertNull("в стеке ровно один home", navController.previousBackStackEntry)
    }

    /**
     * `I3-N7`, переписан под PR 5B. Настоящая строка архива открывает итог по ISO-дате и в
     * АРХИВНОМ варианте — даже для сегодняшнего дня: вариант выбирает `origin`, а не дата
     * (I5-D8). Фикстура продуктовая: день завершён через `startSession()`/`recordAttempt()`.
     */
    @Test
    fun homeToArchiveToRecapByIsoDate() {
        val today = seedCompletedToday()
        startApp()

        openArchivedRecap(today)
        awaitRoute(Destinations.RECAP)

        val date = currentDate()
        assertTrue(
            "archive recap date must be ISO yyyy-MM-dd",
            date != null && ISO_DATE_REGEX.matches(date),
        )
        assertEquals(Destinations.serialize(today), date)
        assertEquals(Destinations.ORIGIN_ARCHIVE, currentOrigin())
        composeTestRule.onNodeWithText(titleOf(today)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(BACK).assertIsDisplayed()
        composeTestRule.onNodeWithTag(DayRecapTestTags.PRIMARY_BUTTON).assertTextEquals(BACK)
    }

    /**
     * `I5-N1`. Home → Archive → архивный итог → исторический результат → «Назад» → итог →
     * «Назад» → Archive → «Назад» → Home; в стеке ровно один `home`, игровой маршрут не
     * строился ни разу и новых попыток нет.
     */
    @Test
    fun archiveChainGoesBackStepByStepToTheExistingHome() {
        val yesterday = seedCompletedYesterday()
        val attemptsBefore = attemptCount()
        startApp()

        openArchivedRecap(yesterday)
        assertEquals(Destinations.RECAP, currentRoute())
        assertEquals(Destinations.serialize(yesterday), currentDate())
        assertEquals(Destinations.ORIGIN_ARCHIVE, currentOrigin())
        assertEquals("все три строки Played открываются", SLOTS_PER_DAY, openableRows().fetchSemanticsNodes().size)

        openableRows().onFirst().performClick()
        awaitRoute(Destinations.PUZZLE_RESULT)
        assertEquals(0, currentSlotIndex())
        assertEquals(Destinations.serialize(yesterday), currentDate())
        assertEquals(Destinations.ORIGIN_ARCHIVE, currentOrigin())
        awaitTag(PuzzleResultTestTags.PRIMARY_BUTTON)
        composeTestRule.onNodeWithTag(PuzzleResultTestTags.PRIMARY_BUTTON).assertTextEquals(TO_RECAP)

        composeTestRule.onNodeWithContentDescription(BACK).performClick()
        awaitRoute(Destinations.RECAP)
        assertEquals(Destinations.ORIGIN_ARCHIVE, currentOrigin())

        composeTestRule.onNodeWithContentDescription(BACK).performClick()
        awaitRoute(Destinations.ARCHIVE)

        composeTestRule.onNodeWithContentDescription(BACK).performClick()
        awaitRoute(Destinations.HOME)
        assertNull("в стеке ровно один home", navController.previousBackStackEntry)
        assertEquals("архив ничего не записывает", attemptsBefore, attemptCount())
    }

    /** `I5-N1`, CTA и системная «назад»: результат → итог → Archive → Home тем же путём. */
    @Test
    fun archiveChainWithTheResultCtaAndSystemBack() {
        val yesterday = seedCompletedYesterday()
        startApp()

        openArchivedRecap(yesterday)
        openableRows().onFirst().performClick()
        awaitRoute(Destinations.PUZZLE_RESULT)
        awaitTag(PuzzleResultTestTags.PRIMARY_BUTTON)

        composeTestRule.onNodeWithTag(PuzzleResultTestTags.PRIMARY_BUTTON).performClick()
        awaitRoute(Destinations.RECAP)
        assertEquals(Destinations.ORIGIN_ARCHIVE, currentOrigin())

        Espresso.pressBackUnconditionally()
        awaitRoute(Destinations.ARCHIVE)
        Espresso.pressBackUnconditionally()
        awaitRoute(Destinations.HOME)
        assertNull("в стеке ровно один home", navController.previousBackStackEntry)
    }

    /** `I5-N2`. Нижняя «Назад» архивного итога возвращает в Archive. */
    @Test
    fun bottomBackOfArchivedRecapReturnsToArchive() {
        val yesterday = seedCompletedYesterday()
        startApp()

        openArchivedRecap(yesterday)
        composeTestRule.onNodeWithTag(DayRecapTestTags.PRIMARY_BUTTON).assertTextEquals(BACK).performClick()

        awaitRoute(Destinations.ARCHIVE)
        awaitTag(ArchiveTestTags.row(yesterday))
    }

    /**
     * `I5-N3`. `recap/{date}?origin=archive` для даты без данных — «Данные за этот день не
     * сохранились», и «Назад» в шапке возвращает в Archive. Маршрут на отсутствующий день
     * из UI не построить, поэтому он открывается напрямую — как `I3-N8`.
     */
    @Test
    fun archivedRecapOfAMissingDayOffersBackToArchive() {
        seedCompletedYesterday()
        startApp()
        awaitHomeCta()
        composeTestRule.onNodeWithContentDescription(ARCHIVE).performClick()
        awaitRoute(Destinations.ARCHIVE)

        composeTestRule.activity.runOnUiThread {
            navController.navigate(Destinations.archivedRecap(LocalDate.of(2001, 1, 1)))
        }
        awaitRoute(Destinations.RECAP)
        awaitTag(DayRecapTestTags.NOT_FOUND)
        composeTestRule.onNodeWithText(RECAP_MISSING).assertIsDisplayed()
        composeTestRule.onNodeWithText(RETRY).assertDoesNotExist()

        composeTestRule.onNodeWithContentDescription(BACK).performClick()
        awaitRoute(Destinations.ARCHIVE)
    }

    /**
     * `I5-N6`. Архивный итог незавершённого прошлого дня: строки «не сыграно» не
     * нажимаются — маршрут прежний, попыток в базе столько же, игра прошлого дня не
     * запускается. Позавчера завершён (иконка «Архив» на Home), вчера — одно задание из трёх.
     */
    @Test
    fun notPlayedRowOfAnIncompleteArchivedDayDoesNothing() {
        seedPastDay(daysAgo = 2, setIndex = 0, answered = SLOTS_PER_DAY)
        val yesterday = seedPastDay(daysAgo = 1, setIndex = 1, answered = 1)
        val attemptsBefore = attemptCount()
        startApp()

        openArchivedRecap(yesterday)
        awaitRoute(Destinations.RECAP)
        composeTestRule.onNodeWithTag(DayRecapTestTags.INCOMPLETE).assertExists()
        assertEquals("открывается только Played", 1, openableRows().fetchSemanticsNodes().size)

        composeTestRule.onAllNodes(hasText(NOT_PLAYED)).onFirst().performClick()
        composeTestRule.waitForIdle()

        assertEquals(Destinations.RECAP, currentRoute())
        assertEquals(Destinations.ORIGIN_ARCHIVE, currentOrigin())
        assertEquals("попыток столько же — игра прошлого дня не запускалась", attemptsBefore, attemptCount())
    }

    /**
     * `I5-N7`. `ActivityScenario.recreate()` на историческом результате и на архивном итоге:
     * после пересоздания те же слот, дата и архивный вариант.
     *
     * Здесь используется собственный граф `MainActivity` — `rememberNavController()`, а не
     * [TestNavHostController]: пересоздание обязано восстановить именно продуктовый
     * `NavController` из сохранённого состояния. Поэтому маршрут проверяется по экрану:
     * заголовок «Результат задания 1» — слот 0; CTA «К итогу дня» у слота 0 и возврат
     * к итогу, а не на Home, — архивный режим; дата — заголовок архивного итога.
     */
    @Test
    fun recreateKeepsTheArchivedRecapAndTheHistoricalResult() {
        val yesterday = seedCompletedYesterday()
        // Home продуктового графа пересчитывается по записи в day_results сам.
        awaitHomeCta()
        composeTestRule.onNodeWithContentDescription(ARCHIVE).performClick()
        awaitTag(ArchiveTestTags.row(yesterday), HOME_TIMEOUT_MS)
        composeTestRule.onNodeWithTag(ArchiveTestTags.row(yesterday)).performClick()
        awaitTag(DayRecapTestTags.RESULTS)
        openableRows().onFirst().performClick()
        awaitTag(PuzzleResultTestTags.PRIMARY_BUTTON)

        composeTestRule.activityRule.scenario.recreate()
        awaitTag(PuzzleResultTestTags.PRIMARY_BUTTON)

        composeTestRule.onNodeWithText(RESULT_TITLE_SLOT_1).assertIsDisplayed()
        composeTestRule.onNodeWithTag(PuzzleResultTestTags.PRIMARY_BUTTON).assertTextEquals(TO_RECAP)

        Espresso.pressBackUnconditionally()
        awaitTag(DayRecapTestTags.RESULTS)
        composeTestRule.onNodeWithText(titleOf(yesterday)).assertIsDisplayed()

        composeTestRule.activityRule.scenario.recreate()
        awaitTag(DayRecapTestTags.RESULTS)

        composeTestRule.onNodeWithText(titleOf(yesterday)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(BACK).assertIsDisplayed()
        composeTestRule.onNodeWithTag(DayRecapTestTags.PRIMARY_BUTTON).assertTextEquals(BACK)
        assertEquals(SLOTS_PER_DAY, openableRows().fetchSemanticsNodes().size)

        composeTestRule.onNodeWithTag(DayRecapTestTags.PRIMARY_BUTTON).performClick()
        awaitTag(ArchiveTestTags.row(yesterday))
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

        /** Первый расчёт Home на чистой базе включает полный импорт пакета. */
        const val HOME_TIMEOUT_MS = 20_000L

        const val ARCHIVE = "Архив"
        const val SETTINGS = "Настройки"
        const val BACK = "Назад"
        const val DONE = "Готово"
        const val TO_RECAP = "К итогу дня"
        const val RETRY = "Повторить"
        const val NOT_PLAYED = "не сыграно"
        const val RECAP_MISSING = "Данные за этот день не сохранились"
        const val RESULT_TITLE_SLOT_1 = "Результат задания 1"
        const val MAX_SLOT_SCORE = 6

        /** Заголовок архивного итога — `d MMMM yyyy` на русском, как у строки архива. */
        val TITLE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"))

        // testTag заглушки итерации 1 — только у Settings, которая ею и осталась (PR 5C).
        const val GENERIC_BACK = "stub_generic_back_button"
    }
}
