package ru.poporyadku.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Долговечное продолжение события смены времени или зоны (ITERATION_6_DESIGN.md, §9.4,
 * I6-D30).
 *
 * Приёмник сохранил событие этой работой и мог быть убит сразу после `finish()`. Систему
 * это не волнует: работа уже в базе WorkManager, и он выполнит её в любом следующем
 * процессе — `MainActivity` для этого не нужна.
 *
 * Работа вызывает ту же `SyncReminderScheduleUseCase`, что коллектор и запасной путь
 * приёмника; собственной копии логики у неё нет.
 */
internal class ReminderResyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sync = ReminderEntryPoint.of(applicationContext).syncReminderSchedule()
        // CancellationException пробрасывается: своего catch нет.
        return when (ResyncAttemptPolicy.decide(sync(), runAttemptCount)) {
            ResyncAttempt.Done -> Result.success()
            ResyncAttempt.Retry -> Result.retry()
            ResyncAttempt.GiveUp -> Result.success()
        }
    }
}
