package ru.poporyadku.notifications

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.poporyadku.domain.reminder.CountingClock
import ru.poporyadku.domain.reminder.ReminderTrigger
import ru.poporyadku.domain.reminder.ScheduleMode
import ru.poporyadku.domain.reminder.clockAt

/**
 * `I6-P1` — адаптер WorkManager (ITERATION_6_DESIGN.md, §9.3, I6-D26, I6-D27).
 *
 * Операции подменены фейком: проверяются имя, политика, данные и задержка — то, что
 * адаптер решает сам. Поведение настоящего WorkManager проверяет `I6-N6` на эмуляторе.
 */
@RunWith(RobolectricTestRunner::class)
class WorkManagerReminderSchedulerTest {

    private val today = LocalDate.of(2026, 9, 16)
    private val nowTime = LocalTime.of(8, 0)
    private val trigger = ReminderTrigger(
        targetDate = today,
        minuteOfDay = 9 * 60,
        at = today.atTime(LocalTime.of(9, 0)).atZone(MOSCOW).toInstant(),
    )

    /** `I6-P1`. Обычная синхронизация — `REPLACE`, имя `daily_reminder`, тег `reminder`. */
    @Test
    fun `I6-P1 Replace enqueues the unique daily work with REPLACE`() = runTest {
        val operations = FakeOperations()

        scheduler(operations).schedule(trigger, ScheduleMode.Replace)

        val enqueued = operations.enqueued.single()
        assertEquals(ReminderWorkNames.DAILY_REMINDER, enqueued.name)
        assertEquals(ExistingWorkPolicy.REPLACE, enqueued.policy)
        assertTrue(enqueued.request.tags.contains(ReminderWorkNames.TAG_REMINDER))
    }

    /** `I6-P1`. Данные — **ровно** дата и минута суток, ничего больше. */
    @Test
    fun `I6-P1 the input data carries exactly the target date and the minute of day`() = runTest {
        val operations = FakeOperations()

        scheduler(operations).schedule(trigger, ScheduleMode.Replace)

        val data = operations.enqueued.single().request.workSpec.input
        assertEquals("2026-09-16", data.getString(ReminderWorkNames.KEY_TARGET_DATE))
        assertEquals(9 * 60, data.getInt(ReminderWorkNames.KEY_MINUTE_OF_DAY, -1))
        assertEquals("лишних полей быть не должно", 2, data.keyValueMap.size)
    }

    /** `I6-P1`. Задержка — расстояние от «сейчас» до момента цели. */
    @Test
    fun `I6-P1 the initial delay is the distance from now to the target`() = runTest {
        val operations = FakeOperations()

        scheduler(operations).schedule(trigger, ScheduleMode.Replace)

        val expected = java.time.Duration.between(
            Instant.ofEpochMilli(clockAt(today, nowTime, MOSCOW).millis()),
            trigger.at,
        ).toMillis()
        assertEquals(expected, operations.enqueued.single().request.workSpec.initialDelay)
    }

    /**
     * `I6-P1`. Момент цели уже в прошлом (часы перевели вперёд) — задержка ноль, а не
     * отрицательное число.
     */
    @Test
    fun `I6-P1 a target in the past yields a zero delay`() = runTest {
        val operations = FakeOperations()
        val past = trigger.copy(at = trigger.at.minusSeconds(SECONDS_IN_TWO_HOURS))

        scheduler(operations).schedule(past, ScheduleMode.Replace)

        assertEquals(0L, operations.enqueued.single().request.workSpec.initialDelay)
    }

    /**
     * `I6-P1`. Перепланирование из worker'а — `APPEND_OR_REPLACE`: следующая работа
     * ставится после текущей и не отменяет ту, внутри которой выполняется.
     */
    @Test
    fun `I6-P1 AfterCurrent enqueues with APPEND_OR_REPLACE`() = runTest {
        val workId = UUID.randomUUID()
        // В цепочке — только сама выполняющаяся работа.
        val operations = FakeOperations(pending = listOf(workId))

        scheduler(operations).schedule(trigger, ScheduleMode.AfterCurrent(workId))

        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, operations.enqueued.single().policy)
    }

    /**
     * `I6-P1`. В цепочке уже есть ожидающая работа, поставленная синхронизацией:
     * перепланирование **не добавляет вторую** — её настройки свежее.
     */
    @Test
    fun `I6-P1 AfterCurrent adds nothing when a newer pending work already exists`() = runTest {
        val workId = UUID.randomUUID()
        val operations = FakeOperations(pending = listOf(workId, UUID.randomUUID()))

        scheduler(operations).schedule(trigger, ScheduleMode.AfterCurrent(workId))

        assertTrue("второй ожидающей работы быть не должно", operations.enqueued.isEmpty())
    }

    /** `I6-P1`. Отмена — `cancelUniqueWork` того же имени. */
    @Test
    fun `I6-P1 cancel cancels the unique daily work`() = runTest {
        val operations = FakeOperations()

        scheduler(operations).cancel()

        assertEquals(listOf(ReminderWorkNames.DAILY_REMINDER), operations.cancelled)
    }

    private fun scheduler(operations: FakeOperations) = WorkManagerReminderScheduler(
        operations = operations,
        clock = CountingClock(clockAt(today, nowTime, MOSCOW)),
    )

    private class Enqueued(
        val name: String,
        val policy: ExistingWorkPolicy,
        val request: OneTimeWorkRequest,
    )

    private class FakeOperations(private val pending: List<UUID> = emptyList()) : UniqueWorkOperations {
        val enqueued = mutableListOf<Enqueued>()
        val cancelled = mutableListOf<String>()

        override suspend fun enqueue(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest) {
            enqueued += Enqueued(name, policy, request)
        }

        override suspend fun cancel(name: String) {
            cancelled += name
        }

        override suspend fun pendingIds(name: String): List<UUID> = pending
    }

    private companion object {
        val MOSCOW: java.time.ZoneId = java.time.ZoneId.of("Europe/Moscow")
        const val SECONDS_IN_TWO_HOURS = 2L * 60 * 60
    }
}
