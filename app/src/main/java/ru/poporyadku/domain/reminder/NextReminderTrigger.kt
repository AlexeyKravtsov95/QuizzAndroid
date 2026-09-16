package ru.poporyadku.domain.reminder

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Расчёт следующего срабатывания (ITERATION_6_DESIGN.md, §9.2, I6-D28).
 *
 * Ближайшее вхождение локального [time] **строго после** `now` в зоне `zone`. Момент и
 * зона приходят из одного `TimeSnapshot` (`ClockProvider.now()`): двух независимых
 * обращений к часам здесь нет и быть не может — оба значения параметры.
 *
 * Почему `ZonedDateTime.of`, а не «прошлое срабатывание + 24 часа»:
 * - **пропуск DST**: локального времени внутри разрыва не существует, и `ZonedDateTime`
 *   сдвигает результат вперёд на длину разрыва (Europe/Berlin, 2026-03-29 02:30 → 03:30
 *   местного того же дня);
 * - **перекрытие DST**: локальное время встречается дважды, и `ZonedDateTime` берёт
 *   более раннее смещение — напоминание не откладывается на лишний час;
 * - фиксированные сутки в обоих случаях уехали бы относительно настенных часов.
 *
 * Равенство моментов (`now` ровно в выбранное время) даёт **следующий** день: работа,
 * поставленная на «сейчас», сработала бы немедленно и второй раз за те же сутки.
 */
object NextReminderTrigger {

    fun next(now: Instant, zone: ZoneId, time: LocalTime): ReminderTrigger {
        val today = now.atZone(zone).toLocalDate()
        // Разрыв и перекрытие DST разрешает сам ZonedDateTime.of — своей ветки для них нет.
        var date = today
        var at = ZonedDateTime.of(date, time, zone).toInstant()
        if (!at.isAfter(now)) {
            date = today.plusDays(1)
            at = ZonedDateTime.of(date, time, zone).toInstant()
        }
        return ReminderTrigger(
            targetDate = date,
            minuteOfDay = time.hour * MINUTES_PER_HOUR + time.minute,
            at = at,
        )
    }

    private const val MINUTES_PER_HOUR = 60
}
