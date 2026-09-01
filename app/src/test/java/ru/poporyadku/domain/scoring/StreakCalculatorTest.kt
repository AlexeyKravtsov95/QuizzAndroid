package ru.poporyadku.domain.scoring

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// ITERATION_3_DESIGN.md, §19: I3-K1 — I3-K10. Чистый JVM-тест.
// StreakCache здесь не участвует: его единственный писатель появляется в PR 3B.
class StreakCalculatorTest {

    private val today = LocalDate.of(2026, 9, 1)

    private fun days(vararg offsets: Long): List<LocalDate> = offsets.map { today.minusDays(it) }

    /** Все сценарии проверяются и на инвариант I3-K10. */
    private fun streaks(dates: Collection<LocalDate>, day: LocalDate = today): Streaks {
        val result = StreakCalculator.streaks(dates, day)

        assertEquals("current расходится с currentStreak", StreakCalculator.currentStreak(dates, day), result.current)
        assertEquals("best расходится с bestStreak", StreakCalculator.bestStreak(dates), result.best)
        assertTrue("I3-K10: best >= current нарушен на $dates", result.best >= result.current)
        return result
    }

    @Test
    fun `I3-K1 - unbroken streak ending today`() {
        val result = streaks(days(0, 1, 2, 3))

        assertEquals(4, result.current)
        assertEquals(4, result.best)
    }

    @Test
    fun `I3-K2 - unbroken streak ending yesterday still counts`() {
        // Сегодняшний день ещё можно завершить — до конца дня серия не обнуляется.
        val result = streaks(days(1, 2, 3))

        assertEquals(3, result.current)
        assertEquals(3, result.best)
    }

    @Test
    fun `I3-K3 - gap - last completed day is the day before yesterday`() {
        val result = streaks(days(2, 3, 4, 5))

        assertEquals(0, result.current)
        assertEquals(4, result.best)
    }

    @Test
    fun `I3-K4 - gap in the middle of history`() {
        // Хвост: сегодня и вчера. Раньше — отрезок из четырёх дней, разделённый пропуском.
        val result = streaks(days(0, 1, 4, 5, 6, 7))

        assertEquals(2, result.current)
        assertEquals(4, result.best)
    }

    @Test
    fun `I3-K5 - empty history`() {
        val result = streaks(emptyList())

        assertEquals(0, result.current)
        assertEquals(0, result.best)
    }

    @Test
    fun `I3-K6 - a single completed day - today, yesterday, long ago`() {
        assertEquals(1, streaks(days(0)).current)
        assertEquals(1, streaks(days(0)).best)

        assertEquals(1, streaks(days(1)).current)
        assertEquals(1, streaks(days(1)).best)

        assertEquals(0, streaks(days(40)).current)
        assertEquals(1, streaks(days(40)).best)
    }

    @Test
    fun `I3-K7 - year boundary`() {
        val newYear = LocalDate.of(2027, 1, 1)
        val dates = listOf(
            LocalDate.of(2026, 12, 30),
            LocalDate.of(2026, 12, 31),
            newYear,
        )

        val result = streaks(dates, newYear)

        assertEquals(3, result.current)
        assertEquals(3, result.best)
    }

    @Test
    fun `I3-K8 - future dates are ignored by current but counted by best`() {
        // Часы переведены вперёд и обратно: записи есть, серия не может заканчиваться
        // в будущем, но фактически завершённые дни из best не исчезают.
        val dates = listOf(
            today.plusDays(1),
            today.plusDays(2),
            today.plusDays(3),
            today.minusDays(4),
            today.minusDays(5),
        )

        val result = streaks(dates)

        assertEquals(0, result.current)
        assertEquals(3, result.best)
    }

    @Test
    fun `I3-K8 - future dates do not extend a streak that ends today`() {
        val result = streaks(days(0, 1) + listOf(today.plusDays(1)))

        assertEquals(2, result.current)
        // Завтрашняя дата примыкает к сегодняшней и удлиняет отрезок в best.
        assertEquals(3, result.best)
        assertTrue(result.best >= result.current)
    }

    @Test
    fun `I3-K9 - unsorted input with duplicates matches the normalised input`() {
        val normalised = days(0, 1, 2, 5, 6)
        val messy = listOf(
            today.minusDays(5),
            today.minusDays(1),
            today,
            today.minusDays(6),
            today.minusDays(1),
            today.minusDays(2),
            today,
            today.minusDays(5),
        )

        assertEquals(streaks(normalised), streaks(messy))
        assertEquals(3, streaks(messy).current)
        assertEquals(3, streaks(messy).best)
    }

    @Test
    fun `I3-K10 - best is never smaller than current across every scenario`() {
        val scenarios = listOf(
            emptyList(),
            days(0),
            days(1),
            days(40),
            days(0, 1, 2, 3),
            days(1, 2, 3),
            days(2, 3, 4, 5),
            days(0, 1, 4, 5, 6, 7),
            days(0, 1) + listOf(today.plusDays(1), today.plusDays(2)),
            listOf(today.plusDays(5)),
        )

        for (dates in scenarios) {
            val result = streaks(dates)
            assertTrue("$dates", result.best >= result.current)
        }
    }
}
