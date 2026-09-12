package ru.poporyadku.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import ru.poporyadku.R
import ru.poporyadku.core.model.ThemeMode
import ru.poporyadku.domain.model.InstalledContentVersion
import ru.poporyadku.domain.model.SettingKey
import ru.poporyadku.ui.components.AppTopBar
import ru.poporyadku.ui.components.ReportInaccuracyAction
import ru.poporyadku.ui.components.ReportInaccuracyStyle
import ru.poporyadku.ui.theme.PoPoRyadkuTheme
import ru.poporyadku.ui.theme.Sizing
import ru.poporyadku.ui.theme.Spacing

/** Стабильные testTag экрана настроек. */
object SettingsTestTags {
    const val SCREEN = "settings_screen"
    const val LIST = "settings_list"
    const val ROW_SOUND = "settings_row_sound"
    const val ROW_VIBRATION = "settings_row_vibration"
    const val THEME_GROUP = "settings_theme_group"
    const val ROW_SKELETON = "settings_row_skeleton"
    const val ABOUT_APP = "settings_about_app"
    const val ABOUT_AUTHOR = "settings_about_author"
    const val CONTENT_VERSION = "settings_content_version"
    const val CONTENT_VERSION_SKELETON = "settings_content_version_skeleton"
    const val SCORING_RULE = "settings_scoring_rule"
    const val EMAIL_ROW = "settings_email_row"
    const val EMAIL = "settings_email"
    const val SOURCES_ROW = "settings_sources_row"
    const val REPORT_ROW = "settings_report_row"

    fun themeRow(mode: ThemeMode): String = "settings_row_theme_${mode.name.lowercase()}"

    fun writeFailure(key: SettingKey): String = "settings_write_failed_${key.name.lowercase()}"
}

/**
 * Настройки (ITERATION_5_DESIGN.md, §3.9, §3.10; UX_FLOW.md §8; COMPONENTS.md,
 * «Settings row», «ReportInaccuracyAction»).
 *
 * Stateless: `SettingsScreen(state, isReportAvailable, onEvent)`, ни Hilt, ни ViewModel.
 *
 * **Один прокручиваемый плоский список** без карточек вокруг строк, группы по
 * DESIGN_PRINCIPLES.md §3: «Звук и вибрация» → «Тема» → «О приложении» →
 * «Обратная связь». Шапка — первый элемент того же списка: при 200 % и в ландшафте она
 * прокручивается вместе со строками и не отъедает высоту. Переключателя напоминания нет
 * ни визуально, ни в дереве семантики — он появится в итерации 6.
 *
 * Значения переключателей и темы — только подтверждённые DataStore; ни одного
 * `remember { mutableStateOf(...) }` для значения настройки здесь нет. Событие несёт
 * целевое значение, вычисленное из этого состояния.
 */
@Composable
fun SettingsScreen(
    state: SettingsState,
    isReportAvailable: Boolean,
    onEvent: (SettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(SettingsTestTags.SCREEN),
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isCompact = maxWidth < Sizing.compactWidthBreakpoint
            val margin = if (isCompact) Spacing.marginCompact else Spacing.marginDefault
            val isWideOrLandscape = maxWidth >= Sizing.mediumWidthBreakpoint || maxWidth > maxHeight
            val columnWidth = if (isWideOrLandscape) {
                Modifier.widthIn(max = Sizing.contentMaxWidth)
            } else {
                Modifier.fillMaxWidth()
            }

            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    modifier = columnWidth
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                        .padding(bottom = Spacing.section)
                        .testTag(SettingsTestTags.LIST),
                ) {
                    AppTopBar(
                        title = stringResource(R.string.settings_title),
                        horizontalMargin = margin,
                        onBackClick = { onEvent(SettingsEvent.BackClicked) },
                    )
                    Column(modifier = Modifier.padding(horizontal = margin)) {
                        SoundGroup(state, onEvent)
                        ThemeGroup(state, onEvent)
                        AboutGroup(state.about, onEvent)
                        FeedbackGroup(isReportAvailable, onEvent)
                    }
                }
            }
        }
    }
}

// --- Группы ------------------------------------------------------------------------------

@Composable
private fun SoundGroup(state: SettingsState, onEvent: (SettingsEvent) -> Unit) {
    SettingsGroupHeader(stringResource(R.string.settings_group_sound))
    val prefs = state.preferences
    if (prefs == null) {
        repeat(SOUND_ROWS) { SettingsRowSkeleton(modifier = Modifier.testTag(SettingsTestTags.ROW_SKELETON)) }
        return
    }
    SettingBlock(key = SettingKey.Sound, failures = state.writeFailures) {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_sound),
            checked = prefs.soundEnabled,
            onCheckedChange = { onEvent(SettingsEvent.SoundToggled(it)) },
            modifier = Modifier.testTag(SettingsTestTags.ROW_SOUND),
        )
    }
    SettingBlock(key = SettingKey.Vibration, failures = state.writeFailures) {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_vibration),
            checked = prefs.vibrationEnabled,
            onCheckedChange = { onEvent(SettingsEvent.VibrationToggled(it)) },
            modifier = Modifier.testTag(SettingsTestTags.ROW_VIBRATION),
        )
    }
}

/**
 * Тема — три вертикальные строки-радиокнопки, а не сегментированный переключатель: на
 * 320 dp и 200 % три подписи в одну строку не помещаются. Выбрана ровно одна — та, что
 * подтвердил DataStore.
 */
@Composable
private fun ThemeGroup(state: SettingsState, onEvent: (SettingsEvent) -> Unit) {
    SettingsGroupHeader(stringResource(R.string.settings_group_theme))
    val prefs = state.preferences
    if (prefs == null) {
        repeat(ThemeMode.entries.size) {
            SettingsRowSkeleton(modifier = Modifier.testTag(SettingsTestTags.ROW_SKELETON))
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup()
            .testTag(SettingsTestTags.THEME_GROUP),
    ) {
        ThemeMode.entries.forEach { mode ->
            SettingsThemeRow(
                title = stringResource(mode.labelRes),
                selected = prefs.themeMode == mode,
                onClick = { onEvent(SettingsEvent.ThemeSelected(mode)) },
                modifier = Modifier.testTag(SettingsTestTags.themeRow(mode)),
            )
            SettingsDivider()
        }
    }
    if (SettingKey.Theme in state.writeFailures) {
        SettingsWriteFailure(
            modifier = Modifier
                .padding(top = Spacing.scale200)
                .testTag(SettingsTestTags.writeFailure(SettingKey.Theme)),
        )
    }
}

/**
 * «О приложении» — раздел того же списка, а не отдельный экран (O5-4): название и
 * версия, автор, версия контента, правило подсчёта, адрес для писем, «Источники».
 * Название пакета не показывается: оно не хранится в Room (I4-D16).
 */
@Composable
private fun AboutGroup(about: AboutUi, onEvent: (SettingsEvent) -> Unit) {
    SettingsGroupHeader(stringResource(R.string.settings_group_about))

    SettingsInfoRow(
        label = stringResource(R.string.app_name),
        value = stringResource(R.string.about_version, about.versionName, about.versionCode),
        valueStyle = ValueStyle.Secondary,
        modifier = Modifier.testTag(SettingsTestTags.ABOUT_APP),
    )
    SettingsDivider()

    SettingsInfoRow(
        label = stringResource(R.string.about_author_label),
        value = stringResource(R.string.about_author),
        modifier = Modifier.testTag(SettingsTestTags.ABOUT_AUTHOR),
    )
    SettingsDivider()

    when (val content = about.contentVersion) {
        null -> SettingsValueSkeleton(modifier = Modifier.testTag(SettingsTestTags.CONTENT_VERSION_SKELETON))
        is InstalledContentVersion.Known -> SettingsInfoRow(
            value = stringResource(R.string.about_content_version, content.version),
            modifier = Modifier.testTag(SettingsTestTags.CONTENT_VERSION),
        )
        InstalledContentVersion.Unknown -> SettingsInfoRow(
            value = stringResource(R.string.about_content_version_unknown),
            modifier = Modifier.testTag(SettingsTestTags.CONTENT_VERSION),
        )
    }
    SettingsDivider()

    SettingsInfoRow(
        value = stringResource(R.string.about_scoring_rule),
        valueStyle = ValueStyle.Secondary,
        modifier = Modifier.testTag(SettingsTestTags.SCORING_RULE),
    )
    SettingsDivider()

    SettingsEmailRow(
        label = stringResource(R.string.about_feedback_label),
        email = stringResource(R.string.feedback_email),
        modifier = Modifier.testTag(SettingsTestTags.EMAIL_ROW),
        emailModifier = Modifier.testTag(SettingsTestTags.EMAIL),
    )
    SettingsDivider()

    SettingsNavigationRow(
        title = stringResource(R.string.settings_sources),
        onClick = { onEvent(SettingsEvent.SourcesClicked) },
        modifier = Modifier.testTag(SettingsTestTags.SOURCES_ROW),
    )
    SettingsDivider()
}

/**
 * «Обратная связь» — одна строка «Сообщить о неточности». Без почтового клиента группы
 * нет целиком: заголовок над пустотой был бы разделом без содержания, а адрес для
 * копирования остаётся в «О приложении».
 */
@Composable
private fun FeedbackGroup(isReportAvailable: Boolean, onEvent: (SettingsEvent) -> Unit) {
    if (!isReportAvailable) return
    SettingsGroupHeader(stringResource(R.string.settings_group_feedback))
    ReportInaccuracyAction(
        isAvailable = true,
        style = ReportInaccuracyStyle.SettingsRow,
        onClick = { onEvent(SettingsEvent.ReportClicked) },
        modifier = Modifier.testTag(SettingsTestTags.REPORT_ROW),
    )
    SettingsDivider()
}

/** Строка настройки, строка отказа её записи (только своего ключа) и hairline под ними. */
@Composable
private fun SettingBlock(
    key: SettingKey,
    failures: Set<SettingKey>,
    row: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        row()
        if (key in failures) {
            SettingsWriteFailure(modifier = Modifier.testTag(SettingsTestTags.writeFailure(key)))
        }
        SettingsDivider()
    }
}

private val ThemeMode.labelRes: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    }

private const val SOUND_ROWS = 2

// --- Preview -----------------------------------------------------------------------------

private val previewState = SettingsState(
    preferences = PreferencesUi(soundEnabled = true, vibrationEnabled = false, themeMode = ThemeMode.SYSTEM),
    about = AboutUi(versionName = "1.0.0", versionCode = 1, contentVersion = InstalledContentVersion.Known(1)),
    writeFailures = emptySet(),
)

@Composable
private fun PreviewSettings(state: SettingsState, darkTheme: Boolean = false) {
    PoPoRyadkuTheme(darkTheme = darkTheme) {
        SettingsScreen(state = state, isReportAvailable = true, onEvent = {})
    }
}

@Preview(name = "Settings — 390×844", widthDp = 390, heightDp = 844)
@Composable
private fun SettingsPreview() = PreviewSettings(previewState)

@Preview(
    name = "Settings — dark 390×844",
    widthDp = 390,
    heightDp = 844,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SettingsDarkPreview() = PreviewSettings(
    previewState.copy(writeFailures = setOf(SettingKey.Sound)),
    darkTheme = true,
)

@Preview(name = "Settings — Loading", widthDp = 390, heightDp = 844)
@Composable
private fun SettingsLoadingPreview() = PreviewSettings(
    previewState.copy(preferences = null, about = previewState.about.copy(contentVersion = null)),
)

@Preview(name = "Settings — 320×844 @200%", widthDp = 320, heightDp = 844, fontScale = 2f)
@Composable
private fun SettingsCompactLargeFontPreview() = PreviewSettings(previewState)
