package ru.poporyadku.domain.usecase

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.poporyadku.core.model.ContentPack
import ru.poporyadku.core.model.DayResult
import ru.poporyadku.core.model.PuzzleAttempt
import ru.poporyadku.core.model.ThemeMode
import ru.poporyadku.core.model.UserPreferences
import ru.poporyadku.core.model.TestContent
import ru.poporyadku.core.time.FakeClockProvider
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.data.db.mapper.toEntity
import ru.poporyadku.data.progress.ProgressRepositoryImpl
import ru.poporyadku.data.repository.DayAssignmentRepositoryImpl
import ru.poporyadku.domain.content.ContentInstallException
import ru.poporyadku.domain.content.ContentInstaller
import ru.poporyadku.domain.model.TodayFailureKind
import ru.poporyadku.domain.model.TodayState
import ru.poporyadku.domain.repository.ProgressRepository
import ru.poporyadku.domain.repository.UserPreferencesRepository

/**
 * ITERATION_3_DESIGN.md, §19: `I3-U3`–`I3-U9`, `I3-U23`, `I3-U43`.
 *
 * Репозитории настоящие (Room в памяти) — состояние выводится из решения политики, а не
 * из подставленного `Decision`. Подменены только два источника: установщик контента
 * (нужен управляемый отказ для `I3-U43`) и `observeDayResults()`, чтобы пересчёт
 * запускался сигналом теста, а не фоновым потоком инвалидации Room.
 *
 * Собирается через `runBlocking`: поток ждёт реальных ответов Room, и виртуальное время
 * `runTest` проматывалось бы мимо них. Каждый следующий сигнал посылается только после
 * получения предыдущего состояния — порядок задан причинно, а не расчётом на планировщик.
 */
@RunWith(RobolectricTestRunner::class)
class GetTodayStateUseCaseTest {

    private lateinit var db: AppDatabase
    private lateinit var clock: FakeClockProvider
    private lateinit var progress: ProgressRepositoryImpl
    private lateinit var observable: ObservableProgress
    private lateinit var assignments: DayAssignmentRepositoryImpl
    private lateinit var installer: SwitchableInstaller
    private lateinit var preferences: RecordingPreferences
    private lateinit var useCase: GetTodayStateUseCase

    private val day1 = LocalDate.of(2026, 9, 1)
    private val zone = ZoneOffset.UTC

    /** Управляемый отказ установщика: `I3-U43` требует и конфликта, и обычного исключения. */
    private class SwitchableInstaller : ContentInstaller {
        var failure: Exception? = null
        var calls = 0
        override suspend fun ensureInstalled() {
            calls++
            failure?.let { throw it }
        }
    }

    /** Пересчёт запускается сигналом теста; остальные чтения — настоящие. */
    private class ObservableProgress(
        delegate: ProgressRepository,
    ) : ProgressRepository by delegate {
        val dayResults = MutableStateFlow<List<DayResult>>(emptyList())
        override fun observeDayResults(): Flow<List<DayResult>> = dayResults
    }

    /** Из всего контракта настроек здесь нужен только кэш серии. */
    private class RecordingPreferences : UserPreferencesRepository {
        var current: Int? = null
        var best: Int? = null
        var date: LocalDate? = null

        override val preferences: Flow<UserPreferences> get() = emptyFlow()
        override suspend fun setSoundEnabled(enabled: Boolean) = unsupported()
        override suspend fun setVibrationEnabled(enabled: Boolean) = unsupported()
        override suspend fun setReminderEnabled(enabled: Boolean) = unsupported()
        override suspend fun setReminderTime(time: LocalTime) = unsupported()
        override suspend fun setThemeMode(mode: ThemeMode) = unsupported()
        override suspend fun setInstalledContent(contentVersion: Int, fingerprint: String) = unsupported()
        override suspend fun setHasSeenDragHint(seen: Boolean) = unsupported()
        override suspend fun setHasSeenScoringHint(seen: Boolean) = unsupported()
        override suspend fun setHasCompletedFirstDay(completed: Boolean) = unsupported()
        override suspend fun setNotificationPromptShown(shown: Boolean) = unsupported()
        override suspend fun setLastSeenDate(date: LocalDate?) = unsupported()

        override suspend fun updateStreakCache(current: Int, best: Int, date: LocalDate) {
            this.current = current
            this.best = best
            this.date = date
        }

        private fun unsupported(): Nothing = throw UnsupportedOperationException("не нужен в этом тесте")
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        clock = FakeClockProvider(Clock.fixed(day1.atTime(LocalTime.NOON).atZone(zone).toInstant(), zone))
        // Три настоящих набора: политике нужен setCountInActivePack, а не установщик.
        runBlocking { db.dailySetDao().upsertAll(TestContent.sets.map { it.toEntity() }) }

        progress = ProgressRepositoryImpl(db, db.attemptDao(), db.dayResultDao(), clock)
        observable = ObservableProgress(progress)
        assignments = DayAssignmentRepositoryImpl(
            db, db.assignmentDao(), db.dailySetDao(), clock, ContentPack.CORE_RU,
        )
        installer = SwitchableInstaller()
        preferences = RecordingPreferences()
        useCase = GetTodayStateUseCase(
            content = installer,
            assignments = assignments,
            progress = observable,
            streaks = GetStreaksUseCase(observable, preferences),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun record(date: LocalDate, slotIndex: Int, score: Int) {
        progress.recordAttempt(
            PuzzleAttempt(
                id = 0,
                localDate = date,
                slotIndex = slotIndex,
                puzzleId = "p-$slotIndex",
                submittedOrder = listOf("c1", "c2", "c3", "c4"),
                score = score,
                submittedAt = 0L,
            )
        )
    }

    private suspend fun playFullDay(date: LocalDate, scores: List<Int> = listOf(6, 5, 4)) {
        clock.setDate(date, zone)
        assignments.startSession()
        scores.forEachIndexed { slot, score -> record(date, slot, score) }
    }

    /** Первая эмиссия — свойство самого потока: событий экрана здесь нет вовсе (I3-D38). */
    private fun firstState(): TodayState = runBlocking { useCase(emptyFlow()).first() }

    @Test
    fun `I3-U3 - an empty database yields FirstRun on day one`() {
        val state = firstState()

        assertTrue("ожидался FirstRun, получен $state", state is TodayState.FirstRun)
        state as TodayState.FirstRun
        assertEquals(day1, state.today)
        assertEquals(1, state.dayNumber)
    }

    @Test
    fun `I3-U4 - Ready when history exists and today has no assignment yet`() = runBlocking {
        playFullDay(LocalDate.of(2026, 8, 31))
        clock.setDate(day1, zone)

        val state = firstState()

        assertTrue("ожидался Ready, получен $state", state is TodayState.Ready)
        state as TodayState.Ready
        assertEquals(day1, state.today)
        assertEquals(2, state.dayNumber)
        assertEquals(1, state.stats.playedDayCount)
        assertEquals(1, state.stats.completedDayCount)
        assertEquals(15, state.stats.bestDayScore)
    }

    @Test
    fun `I3-U5 - an assignment with zero attempts is InProgress, never Ready`() = runBlocking {
        assignments.startSession()

        val state = firstState()

        assertTrue("ноль попыток при готовом назначении дал $state", state is TodayState.InProgress)
        state as TodayState.InProgress
        assertEquals(0, state.completedCount)
        assertEquals(day1, state.sessionDate)
        assertEquals(1, state.dayNumber)
    }

    @Test
    fun `I3-U6 - completedCount follows the recorded attempts`() = runBlocking {
        assignments.startSession()

        record(day1, 0, 6)
        val afterOne = firstState()
        assertEquals(1, (afterOne as TodayState.InProgress).completedCount)

        record(day1, 1, 5)
        val afterTwo = firstState()
        assertEquals(2, (afterTwo as TodayState.InProgress).completedCount)
    }

    @Test
    fun `I3-U7 - three attempts yield Completed with the recorded score`() = runBlocking {
        playFullDay(day1)

        val state = firstState()

        assertTrue("ожидался Completed, получен $state", state is TodayState.Completed)
        state as TodayState.Completed
        assertEquals(15, state.totalScore)
        assertEquals(1, state.dayNumber)
        assertEquals(day1, state.sessionDate)
        // Момент из DecisionContext: начало следующей локальной даты в зоне тех же часов.
        assertEquals(day1.plusDays(1).atStartOfDay(zone).toInstant(), state.nextLocalDateStartsAt)
        assertEquals(1, state.streaks.current)
    }

    @Test
    fun `I3-U8 - AwaitingNextDay reports the last completed day number`() = runBlocking {
        playFullDay(day1)
        clock.setDate(LocalDate.of(2026, 9, 2), zone)
        assignments.startSession() // назначение на 09-02, попыток по нему нет
        clock.setDate(day1, zone) // часы переведены назад

        val state = firstState()

        assertTrue("ожидался AwaitingNextDay, получен $state", state is TodayState.AwaitingNextDay)
        state as TodayState.AwaitingNextDay
        val last = requireNotNull(state.lastCompleted)
        assertEquals(day1, last.localDate)
        assertEquals(1, last.dayNumber)
        assertEquals(15, last.totalScore)
    }

    @Test
    fun `I3-U9 - ContentExhausted once every set of the active pack is spent`() = runBlocking {
        playFullDay(day1)
        playFullDay(LocalDate.of(2026, 9, 2))
        playFullDay(LocalDate.of(2026, 9, 3))
        clock.setDate(LocalDate.of(2026, 9, 4), zone)

        val state = firstState()

        assertTrue("ожидался ContentExhausted, получен $state", state is TodayState.ContentExhausted)
        state as TodayState.ContentExhausted
        assertEquals(3, state.stats.completedDayCount)
        assertEquals(3, state.stats.streaks.current)
    }

    @Test
    fun `I3-U23 - AwaitingNextDay without history invents no date, day number or score`() = runBlocking {
        assignments.startSession() // назначение на 09-01, ни одной попытки
        clock.setDate(LocalDate.of(2026, 8, 31), zone) // часы переведены назад

        val state = firstState()

        assertTrue("ожидался AwaitingNextDay, получен $state", state is TodayState.AwaitingNextDay)
        state as TodayState.AwaitingNextDay
        assertNull(state.lastCompleted)
        assertEquals(LocalDate.of(2026, 8, 31), state.today)
        assertEquals(0, state.stats.playedDayCount)
        assertTrue(db.dayResultDao().getAll().isEmpty())
    }

    @Test
    fun `I3-U43 - a content conflict is classified apart, and neither failure ends the flow`() = runBlocking {
        installer.failure = ContentInstallException.Conflict(
            packId = ContentPack.CORE_RU,
            staleSetIndexes = listOf(4),
            changedSetIndexes = emptyList(),
            blockedDates = listOf(day1),
        )
        val refresh = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val states = Channel<TodayState>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) { useCase(refresh).collect(states::send) }

        try {
            val conflict = states.next()
            assertEquals(
                TodayState.Failure(null, null, TodayFailureKind.ContentConflict),
                conflict,
            )

            // Поток жив: следующий сигнал снова вызывает compute().
            installer.failure = IllegalStateException("база недоступна")
            refresh.emit(Unit)
            val generic = states.next()
            assertEquals(TodayState.Failure(null, null, TodayFailureKind.Generic), generic)

            // И после ДВУХ отказов ретрай возвращает успешное состояние в тот же коллектор.
            installer.failure = null
            refresh.emit(Unit)
            val recovered = states.next()
            assertTrue("после ретрая получен $recovered", recovered is TodayState.FirstRun)
            assertEquals(3, installer.calls)
        } finally {
            collector.cancel()
        }
    }

    /**
     * `I4-V1` (ITERATION_4_DESIGN.md, §11.4): исчерпывающая классификация закрытой
     * taxonomy. Пятый вариант `ContentInstallException` обязан сломать компиляцию
     * `kindOf`, а не молча уехать в `Generic`.
     *
     * Отказ базы среди вариантов отсутствует намеренно: исключения Room не
     * оборачиваются и попадают в общую ветку.
     */
    @Test
    fun `I4-V1 - every failure cause maps to its TodayFailureKind`() = runBlocking {
        val cases = listOf(
            ContentInstallException.Conflict(
                packId = ContentPack.CORE_RU,
                staleSetIndexes = listOf(4),
                changedSetIndexes = listOf(1),
                blockedDates = listOf(day1),
            ) to TodayFailureKind.ContentConflict,
            ContentInstallException.BundleInvalid(
                code = "R19_SET_INDEX_SEQUENCE",
                violations = 7,
                detail = "daily-sets-001.json#/sets — дыра в последовательности",
            ) to TodayFailureKind.ContentUnusable,
            ContentInstallException.UnsupportedSchema(manifest = 2, supported = 1)
                to TodayFailureKind.ContentUnusable,
            ContentInstallException.AssetUnreadable(
                fileName = "puzzles-001.json",
                cause = java.io.IOException("поток закрыт"),
            ) to TodayFailureKind.Generic,
            // Не из taxonomy вовсе: так наружу выходит любое исключение Room.
            IllegalStateException("SQLite: database is locked") to TodayFailureKind.Generic,
            RuntimeException("что угодно ещё") to TodayFailureKind.Generic,
        )

        for ((failure, expected) in cases) {
            installer.failure = failure
            val refresh = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
            val states = Channel<TodayState>(Channel.UNLIMITED)
            val collector = launch(Dispatchers.Default) { useCase(refresh).collect(states::send) }

            try {
                assertEquals(
                    failure.toString(),
                    TodayState.Failure(null, null, expected),
                    states.next(),
                )
            } finally {
                collector.cancel()
            }
        }
    }

    private suspend fun Channel<TodayState>.next(): TodayState = withTimeout(RECEIVE_TIMEOUT_MS) { receive() }

    private companion object {
        /** Реальное время: поток ждёт настоящих ответов Room, а не виртуального таймера. */
        const val RECEIVE_TIMEOUT_MS = 10_000L
    }
}
