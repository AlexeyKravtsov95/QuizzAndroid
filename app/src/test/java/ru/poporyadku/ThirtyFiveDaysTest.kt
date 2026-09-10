package ru.poporyadku

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.poporyadku.core.model.ContentPack
import ru.poporyadku.core.model.SLOTS_PER_DAY
import ru.poporyadku.core.time.FakeClockProvider
import ru.poporyadku.data.content.AssetContentSource
import ru.poporyadku.data.content.ContentImporter
import ru.poporyadku.data.content.ContentPackReader
import ru.poporyadku.data.content.FakeUserPreferencesRepository
import ru.poporyadku.data.content.historySnapshot
import ru.poporyadku.data.content.validation.ContentValidator
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.data.progress.ProgressRepositoryImpl
import ru.poporyadku.data.repository.DailySetRepositoryImpl
import ru.poporyadku.data.repository.DayAssignmentRepositoryImpl
import ru.poporyadku.data.repository.PuzzleRepositoryImpl
import ru.poporyadku.di.ContentModule
import ru.poporyadku.domain.model.TodayState
import ru.poporyadku.domain.repository.UserPreferencesRepository
import ru.poporyadku.domain.usecase.AttemptKind
import ru.poporyadku.domain.usecase.GetPuzzleResult
import ru.poporyadku.domain.usecase.GetPuzzleUseCase
import ru.poporyadku.domain.usecase.GetStreaksUseCase
import ru.poporyadku.domain.usecase.GetTodayStateUseCase
import ru.poporyadku.domain.usecase.SessionStart
import ru.poporyadku.domain.usecase.StartDailySessionUseCase
import ru.poporyadku.domain.usecase.Submission
import ru.poporyadku.domain.usecase.SubmitAnswerUseCase
import ru.poporyadku.domain.usecase.SubmitResult

/**
 * `I4-C7` (ITERATION_4_DESIGN.md, §17; **I4-D29**): 35 последовательных локальных дат
 * проходятся через продуктовые use cases на НАСТОЯЩЕМ пакете, а 36-я честно даёт
 * `ContentExhausted`.
 *
 * Всё настоящее, кроме часов: импортёр над ассетами приложения, Room в памяти, те же
 * репозитории и use cases, что в продуктовом графе. Каждый день начинается
 * `StartDailySessionUseCase`, каждая головоломка открывается `GetPuzzleUseCase` и
 * закрывается `SubmitAnswerUseCase` — попыток в обход use cases тест не пишет. Каждый
 * расчёт Home снова проходит `ensureInstalled()`, поэтому заодно проверено, что
 * сыгранные настоящие головоломки не срывают быстрый путь импортёра.
 *
 * Литерал `35` здесь не нужен: число дней — `setCount` манифеста ассетов, а сам
 * критерий 35/105 закрепляет `I4-C4`.
 */
@RunWith(RobolectricTestRunner::class)
class ThirtyFiveDaysTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val packId = ContentPack.CORE_RU
    private val zone = ZoneOffset.UTC
    private val firstDay = LocalDate.of(2026, 10, 1)

    private lateinit var db: AppDatabase
    private lateinit var clock: FakeClockProvider
    private lateinit var getTodayState: GetTodayStateUseCase
    private lateinit var startDailySession: StartDailySessionUseCase
    private lateinit var getPuzzle: GetPuzzleUseCase
    private lateinit var submitAnswer: SubmitAnswerUseCase
    private var setCount: Int = 0

    /** Отметка контента — как у импортёра; кэш серии просто принимается. */
    private class DaysPreferences(
        delegate: FakeUserPreferencesRepository = FakeUserPreferencesRepository(),
    ) : UserPreferencesRepository by delegate {
        override suspend fun updateStreakCache(current: Int, best: Int, date: LocalDate) = Unit
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        clock = FakeClockProvider(Clock.fixed(firstDay.atTime(LocalTime.NOON).toInstant(zone), zone))

        val prefs = DaysPreferences()
        val reader = ContentPackReader(AssetContentSource(context), ContentModule.assetJson(), verifyIntegrity = true)
        val content = ContentImporter(
            db = db,
            puzzleDao = db.puzzleDao(),
            setDao = db.dailySetDao(),
            assignmentDao = db.assignmentDao(),
            reader = reader,
            validator = ContentValidator(),
            prefs = prefs,
            storageJson = ContentModule.storageJson(),
            activePackId = packId,
        )
        val assignments = DayAssignmentRepositoryImpl(db, db.assignmentDao(), db.dailySetDao(), clock, packId)
        val progress = ProgressRepositoryImpl(db, db.attemptDao(), db.dayResultDao(), clock)
        val sets = DailySetRepositoryImpl(db.dailySetDao())
        val puzzles = PuzzleRepositoryImpl(db.puzzleDao(), ContentModule.storageJson())

        getTodayState = GetTodayStateUseCase(content, assignments, progress, GetStreaksUseCase(progress, prefs))
        startDailySession = StartDailySessionUseCase(content, assignments, sets, progress)
        getPuzzle = GetPuzzleUseCase(content, assignments, sets, puzzles, progress)
        submitAnswer = SubmitAnswerUseCase(assignments, sets, puzzles, progress)
        setCount = runBlocking { reader.readHeader(packId).manifest.setCount }
    }

    @After
    fun tearDown() = db.close()

    private fun today(): TodayState = runBlocking { getTodayState(emptyFlow()).first() }

    @Test
    fun `I4-C7 every set of the pack is played on consecutive days, then ContentExhausted`() =
        runBlocking {
            var previousHistory = db.historySnapshot()

            for (setIndex in 0 until setCount) {
                val date = firstDay.plusDays(setIndex.toLong())
                clock.setDate(date, zone)

                // До старта: день 1 — FirstRun, остальные — Ready со следующим номером дня.
                val before = today()
                if (setIndex == 0) {
                    assertEquals(TodayState.FirstRun(date, dayNumber = 1), before)
                } else {
                    assertTrue("день ${setIndex + 1}: ожидался Ready, получен $before", before is TodayState.Ready)
                    assertEquals(setIndex + 1, (before as TodayState.Ready).dayNumber)
                }

                assertEquals(
                    "день ${setIndex + 1} получает следующий набор",
                    SessionStart.Started(date, packId, setIndex = setIndex, slotIndex = 0),
                    startDailySession(),
                )

                for (slot in 0 until SLOTS_PER_DAY) {
                    val opened = getPuzzle(date, slot)
                    assertTrue("день ${setIndex + 1}, слот $slot: $opened", opened is GetPuzzleResult.Playable)
                    opened as GetPuzzleResult.Playable
                    assertEquals(setIndex, opened.setIndex)

                    assertEquals(
                        SubmitResult.Recorded(slot, score = 6, kind = AttemptKind.Answered),
                        submitAnswer(date, slot, Submission.Answer(opened.puzzle.correctOrder)),
                    )
                }

                val after = today()
                assertTrue("день ${setIndex + 1}: ожидался Completed, получен $after", after is TodayState.Completed)
                after as TodayState.Completed
                assertEquals(setIndex + 1, after.dayNumber)
                assertEquals(18, after.totalScore)

                // Прошлые дни не перезаписаны: каждая строка истории на месте и неизменна.
                val history = db.historySnapshot()
                for ((table, rows) in previousHistory) {
                    assertTrue(
                        "день ${setIndex + 1} изменил прошлые строки $table",
                        history.getValue(table).containsAll(rows),
                    )
                }
                previousHistory = history
            }

            // Последний сыгранный день получил последний набор пакета.
            val lastDate = firstDay.plusDays(setCount - 1L)
            assertEquals(setCount - 1, db.assignmentDao().byDate(lastDate.toString())!!.setIndex)

            // 36-й день: не ошибка, а исчерпание; ни одной записи в историю.
            val exhaustedDate = firstDay.plusDays(setCount.toLong())
            clock.setDate(exhaustedDate, zone)
            val historyBefore = db.historySnapshot()

            val exhausted = today()
            assertTrue("36-й день: ожидался ContentExhausted, получен $exhausted", exhausted is TodayState.ContentExhausted)
            assertEquals(setCount, (exhausted as TodayState.ContentExhausted).stats.completedDayCount)
            assertEquals(SessionStart.ContentExhausted, startDailySession())
            assertEquals("36-й день ничего не записал", historyBefore, db.historySnapshot())

            // Каждый из дней закрыт своим набором и итогом 18 из 18.
            val assignments = db.assignmentDao().observeAll().first().sortedBy { it.localDate }
            assertEquals((0 until setCount).toList(), assignments.map { it.setIndex })
            assertEquals(
                (0 until setCount).map { firstDay.plusDays(it.toLong()).toString() },
                assignments.map { it.localDate },
            )
            val results = db.dayResultDao().getAll()
            assertEquals(setCount, results.size)
            assertTrue(results.all { it.isComplete && it.totalScore == 18 })
            assertEquals(setCount * SLOTS_PER_DAY, db.attemptDao().observeAll().first().size)
        }
}
