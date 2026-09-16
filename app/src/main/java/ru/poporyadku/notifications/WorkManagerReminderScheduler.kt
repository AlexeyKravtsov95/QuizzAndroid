package ru.poporyadku.notifications

import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import ru.poporyadku.core.time.ClockProvider
import ru.poporyadku.domain.reminder.ReminderScheduler
import ru.poporyadku.domain.reminder.ReminderTrigger
import ru.poporyadku.domain.reminder.ScheduleMode

/**
 * Единственная реализация [ReminderScheduler] (ITERATION_6_DESIGN.md, §9.3, I6-D26,
 * I6-D27).
 *
 * **`OneTimeWorkRequest`, а не периодическая работа.** Период в 24 часа не следует
 * местному времени: после смены зоны или перехода на летнее время он уехал бы
 * относительно настенных часов, а пользователь выбирал «9:00», а не «каждые 24 часа».
 * Поэтому каждая работа ставит следующую сама.
 *
 * **Данных ровно два поля** — дата и минута суток. Ни настроек, ни счёта, ни `puzzleId`:
 * всё, что нужно для решения, worker перечитывает при срабатывании, и устаревшая копия
 * в работе не может соврать.
 *
 * **Блокировку [ru.poporyadku.domain.reminder.ReminderScheduleLock] берёт вызывающий**
 * (`SyncReminderScheduleUseCase`, `ReminderRun`), а не этот класс: `Mutex` не
 * реентрантный, и вторая попытка захвата в том же вызове была бы взаимной блокировкой.
 */
@Singleton
internal class WorkManagerReminderScheduler @Inject constructor(
    private val operations: UniqueWorkOperations,
    private val clock: ClockProvider,
) : ReminderScheduler {

    override suspend fun schedule(trigger: ReminderTrigger, mode: ScheduleMode) {
        val policy = when (mode) {
            // Новые настройки важнее: ожидающая и выполняющаяся работа заменяются.
            ScheduleMode.Replace -> ExistingWorkPolicy.REPLACE

            is ScheduleMode.AfterCurrent -> {
                // Если в цепочке уже есть ожидающая работа, отличная от текущей, её
                // поставила синхронизация с более свежими настройками — второй
                // ожидающей работы быть не должно.
                val pending = operations.pendingIds(ReminderWorkNames.DAILY_REMINDER)
                if (pending.any { it != mode.workId }) return
                // APPEND_OR_REPLACE, а не REPLACE: перепланирование не имеет права
                // отменить работу, внутри которой оно выполняется.
                ExistingWorkPolicy.APPEND_OR_REPLACE
            }
        }

        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delayMillisTo(trigger.at), TimeUnit.MILLISECONDS)
            .setInputData(inputDataOf(trigger))
            .addTag(ReminderWorkNames.TAG_REMINDER)
            .build()

        operations.enqueue(ReminderWorkNames.DAILY_REMINDER, policy, request)
    }

    override suspend fun cancel() {
        operations.cancel(ReminderWorkNames.DAILY_REMINDER)
    }

    /** Момент в прошлом (часы перевели вперёд) даёт нулевую задержку, а не отрицательную. */
    private fun delayMillisTo(at: Instant): Long {
        val now = Instant.ofEpochMilli(clock.now().epochMillis)
        return Duration.between(now, at).coerceAtLeast(Duration.ZERO).toMillis()
    }

    private fun inputDataOf(trigger: ReminderTrigger): Data = Data.Builder()
        .putString(ReminderWorkNames.KEY_TARGET_DATE, trigger.targetDate.toString())
        .putInt(ReminderWorkNames.KEY_MINUTE_OF_DAY, trigger.minuteOfDay)
        .build()
}
