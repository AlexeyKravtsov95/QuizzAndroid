package ru.poporyadku.debug

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import ru.poporyadku.domain.reminder.ReminderSyncResult
import ru.poporyadku.domain.reminder.SyncReminderScheduleUseCase
import ru.poporyadku.notifications.ReminderBroadcastHandler
import ru.poporyadku.notifications.ReminderWorkNames

/**
 * Доступ instrumented-тестов к пути напоминания (`I6-N6`, `I6-M7`).
 *
 * Живёт в `src/debug` по той же причине, что и [DebugGraphEntryPoint]: `@EntryPoint`
 * обязан обрабатываться вместе с графом самого приложения. Здесь же проходит граница
 * видимости — `ReminderBroadcastHandler` и имена работ остаются `internal` в `main`, а
 * наружу выходят только публичные типы.
 *
 * Ни одной тестовой реализации не подставляет: вызываются те же экземпляры, которыми
 * работает приложение.
 */
@Singleton
class ReminderTestHooks @Inject internal constructor(
    private val broadcastHandler: ReminderBroadcastHandler,
    private val sync: SyncReminderScheduleUseCase,
) {

    /** Имена уникальных работ — чтобы тест не дублировал строковые литералы. */
    val dailyReminderWorkName: String get() = ReminderWorkNames.DAILY_REMINDER
    val resyncWorkName: String get() = ReminderWorkNames.REMINDER_RESYNC

    /**
     * Доставить событие смены времени или зоны и дождаться `onFinished` — того самого
     * момента, когда настоящий приёмник вызвал бы `PendingResult.finish()`.
     *
     * Возврат из этой функции означает: событие уже долговечно, и процесс можно
     * уничтожать.
     */
    suspend fun deliverTimeChange(action: String): Unit = suspendCoroutine { continuation ->
        var resumed = false
        broadcastHandler.handle(action) {
            // onFinished по контракту вызывается ровно один раз; защита — на случай
            // регрессии, чтобы тест падал понятной ошибкой, а не IllegalStateException
            // из корутины.
            check(!resumed) { "onFinished вызван повторно" }
            resumed = true
            continuation.resume(Unit)
        }
    }

    /** Та же единственная операция синхронизации, что выполняет `ReminderResyncWorker`. */
    suspend fun syncNow(): ReminderSyncResult = sync()
}
