package ru.poporyadku.core.time

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

// ITERATION_2_DESIGN.md, §4: P10, P11. Чистый JVM, без Room и без Android SDK.
class TimeSnapshotTest {

    private val zone = ZoneId.of("Europe/Moscow")

    @Test
    fun `P10 - localDate and epochMillis are derived from the same instant at day boundaries`() {
        val endOfDay = LocalDate.of(2026, 9, 1).atTime(23, 59, 59, 999_000_000).atZone(zone).toInstant()
        val snapshotEnd = TimeSnapshot.of(endOfDay, zone)
        assertEquals(LocalDate.of(2026, 9, 1), snapshotEnd.localDate)
        assertEquals(endOfDay.toEpochMilli(), snapshotEnd.epochMillis)

        val startOfDay = LocalDate.of(2026, 9, 2).atStartOfDay(zone).toInstant()
        val snapshotStart = TimeSnapshot.of(startOfDay, zone)
        assertEquals(LocalDate.of(2026, 9, 2), snapshotStart.localDate)
        assertEquals(startOfDay.toEpochMilli(), snapshotStart.epochMillis)
    }

    @Test
    fun `P11 - noon survives round trip on a DST transition day`() {
        // Europe/Madrid переходит на летнее время в последнее воскресенье марта:
        // 2026-03-29 — полночь в этот день неоднозначна, полдень — нет.
        val dstZone = ZoneId.of("Europe/Madrid")
        val date = LocalDate.of(2026, 3, 29)
        val instant = date.atTime(LocalTime.NOON).atZone(dstZone).toInstant()

        val snapshot = TimeSnapshot.of(instant, dstZone)

        assertEquals(date, snapshot.localDate)
    }
}
