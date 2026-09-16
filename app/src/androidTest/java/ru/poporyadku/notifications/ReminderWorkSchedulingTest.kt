package ru.poporyadku.notifications

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.EntryPointAccessors
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.poporyadku.PoPoRyadkuApp
import ru.poporyadku.debug.DebugGraphEntryPoint
import ru.poporyadku.debug.ReminderTestHooks
import ru.poporyadku.domain.reminder.ReminderSyncResult
import ru.poporyadku.domain.repository.UserPreferencesRepository

/**
 * `I6-N6` — настоящий WorkManager на эмуляторе (ITERATION_6_DESIGN.md, §14.4, I6-D26,
 * I6-D30, I6-D41).
 *
 * Выполняется **вручную** на эмуляторе; в CI только компилируется (§2.8). Уничтожение
 * самого процесса после `finish()` проверяет ручной сценарий `I6-M7` — тест инструментации
 * убить свой процесс и продолжить проверку не может.
 *
 * `MainActivity` здесь **не запускается** и `ReminderScheduleObserver.start()` не
 * вызывается: холодное событие обязано работать без них.
 */
@RunWith(AndroidJUnit4::class)
class ReminderWorkSchedulingTest {

    private lateinit var workManager: WorkManager
    private lateinit var hooks: ReminderTestHooks
    private lateinit var preferences: UserPreferencesRepository

    @Before
    fun setUp() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PoPoRyadkuApp>()
        workManager = WorkManager.getInstance(app)
        val graph = EntryPointAccessors.fromApplication(app, DebugGraphEntryPoint::class.java)
        hooks = graph.reminderHooks()
        preferences = graph.preferences()
        clearWork()
    }

    @After
    fun tearDown() = runBlocking {
        clearWork()
        preferences.setReminderEnabled(false)
    }

    /** `I6-N6`. Включённое напоминание даёт ровно одну ожидающую работу с нужными данными. */
    @Test
    fun i6N6AnEnabledReminderEnqueuesExactlyOneDailyWork() = runBlocking {
        preferences.setReminderTime(LocalTime.of(9, 0))
        preferences.setReminderEnabled(true)

        val result = hooks.syncNow()

        assertTrue("синхронизация подтверждена", result is ReminderSyncResult.Scheduled)
        val infos = enqueuedDaily()
        assertEquals("одна работа на уникальное имя", 1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
    }

    /** `I6-N6`. Повторная синхронизация не добавляет вторую работу: имя уникально. */
    @Test
    fun i6N6ARepeatedSynchronizationKeepsASingleWork() = runBlocking {
        preferences.setReminderEnabled(true)

        hooks.syncNow()
        hooks.syncNow()
        hooks.syncNow()

        assertEquals(1, enqueuedDaily().size)
    }

    /** `I6-N6`. Выключение отменяет работу — подтверждённо. */
    @Test
    fun i6N6DisablingCancelsTheDailyWork() = runBlocking {
        preferences.setReminderEnabled(true)
        hooks.syncNow()

        preferences.setReminderEnabled(false)
        val result = hooks.syncNow()

        assertEquals(ReminderSyncResult.Cancelled, result)
        assertTrue("ожидающих работ не осталось", enqueuedDaily().isEmpty())
    }

    /**
     * `I6-N6`. **Холодное событие**: `MainActivity` не запускалась, коллектор не стартовал.
     * `onFinished` наступает, и **сразу после него** сохранённая `reminder_resync` уже
     * читается из базы WorkManager — значит, процесс можно уничтожать.
     */
    @Test
    fun i6N6AColdTimezoneChangePersistsResyncWorkBeforeFinishing() = runBlocking {
        preferences.setReminderEnabled(true)

        withTimeout(FINISH_TIMEOUT_MS) {
            hooks.deliverTimeChange(Intent.ACTION_TIMEZONE_CHANGED)
        }

        val resync = workManager.getWorkInfosForUniqueWork(hooks.resyncWorkName).get()
        assertTrue(
            "resync-работа обязана быть в базе к моменту onFinished",
            resync.isNotEmpty(),
        )
    }

    /** `I6-N6`. Ручной перевод часов сохраняется так же. */
    @Test
    fun i6N6AColdManualClockChangePersistsResyncWorkToo() = runBlocking {
        preferences.setReminderEnabled(true)

        withTimeout(FINISH_TIMEOUT_MS) {
            hooks.deliverTimeChange(Intent.ACTION_TIME_CHANGED)
        }

        assertTrue(workManager.getWorkInfosForUniqueWork(hooks.resyncWorkName).get().isNotEmpty())
    }

    /**
     * `I6-N6`. Выполнение сохранённой resync-работы (то, что делает `ReminderResyncWorker`
     * в следующем процессе) ставит `daily_reminder` по текущим настройкам и зоне.
     */
    @Test
    fun i6N6RunningThePersistedResyncSchedulesTheDailyWork() = runBlocking {
        preferences.setReminderTime(LocalTime.of(21, 30))
        preferences.setReminderEnabled(true)
        withTimeout(FINISH_TIMEOUT_MS) {
            hooks.deliverTimeChange(Intent.ACTION_TIMEZONE_CHANGED)
        }

        // Так же, как это сделает worker в поднятом системой процессе.
        val result = hooks.syncNow()

        val trigger = (result as ReminderSyncResult.Scheduled).trigger
        assertEquals(21 * 60 + 30, trigger.minuteOfDay)
        assertEquals(1, enqueuedDaily().size)
    }

    /** `I6-N6`. При выключенном напоминании итог resync — отменённая работа. */
    @Test
    fun i6N6AColdEventWithTheReminderDisabledEndsInNoWork() = runBlocking {
        preferences.setReminderEnabled(true)
        hooks.syncNow()
        preferences.setReminderEnabled(false)

        withTimeout(FINISH_TIMEOUT_MS) {
            hooks.deliverTimeChange(Intent.ACTION_TIMEZONE_CHANGED)
        }
        hooks.syncNow()

        assertTrue(enqueuedDaily().isEmpty())
    }

    /**
     * `I6-N6`. Работа без ограничений выполняется при объявленном WorkManager
     * `ACCESS_NETWORK_STATE`: у запроса нет ни одного `Constraint`, поэтому состояние сети
     * на него не влияет.
     */
    @Test
    fun i6N6TheDailyWorkHasNoConstraintsAtAll() = runBlocking {
        preferences.setReminderEnabled(true)
        hooks.syncNow()

        val info = enqueuedDaily().single()
        assertNotNull(info)
        assertEquals(
            "ограничений быть не должно",
            androidx.work.Constraints.NONE,
            info.constraints,
        )
    }

    private suspend fun enqueuedDaily(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWorkFlow(hooks.dailyReminderWorkName).first()
            .filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }

    private fun clearWork() {
        workManager.cancelUniqueWork(hooks.dailyReminderWorkName).result.get()
        workManager.cancelUniqueWork(hooks.resyncWorkName).result.get()
        workManager.pruneWork().result.get()
    }

    private companion object {
        /** Ожидание записи в базу WorkManager — миллисекунды; запас на медленный эмулятор. */
        const val FINISH_TIMEOUT_MS = 30_000L
    }
}
