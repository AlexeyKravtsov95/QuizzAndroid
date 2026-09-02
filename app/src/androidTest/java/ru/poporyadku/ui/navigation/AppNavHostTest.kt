package ru.poporyadku.ui.navigation

import androidx.activity.compose.setContent
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
import ru.poporyadku.debug.DebugGraphEntryPoint
import ru.poporyadku.core.model.PuzzleAttempt
import ru.poporyadku.core.model.SLOTS_PER_DAY
import ru.poporyadku.ui.home.HomeTestTags
import ru.poporyadku.ui.recap.DayRecapTestTags
import ru.poporyadku.ui.theme.PoPoRyadkuTheme

/**
 * Навигация — `UX_FLOW.md` §1 и ITERATION_3_DESIGN.md, `I3-N1`, `I3-N6`, `I3-N7`.
 *
 * После PR 3C `Home` и `DayRecap` — настоящие экраны с `hiltViewModel()`, поэтому граф
 * размещается внутри `MainActivity` (единственной `@AndroidEntryPoint`-активности
 * приложения): Hilt-граф берётся у настоящего `PoPoRyadkuApp`, отдельная тестовая
 * инфраструктура DI не заводится: доступ к синглтонам даёт `DebugGraphEntryPoint`
 * из `src/debug`.
 *
 * **Изоляция состояния.** База — постоянная, и её содержимое напрямую определяет, что
 * покажет Home и что откроет его CTA. Поэтому каждый тест начинается с полной очистки
 * (`@Before`) и сам готовит ровно ту фикстуру, которая ему нужна, через **продуктовые**
 * репозитории. Внешних предусловий и зависимости от порядка
 * выполнения тестов не остаётся: `@After` очищает базу снова, чтобы ни один тест не
 * оставил состояние следующему.
 *
 * Пользовательский поток управляется исключительно кликами по реальному UI
 * (`performClick`); возврат — системной (`Espresso.pressBackUnconditionally`) или
 * экранной кнопкой «Назад». Ни один `@Test` не вызывает `navController.navigate(...)`
 * напрямую — `startApp()` лишь размещает `TestNavHostController` внутри `AppNavHost`.
 *
 * `NavController.currentBackStack` не используется — это `@RestrictTo(LIBRARY_GROUP)`
 * API. Единственное чтение состояния, которое допускают тесты, — `currentBackStackEntry`
 * (текущий route и его аргументы). Очистка предыдущих экранов доказывается наблюдаемо:
 * одно нажатие «назад» сразу приводит к ожидаемому экрану.
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

    /** `I3-N1`. Стартовый экран — настоящий `Home`, а не заглушка итерации 1. */
    @Test
    fun startDestinationIsRealHome() {
        startApp()

        assertEquals(Destinations.HOME, currentRoute())
        composeTestRule.onNodeWithTag(HomeTestTags.SCREEN).assertExists()
        composeTestRule.onNodeWithTag(HomeTestTags.DAILY_ISSUE_PANEL).assertExists()
    }

    /**
     * `Home` → `puzzle/{slotIndex}?date=` → `puzzle/{slotIndex}/result?date=` →
     * `puzzle/{slotIndex + 1}?date=`: аргументы доезжают неизменными.
     *
     * Настоящих игровых экранов PR 3C не добавляет — переходы идут по заглушкам,
     * которые ничего не пишут и только переносят дату дальше.
     */
    @Test
    fun homeToPuzzle0ToResult0ToPuzzle1CarriesSlotIndexAndDate() {
        startApp()

        composeTestRule.onNodeWithTag(HomeTestTags.PRIMARY_BUTTON).performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.PUZZLE, currentRoute())

        val sessionDate = currentDate()
        assertNotNull("сессионная дата обязана доехать в маршрут", sessionDate)
        assertTrue(
            "дата маршрута — ISO yyyy-MM-dd, а не сентинел",
            ISO_DATE_REGEX.matches(sessionDate!!),
        )
        assertEquals(0, currentSlotIndex())

        composeTestRule.onNodeWithTag(PUZZLE_SUBMIT).performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.PUZZLE_RESULT, currentRoute())
        assertEquals(0, currentSlotIndex())
        assertEquals("дата переносится в результат без изменений", sessionDate, currentDate())

        composeTestRule.onNodeWithTag(PUZZLE_RESULT_NEXT).performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.PUZZLE, currentRoute())
        assertEquals(1, currentSlotIndex())
        assertEquals("дата переносится в следующий слот без изменений", sessionDate, currentDate())
    }

    /**
     * Puzzle следующего задания → Back → Home. Если бы `Puzzle(0)`/`PuzzleResult(0)`
     * оставались в стеке, одного системного «назад» не хватило бы.
     */
    @Test
    fun systemBackFromNextPuzzleLandsOnHome() {
        startApp()

        composeTestRule.onNodeWithTag(HomeTestTags.PRIMARY_BUTTON).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(PUZZLE_SUBMIT).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(PUZZLE_RESULT_NEXT).performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.PUZZLE, currentRoute())

        Espresso.pressBackUnconditionally()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.HOME, currentRoute())
    }

    /** `PuzzleResult` → Back → Home (UX_FLOW.md §5: вернуться в отвеченное задание нельзя). */
    @Test
    fun systemBackFromPuzzleResultLandsOnHome() {
        startApp()

        composeTestRule.onNodeWithTag(HomeTestTags.PRIMARY_BUTTON).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(PUZZLE_SUBMIT).performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.PUZZLE_RESULT, currentRoute())

        Espresso.pressBackUnconditionally()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.HOME, currentRoute())
    }

    /**
     * Полная цепочка заглушек до `recap/{ISO}`, затем `recap` → Back → Home. Одно
     * нажатие «назад» доказывает, что весь граф сессии вычищен из стека.
     *
     * Заглушки попыток не пишут, поэтому итог дня здесь ожидаемо пуст (`NotFound`) —
     * проверяются только маршрут, аргумент и бэкстек. Рабочий итог проверяет
     * [doneButtonOnCompletedDayRecapReturnsToExistingHome].
     */
    @Test
    fun fullStubChainReachesRecapByIsoDateAndSystemBackLandsOnHome() {
        startApp()

        composeTestRule.onNodeWithTag(HomeTestTags.PRIMARY_BUTTON).performClick()
        composeTestRule.waitForIdle()
        val sessionDate = currentDate()

        repeat(SLOTS_PER_DAY) { index ->
            composeTestRule.onNodeWithTag(PUZZLE_SUBMIT).performClick()
            composeTestRule.waitForIdle()
            assertEquals(Destinations.PUZZLE_RESULT, currentRoute())

            composeTestRule.onNodeWithTag(PUZZLE_RESULT_NEXT).performClick()
            composeTestRule.waitForIdle()

            if (index < SLOTS_PER_DAY - 1) {
                assertEquals(Destinations.PUZZLE, currentRoute())
                assertEquals(index + 1, currentSlotIndex())
            }
        }

        assertEquals(Destinations.RECAP, currentRoute())
        // Сентинела today больше нет: recap всегда получает явную ISO-дату (I3-D23).
        assertEquals("дата сессии доезжает до итога", sessionDate, currentDate())
        composeTestRule.onNodeWithTag(DayRecapTestTags.NOT_FOUND).assertExists()

        Espresso.pressBackUnconditionally()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.HOME, currentRoute())
    }

    /**
     * `I3-N6`. «Готово» на настоящем `recap` возвращает на существующий `Home`.
     *
     * День готовится завершённым, поэтому Home открывается в `Completed`, его CTA —
     * «Посмотреть итог», а `DayRecap` получает реальный `Content` с кнопкой «Готово».
     * Через цепочку заглушек этот сценарий недостижим: они не пишут ни попыток, ни
     * `day_results`, и итог был бы `NotFound`.
     */
    @Test
    fun doneButtonOnCompletedDayRecapReturnsToExistingHome() {
        val date = seedCompletedToday()
        startApp()

        assertEquals(Destinations.HOME, currentRoute())
        composeTestRule.onNodeWithTag(HomeTestTags.PRIMARY_BUTTON).performClick()
        composeTestRule.waitForIdle()

        assertEquals(Destinations.RECAP, currentRoute())
        assertEquals(Destinations.serialize(date), currentDate())
        composeTestRule.onNodeWithTag(DayRecapTestTags.SCORE_BADGE).assertExists()

        composeTestRule.onNodeWithTag(DayRecapTestTags.DONE_BUTTON).performClick()
        composeTestRule.waitForIdle()

        assertEquals(Destinations.HOME, currentRoute())
        // Системная навигация не создала второго Home: экран тот же самый.
        composeTestRule.onNodeWithTag(HomeTestTags.SCREEN).assertExists()
    }

    /**
     * `I3-N7`. Переход из архива передаёт ISO-дату. Утверждение про сентинел `TODAY`
     * удалено вместе с самим сентинелом (I3-D23).
     *
     * Иконка «Архив» видна только при `completedDayCount > 0` (COMPONENTS.md), поэтому
     * завершённый день готовится тестом, а не ожидается от устройства.
     */
    @Test
    fun homeToArchiveToRecapByIsoDate() {
        seedCompletedToday()
        startApp()

        composeTestRule.onNodeWithContentDescription(ARCHIVE).performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.ARCHIVE, currentRoute())

        composeTestRule.onNodeWithTag(ARCHIVE_OPEN_RECAP).performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.RECAP, currentRoute())

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
        composeTestRule.waitForIdle()
        assertEquals(Destinations.SETTINGS, currentRoute())

        composeTestRule.onNodeWithTag(GENERIC_BACK).performClick()
        composeTestRule.waitForIdle()
        assertEquals(Destinations.HOME, currentRoute())
    }

    private companion object {
        val ISO_DATE_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")

        const val ARCHIVE = "Архив"
        const val SETTINGS = "Настройки"

        // testTag заглушек итерации 1 — они переживут PR 3C без изменений.
        const val PUZZLE_SUBMIT = "puzzle_submit_button"
        const val PUZZLE_RESULT_NEXT = "puzzle_result_next_button"
        const val ARCHIVE_OPEN_RECAP = "archive_open_recap_row"
        const val GENERIC_BACK = "stub_generic_back_button"
    }
}
