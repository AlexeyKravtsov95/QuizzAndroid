package ru.poporyadku.core.time

import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

// ITERATION_2_DESIGN.md, §4: реализует ClockProvider/DateProvider из src/main и не
// упоминает variant-specific типы, поэтому законно живёт в общем src/test.
class FakeClockProvider(private var value: Clock) : ClockProvider, DateProvider {
    override fun clock(): Clock = value
    override fun today(): LocalDate = now().localDate

    fun setDate(date: LocalDate, zone: ZoneId = value.zone) {
        value = Clock.fixed(date.atTime(LocalTime.NOON).atZone(zone).toInstant(), zone)
    }
}
