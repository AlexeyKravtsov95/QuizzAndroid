package ru.poporyadku.core.time

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// ITERATION_2_DESIGN.md, D-16: обычный class, а не data class — copy() не генерируется
// вовсе, поэтому единственный путь получить значение — фабрика of(), и localDate с
// epochMillis не могут разойтись через полночь (оба выводятся из одного Instant).
class TimeSnapshot private constructor(
    val localDate: LocalDate,
    val epochMillis: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is TimeSnapshot && other.localDate == localDate && other.epochMillis == epochMillis

    override fun hashCode(): Int = 31 * localDate.hashCode() + epochMillis.hashCode()

    override fun toString(): String = "TimeSnapshot($localDate, $epochMillis)"

    companion object {
        /** Единственный способ построения: один момент + одна зона.
         *  LocalDate.ofInstant(Instant, ZoneId) требует API 34 без desugaring
         *  (minSdk проекта — 26); instant.atZone(zone).toLocalDate() даёт то же
         *  значение и доступен с API 26. */
        fun of(instant: Instant, zone: ZoneId): TimeSnapshot =
            TimeSnapshot(
                localDate = instant.atZone(zone).toLocalDate(),
                epochMillis = instant.toEpochMilli(),
            )

        fun of(clock: Clock): TimeSnapshot = of(clock.instant(), clock.zone)
    }
}
