package ru.poporyadku.notifications

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Долговечная запись события смены времени или зоны (ITERATION_6_DESIGN.md, §9.4,
 * I6-D30).
 *
 * Приёмник живёт миллисекунды и может быть убит сразу после `finish()`. Поэтому событие
 * не «обрабатывается», а **сохраняется**: в базу WorkManager кладётся уникальная работа
 * `reminder_resync`, которую система выполнит в любом следующем процессе — без
 * `MainActivity`.
 */
internal interface ReminderResyncRequests {

    /**
     * Поставить `reminder_resync`. Возвращается **только** после того, как WorkManager
     * записал работу в свою базу; при отказе записи бросает — вызывающий обязан считать
     * событие несохранённым.
     */
    suspend fun enqueueDurably()
}

@Singleton
internal class WorkManagerResyncRequests @Inject constructor(
    private val operations: UniqueWorkOperations,
) : ReminderResyncRequests {

    override suspend fun enqueueDurably() {
        // Без задержки, данных и ограничений: работе нужно только «выполнись как можно
        // скорее и перечитай настройки». REPLACE — повторное событие заменяет ожидающую
        // или выполняющуюся работу, чтобы новая читала свежие часы и зону.
        val request = OneTimeWorkRequestBuilder<ReminderResyncWorker>()
            .addTag(ReminderWorkNames.TAG_REMINDER)
            .build()
        operations.enqueue(ReminderWorkNames.REMINDER_RESYNC, ExistingWorkPolicy.REPLACE, request)
    }
}
