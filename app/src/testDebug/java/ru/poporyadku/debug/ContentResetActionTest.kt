package ru.poporyadku.debug

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.poporyadku.core.model.ContentPack
import ru.poporyadku.core.model.PuzzleAttempt
import ru.poporyadku.core.model.SLOTS_PER_DAY
import ru.poporyadku.core.model.isPlayable
import ru.poporyadku.core.model.puzzleIdAt
import ru.poporyadku.core.time.FakeClockProvider
import ru.poporyadku.data.content.AssetContentSource
import ru.poporyadku.data.content.ContentImporter
import ru.poporyadku.data.content.ContentPackReader
import ru.poporyadku.data.content.FakeUserPreferencesRepository
import ru.poporyadku.data.content.snapshot
import ru.poporyadku.data.content.validation.ContentValidator
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.data.db.entity.DailySetEntity
import ru.poporyadku.data.db.entity.DayAssignmentEntity
import ru.poporyadku.data.progress.ProgressRepositoryImpl
import ru.poporyadku.data.repository.DailySetRepositoryImpl
import ru.poporyadku.data.repository.PuzzleRepositoryImpl
import ru.poporyadku.di.ContentModule
import ru.poporyadku.domain.content.ContentInstallException
import ru.poporyadku.domain.model.TodayFailureKind

/**
 * Debug-восстановление после hard cutover — ITERATION_4_DESIGN.md, **I4-D20**, §11.3;
 * debug-часть `I4-V2` и JVM-половина `I4-E3` (§17).
 *
 * `ContentResetAction` и `ContentReset` живут в `src/debug`, поэтому и тест — в `testDebug`.
 * Импортёр — настоящий, поверх НАСТОЯЩИХ ассетов приложения (`AssetContentSource` под
 * Robolectric видит `src/main/assets`), база — in-memory Room.
 *
 * Фикстура временного контента итерации 3 воспроизводится ЗДЕСЬ, в тестовой подготовке:
 * продуктовой реализации временного источника больше не существует и не
 * восстанавливается (**I4-D3**). Состояние — ровно то, что оставляла сборка итерации 3:
 * три строки `daily_sets` с ротацией трёх `tmp-*`, назначение и три попытки со своим
 * `day_results`. Строк `puzzles` нет: временный источник их никогда не писал.
 */
@RunWith(RobolectricTestRunner::class)
class ContentResetActionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val packId = ContentPack.CORE_RU
    private val legacyDate = LocalDate.of(2026, 9, 2)

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun action() = ContentResetAction(ContentReset(db))

    private fun importer(prefs: FakeUserPreferencesRepository) = ContentImporter(
        db = db,
        puzzleDao = db.puzzleDao(),
        setDao = db.dailySetDao(),
        assignmentDao = db.assignmentDao(),
        reader = ContentPackReader(
            AssetContentSource(context),
            ContentModule.assetJson(),
            verifyIntegrity = true,
        ),
        validator = ContentValidator(),
        prefs = prefs,
        storageJson = ContentModule.storageJson(),
        activePackId = packId,
    )

    // ---------- I4-V2: применимость и тексты ----------

    /**
     * `I4-V2`. Сброс предлагается ТОЛЬКО при конфликте установки. При непригодном пакете
     * прогресс ни при чём и стирать его нельзя; при обычной ошибке сброс не лечит ничего.
     */
    @Test
    fun `I4-V2 reset is applicable to a content conflict only`() {
        val action = action()

        assertTrue(action.isApplicableTo(TodayFailureKind.ContentConflict))
        assertFalse(action.isApplicableTo(TodayFailureKind.ContentUnusable))
        assertFalse(action.isApplicableTo(TodayFailureKind.Generic))
    }

    /** Стабильный идентификатор и честные тексты: подтверждение называет потерю прогресса. */
    @Test
    fun `stable id and a confirmation that names the loss of all progress`() {
        val action = action()

        assertEquals("content_reset", ContentResetAction.ACTION_ID)
        assertEquals(ContentResetAction.ACTION_ID, action.id)
        assertEquals("Сбросить контент", context.getString(action.labelRes))
        val confirmation = context.getString(action.confirmationRes)
        assertTrue(
            "подтверждение обязано прямо говорить о потере всего прогресса: «$confirmation»",
            confirmation.contains("весь локальный прогресс"),
        )
    }

    /**
     * Вклад восстановления компилируется в debug-вариант. Зеркало release-проверки
     * `ReleaseRecoveryContributionsTest`: без него та проверяла бы имена, которых нет
     * и в debug, — то есть ничего.
     */
    @Test
    fun `the recovery contribution is compiled into the debug variant`() {
        for (name in DEBUG_RECOVERY_CLASSES) {
            assertNotNull(name, Class.forName(name))
        }
    }

    // ---------- ContentReset ----------

    /** Очищается весь Room целиком — все пять таблиц, включая головоломки. */
    @Test
    fun `perform clears all five tables`() = runBlocking {
        importer(FakeUserPreferencesRepository()).ensureInstalled()
        val firstDay = requireNotNull(db.dailySetDao().getSet(packId, 0))
        seedPlayedDay(listOf(firstDay.puzzleId1, firstDay.puzzleId2, firstDay.puzzleId3))
        assertEquals(
            "до сброса заполнены все пять таблиц",
            emptyMap<String, List<String>>(),
            db.snapshot().filterValues { it.isEmpty() },
        )

        action().perform()

        assertEquals(
            "после сброса база пуста целиком",
            emptyMap<String, List<String>>(),
            db.snapshot().filterValues { it.isNotEmpty() },
        )
    }

    // ---------- I4-E3 (JVM): конфликт → подтверждённый сброс → настоящий импорт ----------

    /**
     * `I4-E3`, JVM-половина. База, оставленная временной фикстурой, → настоящий импортёр
     * поднимает `Conflict` и не меняет ни одной таблицы и отметки → подтверждённый сброс
     * → следующий `ensureInstalled()` импортирует настоящий пакет, и первый день читается
     * через продуктовые репозитории.
     *
     * Сквозная проверка того же пути через UI — `HardCutoverTest` (androidTest).
     */
    @Test
    fun `I4-E3 legacy fixture conflicts, confirmed reset lets the real pack install`() =
        runBlocking {
            seedLegacyFixture()
            val prefs = FakeUserPreferencesRepository() // сборка итерации 3 отметку не писала
            val importer = importer(prefs)
            val before = db.snapshot()

            val conflict = assertThrows(ContentInstallException.Conflict::class.java) {
                runBlocking { importer.ensureInstalled() }
            }

            assertEquals("состав набора 0 разошёлся с пакетом", listOf(0), conflict.changedSetIndexes)
            assertEquals(emptyList<Int>(), conflict.staleSetIndexes)
            assertEquals(listOf(legacyDate), conflict.blockedDates)
            assertEquals("ни одна из пяти таблиц не изменена", before, db.snapshot())
            assertEquals("DataStore не тронут", 0, prefs.writes)

            action().perform()
            importer.ensureInstalled()

            assertEquals(1, prefs.writes)
            val sets = DailySetRepositoryImpl(db.dailySetDao())
            val puzzles = PuzzleRepositoryImpl(db.puzzleDao(), ContentModule.storageJson())
            val firstDay = requireNotNull(sets.getSet(packId, 0)) { "первый набор не установлен" }
            for (slot in 0 until SLOTS_PER_DAY) {
                val puzzleId = firstDay.puzzleIdAt(slot)
                assertFalse("в первом дне осталась временная '$puzzleId'", puzzleId.startsWith("tmp-"))
                val puzzle = requireNotNull(puzzles.getPuzzle(puzzleId)) { "'$puzzleId' не установлена" }
                assertTrue("'$puzzleId' не играбельна", puzzle.isPlayable())
            }
            assertEquals("история временной фикстуры стёрта", 0, db.attemptDao().observeAll().first().size)
        }

    // ---------- фикстуры ----------

    /** Ровно то, что оставляла на устройстве сборка итерации 3 после одного сыгранного дня. */
    private suspend fun seedLegacyFixture() {
        db.dailySetDao().upsertAll(
            LEGACY_ROTATION.mapIndexed { setIndex, ids ->
                DailySetEntity(packId, setIndex, ids[0], ids[1], ids[2])
            }
        )
        seedPlayedDay(LEGACY_ROTATION[0])
    }

    /** Назначение набора 0 на [legacyDate] и три попытки продуктовым путём (с `day_results`). */
    private suspend fun seedPlayedDay(puzzleIds: List<String>) {
        db.assignmentDao().insert(
            DayAssignmentEntity(legacyDate.toString(), packId, setIndex = 0, assignedAt = 0L)
        )
        val clock = FakeClockProvider(
            Clock.fixed(legacyDate.atTime(LocalTime.NOON).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
        )
        val progress = ProgressRepositoryImpl(db, db.attemptDao(), db.dayResultDao(), clock)
        puzzleIds.forEachIndexed { slot, puzzleId ->
            progress.recordAttempt(
                PuzzleAttempt(
                    id = 0,
                    localDate = legacyDate,
                    slotIndex = slot,
                    puzzleId = puzzleId,
                    submittedOrder = listOf("c1", "c2", "c3", "c4"),
                    score = 3,
                    submittedAt = 0L,
                )
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

        val DEBUG_RECOVERY_CLASSES = listOf(
            "ru.poporyadku.di.DebugHomeRecoveryModule",
            "ru.poporyadku.debug.ContentResetAction",
            "ru.poporyadku.debug.ContentReset",
        )
    }
}
