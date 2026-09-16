package ru.poporyadku.domain.reminder

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.poporyadku.domain.assignment.Decision

/**
 * `I6-S4` — все строки таблицы 9.6 (ITERATION_6_DESIGN.md, I6-D33, **O6-1**).
 */
class ReminderEligibilityTest {

    private val today = LocalDate.of(2026, 9, 16)

    /** `I6-S4`. Новый набор — показать: задания действительно новые. */
    @Test
    fun `I6-S4 a new set allows the reminder`() {
        assertNull(ReminderEligibility.decide(Decision.NewSet("core-ru", 4), todayResult = null))
    }

    /** `I6-S4`. Перенос — показать: набор для продолжения существует. */
    @Test
    fun `I6-S4 a carry over allows the reminder`() {
        assertNull(
            ReminderEligibility.decide(
                Decision.CarryOver("core-ru", 4, fromDate = today.minusDays(1)),
                todayResult = null,
            ),
        )
    }

    /** `I6-S4`. Набор назначен, но ни один слот не закрыт — показать. */
    @Test
    fun `I6-S4 an assigned set with nothing done allows the reminder`() {
        assertNull(ReminderEligibility.decide(Decision.Assigned("core-ru", 4), todayResult = null))
        assertNull(
            ReminderEligibility.decide(
                Decision.Assigned("core-ru", 4),
                dayResult(today, completedCount = 0, isComplete = false),
            ),
        )
    }

    /**
     * `I6-S4`. Закрыт один или два слота — **не показывать** (**O6-1**): пользователь уже
     * вернулся сам, и напоминание о недоигранном дне читалось бы как давление за серию.
     */
    @Test
    fun `I6-S4 a partially completed day is skipped`() {
        listOf(1, 2).forEach { completed ->
            assertEquals(
                "закрыто слотов: $completed",
                DayNotReady.InProgress,
                ReminderEligibility.decide(
                    Decision.Assigned("core-ru", 4),
                    dayResult(today, completedCount = completed, isComplete = false),
                ),
            )
        }
    }

    /** `I6-S4`. День завершён — показывать нечего. */
    @Test
    fun `I6-S4 a completed day is skipped`() {
        assertEquals(
            DayNotReady.DayCompleted,
            ReminderEligibility.decide(
                Decision.Assigned("core-ru", 4),
                dayResult(today, completedCount = 3, isComplete = true),
            ),
        )
    }

    /**
     * `I6-S4`. Флаг `isComplete` и счётчик закрытых слотов рассматриваются оба: день,
     * помеченный завершённым при двух слотах, — тоже «завершён», а не «в процессе».
     */
    @Test
    fun `I6-S4 an is-complete flag wins over the counter`() {
        assertEquals(
            DayNotReady.DayCompleted,
            ReminderEligibility.decide(
                Decision.Assigned("core-ru", 4),
                dayResult(today, completedCount = 2, isComplete = true),
            ),
        )
    }

    /** `I6-S4`. Новый набор ещё не положен — «новое задание» было бы неправдой. */
    @Test
    fun `I6-S4 awaiting the next day is skipped`() {
        assertEquals(
            DayNotReady.AwaitingNextDay,
            ReminderEligibility.decide(Decision.AwaitingNextDay, todayResult = null),
        )
    }

    /** `I6-S4`. Контент исчерпан — новых заданий нет. */
    @Test
    fun `I6-S4 exhausted content is skipped`() {
        assertEquals(
            DayNotReady.ContentExhausted,
            ReminderEligibility.decide(Decision.ContentExhausted, todayResult = null),
        )
    }
}
