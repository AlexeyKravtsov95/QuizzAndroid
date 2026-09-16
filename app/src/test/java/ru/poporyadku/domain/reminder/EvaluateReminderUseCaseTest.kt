package ru.poporyadku.domain.reminder

import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.poporyadku.domain.assignment.Decision

/**
 * `I6-W2` (часть) — проверки перед показом: порядок, только чтение, отказ базы
 * (ITERATION_6_DESIGN.md, §9.6, I6-D32, I6-D33).
 */
class EvaluateReminderUseCaseTest {

    private val today = LocalDate.of(2026, 9, 16)
    private val nine = LocalTime.of(9, 0)
    private val minuteNine = 9 * 60

    /** Все проверки пройдены — показать, следующая цель на завтра. */
    @Test
    fun `everything passes yields Show and tomorrow as the next target`() = runTest {
        val verdict = evaluate().invoke(today, minuteNine)

        assertTrue(verdict is ReminderVerdict.Show)
        assertEquals(today.plusDays(1), verdict.nextTrigger?.targetDate)
    }

    /**
     * Проверка 1. Напоминание выключено — цели нет: работа не продолжается. База при
     * этом не читается вовсе.
     */
    @Test
    fun `a disabled reminder stops the chain and never touches the database`() = runTest {
        val assignments = ReadOnlyAssignments(Decision.NewSet("core-ru", 4), snapshotAt(today, nine))

        val verdict = evaluate(
            preferences = ReadOnlyPreferences(preferencesOf(reminderEnabled = false)),
            assignments = assignments,
        ).invoke(today, minuteNine)

        assertEquals(ReminderVerdict.Skip(SkipReason.Disabled, nextTrigger = null), verdict)
        assertEquals("базу читать не за чем", 0, assignments.peeks)
    }

    /** Проверка 2. Время изменили после планирования — цель по новым настройкам. */
    @Test
    fun `a stale minute skips and retargets by the current settings`() = runTest {
        val verdict = evaluate(
            preferences = ReadOnlyPreferences(preferencesOf(reminderTime = LocalTime.of(21, 0))),
            nowTime = LocalTime.of(9, 0),
        ).invoke(today, minuteNine)

        assertEquals(SkipReason.StaleTime, (verdict as ReminderVerdict.Skip).reason)
        assertEquals(today, verdict.nextTrigger?.targetDate)
        assertEquals(21 * 60, verdict.nextTrigger?.minuteOfDay)
    }

    /** Проверка 3. Работа сработала после смены даты — вчерашнее обещание не исполняется. */
    @Test
    fun `a stale date skips and retargets`() = runTest {
        val verdict = evaluate(nowTime = LocalTime.of(9, 30)).invoke(today.minusDays(1), minuteNine)

        assertEquals(SkipReason.StaleDate, (verdict as ReminderVerdict.Skip).reason)
        assertEquals(today.plusDays(1), verdict.nextTrigger?.targetDate)
    }

    /**
     * Проверка 4. Момент ещё не наступил (часы перевели вперёд, WorkManager отсчитал
     * задержку по настенным часам) — цель остаётся **той же**.
     */
    @Test
    fun `an early run keeps the same target`() = runTest {
        val verdict = evaluate(nowTime = LocalTime.of(8, 30)).invoke(today, minuteNine)

        assertEquals(SkipReason.Early, (verdict as ReminderVerdict.Skip).reason)
        assertEquals(today, verdict.nextTrigger?.targetDate)
        assertEquals(minuteNine, verdict.nextTrigger?.minuteOfDay)
    }

    /** Проверка 5. Все три причины недоступности одинаково означают «показа нет». */
    @Test
    fun `every unavailability skips without showing`() = runTest {
        listOf(
            NotificationAvailability.RuntimePermissionMissing,
            NotificationAvailability.AppNotificationsDisabled,
            NotificationAvailability.ChannelDisabled,
        ).forEach { status ->
            val assignments = ReadOnlyAssignments(Decision.NewSet("core-ru", 4), snapshotAt(today, nine))
            val verdict = evaluate(
                access = FakeNotificationAccess(status),
                assignments = assignments,
            ).invoke(today, minuteNine)

            assertEquals(SkipReason.NoAccess(status), (verdict as ReminderVerdict.Skip).reason)
            assertEquals(today.plusDays(1), verdict.nextTrigger?.targetDate)
            assertEquals("доступа нет — база не нужна", 0, assignments.peeks)
        }
    }

    /** Проверка 6. Состояние дня не позволяет обещать новые задания. */
    @Test
    fun `a day in progress skips and retargets to tomorrow`() = runTest {
        val verdict = evaluate(
            assignments = ReadOnlyAssignments(Decision.Assigned("core-ru", 4), snapshotAt(today, nine)),
            progress = ReadOnlyProgress(dayResult(today, completedCount = 1, isComplete = false)),
        ).invoke(today, minuteNine)

        assertEquals(
            SkipReason.DayNotReadyYet(DayNotReady.InProgress),
            (verdict as ReminderVerdict.Skip).reason,
        )
        assertEquals(today.plusDays(1), verdict.nextTrigger?.targetDate)
    }

    /**
     * `I6-W2`. Отказ чтения базы выходит наружу: превращать его в «показать» нельзя —
     * решение принимает [ReminderRun], который отделит ошибку оценки от планирования.
     */
    @Test
    fun `a failing peek propagates instead of pretending the day is ready`() = runTest {
        val failure = IllegalStateException("база недоступна")
        val useCase = evaluate(
            assignments = ReadOnlyAssignments(
                Decision.NewSet("core-ru", 4),
                snapshotAt(today, nine),
                peekFailure = failure,
            ),
        )

        val thrown = runCatching { useCase(today, minuteNine) }.exceptionOrNull()

        assertEquals(failure, thrown)
    }

    /** Запасная цель считается без базы: отказ, уронивший оценку, здесь не повторяется. */
    @Test
    fun `the fallback target reads settings and clock only`() = runTest {
        val assignments = ReadOnlyAssignments(
            Decision.NewSet("core-ru", 4),
            snapshotAt(today, nine),
            peekFailure = IllegalStateException("база недоступна"),
        )

        val fallback = evaluate(assignments = assignments, nowTime = LocalTime.of(10, 0))
            .fallbackNextTrigger()

        assertNotNull(fallback)
        assertEquals(today.plusDays(1), fallback?.targetDate)
        assertEquals(0, assignments.peeks)
    }

    /** Выключенное напоминание запасной цели не даёт: продолжать цепочку нечем. */
    @Test
    fun `the fallback is null when the reminder is disabled`() = runTest {
        val fallback = evaluate(
            preferences = ReadOnlyPreferences(preferencesOf(reminderEnabled = false)),
        ).fallbackNextTrigger()

        assertNull(fallback)
    }

    private fun evaluate(
        preferences: ReadOnlyPreferences = ReadOnlyPreferences(preferencesOf()),
        access: FakeNotificationAccess = FakeNotificationAccess(),
        assignments: ReadOnlyAssignments = ReadOnlyAssignments(
            Decision.NewSet("core-ru", 4),
            snapshotAt(today, nine),
        ),
        progress: ReadOnlyProgress = ReadOnlyProgress(),
        nowTime: LocalTime = LocalTime.of(9, 0),
    ) = EvaluateReminderUseCase(
        preferences = preferences,
        clock = CountingClock(clockAt(today, nowTime)),
        access = access,
        assignments = assignments,
        progress = progress,
    )
}
