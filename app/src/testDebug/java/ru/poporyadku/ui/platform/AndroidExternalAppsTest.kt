package ru.poporyadku.ui.platform

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.activity.ComponentActivity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import ru.poporyadku.ui.report.MailDraft
import ru.poporyadku.ui.report.MailtoUri

/**
 * `AndroidExternalApps` — `I5-P1` (ITERATION_5_DESIGN.md, §3.12, §8.1, I5-D19).
 *
 * Обработчики регистрируются в `ShadowPackageManager` фильтрами намерений — так, как их
 * объявил бы браузер или почтовый клиент: `SENDTO mailto:` с любыми параметрами и
 * `VIEW https`. Отказы запуска моделирует Activity, чей `startActivity` бросает.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidExternalAppsTest {

    private lateinit var controller: ActivityController<LaunchingActivity>
    private lateinit var activity: LaunchingActivity
    private var now = START_MS
    private lateinit var apps: AndroidExternalApps

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(LaunchingActivity::class.java).setup()
        activity = controller.get()
        apps = AndroidExternalApps(activity) { now }
    }

    // --- Доступность ---------------------------------------------------------------------

    @Test
    fun `I5-P1 availability follows installed handlers`() {
        assertFalse(apps.canComposeEmail())
        assertFalse(apps.canViewUrl(URL))

        installMailClient()
        installBrowser()

        assertTrue(apps.canComposeEmail())
        assertTrue(apps.canViewUrl(URL))
    }

    @Test
    fun `I5-P1 without a handler nothing is launched`() {
        assertEquals(LaunchResult.NoHandler, apps.composeEmail(DRAFT))
        assertEquals(LaunchResult.NoHandler, apps.viewUrl(URL))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    // --- Письмо ---------------------------------------------------------------------------

    /** `I5-P1`. `composeEmail`: `ACTION_SENDTO`, схема `mailto`, URI RFC 6068, extras темы и тела. */
    @Test
    fun `I5-P1 composeEmail launches SENDTO mailto with subject and body extras`() {
        installMailClient()

        assertEquals(LaunchResult.Launched, apps.composeEmail(DRAFT))

        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("mailto", intent.data?.scheme)
        assertEquals(MailtoUri.of(DRAFT), intent.data.toString())
        assertArrayEquals(arrayOf(DRAFT.to), intent.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertEquals(DRAFT.subject, intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(DRAFT.body, intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    /** `ACTION_VIEW` с URL строки источника. */
    @Test
    fun `I5-P1 viewUrl launches ACTION_VIEW with the url`() {
        installBrowser()

        assertEquals(LaunchResult.Launched, apps.viewUrl(URL))

        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse(URL), intent.data)
    }

    // --- Защита от повторного запуска --------------------------------------------------------

    /**
     * `I5-P1`. Второй запуск раньше возврата на экран и в пределах секунды — `Suppressed`,
     * второй Activity не стартует; через секунду защита снимается и без возврата.
     */
    @Test
    fun `I5-P1 a second launch before resume and within a second is suppressed`() {
        installMailClient()
        installBrowser()

        assertEquals(LaunchResult.Launched, apps.composeEmail(DRAFT))
        now += AndroidExternalApps.LAUNCH_GUARD_MS - 1
        assertEquals(LaunchResult.Suppressed, apps.composeEmail(DRAFT))
        assertEquals("защита общая для всех действий", LaunchResult.Suppressed, apps.viewUrl(URL))

        assertEquals(Intent.ACTION_SENDTO, shadowOf(activity).nextStartedActivity.action)
        assertNull("второй запуск не состоялся", shadowOf(activity).nextStartedActivity)

        now += 1
        assertEquals(LaunchResult.Launched, apps.viewUrl(URL))
    }

    /** `I5-P1`. Успешный `ON_RESUME` снимает защиту немедленно. */
    @Test
    fun `I5-P1 ON_RESUME resets the guard`() {
        installMailClient()

        assertEquals(LaunchResult.Launched, apps.composeEmail(DRAFT))
        controller.pause().stop().start().resume()

        assertEquals(LaunchResult.Launched, apps.composeEmail(DRAFT))
        assertEquals(2, generateSequence { shadowOf(activity).nextStartedActivity }.count())
    }

    // --- Отказы платформы --------------------------------------------------------------------

    /**
     * `I5-P1`. `ActivityNotFoundException` → `NoHandler`, исключение наружу не выходит;
     * неудачный запуск время не фиксирует — следующий не подавляется.
     */
    @Test
    fun `I5-P1 ActivityNotFoundException becomes NoHandler and does not arm the guard`() {
        installMailClient()
        activity.failure = ActivityNotFoundException("клиент удалён между проверкой и запуском")

        assertEquals(LaunchResult.NoHandler, apps.composeEmail(DRAFT))

        activity.failure = null
        assertEquals(LaunchResult.Launched, apps.composeEmail(DRAFT))
    }

    /** `I5-P1`. `SecurityException` → `Failed`; прочие отказы платформы — тоже `Failed`. */
    @Test
    fun `I5-P1 SecurityException becomes Failed`() {
        installMailClient()
        installBrowser()

        activity.failure = SecurityException("запуск запрещён")
        assertEquals(LaunchResult.Failed, apps.composeEmail(DRAFT))
        assertEquals(LaunchResult.Failed, apps.viewUrl(URL))

        activity.failure = IllegalStateException("иной отказ")
        assertEquals(LaunchResult.Failed, apps.viewUrl(URL))

        activity.failure = null
        assertEquals("отказы защиту не взводили", LaunchResult.Launched, apps.viewUrl(URL))
    }

    // --- Инфраструктура ------------------------------------------------------------------

    private fun installMailClient() = installHandler(
        component = ComponentName("com.example.mail", "com.example.mail.ComposeActivity"),
        filter = IntentFilter(Intent.ACTION_SENDTO).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataScheme("mailto")
        },
    )

    private fun installBrowser() = installHandler(
        component = ComponentName("com.example.browser", "com.example.browser.BrowserActivity"),
        filter = IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataScheme("https")
        },
    )

    private fun installHandler(component: ComponentName, filter: IntentFilter) {
        val packageManager = shadowOf(activity.packageManager)
        packageManager.addActivityIfNotPresent(component)
        packageManager.addIntentFilterForActivity(component, filter)
    }

    /** Activity, чей `startActivity` бросает заданный отказ платформы. */
    class LaunchingActivity : ComponentActivity() {
        var failure: RuntimeException? = null

        override fun startActivity(intent: Intent) {
            failure?.let { throw it }
            super.startActivity(intent)
        }
    }

    private companion object {
        const val START_MS = 1_000_000L
        const val URL = "https://www.britannica.com/place/Mont-Blanc"
        val DRAFT = MailDraft(
            to = "feedback@example.test",
            subject = "По порядку! — неточность в задании geo-vysota-gor-007",
            body = "Задание: geo-vysota-gor-007\nВерсия приложения: 1.0.0 (1)\nВерсия контента: 1\n\nОпишите, что не так:\n",
        )
    }
}
