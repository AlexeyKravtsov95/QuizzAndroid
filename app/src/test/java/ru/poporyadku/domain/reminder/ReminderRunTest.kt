package ru.poporyadku.domain.reminder

import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.poporyadku.domain.assignment.Decision

/**
 * `I6-W1`…`I6-W4` — одно срабатывание worker'а (ITERATION_6_DESIGN.md, §9.6, §9.8,
 * I6-D32, I6-D49).
 */
class ReminderRunTest {

    private val today = LocalDate.of(2026, 9, 16)
    private val nine = LocalTime.of(9, 0)
    private val minuteNine = 9 * 60

    // --- I6-W1: таблица исходов -----------------------------------------------------------

    /** `I6-W1`. Всё прошло: один показ и перепланирование на завтра режимом `AfterCurrent`. */
    @Test
    fun `I6-W1 a passing evaluation shows once and reschedules after the current work`() = runTest {
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()

        val report = run(notifier = notifier, scheduler = scheduler)
            .first.invoke(today, minuteNine, TEST_WORK_ID)

        assertEquals(1, notifier.shows)
        assertEquals(1, scheduler.scheduled.size)
        val (trigger, mode) = scheduler.scheduled.single()
        assertEquals(today.plusDays(1), trigger.targetDate)
        assertEquals(ScheduleMode.AfterCurrent(TEST_WORK_ID), mode)
        assertEquals(
            ReminderRun.Report(null, rescheduled = true, rescheduleFailure = null),
            report,
        )
    }

    /** `I6-W1`. Выключенное напоминание: ни показа, ни перепланирования. */
    @Test
    fun `I6-W1 a disabled reminder neither shows nor reschedules`() = runTest {
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()

        val report = run(
            preferences = ReadOnlyPreferences(preferencesOf(reminderEnabled = false)),
            notifier = notifier,
            scheduler = scheduler,
        ).first.invoke(today, minuteNine, TEST_WORK_ID)

        assertEquals(0, notifier.shows)
        assertTrue(scheduler.scheduled.isEmpty())
        assertFalse(report.rescheduled)
        assertNull(report.evaluationFailure)
    }

    /** `I6-W1`. Пропуск с целью: показа нет, цепочка продолжается. */
    @Test
    fun `I6-W1 every skip with a target reschedules without showing`() = runTest {
        val cases = listOf(
            "устаревшее время" to run(
                preferences = ReadOnlyPreferences(preferencesOf(reminderTime = LocalTime.of(21, 0))),
            ),
            "нет разрешения" to run(access = FakeNotificationAccess(NotificationAvailability.RuntimePermissionMissing)),
            "уведомления выключены" to run(access = FakeNotificationAccess(NotificationAvailability.AppNotificationsDisabled)),
            "канал заглушён" to run(access = FakeNotificationAccess(NotificationAvailability.ChannelDisabled)),
            "день завершён" to run(
                assignments = ReadOnlyAssignments(Decision.Assigned("core-ru", 4), snapshotAt(today, nine)),
                progress = ReadOnlyProgress(dayResult(today, completedCount = 3)),
            ),
        )

        cases.forEach { (name, pair) ->
            val (runner, probes) = pair
            val report = runner(today, minuteNine, TEST_WORK_ID)
            assertEquals(name, 0, probes.notifier.shows)
            assertEquals(name, 1, probes.scheduler.scheduled.size)
            assertTrue(name, report.rescheduled)
            assertNull(name, report.evaluationFailure)
        }
    }

    /** `I6-W1`. «Рано» — та же цель, показа нет. */
    @Test
    fun `I6-W1 an early run reschedules the same target`() = runTest {
        val (runner, probes) = run(nowTime = LocalTime.of(8, 30))

        runner(today, minuteNine, TEST_WORK_ID)

        assertEquals(0, probes.notifier.shows)
        assertEquals(today, probes.scheduler.scheduled.single().first.targetDate)
    }

    // --- I6-W2: worker ничего не мутирует -------------------------------------------------

    /**
     * `I6-W2`. Фейки бросают на **любой** записывающий метод (`startSession`,
     * `recordAttempt`, сеттеры настроек). Ни одна ветка их не вызывает — иначе тест
     * упал бы `AssertionError` из самого фейка.
     */
    @Test
    fun `I6-W2 no branch ever calls a writing method`() = runTest {
        // Показ, пропуск по дню, пропуск по доступу и устаревшая работа — четыре ветки.
        run().first.invoke(today, minuteNine, TEST_WORK_ID)
        run(access = FakeNotificationAccess(NotificationAvailability.ChannelDisabled))
            .first.invoke(today, minuteNine, TEST_WORK_ID)
        run(
            assignments = ReadOnlyAssignments(Decision.Assigned("core-ru", 4), snapshotAt(today, nine)),
            progress = ReadOnlyProgress(dayResult(today, completedCount = 2, isComplete = false)),
        ).first.invoke(today, minuteNine, TEST_WORK_ID)
        run().first.invoke(today.minusDays(3), minuteNine, TEST_WORK_ID)
    }

    /**
     * `I6-W2`. Отказ `peek()`: показа нет, перепланирование — по запасной цели (настройки
     * и часы), исключение наружу не выходит и лежит в отчёте.
     */
    @Test
    fun `I6-W2 a failing peek falls back to the settings target and keeps the error`() = runTest {
        val failure = IllegalStateException("база недоступна")
        val (runner, probes) = run(
            assignments = ReadOnlyAssignments(
                Decision.NewSet("core-ru", 4),
                snapshotAt(today, nine),
                peekFailure = failure,
            ),
        )

        val report = runner(today, minuteNine, TEST_WORK_ID)

        assertEquals(0, probes.notifier.shows)
        assertSame(failure, report.evaluationFailure)
        assertTrue(report.rescheduled)
        assertEquals(today.plusDays(1), probes.scheduler.scheduled.single().first.targetDate)
    }

    // --- I6-W3: отмена и ошибки -----------------------------------------------------------

    /** `I6-W3` (а). Отмена из оценки до вердикта: пробрасывается, `schedule` не вызван. */
    @Test
    fun `I6-W3 cancellation before the verdict propagates without scheduling`() = runTest {
        val scheduler = RecordingScheduler()
        val run = ReminderRun(
            evaluate = evaluateUseCase(
                preferences = ReadOnlyPreferences(
                    preferencesOf(),
                    failFromRead = 1,
                    readFailure = CancellationException("отменено"),
                ),
            ),
            notifier = RecordingNotifier(),
            scheduler = scheduler,
            lock = ReminderScheduleLock(),
        )

        val thrown = runCatching { run(today, minuteNine, TEST_WORK_ID) }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    /**
     * `I6-W3` (г). Обычная ошибка оценки, а затем **отмена** из запасного расчёта:
     * отмена пробрасывается и не превращается в отчёт, планировщик не вызывается.
     */
    @Test
    fun `I6-W3 cancellation from the fallback propagates without scheduling`() = runTest {
        val scheduler = RecordingScheduler()
        val run = ReminderRun(
            evaluate = evaluateUseCase(
                // Первое чтение (оценка) проходит, второе (fallback) — отменяется.
                preferences = ReadOnlyPreferences(
                    preferencesOf(),
                    failFromRead = 2,
                    readFailure = CancellationException("отменено"),
                ),
                assignments = ReadOnlyAssignments(
                    Decision.NewSet("core-ru", 4),
                    snapshotAt(today, nine),
                    peekFailure = IllegalStateException("база недоступна"),
                ),
            ),
            notifier = RecordingNotifier(),
            scheduler = scheduler,
            lock = ReminderScheduleLock(),
        )

        val thrown = runCatching { run(today, minuteNine, TEST_WORK_ID) }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    /**
     * `I6-W3` (б). Отмена **после** вычисления цели, но до записи: `ensureActive()`
     * останавливает перепланирование. Иначе отменённый worker поставил бы работу поверх
     * той, ради которой его и отменили.
     */
    @Test
    fun `I6-W3 cancellation after computing the target prevents rescheduling`() = runTest {
        val scheduler = RecordingScheduler()
        val notifier = RecordingNotifier()
        // Показ успевает пройти, но тут же отменяет собственную корутину.
        notifier.beforeShow = { currentCoroutineContext().job.cancel() }
        val run = ReminderRun(
            evaluate = evaluateUseCase(),
            notifier = notifier,
            scheduler = scheduler,
            lock = ReminderScheduleLock(),
        )

        val deferred = async { run(today, minuteNine, TEST_WORK_ID) }
        val thrown = runCatching { deferred.await() }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertTrue("после отмены расписание не трогается", scheduler.scheduled.isEmpty())
    }

    /** `I6-W3` (в). Отмена из `notifier.show()` пробрасывается и планировщика не трогает. */
    @Test
    fun `I6-W3 cancellation from show propagates without scheduling`() = runTest {
        val scheduler = RecordingScheduler()
        val notifier = RecordingNotifier(showFailure = CancellationException("отменено"))
        val run = ReminderRun(evaluateUseCase(), notifier, scheduler, ReminderScheduleLock())

        val thrown = runCatching { run(today, minuteNine, TEST_WORK_ID) }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    /**
     * `I6-W3` (д). Обычная ошибка оценки и упавший запасной расчёт: наружу ничего,
     * `schedule` не вызван, в отчёте — **первоначальная** ошибка оценки.
     */
    @Test
    fun `I6-W3 a failing fallback keeps the original evaluation error`() = runTest {
        val evaluationFailure = IllegalStateException("чтение настроек упало")
        val scheduler = RecordingScheduler()
        // Одно и то же чтение настроек роняет и оценку, и запасной расчёт.
        val run = ReminderRun(
            evaluate = evaluateUseCase(
                preferences = ReadOnlyPreferences(
                    preferencesOf(),
                    failFromRead = 1,
                    readFailure = evaluationFailure,
                ),
            ),
            notifier = RecordingNotifier(),
            scheduler = scheduler,
            lock = ReminderScheduleLock(),
        )

        val report = run(today, minuteNine, TEST_WORK_ID)

        assertSame(evaluationFailure, report.evaluationFailure)
        assertFalse(report.rescheduled)
        assertNull(report.rescheduleFailure)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    /** `I6-W3` (е). Отказ планировщика — отдельным полем; наружу ничего не выходит. */
    @Test
    fun `I6-W3 a scheduler failure is reported separately`() = runTest {
        val schedulerFailure = IllegalStateException("WorkManager отказал")
        val (runner, probes) = run(scheduler = RecordingScheduler(scheduleFailure = schedulerFailure))

        val report = runner(today, minuteNine, TEST_WORK_ID)

        assertEquals(1, probes.notifier.shows)
        assertNull(report.evaluationFailure)
        assertFalse(report.rescheduled)
        assertSame(schedulerFailure, report.rescheduleFailure)
    }

    /**
     * `I6-W3` (ж). Ошибка оценки + удачный fallback + отказ планировщика: поля
     * **не** подменяют друг друга.
     */
    @Test
    fun `I6-W3 evaluation and scheduler failures do not overwrite each other`() = runTest {
        val evaluationFailure = IllegalStateException("база недоступна")
        val schedulerFailure = IllegalStateException("WorkManager отказал")
        // Оценка падает на базе, запасной расчёт (настройки и часы) проходит.
        val run = ReminderRun(
            evaluate = evaluateUseCase(
                assignments = ReadOnlyAssignments(
                    Decision.NewSet("core-ru", 4),
                    snapshotAt(today, nine),
                    peekFailure = evaluationFailure,
                ),
            ),
            notifier = RecordingNotifier(),
            scheduler = RecordingScheduler(scheduleFailure = schedulerFailure),
            lock = ReminderScheduleLock(),
        )

        val report = run(today, minuteNine, TEST_WORK_ID)

        assertSame(evaluationFailure, report.evaluationFailure)
        assertSame(schedulerFailure, report.rescheduleFailure)
    }

    // --- I6-W4: повторный запуск ----------------------------------------------------------

    /**
     * `I6-W4`. Два последовательных запуска в один день дают два вызова `show()` одного
     * notifier'а; одно активное уведомление обеспечивает постоянный ID (`I6-P2`).
     */
    @Test
    fun `I6-W4 two runs in one day call show twice on the same notifier`() = runTest {
        val (runner, probes) = run()

        runner(today, minuteNine, TEST_WORK_ID)
        runner(today, minuteNine, TEST_WORK_ID)

        assertEquals(2, probes.notifier.shows)
    }

    // --- Инфраструктура -------------------------------------------------------------------

    private class Probes(val notifier: RecordingNotifier, val scheduler: RecordingScheduler)

    private fun run(
        preferences: ReadOnlyPreferences = ReadOnlyPreferences(preferencesOf()),
        access: FakeNotificationAccess = FakeNotificationAccess(),
        assignments: ReadOnlyAssignments = ReadOnlyAssignments(
            Decision.NewSet("core-ru", 4),
            snapshotAt(today, nine),
        ),
        progress: ReadOnlyProgress = ReadOnlyProgress(),
        notifier: RecordingNotifier = RecordingNotifier(),
        scheduler: RecordingScheduler = RecordingScheduler(),
        nowTime: LocalTime = LocalTime.of(9, 0),
    ): Pair<ReminderRun, Probes> {
        val runner = ReminderRun(
            evaluate = evaluateUseCase(preferences, access, assignments, progress, nowTime),
            notifier = notifier,
            scheduler = scheduler,
            lock = ReminderScheduleLock(),
        )
        return runner to Probes(notifier, scheduler)
    }

    private fun evaluateUseCase(
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
