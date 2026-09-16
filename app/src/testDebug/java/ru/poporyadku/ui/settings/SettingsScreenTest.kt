package ru.poporyadku.ui.settings

import android.content.ClipboardManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalTime
import ru.poporyadku.core.model.ThemeMode
import ru.poporyadku.domain.model.InstalledContentVersion
import ru.poporyadku.domain.model.SettingKey
import ru.poporyadku.domain.reminder.NotificationAvailability
import ru.poporyadku.ui.components.rememberReportAvailability
import ru.poporyadku.ui.platform.FakeExternalApps
import ru.poporyadku.ui.platform.LocalExternalApps
import ru.poporyadku.ui.theme.PoPoRyadkuTheme
import ru.poporyadku.ui.theme.Sizing

/**
 * `SettingsScreen` — ITERATION_5_DESIGN.md, §3.9, §3.10, §10.4: `I5-C12`, настройковая
 * часть `I5-C13`, `I5-C16`.
 *
 * Экран stateless: рендерится готовое состояние, Hilt не участвует. Доступность почтового
 * клиента в тестах `I5-C13` приходит настоящим `rememberReportAvailability()` поверх
 * фейка `ExternalApps` — ровно так, как её получает route-контейнер.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
class SettingsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    // --- I5-C12: состав, роли, одна цель на строку ---------------------------------------

    /**
     * `I5-C12`. Две строки с ролью `Switch` — каждая одна цель со своим состоянием; группа
     * темы из трёх строк с ролью `RadioButton`, выбрана ровно одна; сам `Switch` и
     * `RadioButton` отдельной цели не создают.
     */
    @Test
    fun `I5-C12 switch rows and the theme group expose one target per row`() {
        rule.setContent { Settings(state(sound = true, vibration = false, theme = ThemeMode.DARK)) }

        // С PR 6B строк-переключателей три: звук, вибрация и напоминание (I6-C9).
        val switches = rule.onAllNodes(hasRole(Role.Switch)).fetchSemanticsNodes()
        assertEquals("ровно три строки-переключателя", 3, switches.size)
        assertEquals(
            listOf(ToggleableState.On, ToggleableState.Off, ToggleableState.Off),
            switches.map { it.config[SemanticsProperties.ToggleableState] },
        )
        assertEquals(
            "состояние переключения есть только у трёх строк — у Switch внутри своей цели нет",
            3,
            rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState), useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        rule.onNodeWithTag(SettingsTestTags.ROW_SOUND).assertHasClickAction()
            .assertHeightIsAtLeast(Sizing.touchTargetMin)

        val radios = rule.onAllNodes(hasRole(Role.RadioButton)).fetchSemanticsNodes()
        assertEquals(3, radios.size)
        assertEquals(listOf(false, false, true), radios.map { it.config[SemanticsProperties.Selected] })
        assertEquals(
            "признак выбора — только у трёх строк",
            3,
            rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected), useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        assertTrue(
            "контейнер темы — selectableGroup",
            rule.onNodeWithTag(SettingsTestTags.THEME_GROUP).fetchSemanticsNode()
                .config.contains(SemanticsProperties.SelectableGroup),
        )
        ThemeMode.entries.forEach { mode ->
            rule.onNodeWithTag(SettingsTestTags.themeRow(mode)).assertHeightIsAtLeast(Sizing.touchTargetMin)
        }
    }

    /**
     * `I5-C12`. Нажатие несёт ЦЕЛЕВОЕ значение из подтверждённого состояния; значение на
     * экране от нажатия не меняется — меняет его только новое состояние.
     */
    @Test
    fun `I5-C12 clicks send target values and the screen shows only the given state`() {
        val events = mutableListOf<SettingsEvent>()
        rule.setContent { Settings(state(sound = true, vibration = false), onEvent = { events += it }) }

        rule.onNodeWithTag(SettingsTestTags.ROW_SOUND).performClick()
        rule.onNodeWithTag(SettingsTestTags.ROW_SOUND).performClick()
        rule.onNodeWithTag(SettingsTestTags.ROW_VIBRATION).performClick()
        rule.onNodeWithTag(SettingsTestTags.themeRow(ThemeMode.LIGHT)).performClick()
        rule.onNodeWithTag(SettingsTestTags.SOURCES_ROW).performScrollTo().performClick()
        rule.onNodeWithContentDescription(BACK).performScrollTo().performClick()

        assertEquals(
            listOf(
                SettingsEvent.SoundToggled(false),
                // Второе нажатие до новой эмиссии несёт то же значение — нет «туда-обратно».
                SettingsEvent.SoundToggled(false),
                SettingsEvent.VibrationToggled(true),
                SettingsEvent.ThemeSelected(ThemeMode.LIGHT),
                SettingsEvent.SourcesClicked,
                SettingsEvent.BackClicked,
            ),
            events,
        )
        // Состояние не подменено локально.
        assertEquals(
            ToggleableState.On,
            rule.onNodeWithTag(SettingsTestTags.ROW_SOUND).fetchSemanticsNode().config[SemanticsProperties.ToggleableState],
        )
        assertEquals(
            true,
            rule.onNodeWithTag(SettingsTestTags.themeRow(ThemeMode.SYSTEM)).fetchSemanticsNode()
                .config[SemanticsProperties.Selected],
        )
    }

    /**
     * `I6-C9`. Заменяет утверждение `I5-C12` «строки „Напоминание" нет»: в PR 6B группа
     * появилась (ITERATION_6_DESIGN.md, §8.1). Само утверждение итерации 5 не ослаблено,
     * а исполнено — строка существует ровно в оговорённом виде.
     */
    @Test
    fun `I6-C9 reminder group is a heading with a switch row`() {
        rule.setContent { Settings(state(), isReportAvailable = true) }

        rule.onNode(hasText("Напоминание") and isHeading()).assertExists()
        rule.onNodeWithTag(SettingsTestTags.ROW_REMINDER)
            .assertHasClickAction()
            .assertHeightIsAtLeast(Sizing.touchTargetMin)
        assertEquals(
            Role.Switch,
            rule.onNodeWithTag(SettingsTestTags.ROW_REMINDER).fetchSemanticsNode().config[SemanticsProperties.Role],
        )
        assertEquals("переключателей ровно три: звук, вибрация и напоминание", 3, rule.onAllNodes(hasRole(Role.Switch)).fetchSemanticsNodes().size)
    }

    /**
     * `I5-C12`. Порядок групп — «Звук и вибрация» → «Тема» → «О приложении» →
     * «Обратная связь»; заголовки — `heading()`.
     */
    @Test
    fun `I5-C12 groups follow the approved order and are headings`() {
        rule.setContent { Settings(state(), isReportAvailable = true) }

        val tops = GROUPS.map { title ->
            rule.onNode(hasText(title) and isHeading()).fetchSemanticsNode().positionInRoot.y
        }
        assertEquals(tops.sorted(), tops)
        rule.onNode(hasText(TITLE) and isHeading()).assertIsDisplayed()
    }

    /** `I5-C12`. «О приложении»: название, «Версия 1.0.0 (1)», автор, версия контента, правило, адрес. */
    @Test
    fun `I5-C12 about shows name version author content version rule and email`() {
        rule.setContent { Settings(state(content = InstalledContentVersion.Known(1))) }

        rule.onNodeWithText("По порядку!").assertExists()
        rule.onNodeWithText("Версия 1.0.0 (1)").assertExists()
        rule.onNodeWithText("Автор").assertExists()
        rule.onNodeWithText("Алексей").assertExists()
        rule.onNodeWithText("Версия контента: 1").assertExists()
        rule.onNodeWithText(SCORING_RULE).assertExists()
        rule.onNodeWithText("Адрес для писем").assertExists()
        rule.onNodeWithTag(SettingsTestTags.EMAIL, useUnmergedTree = true).assertExists()
        rule.onNodeWithText(EMAIL).assertExists()
        rule.onNodeWithText("Источники").assertExists()
    }

    /** `I5-C12`. Версия контента не установлена — так и сказано. */
    @Test
    fun `I5-C12 unknown content version is spelled out`() {
        rule.setContent { Settings(state(content = InstalledContentVersion.Unknown)) }

        rule.onNodeWithText("Версия контента: не установлена").assertExists()
    }

    /**
     * До первой эмиссии DataStore — skeleton пяти строк звука/вибрации/темы; версия
     * контента до чтения — skeleton; «О приложении» с версией приложения — сразу.
     */
    @Test
    fun `before the first emission rows are skeletons and the app version is shown`() {
        rule.setContent {
            Settings(
                SettingsState(
                    preferences = null,
                    reminder = null,
                    about = AboutUi("1.0.0", 1, contentVersion = null),
                    writeFailures = emptySet(),
                ),
            )
        }

        // Две строки звука, одна напоминания и три темы (I6-C9).
        assertEquals(6, rule.onAllNodesWithTag(SettingsTestTags.ROW_SKELETON).fetchSemanticsNodes().size)
        assertTrue(rule.onAllNodes(hasRole(Role.Switch)).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodes(hasRole(Role.RadioButton)).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag(SettingsTestTags.CONTENT_VERSION_SKELETON).assertExists()
        rule.onNodeWithTag(SettingsTestTags.CONTENT_VERSION).assertDoesNotExist()
        rule.onNodeWithText("Версия 1.0.0 (1)").assertExists()
    }

    // --- I5-C12: ошибка записи под своей строкой -----------------------------------------

    /**
     * `I5-C12`. «Не удалось сохранить настройку» — под строкой своего ключа и только под
     * ней; значение строки — прежнее, из состояния.
     */
    @Test
    fun `I5-C12 a write failure is shown under its own row only`() {
        rule.setContent { Settings(state(sound = true, failures = setOf(SettingKey.Sound, SettingKey.Theme))) }

        assertEquals(2, rule.onAllNodes(hasText(WRITE_FAILED)).fetchSemanticsNodes().size)
        rule.onNodeWithTag(SettingsTestTags.writeFailure(SettingKey.Vibration)).assertDoesNotExist()

        val soundRow = rule.onNodeWithTag(SettingsTestTags.ROW_SOUND).fetchSemanticsNode().boundsInRoot
        val soundFailure = rule.onNodeWithTag(SettingsTestTags.writeFailure(SettingKey.Sound)).fetchSemanticsNode().boundsInRoot
        val vibrationRow = rule.onNodeWithTag(SettingsTestTags.ROW_VIBRATION).fetchSemanticsNode().boundsInRoot
        assertTrue("под строкой «Звук»", soundFailure.top >= soundRow.bottom - 1f)
        assertTrue("выше строки «Вибрация»", soundFailure.bottom <= vibrationRow.top + 1f)

        val themeGroup = rule.onNodeWithTag(SettingsTestTags.THEME_GROUP).fetchSemanticsNode().boundsInRoot
        val themeFailure = rule.onNodeWithTag(SettingsTestTags.writeFailure(SettingKey.Theme)).fetchSemanticsNode().boundsInRoot
        assertTrue("под группой темы", themeFailure.top >= themeGroup.bottom - 1f)

        assertEquals(
            "значение — прежнее",
            ToggleableState.On,
            rule.onNodeWithTag(SettingsTestTags.ROW_SOUND).fetchSemanticsNode().config[SemanticsProperties.ToggleableState],
        )
    }

    // --- Копирование адреса --------------------------------------------------------------

    /**
     * Адрес копируется действием TalkBack «Скопировать адрес» через буфер обмена — и без
     * почтового клиента: строка адреса от доступности письма не зависит.
     */
    @Test
    fun `the copy email action puts the address to the clipboard without a mail client`() {
        rule.setContent { Settings(state(), isReportAvailable = false) }

        val row = rule.onNodeWithTag(SettingsTestTags.EMAIL_ROW).performScrollTo()
        val action = row.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == COPY }
        rule.runOnIdle { action.action() }
        rule.waitForIdle()

        val clipboard = ApplicationProvider.getApplicationContext<android.app.Application>()
            .getSystemService(ClipboardManager::class.java)
        assertEquals(EMAIL, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    /** Объявление «Адрес скопирован» — только на API 26–32; на 33+ подтверждает система. */
    @Test
    fun `the copy announcement is needed only below API 33`() {
        assertTrue(needsCopyAnnouncement(26))
        assertTrue(needsCopyAnnouncement(32))
        assertFalse(needsCopyAnnouncement(33))
        assertFalse(needsCopyAnnouncement(35))
    }

    // --- I5-C13: «Сообщить о неточности» ---------------------------------------------------

    /** `I5-C13`. Без почтового клиента действия (и группы «Обратная связь») нет в дереве. */
    @Test
    fun `I5-C13 without a mail client the report row is absent`() {
        val apps = FakeExternalApps(emailAvailable = false)
        rule.setContent { SettingsWithApps(apps) }

        rule.onNodeWithText(REPORT).assertDoesNotExist()
        rule.onNodeWithText("Обратная связь").assertDoesNotExist()
        rule.onNodeWithTag(SettingsTestTags.REPORT_ROW).assertDoesNotExist()
        // Адрес для копирования остаётся.
        rule.onNodeWithText(EMAIL).assertExists()
    }

    /** `I5-C13`. С клиентом действие есть — одна цель с ролью кнопки — и шлёт событие. */
    @Test
    fun `I5-C13 with a mail client the report row is present and sends the event`() {
        val apps = FakeExternalApps(emailAvailable = true)
        val events = mutableListOf<SettingsEvent>()
        rule.setContent { SettingsWithApps(apps, onEvent = { events += it }) }

        val row = rule.onNodeWithTag(SettingsTestTags.REPORT_ROW).performScrollTo()
        row.assertHeightIsAtLeast(Sizing.touchTargetMin)
        assertEquals(Role.Button, row.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        rule.onNodeWithText(REPORT).performClick()

        assertEquals(listOf<SettingsEvent>(SettingsEvent.ReportClicked), events)
        assertTrue("письмо открывает route-контейнер, а не экран", apps.composedDrafts.isEmpty())
    }

    /** `I5-C13`. Доступность перепроверяется на каждом `ON_START`: клиент установили в фоне. */
    @Test
    fun `I5-C13 availability is rechecked on every ON_START`() {
        val apps = FakeExternalApps(emailAvailable = false)
        val owner = TestLifecycleOwner()
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { SettingsWithApps(apps) }
        }
        rule.onNodeWithText(REPORT).assertDoesNotExist()

        apps.emailAvailable = true
        rule.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        rule.onNodeWithText(REPORT).performScrollTo().assertIsDisplayed()

        apps.emailAvailable = false
        rule.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        rule.onNodeWithText(REPORT).assertDoesNotExist()
    }

    // --- 320 dp / 200 %, темы, ландшафт ---------------------------------------------------

    /** `I5-C12`. 320 dp и 200 %: строки растут по высоте, горизонтальной прокрутки нет. */
    @Test
    @Config(qualifiers = "w320dp-h844dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `I5-C12 at 320 dp and 200 percent rows grow and nothing scrolls horizontally`() {
        rule.setContent { WithFontScale(FONT_SCALE_200) { Settings(state(failures = setOf(SettingKey.Sound)), isReportAvailable = true) } }

        listOf(
            SettingsTestTags.ROW_SOUND,
            SettingsTestTags.ROW_VIBRATION,
            SettingsTestTags.themeRow(ThemeMode.SYSTEM),
            SettingsTestTags.SOURCES_ROW,
            SettingsTestTags.REPORT_ROW,
        ).forEach { tag ->
            rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(Sizing.touchTargetMin)
        }
        rule.onNodeWithText(SCORING_RULE).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(EMAIL).performScrollTo().assertIsDisplayed()

        val list = rule.onNodeWithTag(SettingsTestTags.LIST).fetchSemanticsNode().boundsInRoot
        val rule320 = rule.onNodeWithText(SCORING_RULE).fetchSemanticsNode().boundsInRoot
        assertTrue("правило подсчёта переносится в пределах колонки", rule320.right <= list.right + 1f)
        assertNoHorizontalScrolling()
    }

    /**
     * `I5-C16`. Светлая и тёмная темы отрисовываются; смена темы — перекомпозиция того же
     * экрана, выбранное значение и строки на месте.
     */
    @Test
    fun `I5-C16 settings render in light and dark themes`() {
        var dark by mutableStateOf(false)
        rule.setContent {
            PoPoRyadkuTheme(darkTheme = dark) {
                SettingsScreen(state = state(theme = ThemeMode.DARK), isReportAvailable = true, onEvent = {})
            }
        }
        rule.onNodeWithTag(SettingsTestTags.ROW_SOUND).assertIsDisplayed()

        dark = true
        rule.onNodeWithTag(SettingsTestTags.ROW_SOUND).assertIsDisplayed()
        rule.onNodeWithTag(SettingsTestTags.REPORT_ROW).performScrollTo().assertIsDisplayed()
        assertEquals(
            true,
            rule.onNodeWithTag(SettingsTestTags.themeRow(ThemeMode.DARK)).fetchSemanticsNode()
                .config[SemanticsProperties.Selected],
        )
    }

    @Test
    @Config(qualifiers = "w844dp-h390dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `I5-C12 in landscape the column is limited and the list scrolls`() {
        rule.setContent { Settings(state(), isReportAvailable = true) }

        val list = rule.onNodeWithTag(SettingsTestTags.LIST).fetchSemanticsNode()
        val maxWidth = with(rule.density) { Sizing.contentMaxWidth.roundToPx() }
        assertTrue("колонка ${list.size.width} ≤ $maxWidth", list.size.width <= maxWidth)
        rule.onNodeWithTag(SettingsTestTags.REPORT_ROW).performScrollTo().assertIsDisplayed()
        assertNoHorizontalScrolling()
    }

    // --- Инфраструктура ------------------------------------------------------------------

    @Composable
    private fun Settings(
        state: SettingsState,
        isReportAvailable: Boolean = false,
        onEvent: (SettingsEvent) -> Unit = {},
        darkTheme: Boolean = false,
        fontScale: Float = 1f,
    ) {
        val density = Density(
            density = LocalDensity.current.density,
            fontScale = fontScale,
        )
        CompositionLocalProvider(LocalDensity provides density) {
            PoPoRyadkuTheme(darkTheme = darkTheme) {
                SettingsScreen(state = state, isReportAvailable = isReportAvailable, onEvent = onEvent)
            }
        }
    }

    /** Строки напоминания для экранных тестов (ITERATION_6_DESIGN.md, §8.1). */
    private fun reminderUi(
        enabledShown: Boolean,
        time: LocalTime = LocalTime.of(9, 0),
        unavailability: NotificationAvailability? = null,
        showPermissionHint: Boolean = false,
    ) = ReminderUi(
        enabledShown = enabledShown,
        time = time,
        unavailability = unavailability,
        showPermissionHint = showPermissionHint,
    )

    /** Доступность — настоящим `rememberReportAvailability()` поверх фейка, как у route. */
    @Composable
    private fun SettingsWithApps(apps: FakeExternalApps, onEvent: (SettingsEvent) -> Unit = {}) {
        CompositionLocalProvider(LocalExternalApps provides apps) {
            Settings(state(), isReportAvailable = rememberReportAvailability(), onEvent = onEvent)
        }
    }

    @Composable
    private fun WithFontScale(scale: Float, content: @Composable () -> Unit) {
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) { content() }
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private fun isHeading() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    private fun assertNoHorizontalScrolling() {
        assertTrue(
            rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    private fun state(
        sound: Boolean = true,
        vibration: Boolean = true,
        theme: ThemeMode = ThemeMode.SYSTEM,
        content: InstalledContentVersion? = InstalledContentVersion.Known(1),
        failures: Set<SettingKey> = emptySet(),
        reminder: ReminderUi? = ReminderUi(
            enabledShown = false,
            time = LocalTime.of(9, 0),
            unavailability = null,
            showPermissionHint = false,
        ),
    ) = SettingsState(
        preferences = PreferencesUi(soundEnabled = sound, vibrationEnabled = vibration, themeMode = theme),
        reminder = reminder,
        about = AboutUi(versionName = "1.0.0", versionCode = 1, contentVersion = content),
        writeFailures = failures,
    )

    // --- I6-C9 / I6-C11: напоминание ------------------------------------------------------

    /**
     * `I6-C9`. Строка времени видна **только** при визуально включённом напоминании: без
     * доступа её быть не должно, иначе экран обещал бы то, чего не будет.
     */
    @Test
    fun `I6-C9 the time row is absent while the reminder is shown disabled`() {
        rule.setContent { Settings(state(reminder = reminderUi(enabledShown = false))) }

        rule.onNodeWithTag(SettingsTestTags.ROW_REMINDER_TIME).assertDoesNotExist()
    }

    /** `I6-C9`. …и появляется, как только напоминание показано включённым. */
    @Test
    fun `I6-C9 the time row appears when the reminder is shown enabled`() {
        rule.setContent { Settings(state(reminder = reminderUi(enabledShown = true))) }

        rule.onNodeWithTag(SettingsTestTags.ROW_REMINDER_TIME).assertExists()
    }

    /**
     * `I6-C9`. Для **каждой** недоступности под строкой — одна и та же подсказка и
     * действие (**O6-4**); нажатие отправляет `OpenNotificationSettingsClicked`.
     */
    @Test
    fun `I6-C9 every unavailability shows the same hint and action`() {
        // Причина недоступности меняется состоянием: setContent в одном тесте — один раз.
        var status by mutableStateOf(NotificationAvailability.RuntimePermissionMissing)
        val events = mutableListOf<SettingsEvent>()
        rule.setContent {
            Settings(
                state(
                    reminder = reminderUi(
                        enabledShown = false,
                        unavailability = status,
                        showPermissionHint = true,
                    ),
                ),
                onEvent = { events += it },
            )
        }

        listOf(
            NotificationAvailability.RuntimePermissionMissing,
            NotificationAvailability.AppNotificationsDisabled,
            NotificationAvailability.ChannelDisabled,
        ).forEachIndexed { index, value ->
            status = value
            rule.waitForIdle()

            rule.onNodeWithText(PERMISSION_HINT).assertExists()
            rule.onNodeWithTag(SettingsTestTags.REMINDER_OPEN_SYSTEM)
                .performScrollTo()
                .assertHeightIsAtLeast(Sizing.touchTargetMin)
                .performClick()

            assertEquals("$value", index + 1, events.size)
            assertEquals("$value", SettingsEvent.OpenNotificationSettingsClicked, events.last())
        }
    }

    /** `I6-C9`. При доступе подсказки нет. */
    @Test
    fun `I6-C9 there is no hint when notifications are allowed`() {
        rule.setContent { Settings(state(reminder = reminderUi(enabledShown = true))) }

        rule.onNodeWithTag(SettingsTestTags.REMINDER_HINT).assertDoesNotExist()
        rule.onNodeWithText(PERMISSION_HINT).assertDoesNotExist()
    }

    /** `I6-C9`. Ошибка записи — под своей строкой, отдельно для переключателя и времени. */
    @Test
    fun `I6-C9 reminder write failures appear under their own rows`() {
        rule.setContent {
            Settings(
                state(
                    reminder = reminderUi(enabledShown = true),
                    failures = setOf(SettingKey.Reminder, SettingKey.ReminderTime),
                ),
            )
        }

        rule.onNodeWithTag(SettingsTestTags.writeFailure(SettingKey.Reminder)).assertExists()
        rule.onNodeWithTag(SettingsTestTags.writeFailure(SettingKey.ReminderTime)).assertExists()
    }

    /**
     * `I6-C9`. На 320 dp при 200 % строки напоминания видны, цели не меньше минимума и
     * горизонтальной прокрутки нет.
     */
    @Test
    @Config(qualifiers = "w320dp-h844dp")
    fun `I6-C9 the reminder rows survive 320 dp at 200 percent`() {
        rule.setContent {
            Settings(state(reminder = reminderUi(enabledShown = true)), fontScale = FONT_SCALE_200)
        }

        rule.onNodeWithTag(SettingsTestTags.ROW_REMINDER)
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(Sizing.touchTargetMin)
        rule.onNodeWithTag(SettingsTestTags.ROW_REMINDER_TIME)
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(Sizing.touchTargetMin)
    }

    /** `I6-C9`. Тёмная тема: строки напоминания на месте. */
    @Test
    fun `I6-C9 the reminder rows render in the dark theme`() {
        rule.setContent {
            Settings(state(reminder = reminderUi(enabledShown = true)), darkTheme = true)
        }

        rule.onNodeWithTag(SettingsTestTags.ROW_REMINDER).assertExists()
        rule.onNodeWithTag(SettingsTestTags.ROW_REMINDER_TIME).assertExists()
    }

    /**
     * `I6-C11`. Описание строки времени для TalkBack — «Время напоминания, 9:00»: подпись
     * и значение одним узлом.
     */
    @Test
    fun `I6-C11 the time row is announced with its value`() {
        rule.setContent { Settings(state(reminder = reminderUi(enabledShown = true))) }

        rule.onNodeWithTag(SettingsTestTags.ROW_REMINDER_TIME)
            .assertContentDescriptionEquals("Время напоминания, 9:00")
    }

    /**
     * `I6-C11`. Выбор времени раскрывается **внутри списка** (**O6-3**): диалога нет,
     * встроенный `TimeInput` появляется под строкой.
     */
    @Test
    fun `I6-C11 the time picker is inline and not a dialog`() {
        rule.setContent { Settings(state(reminder = reminderUi(enabledShown = true))) }

        rule.onNodeWithTag(SettingsTestTags.REMINDER_TIME_INPUT).assertDoesNotExist()
        rule.onNodeWithTag(SettingsTestTags.ROW_REMINDER_TIME).performScrollTo().performClick()

        rule.onNodeWithTag(SettingsTestTags.REMINDER_TIME_INPUT).assertExists()
        assertTrue("диалога быть не должно", rule.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText(CONFIRM).assertExists()
        rule.onNodeWithText(CANCEL).assertExists()
    }

    /** `I6-C11`. «Отмена» закрывает выбор и **не** шлёт ни одного события записи. */
    @Test
    fun `I6-C11 cancel closes the picker without any event`() {
        val events = mutableListOf<SettingsEvent>()
        rule.setContent {
            Settings(state(reminder = reminderUi(enabledShown = true)), onEvent = { events += it })
        }

        rule.onNodeWithTag(SettingsTestTags.ROW_REMINDER_TIME).performScrollTo().performClick()
        rule.onNodeWithTag(SettingsTestTags.REMINDER_TIME_CANCEL).performScrollTo().performClick()

        rule.onNodeWithTag(SettingsTestTags.REMINDER_TIME_INPUT).assertDoesNotExist()
        assertTrue("событий записи нет", events.isEmpty())
    }

    /** `I6-C11`. «Сохранить» отправляет ровно одно `ReminderTimeChosen` с текущим значением. */
    @Test
    fun `I6-C11 confirming sends exactly one ReminderTimeChosen`() {
        val events = mutableListOf<SettingsEvent>()
        rule.setContent {
            Settings(
                state(reminder = reminderUi(enabledShown = true, time = LocalTime.of(10, 30))),
                onEvent = { events += it },
            )
        }

        rule.onNodeWithTag(SettingsTestTags.ROW_REMINDER_TIME).performScrollTo().performClick()
        rule.onNodeWithTag(SettingsTestTags.REMINDER_TIME_CONFIRM).performScrollTo().performClick()

        assertEquals(listOf(SettingsEvent.ReminderTimeChosen(LocalTime.of(10, 30))), events)
        rule.onNodeWithTag(SettingsTestTags.REMINDER_TIME_INPUT).assertDoesNotExist()
    }

    private companion object {
        const val FONT_SCALE_200 = 2f
        const val PERMISSION_HINT = "Разрешите уведомления в настройках системы"
        const val CONFIRM = "Сохранить"
        const val CANCEL = "Отмена"
        const val TITLE = "Настройки"
        const val BACK = "Назад"
        const val REPORT = "Сообщить о неточности"
        const val COPY = "Скопировать адрес"
        const val EMAIL = "alexey.kravtsov95@gmail.com"
        const val WRITE_FAILED = "Не удалось сохранить настройку"
        const val SCORING_RULE = "Баллы даются за каждую пару карточек в правильном порядке: " +
            "у четырёх карточек шесть пар, максимум 6 баллов за задание и 18 за день"
        // «Напоминание» — между звуком и темой (DESIGN_PRINCIPLES.md §3, O6-3).
        val GROUPS = listOf("Звук и вибрация", "Напоминание", "Тема", "О приложении", "Обратная связь")
    }
}
