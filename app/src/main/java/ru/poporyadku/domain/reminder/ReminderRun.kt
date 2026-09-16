package ru.poporyadku.domain.reminder

import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock

/**
 * Оркестрация одного срабатывания worker'а (ITERATION_6_DESIGN.md, §9.6, §9.8, I6-D49).
 *
 * Порядок единственный: **оценка → возможный показ → проверка отмены → перепланирование**.
 *
 * **Блока, выполняющегося при отмене, здесь нет.** Ни `finally`, ни
 * `invokeOnCompletion`, ни `NonCancellable`: иначе отменённый worker — а отменяет его
 * ровно тот, кто уже поставил новую работу с более свежими настройками, — успел бы
 * поставить свою поверх неё.
 *
 * **Ошибки разделены.** Ошибка оценки и ошибка планировщика лежат в разных полях
 * [Report] и не подменяют друг друга: «не смогли решить, показывать ли» и «не смогли
 * поставить следующую работу» — разные отказы с разными последствиями. Наружу не
 * выходит ни одна из них: пользователь ошибок планирования не видит, игровой поток от
 * них не зависит.
 */
class ReminderRun @Inject constructor(
    private val evaluate: EvaluateReminderUseCase,
    private val notifier: ReminderNotifier,
    private val scheduler: ReminderScheduler,
    private val lock: ReminderScheduleLock,
) {

    /**
     * @param evaluationFailure первоначальная ошибка оценки или показа; ошибка
     *  запасного расчёта цели её не подменяет.
     * @param rescheduled поставлена ли следующая работа.
     * @param rescheduleFailure отказ планировщика — отдельно от [evaluationFailure].
     */
    data class Report(
        val evaluationFailure: Exception?,
        val rescheduled: Boolean,
        val rescheduleFailure: Exception?,
    )

    /** Бросает только [CancellationException]. */
    suspend operator fun invoke(
        scheduledDate: LocalDate,
        scheduledMinute: Int,
        workId: UUID,
    ): Report {
        var evaluationFailure: Exception? = null

        // 1. Оценка и показ. Отмена отсюда пробрасывается — ни показа, ни перепланирования.
        val next: ReminderTrigger? = try {
            val verdict = evaluate(scheduledDate, scheduledMinute)
            if (verdict is ReminderVerdict.Show) notifier.show()
            verdict.nextTrigger
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            evaluationFailure = e
            // Запасная цель считается по настройкам и часам, без базы; её собственная
            // ошибка наружу не выходит и первоначальную не подменяет.
            fallbackOrNull()
        }

        // Цели нет: напоминание выключено (или запасной расчёт тоже отказал) —
        // продолжать цепочку нечем.
        if (next == null) {
            return Report(evaluationFailure, rescheduled = false, rescheduleFailure = null)
        }

        // 2. Отмена, пришедшая после вычисления цели, но до записи: schedule() не вызывается.
        currentCoroutineContext().ensureActive()

        // 3. Перепланирование под общей блокировкой: если синхронизация уже поставила
        //    ожидающую работу, AfterCurrent не добавит в цепочку вторую.
        val rescheduleFailure = try {
            lock.mutex.withLock { scheduler.schedule(next, ScheduleMode.AfterCurrent(workId)) }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }

        return Report(
            evaluationFailure = evaluationFailure,
            rescheduled = rescheduleFailure == null,
            rescheduleFailure = rescheduleFailure,
        )
    }

    private suspend fun fallbackOrNull(): ReminderTrigger? = try {
        evaluate.fallbackNextTrigger()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
