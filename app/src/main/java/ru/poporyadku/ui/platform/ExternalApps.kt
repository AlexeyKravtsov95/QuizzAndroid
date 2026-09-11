package ru.poporyadku.ui.platform

import ru.poporyadku.ui.report.MailDraft

/**
 * Внешние Android-действия приложения (ITERATION_5_DESIGN.md, §8.1, I5-D19; ADR-018).
 *
 * Android-типов в сигнатурах нет: Compose-тесты подставляют фейк через
 * `LocalExternalApps` без Robolectric-теней. Единственная продуктовая реализация —
 * [AndroidExternalApps].
 *
 * Правила границы:
 * - доступность (`can*`) спрашивается лениво и не бросает;
 * - запуск никогда не выпускает исключение наружу — исход возвращается [LaunchResult];
 * - запуск выполняется из обработчика нажатия или из коллектора эффектов route-контейнера,
 *   никогда из render-функции и никогда из ViewModel.
 */
interface ExternalApps {

    /** Есть ли на устройстве обработчик `ACTION_VIEW` для [url]. */
    fun canViewUrl(url: String): Boolean

    /** Открыть [url] во внешнем приложении (браузере). */
    fun viewUrl(url: String): LaunchResult

    /** Есть ли почтовый клиент (`ACTION_SENDTO` со схемой `mailto`). */
    fun canComposeEmail(): Boolean

    /** Открыть письмо в почтовом клиенте. Отправляет его пользователь сам. */
    fun composeEmail(draft: MailDraft): LaunchResult
}

/** Исход запуска внешнего действия. Ни один исход не роняет экран. */
enum class LaunchResult {
    /** Система приняла запуск. */
    Launched,

    /** Обработчика нет (или он исчез между проверкой и запуском). */
    NoHandler,

    /** Запуск отклонён платформой (`SecurityException` и прочие отказы). */
    Failed,

    /** Повторный запуск раньше возврата на экран и раньше секунды — отброшен. */
    Suppressed,
}
