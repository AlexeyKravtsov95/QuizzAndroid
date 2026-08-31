package ru.poporyadku.core.time

import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

// ITERATION_2_DESIGN.md, §4: P17. Живёт в src/testRelease — SystemClockProvider виден
// только релизному варианту; запускается testReleaseUnitTest.
class SystemClockProviderTest {

    @Test
    fun `P17 - zone follows system default`() {
        val original = TimeZone.getDefault()
        // ОДИН экземпляр на весь тест: он создаётся до первой смены зоны и переживает обе.
        val provider = SystemClockProvider()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))
            assertEquals(ZoneId.of("Europe/Moscow"), provider.clock().zone)

            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Vladivostok"))
            assertEquals(ZoneId.of("Asia/Vladivostok"), provider.clock().zone)
        } finally {
            TimeZone.setDefault(original) // обязательно: иначе зона утечёт в другие тесты
        }
    }
}
