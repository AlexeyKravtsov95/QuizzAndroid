package ru.poporyadku.notifications

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import ru.poporyadku.domain.reminder.ReminderSyncResult
import ru.poporyadku.domain.reminder.ReminderTrigger

/**
 * `I6-P8` — отображение исхода синхронизации в решение resync-работы
 * (ITERATION_6_DESIGN.md, §9.4, I6-D30).
 */
class ResyncAttemptPolicyTest {

    private val scheduled = ReminderSyncResult.Scheduled(
        ReminderTrigger(LocalDate.of(2026, 9, 17), 9 * 60, Instant.parse("2026-09-17T06:00:00Z")),
    )

    /** `I6-P8`. Подтверждённая синхронизация — работа успешна, повторов нет. */
    @Test
    fun `I6-P8 a confirmed synchronization is done`() {
        assertEquals(ResyncAttempt.Done, ResyncAttemptPolicy.decide(scheduled, runAttemptCount = 0))
        assertEquals(
            ResyncAttempt.Done,
            ResyncAttemptPolicy.decide(ReminderSyncResult.Cancelled, runAttemptCount = 0),
        )
    }

    /** `I6-P8`. Отказ, пока попытки остались, — повтор с backoff WorkManager. */
    @Test
    fun `I6-P8 a failure retries while attempts remain`() {
        val failed = ReminderSyncResult.Failed(IllegalStateException("отказ"))

        (0 until ResyncAttemptPolicy.RESYNC_MAX_ATTEMPTS - 1).forEach { attempt ->
            assertEquals(
                "попытка $attempt",
                ResyncAttempt.Retry,
                ResyncAttemptPolicy.decide(failed, runAttemptCount = attempt),
            )
        }
    }

    /**
     * `I6-P8`. Отказ на последней попытке — `GiveUp`: работа завершается, чтобы
     * WorkManager не держал её вечно; расписание всё равно синхронизирует коллектор при
     * следующем старте `MainActivity`.
     */
    @Test
    fun `I6-P8 a failure on the last attempt gives up`() {
        val failed = ReminderSyncResult.Failed(IllegalStateException("отказ"))

        assertEquals(
            ResyncAttempt.GiveUp,
            ResyncAttemptPolicy.decide(failed, runAttemptCount = ResyncAttemptPolicy.RESYNC_MAX_ATTEMPTS - 1),
        )
        assertEquals(
            ResyncAttempt.GiveUp,
            ResyncAttemptPolicy.decide(failed, runAttemptCount = ResyncAttemptPolicy.RESYNC_MAX_ATTEMPTS + 5),
        )
    }

    /** `I6-P8`. `Failed` не даёт `Done` **ни при какой** попытке — отказ не успех. */
    @Test
    fun `I6-P8 a failure never yields Done`() {
        val failed = ReminderSyncResult.Failed(IllegalStateException("отказ"))

        (0..ResyncAttemptPolicy.RESYNC_MAX_ATTEMPTS + 5).forEach { attempt ->
            assertNotEquals(
                "попытка $attempt",
                ResyncAttempt.Done,
                ResyncAttemptPolicy.decide(failed, runAttemptCount = attempt),
            )
        }
    }
}
