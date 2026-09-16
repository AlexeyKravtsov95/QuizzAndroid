package ru.poporyadku.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.time.format.DateTimeParseException
import ru.poporyadku.domain.reminder.ReminderTrigger

/**
 * Срабатывание напоминания (ITERATION_6_DESIGN.md, §9.6, I6-D27, I6-D32).
 *
 * Worker — тонкая оболочка: разобрать данные, вызвать `ReminderRun`, вернуть результат.
 * Вся логика — в доменных объектах, поэтому проверяется на JVM без WorkManager.
 *
 * **Чего worker не делает** (и это проверяется `I6-W2` фейками, бросающими на запись, и
 * `rg`-проверкой `I6-K3`): не назначает набор, не импортирует контент, не пишет прогресс
 * и настройки, не обращается к UI и ViewModel. Фоновое срабатывание не имеет права
 * менять состояние дня, который пользователь не открывал.
 *
 * **`Result.success()` при любом `Report`.** Повтор с backoff не нужен: ошибка оценки
 * или планировщика не исправляется повторным запуском через минуту, а следующая работа
 * ставится либо fallback-целью, либо коллектором при старте приложения.
 */
internal class ReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entryPoint = ReminderEntryPoint.of(applicationContext)

        val date = parseTargetDate()
        val minute = inputData.getInt(ReminderWorkNames.KEY_MINUTE_OF_DAY, INVALID_MINUTE)

        // Неразбираемые данные: показывать нечего — обещание могло бы оказаться ложным.
        // Вместо показа — та же единственная операция синхронизации, которая поставит
        // корректную работу по актуальным настройкам.
        if (date == null || minute !in ReminderTrigger.MIN_MINUTE_OF_DAY..ReminderTrigger.MAX_MINUTE_OF_DAY) {
            // CancellationException отсюда пробрасывается — своего catch нет намеренно.
            entryPoint.syncReminderSchedule().invoke()
            return Result.success()
        }

        entryPoint.reminderRun().invoke(scheduledDate = date, scheduledMinute = minute, workId = id)
        return Result.success()
    }

    private fun parseTargetDate(): LocalDate? {
        val raw = inputData.getString(ReminderWorkNames.KEY_TARGET_DATE) ?: return null
        return try {
            LocalDate.parse(raw)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    private companion object {
        const val INVALID_MINUTE = -1
    }
}
