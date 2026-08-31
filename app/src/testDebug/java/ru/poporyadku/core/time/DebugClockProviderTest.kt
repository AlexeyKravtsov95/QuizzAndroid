package ru.poporyadku.core.time

import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

// ITERATION_2_DESIGN.md, §4: P16. Живёт в src/testDebug — DebugClockProvider виден
// только debug-варианту; запускается testDebugUnitTest.
class DebugClockProviderTest {

    @Test
    fun `P16 - unfixed clock follows the zone, fixed clock does not`() {
        val original = TimeZone.getDefault()
        // ОДИН экземпляр на весь тест: создаётся до первой смены зоны и переживает обе.
        val provider = DebugClockProvider()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))
            assertEquals(ZoneId.of("Europe/Moscow"), provider.clock().zone)

            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Vladivostok"))
            assertEquals(ZoneId.of("Asia/Vladivostok"), provider.clock().zone)

            provider.setDate(LocalDate.of(2026, 9, 1), ZoneId.of("Europe/Moscow"))
            assertEquals(ZoneId.of("Europe/Moscow"), provider.clock().zone)
            assertEquals(LocalDate.of(2026, 9, 1), provider.today())

            // Зафиксировано: смена системной зоны больше не влияет на часы.
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Vladivostok"))
            assertEquals(ZoneId.of("Europe/Moscow"), provider.clock().zone)

            provider.reset()
            assertEquals(ZoneId.of("Asia/Vladivostok"), provider.clock().zone)
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
