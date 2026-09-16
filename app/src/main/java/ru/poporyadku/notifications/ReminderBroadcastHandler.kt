package ru.poporyadku.notifications

import android.content.Intent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.poporyadku.di.ApplicationScope
import ru.poporyadku.domain.reminder.ReminderSyncResult
import ru.poporyadku.domain.reminder.SyncReminderScheduleUseCase

/** Чем закончилась попытка сделать событие долговечным (ITERATION_6_DESIGN.md, §9.4). */
internal sealed interface ResyncRequestOutcome {

    /** Основной путь: строка `reminder_resync` записана в базу WorkManager. */
    data object ResyncWorkPersisted : ResyncRequestOutcome

    /** Запасной путь: WorkManager отказал в resync, но `daily_reminder` записана. */
    data class SyncedDirectly(val result: ReminderSyncResult) : ResyncRequestOutcome

    /**
     * WorkManager отказал в обеих записях. Единственный исход, при котором событие
     * сохранить нечем; восстановление — синхронизация коллектора при следующем старте
     * `MainActivity` (риск зафиксирован в §16).
     */
    data class NotPersisted(val cause: Throwable) : ResyncRequestOutcome
}

/**
 * Обработчик события смены времени и часового пояса (ITERATION_6_DESIGN.md, §9.4, §9.5,
 * I6-D30).
 *
 * **`finish()` вызывается только после долговечного результата.** Отменяющего таймаута
 * нет и быть не может: таймаут, вызывающий `finish()` «на всякий случай», означал бы
 * потерянное событие — процесс разрешено убить сразу после `finish()`, и незаписанная
 * синхронизация исчезла бы вместе с ним. Ждать приходится одну запись строки в базу
 * WorkManager, то есть миллисекунды.
 *
 * Если система всё же завершит процесс раньше, `finish()` просто не будет вызван —
 * контракт не нарушен, транзакция WorkManager атомарна, а следующий старт `MainActivity`
 * синхронизирует расписание заново.
 *
 * Собственной бизнес-логики нет: и запасной путь, и сама resync-работа вызывают ту же
 * [SyncReminderScheduleUseCase].
 */
@Singleton
internal class ReminderBroadcastHandler @Inject constructor(
    private val resyncRequests: ReminderResyncRequests,
    private val sync: SyncReminderScheduleUseCase,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /**
     * @param onFinished вызывается **ровно один раз** при любом завершении работы —
     *  успехе, ошибке и отмене scope (`invokeOnCompletion`).
     * @return запущенная корутина; `null` — действие чужое, работы нет.
     */
    fun handle(action: String?, onFinished: () -> Unit): Job? {
        if (action != Intent.ACTION_TIMEZONE_CHANGED && action != Intent.ACTION_TIME_CHANGED) {
            onFinished()
            return null
        }
        return scope.launch { persist() }
            .also { job -> job.invokeOnCompletion { onFinished() } }
    }

    private suspend fun persist(): ResyncRequestOutcome = try {
        // 1. Основной путь: сохранить событие, а не обработать его.
        resyncRequests.enqueueDurably()
        ResyncRequestOutcome.ResyncWorkPersisted
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 2. WorkManager отказал в записи resync — синхронизируем прямо здесь.
        //    Подтверждённые Scheduled/Cancelled тоже долговечны: они уже в базе.
        when (val direct = sync()) {
            is ReminderSyncResult.Scheduled,
            ReminderSyncResult.Cancelled,
            -> ResyncRequestOutcome.SyncedDirectly(direct)

            is ReminderSyncResult.Failed -> ResyncRequestOutcome.NotPersisted(e)
        }
    }
}
