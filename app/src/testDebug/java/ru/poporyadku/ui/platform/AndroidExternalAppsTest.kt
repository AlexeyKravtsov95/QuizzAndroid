package ru.poporyadku.ui.platform

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
 * `AndroidExternalApps` — `I5-P1` (ITERATION_5_DESIGN.md, §3.12, §8.1, I5-D19) и `I5-P2`
 * (§3.13, шеринг карточки).
 *
 * Обработчики регистрируются в `ShadowPackageManager` фильтрами намерений — так, как их
 * объявил бы браузер или почтовый клиент: `SENDTO mailto:` с любыми параметрами и
 * `VIEW https`. Отказы платформы моделирует Activity, чьи `startActivity` и
 * `getPackageManager` бросают.
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

    /**
     * `I5-P1`. Отказ `PackageManager` при разрешении обработчика наружу не выходит:
     * `can*` отвечают «нет» (строка источника деградирует, действие письма скрыто), запуск —
     * `Failed`, ничего не стартует; после восстановления сервиса всё работает, защита от
     * повторного запуска отказом не взведена.
     */
    @Test
    fun `I5-P1 a PackageManager failure during resolution stays inside the boundary`() {
        installMailClient()
        installBrowser()
        activity.packageManagerFailure = IllegalStateException("Package manager has died")

        assertFalse(apps.canComposeEmail())
        assertFalse(apps.canViewUrl(URL))
        assertEquals(LaunchResult.Failed, apps.composeEmail(DRAFT))
        assertEquals(LaunchResult.Failed, apps.viewUrl(URL))
        assertNull(shadowOf(activity).nextStartedActivity)

        activity.packageManagerFailure = null
        assertTrue(apps.canComposeEmail())
        assertEquals(LaunchResult.Launched, apps.composeEmail(DRAFT))
    }

    // --- I5-P2: системный шеринг -------------------------------------------------------

    /**
     * `I5-P2`. Наружу уходит системный выбор, внутри него — `ACTION_SEND` с `text/plain` и
     * посимвольно тем текстом, что передали; заголовок выбора — переданный аргумент.
     * Обработчик заранее не разрешается: выбор приложения разрешается системой всегда.
     */
    @Test
    fun `I5-P2 shareText launches a chooser over ACTION_SEND with the exact text`() {
        assertEquals(LaunchResult.Launched, apps.shareText(CARD, CHOOSER_TITLE))

        val chooser = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals(CHOOSER_TITLE, chooser.getStringExtra(Intent.EXTRA_TITLE))

        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(CARD, send.getStringExtra(Intent.EXTRA_TEXT))
        // Семь строк доезжают до Intent как есть: ни обрезки, ни лишнего перевода строки.
        assertEquals(7, send.getStringExtra(Intent.EXTRA_TEXT)!!.lines().size)
        assertNull("запусков ровно один", shadowOf(activity).nextStartedActivity)
    }

    /** `I5-P2`. Защита от повторного запуска общая: второй быстрый шеринг подавляется. */
    @Test
    fun `I5-P2 a second share before resume and within a second is suppressed`() {
        installMailClient()

        assertEquals(LaunchResult.Launched, apps.shareText(CARD, CHOOSER_TITLE))
        now += AndroidExternalApps.LAUNCH_GUARD_MS - 1
        assertEquals(LaunchResult.Suppressed, apps.shareText(CARD, CHOOSER_TITLE))
        assertEquals("защита общая для всех действий", LaunchResult.Suppressed, apps.composeEmail(DRAFT))

        assertEquals(Intent.ACTION_CHOOSER, shadowOf(activity).nextStartedActivity.action)
        assertNull("второй запуск не состоялся", shadowOf(activity).nextStartedActivity)
    }

    /** `I5-P2`. Возврат на экран снимает защиту, и следующий шеринг снова проходит. */
    @Test
    fun `I5-P2 ON_RESUME releases the guard for sharing`() {
        assertEquals(LaunchResult.Launched, apps.shareText(CARD, CHOOSER_TITLE))
        controller.pause().stop().start().resume()

        assertEquals(LaunchResult.Launched, apps.shareText(CARD, CHOOSER_TITLE))
        assertEquals(2, generateSequence { shadowOf(activity).nextStartedActivity }.count())
    }

    /**
     * `I5-P2`. Любой отказ запуска шеринга даёт `Failed` и наружу не выходит: у системного
     * выбора обработчику исчезать некуда, поэтому `NoHandler` здесь не бывает. Неудачный
     * запуск защиту не взводит — следующий не подавляется.
     */
    @Test
    fun `I5-P2 a failing share is Failed and does not arm the guard`() {
        activity.failure = ActivityNotFoundException("системный выбор недоступен")
        assertEquals(LaunchResult.Failed, apps.shareText(CARD, CHOOSER_TITLE))

        activity.failure = SecurityException("запуск запрещён")
        assertEquals(LaunchResult.Failed, apps.shareText(CARD, CHOOSER_TITLE))

        activity.failure = IllegalStateException("иной отказ платформы")
        assertEquals(LaunchResult.Failed, apps.shareText(CARD, CHOOSER_TITLE))
        assertNull("ни один отказ не запустил Activity", shadowOf(activity).nextStartedActivity)

        activity.failure = null
        assertEquals("отказы защиту не взводили", LaunchResult.Launched, apps.shareText(CARD, CHOOSER_TITLE))
    }

    /** `I5-P2`. Шеринг не спрашивает `PackageManager`: его отказ на запуск не влияет. */
    @Test
    fun `I5-P2 sharing does not depend on handler resolution`() {
        activity.packageManagerFailure = IllegalStateException("Package manager has died")

        assertEquals(LaunchResult.Launched, apps.shareText(CARD, CHOOSER_TITLE))
        assertEquals(Intent.ACTION_CHOOSER, shadowOf(activity).nextStartedActivity.action)
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
        val packageManager = shadowOf(activity.applicationContext.packageManager)
        packageManager.addActivityIfNotPresent(component)
        packageManager.addIntentFilterForActivity(component, filter)
    }

    /** Activity, чьи `startActivity` и `getPackageManager` бросают заданные отказы платформы. */
    class LaunchingActivity : ComponentActivity() {
        var failure: RuntimeException? = null
        var packageManagerFailure: RuntimeException? = null

        override fun startActivity(intent: Intent) {
            failure?.let { throw it }
            super.startActivity(intent)
        }

        override fun getPackageManager(): PackageManager {
            packageManagerFailure?.let { throw it }
            return super.getPackageManager()
        }
    }

    private companion object {
        const val START_MS = 1_000_000L
        const val URL = "https://www.britannica.com/place/Mont-Blanc"
        const val CHOOSER_TITLE = "Поделиться результатом"

        /** Карточка дня из семи строк — ровно такая, какую строит `ShareCardBuilder`. */
        val CARD = listOf(
            "По порядку! · День 12",
            "15/18",
            "🟩🟩🟩🟩🟩🟩",
            "🟩🟩🟩🟩⬜⬜",
            "🟩🟩🟩🟩🟩⬜",
            "Серия: 6 дней",
            "https://poporyadku.invalid/",
        ).joinToString("\n")
        val DRAFT = MailDraft(
            to = "feedback@example.test",
            subject = "По порядку! — неточность в задании geo-vysota-gor-007",
            body = "Задание: geo-vysota-gor-007\nВерсия приложения: 1.0.0 (1)\nВерсия контента: 1\n\nОпишите, что не так:\n",
        )
    }
}
