package ru.poporyadku.notifications

import ru.poporyadku.domain.reminder.ReminderSyncResult

/** Что делать с исходом синхронизации внутри resync-работы (ITERATION_6_DESIGN.md, §9.4). */
internal enum class ResyncAttempt {
    /** Синхронизация подтверждена — работа успешна. */
    Done,

    /** Обычный отказ, попытки ещё остались — повтор с backoff WorkManager. */
    Retry,

    /**
     * Отказ на последней попытке. Работа завершается успешно, чтобы WorkManager не
     * держал её вечно: расписание всё равно синхронизирует коллектор при следующем
     * старте `MainActivity`.
     */
    GiveUp,
}

/**
 * Чистое отображение исхода синхронизации в решение worker'а (`I6-P8`).
 *
 * Вынесено из worker'а намеренно: правило «`Failed` никогда не даёт `Done`» проверяется
 * на JVM, без WorkManager и без Robolectric.
 */
internal object ResyncAttemptPolicy {

    /** Сколько раз resync-работа пытается синхронизироваться, считая первую попытку. */
    const val RESYNC_MAX_ATTEMPTS = 3

    fun decide(result: ReminderSyncResult, runAttemptCount: Int): ResyncAttempt = when (result) {
        is ReminderSyncResult.Scheduled, ReminderSyncResult.Cancelled -> ResyncAttempt.Done
        is ReminderSyncResult.Failed ->
            // runAttemptCount у первой попытки — 0, поэтому последняя попытка — это
            // RESYNC_MAX_ATTEMPTS - 1.
            if (runAttemptCount < RESYNC_MAX_ATTEMPTS - 1) ResyncAttempt.Retry else ResyncAttempt.GiveUp
    }
}
