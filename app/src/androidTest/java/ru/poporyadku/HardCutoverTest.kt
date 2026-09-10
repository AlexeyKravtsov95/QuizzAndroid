package ru.poporyadku

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.poporyadku.core.model.ContentPack
import ru.poporyadku.core.model.PuzzleAttempt
import ru.poporyadku.core.model.SLOTS_PER_DAY
import ru.poporyadku.data.db.entity.DailySetEntity
import ru.poporyadku.data.db.entity.DayAssignmentEntity
import ru.poporyadku.debug.ContentResetAction
import ru.poporyadku.ui.home.HomeTestTags

/**
 * Предрелизный hard cutover — ITERATION_4_DESIGN.md, `I4-E3` (**I4-D3**, **I4-D20**,
 * ARCHITECTURE.md ADR-016).
 *
 * Состояние временной фикстуры итерации 3 готовится ЗДЕСЬ, в тестовой подготовке, —
 * продуктовой реализации временного источника больше нет и она не восстанавливается.
 * Воспроизводится ровно то, что оставляла сборка итерации 3 после одного сыгранного
 * дня: три строки `daily_sets` с ротацией трёх временных головоломок, назначение набора 0
 * и три попытки со своим `day_results`. Строк `puzzles` временный источник не писал.
 *
 * Дальше — только продуктовый путь и UI: запуск → `Home.Error(ContentConflict)` без
 * единой записи → «Сбросить контент» → подтверждение → полный сброс → приложение само
 * импортирует настоящий пакет → первый настоящий день проходится до «18 из 18».
 */
@RunWith(AndroidJUnit4::class)
class HardCutoverTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private val driver by lazy { DayFlowDriver(composeTestRule) }

    @Before
    fun resetDatabase() = driver.clearDatabase()

    @After
    fun cleanUp() = driver.clearDatabase()

    @Test
    fun i4E3LegacyFixtureConflictsAndConfirmedResetInstallsThePlayableRealPack() {
        seedLegacyFixture(LocalDate.now().minusDays(1))
        val before = tables()

        driver.startApp()

        // Home.Error(ContentConflict): прежний текст, «Повторить» и debug-действие сброса.
        driver.awaitTag(HomeTestTags.recoveryAction(ContentResetAction.ACTION_ID))
        composeTestRule.onNodeWithText(LOAD_FAILED).assertExists()
        composeTestRule.onNodeWithTag(HomeTestTags.RETRY_BUTTON).assertExists()
        assertEquals("конфликт не изменил ни одной таблицы", before, tables())

        // Сброс — только через явное подтверждение, и оно называет потерю прогресса.
        composeTestRule.onNodeWithTag(HomeTestTags.recoveryAction(ContentResetAction.ACTION_ID))
            .performClick()
        composeTestRule.onNodeWithTag(HomeTestTags.RECOVERY_DIALOG).assertExists()
        composeTestRule.onNodeWithText(RESET_CONFIRMATION).assertExists()
        composeTestRule.onNodeWithTag(HomeTestTags.RECOVERY_DIALOG_CONFIRM).performClick()

        // После сброса приложение само переустанавливает пакет: Home.FirstRun.
        driver.awaitHomeCta()
        composeTestRule.onNodeWithText(HOME_CTA_START).assertExists()
        assertEquals("история временной фикстуры стёрта", 0, driver.attemptCount())
        assertNotNull(
            "настоящий пакет установлен целиком",
            runBlocking { driver.deps.sets().getSet(ContentPack.CORE_RU, RELEASE_SET_COUNT - 1) },
        )

        // Первый настоящий день играбелен до конца.
        assertEquals(0, driver.playTodayThroughTheUi())
        assertEquals(SLOTS_PER_DAY, driver.attemptCount())
    }

    /** Ровно то, что оставляла на устройстве сборка итерации 3 после одного сыгранного дня. */
    private fun seedLegacyFixture(date: LocalDate) = runBlocking {
        val db = driver.deps.database()
        withContext(Dispatchers.IO) {
            db.dailySetDao().upsertAll(
                LEGACY_ROTATION.mapIndexed { setIndex, ids ->
                    DailySetEntity(ContentPack.CORE_RU, setIndex, ids[0], ids[1], ids[2])
                }
            )
            db.assignmentDao().insert(
                DayAssignmentEntity(date.toString(), ContentPack.CORE_RU, setIndex = 0, assignedAt = 0L)
            )
        }
        LEGACY_ROTATION[0].forEachIndexed { slot, puzzleId ->
            driver.deps.progress().recordAttempt(
                PuzzleAttempt(
                    id = 0L,
                    localDate = date,
                    slotIndex = slot,
                    puzzleId = puzzleId,
                    submittedOrder = listOf("c1", "c2", "c3", "c4"),
                    score = 3,
                    // Игнорируется: фактическую метку ставит репозиторий из ClockProvider.
                    submittedAt = 0L,
                )
            )
        }
    }

    /** Содержимое всех пяти таблиц: «ничего не изменено» — утверждение обо всей базе. */
    private fun tables(): List<Any> = runBlocking {
        val db = driver.deps.database()
        withContext(Dispatchers.IO) {
            listOf(
                db.puzzleDao().countByPack(ContentPack.CORE_RU),
                db.dailySetDao().observeAll().first(),
                db.assignmentDao().observeAll().first(),
                db.attemptDao().observeAll().first(),
                db.dayResultDao().observeAll().first(),
            )
        }
    }

    private companion object {
        /** Ротация временных наборов итерации 3: гео/ист/наука, сдвиг на слот в день. */
        val LEGACY_ROTATION = listOf(
            listOf("tmp-geo-vysota-001", "tmp-hist-izobreteniya-002", "tmp-sci-otkrytiya-003"),
            listOf("tmp-hist-izobreteniya-002", "tmp-sci-otkrytiya-003", "tmp-geo-vysota-001"),
            listOf("tmp-sci-otkrytiya-003", "tmp-geo-vysota-001", "tmp-hist-izobreteniya-002"),
        )

        const val RELEASE_SET_COUNT = 35

        const val LOAD_FAILED = "Не удалось загрузить задания"
        const val RESET_CONFIRMATION =
            "Удалит весь локальный прогресс и весь загруженный контент. Действие необратимо."
        const val HOME_CTA_START = "Начать"
    }
}
