package ru.poporyadku.notifications

import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.poporyadku.core.model.ThemeMode
import ru.poporyadku.domain.reminder.CountingClock
import ru.poporyadku.domain.reminder.RecordingScheduler
import ru.poporyadku.domain.reminder.ReminderScheduleLock
import ru.poporyadku.domain.reminder.SyncReminderScheduleUseCase
import ru.poporyadku.domain.reminder.clockAt
import ru.poporyadku.ui.settings.ControllablePreferences

/**
 * `I6-Y1` — постоянный коллектор настроек напоминания (ITERATION_6_DESIGN.md, §9.4,
 * I6-D29).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReminderScheduleObserverTest {

    private val today = LocalDate.of(2026, 9, 16)
    private val jobs = mutableListOf<Job>()

    @After
    fun tearDown() {
        jobs.forEach { it.cancel() }
    }

    /** `I6-Y1`. `start()` синхронизирует уже на первой эмиссии — при старте приложения. */
    @Test
    fun `I6-Y1 start synchronizes on the first emission`() = runTest {
        val prefs = ControllablePreferences()
        val scheduler = RecordingScheduler()
        observer(prefs, scheduler).start()

        advanceUntilIdle()

        // По умолчанию напоминание выключено — синхронизация это подтверждает отменой.
        assertEquals(1, scheduler.cancels)
    }

    /**
     * `I6-Y1`. Каждая изменившаяся пара «включено + время» даёт ровно одну синхронизацию,
     * и все они идут по порядку.
     */
    @Test
    fun `I6-Y1 every changed pair yields exactly one synchronization in order`() = runTest {
        val prefs = ControllablePreferences(ControllablePreferences.defaults(reminderEnabled = false))
        val scheduler = RecordingScheduler()
        observer(prefs, scheduler).start()
        advanceUntilIdle()

        prefs.emit(ControllablePreferences.defaults(reminderEnabled = true))
        advanceUntilIdle()
        prefs.emit(ControllablePreferences.defaults(reminderEnabled = false))
        advanceUntilIdle()
        prefs.emit(ControllablePreferences.defaults(reminderEnabled = true))
        advanceUntilIdle()
        prefs.emit(
            ControllablePreferences.defaults(reminderEnabled = true, reminderTime = LocalTime.of(21, 30)),
        )
        advanceUntilIdle()

        // Отмена → план → отмена → план → план с новым временем.
        assertEquals(2, scheduler.cancels)
        assertEquals(listOf(9 * 60, 9 * 60, 21 * 60 + 30), scheduler.scheduled.map { it.first.minuteOfDay })
    }

    /** `I6-Y1`. Эмиссия чужого ключа расписание не трогает. */
    @Test
    fun `I6-Y1 an unrelated key does not synchronize`() = runTest {
        val prefs = ControllablePreferences(ControllablePreferences.defaults(reminderEnabled = true))
        val scheduler = RecordingScheduler()
        observer(prefs, scheduler).start()
        advanceUntilIdle()
        val afterStart = scheduler.scheduled.size

        prefs.emit(ControllablePreferences.defaults(reminderEnabled = true, themeMode = ThemeMode.DARK))
        advanceUntilIdle()

        assertEquals("тема расписания не касается", afterStart, scheduler.scheduled.size)
    }

    /** `I6-Y1`. Повторный `start()` второго коллектора не создаёт. */
    @Test
    fun `I6-Y1 start is idempotent`() = runTest {
        val prefs = ControllablePreferences(ControllablePreferences.defaults(reminderEnabled = true))
        val scheduler = RecordingScheduler()
        val observer = observer(prefs, scheduler)

        observer.start()
        observer.start()
        observer.start()
        advanceUntilIdle()

        assertEquals("одна синхронизация на одну эмиссию", 1, scheduler.scheduled.size)
    }

    /**
     * `I6-Y1`. `Failed` не останавливает сбор: следующая эмиссия по-прежнему
     * синхронизируется — иначе один отказ WorkManager выключил бы напоминания до
     * перезапуска процесса.
     */
    @Test
    fun `I6-Y1 a Failed result does not stop the collector`() = runTest {
        val prefs = ControllablePreferences(ControllablePreferences.defaults(reminderEnabled = true))
        // Планировщик отказывает всегда: каждая синхронизация возвращает Failed.
        val scheduler = RecordingScheduler(scheduleFailure = IllegalStateException("отказ"))
        observer(prefs, scheduler).start()
        advanceUntilIdle()

        assertEquals(1, scheduler.attempts)
        assertTrue("ни одна запись не удалась", scheduler.scheduled.isEmpty())

        prefs.emit(
            ControllablePreferences.defaults(reminderEnabled = true, reminderTime = LocalTime.of(7, 0)),
        )
        advanceUntilIdle()

        assertEquals("сбор продолжается после Failed", 2, scheduler.attempts)
    }

    private fun TestScope.observer(
        prefs: ControllablePreferences,
        scheduler: RecordingScheduler,
    ): ReminderScheduleObserver {
        val sync = SyncReminderScheduleUseCase(
            preferences = prefs,
            scheduler = scheduler,
            clock = CountingClock(clockAt(today, LocalTime.of(8, 0))),
            lock = ReminderScheduleLock(),
        )
        return ReminderScheduleObserver(prefs, sync, scope())
    }

    private fun TestScope.scope(): CoroutineScope {
        val job = SupervisorJob()
        jobs += job
        return CoroutineScope(job + StandardTestDispatcher(testScheduler))
    }
}
