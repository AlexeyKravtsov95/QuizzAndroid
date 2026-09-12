package ru.poporyadku.ui.platform

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ru.poporyadku.ui.report.MailDraft
import ru.poporyadku.ui.report.MailtoUri

/**
 * Android-реализация [ExternalApps] (ITERATION_5_DESIGN.md, §3.12, §8.1, I5-D19; ADR-018).
 *
 * **Единственное место в `src/main`, где создаются и запускаются `Intent` внешних
 * действий.** Доступность — `resolveActivity(...) != null`; на API 30+ она видна только
 * благодаря объявлениям `<queries>` в манифесте (`VIEW http/https`, `SENDTO mailto`).
 *
 * **Одно нажатие — один запуск.** Второй запуск раньше возврата на экран (`ON_RESUME`
 * activity) и раньше [LAUNCH_GUARD_MS] отбрасывается как [LaunchResult.Suppressed].
 * Секундный предел снимает блокировку, если система открыла выбор без паузы Activity.
 * Время фиксирует только успешный `startActivity`: неудачная попытка следующую не
 * блокирует.
 *
 * Исключения наружу не выходят ни из разрешения обработчика, ни из запуска: отказ
 * `PackageManager` при `resolveActivity` — «обработчика нет» для `can*` и
 * [LaunchResult.Failed] для запуска; `ActivityNotFoundException` →
 * [LaunchResult.NoHandler], `SecurityException` и остальные отказы платформы →
 * [LaunchResult.Failed]. Экран при этом остаётся на месте и сообщения не показывает:
 * действие необязательное, а доступность перепроверится на следующем `ON_START`.
 *
 * Продуктовый экземпляр один на Activity — его создаёт `MainActivity` и отдаёт экранам
 * через `LocalExternalApps`.
 *
 * @param clock монотонное время в миллисекундах (`SystemClock.elapsedRealtime`).
 */
class AndroidExternalApps(
    private val activity: ComponentActivity,
    private val clock: () -> Long,
) : ExternalApps {

    /** Время последнего успешного запуска; `null` — защиты нет. */
    private var lastLaunchAt: Long? = null

    init {
        // Возврат на экран снимает защиту: пользователь уже вернулся из внешнего приложения.
        activity.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) lastLaunchAt = null
            },
        )
    }

    override fun canViewUrl(url: String): Boolean = resolve(viewIntent(url)) == Resolution.Found

    override fun viewUrl(url: String): LaunchResult = launchIfResolvable(viewIntent(url))

    override fun canComposeEmail(): Boolean =
        resolve(Intent(Intent.ACTION_SENDTO, MAILTO_PROBE.toUri())) == Resolution.Found

    override fun composeEmail(draft: MailDraft): LaunchResult {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            // Единственный разбор строки URI: тема и тело — и в URI (их читает
            // большинство клиентов SENDTO), и в extras (для клиентов, игнорирующих
            // параметры mailto:).
            data = MailtoUri.of(draft).toUri()
            putExtra(Intent.EXTRA_EMAIL, arrayOf(draft.to))
            putExtra(Intent.EXTRA_SUBJECT, draft.subject)
            putExtra(Intent.EXTRA_TEXT, draft.body)
        }
        return launchIfResolvable(intent)
    }

    private fun viewIntent(url: String): Intent = Intent(Intent.ACTION_VIEW, url.toUri())

    /**
     * Есть ли обработчик. Разрешение тоже под защитой: `can*` вызываются из композиции и
     * при построении экрана, и отказ `PackageManager` (например, умерший системный сервис
     * — `RuntimeException`) не должен уронить экран. Отказ разрешения — «обработчика не
     * видно» для `can*` и `Failed` для запуска.
     */
    private fun resolve(intent: Intent): Resolution =
        try {
            if (intent.resolveActivity(activity.packageManager) != null) Resolution.Found else Resolution.Missing
        } catch (e: RuntimeException) {
            Resolution.Failed
        }

    /** Перед открытием — проверка обработчика: без него запуск даже не пробуется. */
    private fun launchIfResolvable(intent: Intent): LaunchResult =
        when (resolve(intent)) {
            Resolution.Found -> launch(intent)
            Resolution.Missing -> LaunchResult.NoHandler
            Resolution.Failed -> LaunchResult.Failed
        }

    private enum class Resolution { Found, Missing, Failed }

    private fun launch(intent: Intent): LaunchResult {
        val now = clock()
        val previous = lastLaunchAt
        if (previous != null && now - previous < LAUNCH_GUARD_MS) return LaunchResult.Suppressed
        return try {
            activity.startActivity(intent)
            lastLaunchAt = now
            LaunchResult.Launched
        } catch (e: ActivityNotFoundException) {
            LaunchResult.NoHandler
        } catch (e: SecurityException) {
            LaunchResult.Failed
        } catch (e: RuntimeException) {
            // Любой другой отказ платформы — тоже не повод уронить экран.
            LaunchResult.Failed
        }
    }

    companion object {
        /** Окно защиты от повторного запуска до возврата на экран. */
        const val LAUNCH_GUARD_MS = 1_000L

        /** Проба доступности почтового клиента — схема без адреса. */
        private const val MAILTO_PROBE = "mailto:"
    }
}
