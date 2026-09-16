package ru.poporyadku.notifications

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.poporyadku.domain.reminder.NotificationAvailability
import ru.poporyadku.domain.reminder.REMINDER_CHANNEL_ID

/**
 * `I6-P3` — статус доступа к уведомлениям по таблице 8.2 (ITERATION_6_DESIGN.md, I6-D36).
 *
 * Проверяется именно **порядок**: каждая следующая причина имеет смысл только тогда,
 * когда предыдущая устранена.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidNotificationAccessTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    /** `I6-P3`. API 33 без разрешения — `RuntimePermissionMissing`… */
    @Test
    @Config(sdk = [SDK_33])
    fun `I6-P3 API 33 without the permission is RuntimePermissionMissing`() {
        denyPostNotifications()
        setAppNotificationsEnabled(true)

        assertEquals(NotificationAvailability.RuntimePermissionMissing, access().availability())
    }

    /**
     * `I6-P3`. …даже при включённых уведомлениях приложения: разрешение проверяется
     * первым, потому что без него показа не будет ни при каких настройках.
     */
    @Test
    @Config(sdk = [SDK_33])
    fun `I6-P3 the runtime permission is checked before the app switch`() {
        denyPostNotifications()
        setAppNotificationsEnabled(false)

        assertEquals(NotificationAvailability.RuntimePermissionMissing, access().availability())
    }

    /** `I6-P3`. API 33 с разрешением, но уведомления приложения выключены. */
    @Test
    @Config(sdk = [SDK_33])
    fun `I6-P3 API 33 with the permission but notifications off is AppNotificationsDisabled`() {
        grantPostNotifications()
        setAppNotificationsEnabled(false)

        assertEquals(NotificationAvailability.AppNotificationsDisabled, access().availability())
    }

    /**
     * `I6-P3`. На API 32 `RuntimePermissionMissing` невозможен по построению: выключенные
     * уведомления дают `AppNotificationsDisabled`, и запрашивать нечего.
     */
    @Test
    @Config(sdk = [SDK_32])
    fun `I6-P3 API 32 never reports a missing runtime permission`() {
        setAppNotificationsEnabled(false)

        assertEquals(NotificationAvailability.AppNotificationsDisabled, access().availability())
    }

    /** `I6-P3`. Заглушённый канал — `ChannelDisabled`. */
    @Test
    @Config(sdk = [SDK_33])
    fun `I6-P3 a muted channel is ChannelDisabled`() {
        grantPostNotifications()
        setAppNotificationsEnabled(true)
        createChannel(NotificationManager.IMPORTANCE_NONE)

        assertEquals(NotificationAvailability.ChannelDisabled, access().availability())
    }

    /** `I6-P3`. Ещё не созданный канал — **не** отказ: он создаётся перед показом. */
    @Test
    @Config(sdk = [SDK_33])
    fun `I6-P3 a channel that does not exist yet is not a refusal`() {
        grantPostNotifications()
        setAppNotificationsEnabled(true)

        assertEquals(NotificationAvailability.Allowed, access().availability())
    }

    /** `I6-P3`. Всё включено — `Allowed`. */
    @Test
    @Config(sdk = [SDK_33])
    fun `I6-P3 everything enabled is Allowed`() {
        grantPostNotifications()
        setAppNotificationsEnabled(true)
        createChannel(NotificationManager.IMPORTANCE_DEFAULT)

        assertEquals(NotificationAvailability.Allowed, access().availability())
    }

    private fun access() = AndroidNotificationAccess(app)

    private fun grantPostNotifications() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun denyPostNotifications() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS),
        )
    }

    private fun setAppNotificationsEnabled(enabled: Boolean) {
        shadowOf(app.getSystemService<NotificationManager>()).setNotificationsEnabled(enabled)
    }

    private fun createChannel(importance: Int) {
        app.getSystemService<NotificationManager>()!!
            .createNotificationChannel(NotificationChannel(REMINDER_CHANNEL_ID, "Напоминание", importance))
    }

    private companion object {
        const val SDK_33 = 33
        const val SDK_32 = 32
    }
}
