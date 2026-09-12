package ru.poporyadku.ui.platform

import ru.poporyadku.ui.report.MailDraft

/**
 * Граница внешних действий для Compose-тестов (ITERATION_5_DESIGN.md, §8.1): подставляется
 * через `LocalExternalApps`, ничего не запускает и записывает каждый запрос.
 *
 * @param canView доступность обработчика ссылки — по URL; по умолчанию браузер есть.
 * @param emailAvailable есть ли почтовый клиент; изменяемо — модель установки/удаления.
 */
class FakeExternalApps(
    private val canView: (String) -> Boolean = { true },
    var emailAvailable: Boolean = true,
) : ExternalApps {

    /** Каждый вопрос «есть ли обработчик ссылки» — проверка ленивости. */
    val viewQueries = mutableListOf<String>()
    val viewedUrls = mutableListOf<String>()
    val composedDrafts = mutableListOf<MailDraft>()

    /** Каждый шеринг: текст карточки и заголовок системного выбора. */
    val sharedTexts = mutableListOf<Pair<String, String>>()

    override fun canViewUrl(url: String): Boolean {
        viewQueries += url
        return canView(url)
    }

    override fun viewUrl(url: String): LaunchResult {
        viewedUrls += url
        return LaunchResult.Launched
    }

    override fun canComposeEmail(): Boolean = emailAvailable

    override fun composeEmail(draft: MailDraft): LaunchResult {
        composedDrafts += draft
        return if (emailAvailable) LaunchResult.Launched else LaunchResult.NoHandler
    }

    /** Системный выбор доступен всегда: доступность у шеринга не спрашивается. */
    override fun shareText(text: String, chooserTitle: String): LaunchResult {
        sharedTexts += text to chooserTitle
        return LaunchResult.Launched
    }
}
