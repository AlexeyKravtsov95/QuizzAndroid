package ru.poporyadku.domain.reminder

import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import ru.poporyadku.core.time.ClockProvider
import ru.poporyadku.domain.repository.DayAssignmentRepository
import ru.poporyadku.domain.repository.ProgressRepository
import ru.poporyadku.domain.repository.UserPreferencesRepository

/**
 * Проверка непосредственно перед показом (ITERATION_6_DESIGN.md, §9.6, I6-D32, I6-D33).
 *
 * **Только чтение.** Ни `GetTodayStateUseCase`, ни `StartDailySessionUseCase`, ни
 * `ContentInstaller` здесь не вызываются: первые два пишут (назначение набора, кэш
 * серии), третий импортирует контент. Фоновое срабатывание не имеет права менять
 * состояние дня — пользователь его не открывал. Доступны ровно три чтения: настройки,
 * `peek()` и `getDayResult(today)`.
 *
 * **Порядок проверок фиксирован** (таблица §9.6) и идёт от дешёвых к дорогим: настройка,
 * актуальность работы, момент, доступ, состояние дня. База читается последней — если
 * напоминание выключено или работа устарела, её не трогают вовсе.
 *
 * Исключения наружу не перехватываются: их обрабатывает [ReminderRun], который отделяет
 * ошибку оценки от ошибки планировщика (I6-D49).
 */
class EvaluateReminderUseCase @Inject constructor(
    private val preferences: UserPreferencesRepository,
    private val clock: ClockProvider,
    private val access: NotificationAccess,
    private val assignments: DayAssignmentRepository,
    private val progress: ProgressRepository,
) {

    suspend operator fun invoke(scheduledDate: LocalDate, scheduledMinute: Int): ReminderVerdict {
        val prefs = preferences.preferences.first()
        // 1. Намерение пользователя — единственный источник: работа могла пережить выключение.
        if (!prefs.reminderEnabled) return ReminderVerdict.Skip(SkipReason.Disabled, nextTrigger = null)

        val time = prefs.reminderTime
        val now = clock.now()
        val instant = Instant.ofEpochMilli(now.epochMillis)
        val nextByCurrentSettings = NextReminderTrigger.next(instant, now.zone, time)

        // 2. Работа поставлена на другое время — настройку изменили после планирования.
        val currentMinute = time.hour * MINUTES_PER_HOUR + time.minute
        if (scheduledMinute != currentMinute) {
            return ReminderVerdict.Skip(SkipReason.StaleTime, nextByCurrentSettings)
        }

        // 3. Работа поставлена на другую дату: сегодняшнее обещание вчерашней работы — ложь.
        if (scheduledDate != now.localDate) {
            return ReminderVerdict.Skip(SkipReason.StaleDate, nextByCurrentSettings)
        }

        // 4. Момент ещё не наступил: часы могли перевести вперёд, и WorkManager
        //    отсчитал задержку по настенным часам. Цель остаётся прежней.
        val scheduledAt = ZonedDateTime.of(scheduledDate, time, now.zone).toInstant()
        if (instant.isBefore(scheduledAt)) {
            val sameTarget = ReminderTrigger(scheduledDate, scheduledMinute, scheduledAt)
            return ReminderVerdict.Skip(SkipReason.Early, sameTarget)
        }

        // 5. Доступ: все три причины отказа одинаково означают «показа не будет».
        val availability = access.availability()
        if (availability != NotificationAvailability.Allowed) {
            return ReminderVerdict.Skip(SkipReason.NoAccess(availability), nextByCurrentSettings)
        }

        // 6. Состояние дня — последним: единственная пара чтений базы на всём пути.
        val decision = assignments.peek().decision
        val todayResult = progress.getDayResult(now.localDate)
        val notReady = ReminderEligibility.decide(decision, todayResult)
        if (notReady != null) {
            return ReminderVerdict.Skip(SkipReason.DayNotReadyYet(notReady), nextByCurrentSettings)
        }

        return ReminderVerdict.Show(nextByCurrentSettings)
    }

    /**
     * Цель следующей работы **без обращения к базе** — запасной путь [ReminderRun] после
     * обычной ошибки оценки (§9.6). Читает только настройки и часы, поэтому отказ базы,
     * уронивший оценку, здесь не повторяется.
     *
     * @return `null` — напоминание выключено: цепочку продолжать нечем.
     */
    suspend fun fallbackNextTrigger(): ReminderTrigger? {
        val prefs = preferences.preferences.first()
        if (!prefs.reminderEnabled) return null
        val now = clock.now()
        return NextReminderTrigger.next(Instant.ofEpochMilli(now.epochMillis), now.zone, prefs.reminderTime)
    }

    private companion object {
        const val MINUTES_PER_HOUR = 60
    }
}
