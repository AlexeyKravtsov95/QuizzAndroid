package ru.poporyadku.notifications

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager
import ru.poporyadku.MainActivity
import ru.poporyadku.R
import ru.poporyadku.domain.reminder.CountingClock
import ru.poporyadku.domain.reminder.FakeNotificationAccess
import ru.poporyadku.domain.reminder.NotificationAccess
import ru.poporyadku.domain.reminder.NotificationAvailability
import ru.poporyadku.domain.reminder.REMINDER_CHANNEL_ID
import ru.poporyadku.domain.reminder.clockAt

/**
 * `I6-P2` — канал, уведомление и нажатие (ITERATION_6_DESIGN.md, §10.1, §10.2, I6-D34,
 * I6-D35).
 */
@RunWith(RobolectricTestRunner::class)
class AndroidReminderNotifierTest {

    private lateinit var app: Application
    private val today = LocalDate.of(2026, 9, 16)
    private val moscow = ZoneId.of("Europe/Moscow")

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    /** `I6-P2`. Канал: `IMPORTANCE_DEFAULT`, без звука, без вибрации, без значка. */
    @Test
    fun `I6-P2 the channel is default importance without sound vibration or badge`() {
        notifier().ensureChannel()

        val channel = app.getSystemService<NotificationManager>()!!
            .getNotificationChannel(REMINDER_CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertNull("спокойный тон продукта: звука у канала нет", channel.sound)
        assertFalse(channel.shouldVibrate())
        assertFalse("счётчик на иконке был бы давлением", channel.canShowBadge())
        assertEquals(app.getString(R.string.reminder_channel_name), channel.name)
    }

    /** `I6-P2`. Создание канала идемпотентно: второго канала не появляется. */
    @Test
    fun `I6-P2 ensureChannel is idempotent`() {
        val notifier = notifier()

        notifier.ensureChannel()
        notifier.ensureChannel()
        notifier.ensureChannel()

        val channels = app.getSystemService<NotificationManager>()!!.notificationChannels
        assertEquals(1, channels.count { it.id == REMINDER_CHANNEL_ID })
    }

    /** `I6-P2`. Показ: ID 1001, малая иконка, категория, `autoCancel`, `onlyAlertOnce`. */
    @Test
    fun `I6-P2 the notification carries the agreed id icon category and flags`() = runTest {
        notifier().show()

        val posted = shadowOf(app.getSystemService<NotificationManager>()).allNotifications.single()
        assertEquals(REMINDER_CHANNEL_ID, posted.channelId)
        assertEquals(R.drawable.ic_stat_reminder, posted.smallIcon.resId)
        assertEquals(Notification.CATEGORY_REMINDER, posted.category)
        assertEquals(Notification.VISIBILITY_PUBLIC, posted.visibility)
        assertTrue("autoCancel", posted.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertTrue("onlyAlertOnce", posted.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(
            app.getString(R.string.app_name),
            posted.extras.getString(NotificationCompat.EXTRA_TITLE),
        )
        assertEquals(
            app.getString(R.string.reminder_notification_text),
            posted.extras.getString(NotificationCompat.EXTRA_TEXT),
        )
    }

    /** `I6-P2`. Уведомление показано под постоянным идентификатором 1001. */
    @Test
    fun `I6-P2 the notification uses the constant id`() = runTest {
        notifier().show()

        val shadow = shadowOf(app.getSystemService<NotificationManager>())
        assertNotNull(shadow.getNotification(AndroidReminderNotifier.REMINDER_NOTIFICATION_ID))
    }

    /**
     * `I6-P2`. Уведомление живёт только «сегодня»: `timeoutAfter` равен остатку до начала
     * следующей локальной даты.
     */
    @Test
    fun `I6-P2 the timeout lasts until the start of the next local date`() = runTest {
        val nowTime = LocalTime.of(9, 0)
        notifier(clock = CountingClock(clockAt(today, nowTime, moscow))).show()

        val posted = shadowOf(app.getSystemService<NotificationManager>()).allNotifications.single()
        val expected = java.time.Duration.between(
            today.atTime(nowTime).atZone(moscow),
            today.plusDays(1).atStartOfDay(moscow),
        ).toMillis()
        assertEquals(expected, posted.timeoutAfter)
    }

    /** `I6-P2`. Повторный показ заменяет уведомление: активным остаётся одно. */
    @Test
    fun `I6-P2 a repeated show keeps exactly one active notification`() = runTest {
        val notifier = notifier()

        notifier.show()
        notifier.show()

        assertEquals(1, shadowOf(app.getSystemService<NotificationManager>()).size())
    }

    /** `I6-P2`. `cancelShown` снимает уведомление и идемпотентен. */
    @Test
    fun `I6-P2 cancelShown removes the notification and is idempotent`() = runTest {
        val notifier = notifier()
        notifier.show()

        notifier.cancelShown()
        notifier.cancelShown()

        assertEquals(0, shadowOf(app.getSystemService<NotificationManager>()).size())
    }

    /**
     * `I6-P2`. Доступ отозвали между оценкой и показом — уведомления нет: проверка перед
     * `notify()` повторяется.
     */
    @Test
    fun `I6-P2 a revoked access between evaluation and show posts nothing`() = runTest {
        val access = FakeNotificationAccess(NotificationAvailability.RuntimePermissionMissing)

        notifier(access = access).show()

        assertEquals(0, shadowOf(app.getSystemService<NotificationManager>()).size())
    }

    /**
     * `I6-P2`. `SecurityException` в гонке с отзывом разрешения между проверкой и
     * `notify()` перехватывается: пропущенное напоминание — не повод уронить worker.
     *
     * Отказ моделируется подменённой тенью `NotificationManager`, которая бросает на
     * самом `notify` — статус доступа при этом разрешён, то есть исполняется именно
     * ветка гонки, а не ранний выход по недоступности.
     */
    @Test
    @Config(shadows = [ThrowingNotificationManager::class])
    fun `I6-P2 a SecurityException from notify is swallowed`() = runTest {
        notifier().show()

        assertTrue("исключение не вышло наружу", ThrowingNotificationManager.attempted)
    }

    /**
     * `I6-P2`. Нажатие ведёт себя как значок приложения: `MainActivity`, `ACTION_MAIN` +
     * `CATEGORY_LAUNCHER`, `FLAG_IMMUTABLE`. Deep link не вводится.
     */
    @Test
    fun `I6-P2 the content intent behaves like the launcher icon`() = runTest {
        notifier().show()

        val posted = shadowOf(app.getSystemService<NotificationManager>()).allNotifications.single()
        val pending = shadowOf(posted.contentIntent)
        assertTrue("PendingIntent обязан быть immutable с API 31", pending.isImmutable)
        val intent = pending.savedIntent
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(intent.categories.contains(Intent.CATEGORY_LAUNCHER))
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertNull("никакого deep link", intent.data)
    }

    private fun notifier(
        access: NotificationAccess = FakeNotificationAccess(),
        clock: CountingClock = CountingClock(clockAt(today, LocalTime.of(9, 0), moscow)),
    ) = AndroidReminderNotifier(app, access, clock)

}

/**
 * Тень `NotificationManager`, отказывающая на самом показе, — модель гонки с отзывом
 * разрешения между проверкой доступа и `notify()` (ITERATION_6_DESIGN.md, §9.8).
 */
@Implements(NotificationManager::class)
class ThrowingNotificationManager : ShadowNotificationManager() {

    @Implementation
    override fun notify(tag: String?, id: Int, notification: Notification?) {
        attempted = true
        throw SecurityException("разрешение отозвано между проверкой и notify")
    }

    companion object {
        /** Была ли попытка показа: иначе тест прошёл бы и при полном отсутствии показа. */
        @JvmStatic
        var attempted: Boolean = false
    }
}
