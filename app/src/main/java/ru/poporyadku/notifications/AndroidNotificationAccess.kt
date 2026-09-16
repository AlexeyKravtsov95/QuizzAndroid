package ru.poporyadku.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import ru.poporyadku.domain.reminder.NotificationAccess
import ru.poporyadku.domain.reminder.NotificationAvailability

/**
 * Статус доступа к уведомлениям (ITERATION_6_DESIGN.md, §8.2, I6-D36).
 *
 * Порядок проверок фиксирован таблицей 8.2 и важен: каждая следующая причина имеет смысл
 * только тогда, когда предыдущая устранена. Сначала runtime-разрешение (без него система
 * не покажет уведомление ни при каких настройках канала), затем уведомления приложения
 * целиком, затем конкретный канал.
 *
 * На API 26–32 [NotificationAvailability.RuntimePermissionMissing] невозможен по
 * построению: `POST_NOTIFICATIONS` там не существует, и ветка не выполняется.
 *
 * Отдельного распознавания «отказано навсегда» нет: система не даёт его честно
 * отличить, а пользователю в обоих случаях нужен один и тот же путь — системные
 * настройки.
 */
@Singleton
internal class AndroidNotificationAccess @Inject constructor(
    @ApplicationContext private val context: Context,
) : NotificationAccess {

    override fun availability(): NotificationAvailability {
        // 1. API 33+ без разрешения: показа не будет независимо от остальных настроек.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPostNotifications()) {
            return NotificationAvailability.RuntimePermissionMissing
        }

        // 2. Уведомления приложения выключены целиком — runtime-запрос бесполезен.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return NotificationAvailability.AppNotificationsDisabled
        }

        // 3. Канал заглушён пользователем. Ещё не созданный канал — НЕ отказ: он будет
        //    создан перед показом со своей важностью.
        val channel = context.getSystemService<NotificationManager>()
            ?.getNotificationChannel(AndroidReminderNotifier.CHANNEL_ID)
        if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
            return NotificationAvailability.ChannelDisabled
        }

        return NotificationAvailability.Allowed
    }

    private fun hasPostNotifications(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
