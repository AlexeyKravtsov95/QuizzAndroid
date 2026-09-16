package ru.poporyadku.domain.reminder

import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `I6-Y2` — единственная операция синхронизации (ITERATION_6_DESIGN.md, §9.4, I6-D29).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncReminderScheduleUseCaseTest {

    private val today = LocalDate.of(2026, 9, 16)

    /**
     * `I6-Y2`. Включено → `schedule(Replace)` с целью, посчитанной от **одного** снимка
     * часов: фейк считает обращения, и их ровно одно.
     */
    @Test
    fun `I6-Y2 an enabled reminder schedules with Replace from a single clock read`() = runTest {
        val scheduler = RecordingScheduler()
        val clock = CountingClock(clockAt(today, LocalTime.of(8, 0)))

        val result = sync(scheduler = scheduler, clock = clock).invoke()

        val trigger = (result as ReminderSyncResult.Scheduled).trigger
        assertEquals(today, trigger.targetDate)
        assertEquals(ScheduleMode.Replace, scheduler.scheduled.single().second)
        assertEquals("один TimeSnapshot на операцию", 1, clock.reads)
    }

    /** `I6-Y2`. Выключено → подтверждённая отмена, без планирования. */
    @Test
    fun `I6-Y2 a disabled reminder cancels`() = runTest {
        val scheduler = RecordingScheduler()

        val result = sync(
            preferences = ReadOnlyPreferences(preferencesOf(reminderEnabled = false)),
            scheduler = scheduler,
        ).invoke()

        assertEquals(ReminderSyncResult.Cancelled, result)
        assertEquals(1, scheduler.cancels)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    /**
     * `I6-Y2`. Настройки перечитываются на **каждый** вызов: изменение между двумя
     * вызовами меняет цель, а не повторяет прежнюю.
     */
    @Test
    fun `I6-Y2 settings are re-read on every call`() = runTest {
        val preferences = ReadOnlyPreferences(preferencesOf(reminderTime = LocalTime.of(9, 0)))
        val scheduler = RecordingScheduler()
        val useCase = sync(preferences = preferences, scheduler = scheduler)

        useCase()
        preferences.set(preferencesOf(reminderTime = LocalTime.of(21, 30)))
        useCase()

        assertEquals(listOf(9 * 60, 21 * 60 + 30), scheduler.scheduled.map { it.first.minuteOfDay })
    }

    /**
     * `I6-Y2`. Результат возвращается только после завершения операции планировщика:
     * пока запись не подтверждена, вызывающий ждёт.
     */
    @Test
    fun `I6-Y2 the result waits for the scheduler operation to complete`() = runTest {
        val scheduler = RecordingScheduler()
        val gate = CompletableDeferred<Unit>()
        scheduler.gate = gate

        val deferred = async { sync(scheduler = scheduler).invoke() }
        runCurrent()

        assertFalse("запись не подтверждена — результата быть не может", deferred.isCompleted)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(deferred.await() is ReminderSyncResult.Scheduled)
    }

    /** `I6-Y2`. Исключение планировщика — `Failed`, а не `Scheduled` и не `Cancelled`. */
    @Test
    fun `I6-Y2 a scheduler failure yields Failed and never a success`() = runTest {
        val failure = IllegalStateException("WorkManager отказал")

        val result = sync(scheduler = RecordingScheduler(scheduleFailure = failure)).invoke()

        assertSame(failure, (result as ReminderSyncResult.Failed).cause)
    }

    /** `I6-Y2`. Отказ отмены — тоже `Failed`. */
    @Test
    fun `I6-Y2 a cancel failure yields Failed`() = runTest {
        val failure = IllegalStateException("WorkManager отказал")

        val result = sync(
            preferences = ReadOnlyPreferences(preferencesOf(reminderEnabled = false)),
            scheduler = RecordingScheduler(cancelFailure = failure),
        ).invoke()

        assertSame(failure, (result as ReminderSyncResult.Failed).cause)
    }

    /** `I6-Y2`. `CancellationException` пробрасывается и в `Failed` не превращается. */
    @Test
    fun `I6-Y2 cancellation propagates instead of becoming Failed`() = runTest {
        val useCase = sync(
            scheduler = RecordingScheduler(scheduleFailure = CancellationException("отменено")),
        )

        val thrown = runCatching { useCase() }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
    }

    /**
     * `I6-Y2`. Два одновременных вызова выполняются **последовательно**: второй ждёт
     * освобождения `ReminderScheduleLock`, поэтому две записи никогда не идут внахлёст.
     */
    @Test
    fun `I6-Y2 concurrent calls are serialized by the schedule lock`() = runTest {
        val scheduler = RecordingScheduler()
        val gate = CompletableDeferred<Unit>()
        scheduler.gate = gate
        val useCase = sync(scheduler = scheduler)

        val first = async { useCase() }
        val second = async { useCase() }
        runCurrent()

        assertEquals("вторая операция ждёт блокировку", 0, scheduler.scheduled.size)
        gate.complete(Unit)
        advanceUntilIdle()
        first.await()
        second.await()
        assertEquals(2, scheduler.scheduled.size)
    }

    private fun sync(
        preferences: ReadOnlyPreferences = ReadOnlyPreferences(preferencesOf()),
        scheduler: RecordingScheduler = RecordingScheduler(),
        clock: CountingClock = CountingClock(clockAt(today, LocalTime.of(8, 0))),
        lock: ReminderScheduleLock = ReminderScheduleLock(),
    ) = SyncReminderScheduleUseCase(preferences, scheduler, clock, lock)
}
