package ru.poporyadku.ui.components

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.poporyadku.core.model.Puzzle
import ru.poporyadku.ui.theme.PoPoRyadkuTheme

/**
 * `SourcesBlock` и `SourceRow` — три состояния строки источника (COMPONENTS.md) и
 * объявляемая TalkBack семантика.
 *
 * Наличие обработчика `ACTION_VIEW` — часть условия состояния `link`, поэтому тесты
 * управляют им явно: браузер регистрируется в `ShadowPackageManager` там, где строка
 * обязана стать кликабельной, и не регистрируется там, где обязана деградировать.
 * Так проверяется само правило, а не то, что оказалось установлено в образе.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
class SourcesBlockTest {

    @get:Rule
    val rule = createComposeRule()

    // --- SourcesBlock -------------------------------------------------------------------

    /** Блок свёрнут по умолчанию, объявляет это состояние словом и имеет роль. */
    @Test
    fun `sources block starts collapsed and says so`() {
        rule.setContent { Sources(listOf(referenceOnlySource)) }

        rule.onNodeWithText(SOURCES).assertHasClickAction()
        assertEquals(COLLAPSED, stateDescriptionOf(SOURCES))
        rule.onNodeWithText(referenceOnlySource.title).assertDoesNotExist()
    }

    /**
     * Роль заголовка объявляется ЯВНО: без неё `toggleable` не выставляет
     * `SemanticsProperties.Role`, и TalkBack прочитал бы заголовок как обычный текст,
     * за которым почему-то есть действие.
     */
    @Test
    fun `sources block header announces its role`() {
        rule.setContent { Sources(listOf(referenceOnlySource)) }

        assertEquals(Role.Button, roleOf(SOURCES))

        // Роль не теряется при смене состояния.
        rule.onNodeWithText(SOURCES).performClick()
        assertEquals(EXPANDED, stateDescriptionOf(SOURCES))
        assertEquals(Role.Button, roleOf(SOURCES))
    }

    /** После раскрытия меняется и accessibility-состояние, и содержимое. */
    @Test
    fun `sources block changes its accessibility state when expanded`() {
        rule.setContent { Sources(listOf(referenceOnlySource)) }

        assertEquals(COLLAPSED, stateDescriptionOf(SOURCES))

        rule.onNodeWithText(SOURCES).performClick()

        assertEquals(EXPANDED, stateDescriptionOf(SOURCES))
        rule.onNodeWithText(referenceOnlySource.title).assertExists()

        // И обратно: состояние не «залипает» на раскрытом.
        rule.onNodeWithText(SOURCES).performClick()
        assertEquals(COLLAPSED, stateDescriptionOf(SOURCES))
    }

    // --- SourceRow.link -----------------------------------------------------------------

    /**
     * `link`: есть `url` и найден обработчик — строка кликабельна целиком, имеет роль
     * кнопки и описание с НАЗВАНИЕМ источника, а не только шаблонной фразой.
     */
    @Test
    fun `link row is clickable announces the role and names the source`() {
        installBrowser()
        renderExpanded(listOf(linkSource))

        val description = "${linkSource.title}. Открыть источник в браузере"
        val node = rule.onNodeWithContentDescription(description)

        node.assertExists()
        node.assertHasClickAction()
        assertEquals(
            "роль кликабельной строки объявляется явно",
            Role.Button,
            node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role),
        )
    }

    /** Нажатие на строку `link` отправляет `ACTION_VIEW` с её `url`. */
    @Test
    fun `link row opens the url`() {
        installBrowser()
        renderExpanded(listOf(linkSource))

        rule.onNodeWithContentDescription("${linkSource.title}. Открыть источник в браузере")
            .performClick()

        val started = shadowOf(
            ApplicationProvider.getApplicationContext<android.app.Application>(),
        ).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started?.action)
        assertEquals(linkSource.url?.toUri(), started?.data)
    }

    // --- SourceRow.urlPlainText ---------------------------------------------------------

    /**
     * `urlPlainText`: `url` есть, обработчика нет — строка НЕ кликабельна, `url`
     * показывается обычным читаемым текстом, ошибки нет и строка не прячется.
     */
    @Test
    fun `url plain text row has no click action`() {
        // Браузер намеренно не регистрируется.
        renderExpanded(listOf(linkSource))

        rule.onNodeWithText(linkSource.title).assertHasNoClickAction()
        rule.onNodeWithText(linkSource.url!!).assertExists()
        rule.onNodeWithContentDescription(
            "${linkSource.title}. Открыть источник в браузере",
        ).assertDoesNotExist()
    }

    // --- SourceRow.referenceOnly --------------------------------------------------------

    /** `referenceOnly`: `url` отсутствует — строка некликабельна, `reference` показан. */
    @Test
    fun `reference only row has no click action`() {
        installBrowser()
        renderExpanded(listOf(referenceOnlySource))

        rule.onNodeWithText(referenceOnlySource.title).assertHasNoClickAction()
        rule.onNodeWithText(referenceOnlySource.reference!!).assertExists()
    }

    /** Дата обращения показывается в любом из трёх состояний, без исключений. */
    @Test
    fun `accessed at is always shown`() {
        installBrowser()
        renderExpanded(listOf(linkSource, referenceOnlySource))

        assertEquals(
            2,
            rule.onAllNodesWithTextContaining("Обращение: 2026-08-20").fetchSemanticsNodes().size,
        )
    }

    /** Raw-значение `kind` пользователю не показывается — только русская подпись. */
    @Test
    fun `kind is shown as a russian label`() {
        installBrowser()
        renderExpanded(listOf(linkSource))

        rule.onNodeWithText("Энциклопедия").assertExists()
        rule.onNodeWithText(linkSource.kind).assertDoesNotExist()
    }

    // --- Инфраструктура -------------------------------------------------------------------

    @Composable
    private fun Sources(sources: List<Puzzle.Source>) {
        PoPoRyadkuTheme(darkTheme = false) {
            SourcesBlock(sources = sources)
        }
    }

    /**
     * Рендерит блок и раскрывает его нажатием — блок всегда начинается свёрнутым, и
     * добраться до строк можно только пользовательским действием.
     */
    private fun renderExpanded(sources: List<Puzzle.Source>) {
        rule.setContent { Sources(sources) }
        rule.onNodeWithText(SOURCES).performClick()
    }

    /** Регистрирует обработчик `ACTION_VIEW` для https — модель установленного браузера. */
    private fun installBrowser() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val intent = Intent(Intent.ACTION_VIEW, "https://www.britannica.com/".toUri())
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.example.browser"
                name = "BrowserActivity"
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(intent, resolveInfo)
    }

    private fun roleOf(text: String): Role? =
        rule.onNodeWithText(text)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.Role)

    private fun stateDescriptionOf(text: String): String? =
        rule.onNodeWithText(text)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.StateDescription)

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextContaining(
        text: String,
    ) = onAllNodes(androidx.compose.ui.test.hasText(text, substring = true))

    private companion object {
        const val SOURCES = "Источники"
        const val COLLAPSED = "Свёрнуто"
        const val EXPANDED = "Развёрнуто"

        val linkSource = Puzzle.Source(
            sourceId = "s1",
            title = "Encyclopaedia Britannica",
            kind = "encyclopedia",
            url = "https://www.britannica.com/",
            reference = null,
            accessedAt = "2026-08-20",
            note = null,
        )

        val referenceOnlySource = Puzzle.Source(
            sourceId = "s2",
            title = "Большая российская энциклопедия",
            kind = "encyclopedia",
            url = null,
            reference = "БРЭ. Т. 35. М., 2017",
            accessedAt = "2026-08-20",
            note = null,
        )
    }
}
