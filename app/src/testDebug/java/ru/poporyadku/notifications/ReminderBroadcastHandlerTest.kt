package ru.poporyadku.notifications

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.poporyadku.core.model.UserPreferences
import ru.poporyadku.domain.reminder.CountingClock
import ru.poporyadku.domain.reminder.ReadOnlyPreferences
import ru.poporyadku.domain.reminder.RecordingScheduler
import ru.poporyadku.domain.reminder.ReminderScheduleLock
import ru.poporyadku.domain.reminder.SyncReminderScheduleUseCase
import ru.poporyadku.domain.reminder.clockAt
import ru.poporyadku.domain.reminder.preferencesOf

/**
 * `I6-P4` — приёмник смены времени и зоны (ITERATION_6_DESIGN.md, §9.4, §9.5, I6-D30).
 *
 * Главное утверждение: **`finish()` вызывается только после долговечного результата**, и
 * отменяющего таймаута не существует. Поэтому прямая синхронизация в тесте задержана
 * бесконечно: если бы обработчик ждал её, `onFinished` не наступил бы никогда.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReminderBroadcastHandlerTest {

    private val today = LocalDate.of(2026, 9, 16)
    private val jobs = mutableListOf<Job>()

    @After
    fun tearDown() {
        jobs.forEach { it.cancel() }
    }

    /**
     * `I6-P4`. `TIMEZONE_CHANGED`: resync-работа сохраняется, и `onFinished` наступает
     * **только** после подтверждённой записи — ровно один раз.
     */
    @Test
    fun `I6-P4 timezone change persists resync work before finishing exactly once`() = runTest {
        val store = DurableWorkStore()
        val gate = CompletableDeferred<Unit>()
        store.gate = gate
        val world = world(store)
        var finished = 0

        world.handler.handle(Intent.ACTION_TIMEZONE_CHANGED) { finished++ }
        runCurrent()

        assertEquals("до подтверждения записи finish() невозможен", 0, finished)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(ReminderWorkNames.REMINDER_RESYNC), store.persisted)
        assertEquals(1, finished)
    }

    /** `I6-P4`. `TIME_SET` обрабатывается так же. */
    @Test
    fun `I6-P4 a manual clock change persists resync work too`() = runTest {
        val store = DurableWorkStore()
        val world = world(store)
        var finished = 0

        world.handler.handle(Intent.ACTION_TIME_CHANGED) { finished++ }
        advanceUntilIdle()

        assertEquals(listOf(ReminderWorkNames.REMINDER_RESYNC), store.persisted)
        assertEquals(1, finished)
    }

    /**
     * `I6-P4`. Прямая синхронизация задержана **бесконечно** и `onFinished` этому не
     * мешает: обработчик ждёт запись resync-работы, а не бизнес-операцию.
     */
    @Test
    fun `I6-P4 an indefinitely delayed direct sync does not block finishing`() = runTest {
        val store = DurableWorkStore()
        val scheduler = RecordingScheduler()
        // Прямая синхронизация не завершится никогда.
        scheduler.gate = CompletableDeferred()
        val world = world(store, scheduler)
        var finished = 0

        world.handler.handle(Intent.ACTION_TIMEZONE_CHANGED) { finished++ }
        advanceUntilIdle()

        assertEquals(1, finished)
        assertTrue("прямая синхронизация не выполнялась", scheduler.scheduled.isEmpty())
    }

    /**
     * `I6-P4`. Процесс уничтожен сразу после `finish()`: обработчик и его scope
     * отброшены, но сохранённая работа переживает их — новый экземпляр
     * `SyncReminderScheduleUseCase` выполняет её и ставит `daily_reminder` по текущим
     * настройкам.
     */
    @Test
    fun `I6-P4 persisted work survives the process and schedules the reminder`() = runTest {
        val store = DurableWorkStore()
        val world = world(store)
        world.handler.handle(Intent.ACTION_TIMEZONE_CHANGED) { }
        advanceUntilIdle()

        // «Процесс уничтожен»: scope обработчика отменён, объекты отброшены.
        world.job.cancel()
        assertEquals(listOf(ReminderWorkNames.REMINDER_RESYNC), store.persisted)

        // Новый процесс: WorkManager поднял ReminderResyncWorker.
        val scheduler = RecordingScheduler()
        val sync = SyncReminderScheduleUseCase(
            preferences = ReadOnlyPreferences(preferencesOf(reminderTime = LocalTime.of(9, 0))),
            scheduler = scheduler,
            clock = CountingClock(clockAt(today, LocalTime.of(8, 0))),
            lock = ReminderScheduleLock(),
        )
        sync()

        assertEquals(9 * 60, scheduler.scheduled.single().first.minuteOfDay)
    }

    /** `I6-P4`. Выключенное напоминание: resync сохраняется, а его выполнение — отмена. */
    @Test
    fun `I6-P4 a disabled reminder persists resync whose run cancels the work`() = runTest {
        val store = DurableWorkStore()
        val world = world(store, preferences = preferencesOf(reminderEnabled = false))
        world.handler.handle(Intent.ACTION_TIMEZONE_CHANGED) { }
        advanceUntilIdle()

        assertEquals(listOf(ReminderWorkNames.REMINDER_RESYNC), store.persisted)

        val scheduler = RecordingScheduler()
        SyncReminderScheduleUseCase(
            preferences = ReadOnlyPreferences(preferencesOf(reminderEnabled = false)),
            scheduler = scheduler,
            clock = CountingClock(clockAt(today, LocalTime.of(8, 0))),
            lock = ReminderScheduleLock(),
        ).invoke()

        assertEquals(1, scheduler.cancels)
    }

    /**
     * `I6-P4`. Запись resync отказала — запасной путь: прямая синхронизация, и `finish()`
     * только после её подтверждённой записи.
     */
    @Test
    fun `I6-P4 a refused resync falls back to a confirmed direct synchronization`() = runTest {
        val store = DurableWorkStore(failure = IllegalStateException("WorkManager отказал"))
        val scheduler = RecordingScheduler()
        val world = world(store, scheduler)
        var finished = 0

        world.handler.handle(Intent.ACTION_TIMEZONE_CHANGED) { finished++ }
        advanceUntilIdle()

        assertTrue("resync не сохранена", store.persisted.isEmpty())
        assertEquals("зато записана сама работа напоминания", 1, scheduler.scheduled.size)
        assertEquals(1, finished)
    }

    /**
     * `I6-P4`. Отказали обе записи (`NotPersisted`): `finish()` всё равно ровно один раз,
     * исключение наружу не выходит — сохранить событие больше нечем.
     */
    @Test
    fun `I6-P4 when both writes fail it still finishes exactly once without throwing`() = runTest {
        val store = DurableWorkStore(failure = IllegalStateException("WorkManager отказал"))
        val scheduler = RecordingScheduler(scheduleFailure = IllegalStateException("и здесь отказал"))
        val world = world(store, scheduler)
        var finished = 0

        world.handler.handle(Intent.ACTION_TIMEZONE_CHANGED) { finished++ }
        advanceUntilIdle()

        assertEquals(1, finished)
    }

    /** `I6-P4`. Отмена scope во время ожидания записи: `onFinished` ровно один раз. */
    @Test
    fun `I6-P4 a cancelled scope still finishes exactly once`() = runTest {
        val store = DurableWorkStore()
        store.gate = CompletableDeferred()
        val world = world(store)
        var finished = 0

        world.handler.handle(Intent.ACTION_TIMEZONE_CHANGED) { finished++ }
        runCurrent()
        assertEquals(0, finished)

        world.job.cancel()
        advanceUntilIdle()

        assertEquals(1, finished)
    }

    /** `I6-P4`. Чужое действие: `finish()` сразу, работы нет. */
    @Test
    fun `I6-P4 an unknown action finishes immediately without any work`() = runTest {
        val store = DurableWorkStore()
        val world = world(store)
        var finished = 0

        val job = world.handler.handle(Intent.ACTION_BATTERY_LOW) { finished++ }
        advanceUntilIdle()

        assertEquals(1, finished)
        assertTrue(store.persisted.isEmpty())
        assertFalse("корутина не запускается", job != null)
    }

    /**
     * `I6-P4`. Итоговый манифест: приёмник объявлен, не экспортирован и слушает ровно два
     * действия — ни `BOOT_COMPLETED`, ни смену даты он не получает.
     */
    @Test
    @Config(manifest = Config.DEFAULT_MANIFEST_NAME)
    fun `I6-P4 the merged manifest declares the receiver with exactly two actions`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val receivers = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_RECEIVERS)
            .receivers
            .orEmpty()

        val receiver = receivers.single { it.name == ReminderTimeChangeReceiver::class.java.name }
        assertFalse("приёмник не экспортирован", receiver.exported)

        val actions = listOf(Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED)
        actions.forEach { action ->
            val resolved = context.packageManager.queryBroadcastReceivers(Intent(action), 0)
            assertTrue(
                "приёмник обязан отвечать на $action",
                resolved.any { it.activityInfo?.name == ReminderTimeChangeReceiver::class.java.name },
            )
        }
    }

    // --- Инфраструктура -------------------------------------------------------------------

    /** Хранилище работ, переживающее «процесс»: его состояние не принадлежит обработчику. */
    private class DurableWorkStore(private val failure: Exception? = null) {
        val persisted = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null

        suspend fun enqueue(name: String) {
            gate?.await()
            failure?.let { throw it }
            persisted += name
        }
    }

    private class FakeResyncRequests(private val store: DurableWorkStore) : ReminderResyncRequests {
        override suspend fun enqueueDurably() = store.enqueue(ReminderWorkNames.REMINDER_RESYNC)
    }

    private class World(val handler: ReminderBroadcastHandler, val job: Job)

    private fun TestScope.world(
        store: DurableWorkStore,
        scheduler: RecordingScheduler = RecordingScheduler(),
        preferences: UserPreferences = preferencesOf(),
    ): World {
        val job = SupervisorJob()
        jobs += job
        val scope = CoroutineScope(job + StandardTestDispatcher(testScheduler))
        val sync = SyncReminderScheduleUseCase(
            preferences = ReadOnlyPreferences(preferences),
            scheduler = scheduler,
            clock = CountingClock(clockAt(today, LocalTime.of(8, 0))),
            lock = ReminderScheduleLock(),
        )
        return World(ReminderBroadcastHandler(FakeResyncRequests(store), sync, scope), job)
    }
}
