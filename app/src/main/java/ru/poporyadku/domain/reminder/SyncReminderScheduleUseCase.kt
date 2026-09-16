package ru.poporyadku.domain.reminder

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import ru.poporyadku.core.time.ClockProvider
import ru.poporyadku.domain.repository.UserPreferencesRepository

/** Исход синхронизации расписания (ITERATION_6_DESIGN.md, §9.4, I6-D49). */
sealed interface ReminderSyncResult {

    data class Scheduled(val trigger: ReminderTrigger) : ReminderSyncResult

    data object Cancelled : ReminderSyncResult

    /**
     * Обычная ошибка. **Не успех**: вызывающий обязан обращаться с ней как с отказом —
     * `ReminderResyncWorker` повторяет попытку, приёмник переходит к запасному пути.
     */
    data class Failed(val cause: Throwable) : ReminderSyncResult
}

/**
 * **Единственная** операция синхронизации расписания (ITERATION_6_DESIGN.md, §9.4,
 * I6-D29).
 *
 * Её вызывают все четверо, и ни у кого нет своей копии логики:
 * - постоянный коллектор настроек `ReminderScheduleObserver` (открытое приложение);
 * - `ReminderResyncWorker` — долговечное продолжение события смены времени или зоны;
 * - запасной путь `ReminderBroadcastHandler`, если WorkManager отказал в записи resync;
 * - `ReminderWorker` с неразбираемыми входными данными.
 *
 * **Настройки перечитываются на каждый вызов**, а не приходят параметром: между
 * постановкой команды и её исполнением пользователь мог передумать, и правильным ответом
 * всегда является последнее записанное значение.
 *
 * **Один снимок часов** ([ClockProvider.now]) даёт и момент, и зону: двух независимых
 * чтений, способных разойтись через полночь или смену зоны, здесь нет.
 *
 * **Одна блокировка** [ReminderScheduleLock] сериализует все записи планировщика в
 * процессе, поэтому приёмник и открытое приложение не пишут работу одновременно.
 *
 * Отмена ([CancellationException]) пробрасывается всегда и никогда не превращается в
 * [ReminderSyncResult.Failed]: отменил тот, кто владеет вызовом, и его ждёт своя ветка.
 */
@Singleton
class SyncReminderScheduleUseCase @Inject constructor(
    private val preferences: UserPreferencesRepository,
    private val scheduler: ReminderScheduler,
    private val clock: ClockProvider,
    private val lock: ReminderScheduleLock,
) {

    suspend operator fun invoke(): ReminderSyncResult = lock.mutex.withLock {
        try {
            val prefs = preferences.preferences.first()
            if (!prefs.reminderEnabled) {
                // Ожидается запись WorkManager: «отменили» — это подтверждённая отмена.
                scheduler.cancel()
                ReminderSyncResult.Cancelled
            } else {
                val now = clock.now()
                val trigger = NextReminderTrigger.next(
                    now = Instant.ofEpochMilli(now.epochMillis),
                    zone = now.zone,
                    time = prefs.reminderTime,
                )
                scheduler.schedule(trigger, ScheduleMode.Replace)
                ReminderSyncResult.Scheduled(trigger)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ReminderSyncResult.Failed(e)
        }
    }
}
