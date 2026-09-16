package ru.poporyadku.domain.reminder

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `I6-S1`, `I6-S2`, `I6-S3` — расчёт следующего срабатывания
 * (ITERATION_6_DESIGN.md, §9.2, I6-D28).
 */
class NextReminderTriggerTest {

    private val moscow = ZoneId.of("Europe/Moscow")
    private val berlin = ZoneId.of("Europe/Berlin")

    // --- I6-S1: сегодня, завтра, границы суток и календаря --------------------------------

    /** `I6-S1`. До выбранного времени цель — сегодня. */
    @Test
    fun `I6-S1 before the chosen time the target is today`() {
        val now = instant(LocalDate.of(2026, 9, 16), LocalTime.of(8, 59), moscow)

        val trigger = NextReminderTrigger.next(now, moscow, LocalTime.of(9, 0))

        assertEquals(LocalDate.of(2026, 9, 16), trigger.targetDate)
        assertEquals(MINUTE_9_00, trigger.minuteOfDay)
        assertEquals(instant(LocalDate.of(2026, 9, 16), LocalTime.of(9, 0), moscow), trigger.at)
    }

    /**
     * `I6-S1`. Ровно в выбранное время цель — **завтра**: работа, поставленная на
     * «сейчас», сработала бы немедленно и второй раз за те же сутки.
     */
    @Test
    fun `I6-S1 exactly at the chosen time the target is tomorrow`() {
        val now = instant(LocalDate.of(2026, 9, 16), LocalTime.of(9, 0), moscow)

        val trigger = NextReminderTrigger.next(now, moscow, LocalTime.of(9, 0))

        assertEquals(LocalDate.of(2026, 9, 17), trigger.targetDate)
        assertEquals(instant(LocalDate.of(2026, 9, 17), LocalTime.of(9, 0), moscow), trigger.at)
    }

    /** `I6-S1`. После выбранного времени — тоже завтра. */
    @Test
    fun `I6-S1 after the chosen time the target is tomorrow`() {
        val now = instant(LocalDate.of(2026, 9, 16), LocalTime.of(9, 1), moscow)

        val trigger = NextReminderTrigger.next(now, moscow, LocalTime.of(9, 0))

        assertEquals(LocalDate.of(2026, 9, 17), trigger.targetDate)
    }

    /** `I6-S1`. Полночь: 00:00 сегодня уже прошло — цель завтра, минута 0. */
    @Test
    fun `I6-S1 midnight is handled at both ends of the day`() {
        val justAfterMidnight = instant(LocalDate.of(2026, 9, 16), LocalTime.of(0, 0), moscow)

        val atMidnight = NextReminderTrigger.next(justAfterMidnight, moscow, LocalTime.MIDNIGHT)
        assertEquals(LocalDate.of(2026, 9, 17), atMidnight.targetDate)
        assertEquals(0, atMidnight.minuteOfDay)

        // 23:59 того же дня ещё впереди.
        val lateEvening = NextReminderTrigger.next(justAfterMidnight, moscow, LocalTime.of(23, 59))
        assertEquals(LocalDate.of(2026, 9, 16), lateEvening.targetDate)
        assertEquals(MAX_MINUTE, lateEvening.minuteOfDay)
    }

    /** `I6-S1`. Конец месяца и конец года: 31 декабря 23:59 → 1 января. */
    @Test
    fun `I6-S1 month and year boundaries roll over`() {
        val endOfMonth = instant(LocalDate.of(2026, 9, 30), LocalTime.of(10, 0), moscow)
        assertEquals(
            LocalDate.of(2026, 10, 1),
            NextReminderTrigger.next(endOfMonth, moscow, LocalTime.of(9, 0)).targetDate,
        )

        val endOfYear = instant(LocalDate.of(2026, 12, 31), LocalTime.of(23, 59), moscow)
        assertEquals(
            LocalDate.of(2027, 1, 1),
            NextReminderTrigger.next(endOfYear, moscow, LocalTime.of(23, 59)).targetDate,
        )
    }

    // --- I6-S2: DST -----------------------------------------------------------------------

    /**
     * `I6-S2`. Переход на летнее время: 2026-03-29 в Europe/Berlin локального 02:30 не
     * существует — `ZonedDateTime` сдвигает момент вперёд на длину разрыва, и напоминание
     * приходит в 03:30 местного того же дня.
     */
    @Test
    fun `I6-S2 a time inside the DST gap moves forward by the gap`() {
        val now = instant(LocalDate.of(2026, 3, 29), LocalTime.of(1, 0), berlin)

        val trigger = NextReminderTrigger.next(now, berlin, LocalTime.of(2, 30))

        assertEquals(LocalDate.of(2026, 3, 29), trigger.targetDate)
        assertEquals(
            LocalTime.of(3, 30),
            trigger.at.atZone(berlin).toLocalTime(),
        )
        // Минута суток — выбранная пользователем, а не фактическая: настройка не менялась.
        assertEquals(MINUTE_2_30, trigger.minuteOfDay)
    }

    /**
     * `I6-S2`. Переход на зимнее время: локальное 02:30 встречается дважды, берётся
     * **более раннее** смещение (CEST, +02:00) — напоминание не откладывается на час.
     */
    @Test
    fun `I6-S2 a time inside the DST overlap takes the earlier offset`() {
        val now = instant(LocalDate.of(2026, 10, 25), LocalTime.of(0, 30), berlin)

        val trigger = NextReminderTrigger.next(now, berlin, LocalTime.of(2, 30))

        val earlier = ZonedDateTime.of(LocalDateTime.of(2026, 10, 25, 2, 30), berlin)
            .withEarlierOffsetAtOverlap()
        assertEquals(earlier.toInstant(), trigger.at)
    }

    /** `I6-S2`. В зоне без перехода между последовательными целями ровно сутки. */
    @Test
    fun `I6-S2 without a DST transition consecutive targets are exactly 24 hours apart`() {
        var now = instant(LocalDate.of(2026, 9, 16), LocalTime.of(8, 0), moscow)
        val first = NextReminderTrigger.next(now, moscow, LocalTime.of(9, 0))

        // «Сработали» ровно в момент цели — следующая считается от него.
        now = first.at
        val second = NextReminderTrigger.next(now, moscow, LocalTime.of(9, 0))

        assertEquals(Duration.ofHours(24), Duration.between(first.at, second.at))
    }

    // --- I6-S3: смена зоны ----------------------------------------------------------------

    /**
     * `I6-S3`. Один и тот же момент в двух зонах даёт разные моменты цели при одном и том
     * же местном времени: пользователь выбирал «9:00 по своим часам».
     */
    @Test
    fun `I6-S3 the same instant in two zones yields different targets for the same local time`() {
        val novosibirsk = ZoneId.of("Asia/Novosibirsk")
        val now = instant(LocalDate.of(2026, 9, 16), LocalTime.of(5, 0), moscow)

        val inMoscow = NextReminderTrigger.next(now, moscow, LocalTime.of(9, 0))
        val inNovosibirsk = NextReminderTrigger.next(now, novosibirsk, LocalTime.of(9, 0))

        assertEquals(LocalTime.of(9, 0), inMoscow.at.atZone(moscow).toLocalTime())
        assertEquals(LocalTime.of(9, 0), inNovosibirsk.at.atZone(novosibirsk).toLocalTime())
        // Новосибирск на 4 часа восточнее: его 9:00 наступает раньше — и уже прошло,
        // поэтому цель там на следующий день.
        assertEquals(LocalDate.of(2026, 9, 16), inMoscow.targetDate)
        assertEquals(LocalDate.of(2026, 9, 17), inNovosibirsk.targetDate)
        assert(inMoscow.at != inNovosibirsk.at) { "моменты обязаны различаться" }
    }

    private fun instant(date: LocalDate, time: LocalTime, zone: ZoneId) =
        date.atTime(time).atZone(zone).toInstant()

    private companion object {
        const val MINUTE_9_00 = 9 * 60
        const val MINUTE_2_30 = 2 * 60 + 30
        const val MAX_MINUTE = 23 * 60 + 59
    }
}
