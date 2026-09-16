package ru.poporyadku.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import ru.poporyadku.MainActivity
import ru.poporyadku.R
import ru.poporyadku.core.time.ClockProvider
import ru.poporyadku.domain.reminder.NotificationAccess
import ru.poporyadku.domain.reminder.NotificationAvailability
import ru.poporyadku.domain.reminder.REMINDER_CHANNEL_ID
import ru.poporyadku.domain.reminder.ReminderNotifier

/**
 * Канал, показ и снятие напоминания (ITERATION_6_DESIGN.md, §10.1, §10.2, I6-D34,
 * I6-D35).
 *
 * **Канал `daily_reminder`** — `IMPORTANCE_DEFAULT` (строка в статус-баре без
 * всплывающего баннера), но **без звука и вибрации**: спокойный тон продукта. Значка на
 * иконке нет — счётчик непрочитанного был бы давлением, которого продукт избегает.
 * Идентификатор канала стабилен навсегда: переименование создало бы новый канал и
 * потеряло бы выбор пользователя.
 *
 * **Нажатие ведёт себя как значок приложения** (§10.2): `ACTION_MAIN` +
 * `CATEGORY_LAUNCHER`. Deep link не вводится сознательно — задание зависит от даты и
 * назначения, и ссылка, построенная вчера, открыла бы чужой день, тогда как Home сам
 * решает, что показать. Живая задача поэтому выводится на передний план как есть, с
 * сохранённым порядком карточек и бэкстеком.
 */
@Singleton
class AndroidReminderNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val access: NotificationAccess,
    private val clock: ClockProvider,
) : ReminderNotifier {

    override suspend fun show() {
        ensureChannel()

        // Повторная проверка непосредственно перед показом: между оценкой worker'а и
        // этим моментом разрешение могли отозвать.
        if (access.availability() != NotificationAvailability.Allowed) return

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.reminder_notification_text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            // Личных данных в тексте нет — уведомление можно показывать на экране блокировки.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Напоминание живёт только «сегодня»: в начале следующей локальной даты
            // система снимает его сама, и вчерашнее обещание не висит в шторке.
            .setTimeoutAfter(millisUntilNextLocalDate())
            .setContentIntent(contentIntent())
            .build()

        try {
            NotificationManagerCompat.from(context).notify(REMINDER_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Гонка с отзывом разрешения между проверкой и notify: пропущенное
            // напоминание — не повод уронить worker.
        }
    }

    override fun cancelShown() {
        NotificationManagerCompat.from(context).cancel(REMINDER_NOTIFICATION_ID)
    }

    /**
     * Идемпотентное создание канала: повторный вызов с тем же идентификатором не
     * перезаписывает пользовательские изменения важности, звука и вибрации.
     * `minSdk 26` — ветвления по версии не требуется.
     */
    fun ensureChannel() {
        val manager = context.getSystemService<NotificationManager>() ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.reminder_channel_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** Сколько миллисекунд осталось до начала следующей локальной даты. */
    private fun millisUntilNextLocalDate(): Long {
        val now = clock.now()
        val nextDateStart = now.localDate.plusDays(1).atStartOfDay(now.zone).toInstant().toEpochMilli()
        return (nextDateStart - now.epochMillis).coerceAtLeast(0L)
    }

    /**
     * Тот же интент, что у значка приложения: холодный старт — Home, живая задача —
     * на передний план без пересоздания. `FLAG_IMMUTABLE` обязателен с API 31;
     * `FLAG_UPDATE_CURRENT` с постоянным `requestCode` не плодит разных `PendingIntent`.
     */
    private fun contentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(
            context,
            REMINDER_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        /** Общий с `ui/platform` идентификатор канала — объявлен в домене (§10.1). */
        const val CHANNEL_ID = REMINDER_CHANNEL_ID

        /** Один идентификатор — повторный показ заменяет, а не добавляет уведомление. */
        const val REMINDER_NOTIFICATION_ID = 1001

        private const val REMINDER_REQUEST_CODE = 1001
    }
}
