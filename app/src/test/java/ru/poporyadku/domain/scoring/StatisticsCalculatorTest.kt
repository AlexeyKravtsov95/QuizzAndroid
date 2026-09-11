package ru.poporyadku.domain.scoring

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.poporyadku.core.model.DayResult
import ru.poporyadku.domain.model.Statistics

/**
 * `StatisticsCalculator` — ITERATION_5_DESIGN.md, §3.5, §6.2, §10.1: `I5-S1`…`I5-S5`.
 *
 * Чистый JVM. Ожидаемые значения — ручные расчёты примеров 1–4 раздела 3.5, а не вывод
 * самой функции; округление дополнительно сверяется с `BigDecimal` и `HALF_UP` —
 * независимой от целочисленной формулы арифметикой.
 */
class StatisticsCalculatorTest {

    private val today = LocalDate.of(2026, 8, 7)

    private fun date(day: Int): LocalDate = LocalDate.of(2026, 8, day)

    private fun complete(date: LocalDate, score: Int) = DayResult(
        localDate = date,
        totalScore = score,
        completedCount = 3,
        isComplete = true,
        completedAt = 1L,
    )

    private fun partial(date: LocalDate, completedCount: Int, score: Int) = DayResult(
        localDate = date,
        totalScore = score,
        completedCount = completedCount,
        isComplete = false,
        completedAt = null,
    )

    /** Пример 1 раздела 3.5. */
    private val example1 = listOf(
        complete(date(1), 15),
        complete(date(2), 12),
        partial(date(3), completedCount = 2, score = 8),
        complete(date(5), 18),
        complete(date(6), 11),
        partial(date(7), completedCount = 1, score = 5),
    )

    // --- I5-S1 -------------------------------------------------------------------

    @Test
    fun `I5-S1 - example 1 gives 6 played, average 14,0, best 18 and streaks 2 of 2`() {
        val statistics = StatisticsCalculator.of(example1, today)

        assertEquals(
            Statistics(
                playedDays = 6,
                completedDays = 4,
                // (20 · 56 + 4) div 8 = 140 → «14,0»
                averageTenths = 140,
                bestDayScore = 18,
                streaks = Streaks(current = 2, best = 2),
            ),
            statistics,
        )
        // Среднее по всем сыгранным было бы 69 / 6 = 11,5 — отклонено (O5-3).
        assertNotEquals(115, statistics.averageTenths)
    }

    @Test
    fun `I5-S1 - input order does not matter`() {
        val expected = StatisticsCalculator.of(example1, today)

        assertEquals(expected, StatisticsCalculator.of(example1.reversed(), today))
        assertEquals(expected, StatisticsCalculator.of(example1.sortedBy { it.totalScore }, today))
    }

    // --- I5-S2 -------------------------------------------------------------------

    @Test
    fun `I5-S2 - empty history gives zeros, no average and no streaks`() {
        assertEquals(
            Statistics(
                playedDays = 0,
                completedDays = 0,
                averageTenths = null,
                bestDayScore = 0,
                streaks = Streaks(current = 0, best = 0),
            ),
            StatisticsCalculator.of(emptyList(), today),
        )
    }

    // --- I5-S3 -------------------------------------------------------------------

    @Test
    fun `I5-S3 - only an incomplete day - played, no average, best 6, no streaks`() {
        // Пример 3: 08-06 — одна попытка, 6 баллов.
        val statistics = StatisticsCalculator.of(listOf(partial(date(6), completedCount = 1, score = 6)), today)

        assertEquals(
            Statistics(
                playedDays = 1,
                completedDays = 0,
                averageTenths = null,
                bestDayScore = 6,
                streaks = Streaks(current = 0, best = 0),
            ),
            statistics,
        )
    }

    @Test
    fun `I5-S3 - several incomplete days never enter the average but set the best day`() {
        val statistics = StatisticsCalculator.of(
            listOf(
                partial(date(4), completedCount = 2, score = 4),
                partial(date(5), completedCount = 1, score = 6),
                partial(date(6), completedCount = 2, score = 3),
            ),
            today,
        )

        assertEquals(3, statistics.playedDays)
        assertEquals(0, statistics.completedDays)
        assertNull(statistics.averageTenths)
        assertEquals(6, statistics.bestDayScore)
        assertEquals(Streaks(current = 0, best = 0), statistics.streaks)
    }

    // --- I5-S4 -------------------------------------------------------------------

    @Test
    fun `I5-S4 - a future completed date counts in played, average and best, but not in the current streak`() {
        // Пример 2: завершены 08-05, 08-06 и будущая 08-09; сегодня 08-07.
        val statistics = StatisticsCalculator.of(
            listOf(complete(date(5), 10), complete(date(6), 14), complete(date(9), 17)),
            today,
        )

        assertEquals(
            Statistics(
                playedDays = 3,
                completedDays = 3,
                // (20 · 41 + 3) div 6 = 137 → «13,7»
                averageTenths = 137,
                bestDayScore = 17,
                // Текущая: 08-09 > today отброшена, вчера 08-06 и 08-05 → 2; лучшая: 08-05…08-06 = 2.
                streaks = Streaks(current = 2, best = 2),
            ),
            statistics,
        )
    }

    @Test
    fun `I5-S4 - future dates may form the best streak and an incomplete future day may set the best score`() {
        val statistics = StatisticsCalculator.of(
            listOf(
                complete(date(6), 9),
                complete(date(10), 11),
                complete(date(11), 11),
                complete(date(12), 11),
                // Будущий незавершённый: в played и в лучшем дне, вне среднего и серий.
                partial(date(13), completedCount = 2, score = 12),
            ),
            today,
        )

        assertEquals(5, statistics.playedDays)
        assertEquals(4, statistics.completedDays)
        // (20 · 42 + 4) div 8 = 105 → «10,5»; с незавершённым было бы 54 / 5.
        assertEquals(105, statistics.averageTenths)
        assertEquals(12, statistics.bestDayScore)
        // Текущая заканчивается вчерашним 08-06; лучшая — будущий отрезок 08-10…08-12.
        assertEquals(Streaks(current = 1, best = 3), statistics.streaks)
    }

    @Test
    fun `I5-S4 - only future completed dates give no current streak`() {
        val statistics = StatisticsCalculator.of(listOf(complete(date(8), 18), complete(date(9), 18)), today)

        assertEquals(2, statistics.playedDays)
        assertEquals(180, statistics.averageTenths)
        assertEquals(18, statistics.bestDayScore)
        // 08-08 — завтра: в текущую серию не входит, в лучшую — да.
        assertEquals(Streaks(current = 0, best = 2), statistics.streaks)
    }

    // --- I5-S5 -------------------------------------------------------------------

    /** `completedCount` завершённых дней с общей суммой [sum]; каждый день — не больше 18. */
    private fun completedDaysWithSum(completedCount: Int, sum: Int, from: LocalDate = date(1)): List<DayResult> {
        require(sum in 0..completedCount * PairwiseScoreCalculator.MAX_PER_DAY)
        var rest = sum
        return (0 until completedCount).map { index ->
            val score = minOf(rest, PairwiseScoreCalculator.MAX_PER_DAY)
            rest -= score
            complete(from.plusDays(index.toLong()), score)
        }
    }

    /** Независимая арифметика: точное частное, одна десятичная, HALF_UP. */
    private fun halfUpTenths(sum: Int, count: Int): Int =
        BigDecimal(sum).divide(BigDecimal(count), 1, RoundingMode.HALF_UP).unscaledValue().intValueExact()

    private fun averageOf(completedCount: Int, sum: Int): Int? =
        StatisticsCalculator.of(completedDaysWithSum(completedCount, sum), today).averageTenths

    @Test
    fun `I5-S5 - half-up rounding of the design examples`() {
        assertEquals(3, averageOf(completedCount = 4, sum = 1)) // 0,25 → 0,3
        assertEquals(8, averageOf(completedCount = 4, sum = 3)) // 0,75 → 0,8
        assertEquals(135, averageOf(completedCount = 2, sum = 27)) // 13,5 → 13,5
        assertEquals(150, averageOf(completedCount = 1, sum = 15)) // 15 → 15,0
        assertEquals(150, averageOf(completedCount = 3, sum = 45)) // 15 → 15,0
    }

    @Test
    fun `I5-S5 - a half at the second decimal rounds up, just below it rounds down`() {
        assertEquals(1, averageOf(completedCount = 20, sum = 1)) // 0,05 → 0,1
        assertEquals(135, averageOf(completedCount = 20, sum = 269)) // 13,45 → 13,5
        assertEquals(2, averageOf(completedCount = 25, sum = 6)) // 0,24 → 0,2
        assertEquals(7, averageOf(completedCount = 3, sum = 2)) // 0,666… → 0,7
        assertEquals(3, averageOf(completedCount = 3, sum = 1)) // 0,333… → 0,3
    }

    @Test
    fun `I5-S5 - the integer formula equals BigDecimal HALF_UP for every sum up to 40 completed days`() {
        for (count in 1..40) {
            for (sum in 0..count * PairwiseScoreCalculator.MAX_PER_DAY) {
                assertEquals("sum=$sum, count=$count", halfUpTenths(sum, count), averageOf(count, sum))
            }
        }
    }

    @Test
    fun `I5-S5 - incomplete days change neither numerator nor denominator`() {
        val completed = completedDaysWithSum(completedCount = 4, sum = 1)
        val withPartials = completed +
            partial(date(20), completedCount = 2, score = 12) +
            partial(date(21), completedCount = 1, score = 0)

        assertEquals(3, StatisticsCalculator.of(withPartials, today).averageTenths)
    }

    @Test
    fun `I5-S5 - histories of 1 and 1000 days do not overflow`() {
        assertEquals(180, averageOf(completedCount = 1, sum = 18))
        assertEquals(0, averageOf(completedCount = 1, sum = 0))
        assertEquals(180, averageOf(completedCount = 1000, sum = 18_000))
        assertEquals(0, averageOf(completedCount = 1000, sum = 0))

        val start = LocalDate.of(2024, 1, 1)
        val mixed = (0 until 1000).map { index -> complete(start.plusDays(index.toLong()), index % 19) }
        val statistics = StatisticsCalculator.of(mixed, start.plusDays(999))

        assertEquals(1000, statistics.playedDays)
        assertEquals(halfUpTenths(mixed.sumOf { it.totalScore }, 1000), statistics.averageTenths)
        assertEquals(18, statistics.bestDayScore)
        assertEquals(Streaks(current = 1000, best = 1000), statistics.streaks)
    }
}
