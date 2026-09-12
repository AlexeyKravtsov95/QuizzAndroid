package ru.poporyadku.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.poporyadku.core.model.AppBuildInfo
import ru.poporyadku.core.model.UserPreferences
import ru.poporyadku.domain.model.InstalledContentVersion
import ru.poporyadku.domain.model.SettingMutation
import ru.poporyadku.domain.repository.SettingsWriter
import ru.poporyadku.domain.repository.UserPreferencesRepository
import ru.poporyadku.domain.usecase.GetInstalledContentVersionUseCase
import ru.poporyadku.ui.report.ReportContext

/**
 * ViewModel настроек (ITERATION_5_DESIGN.md, §3.9, §3.10, §4.5, §6.9, I5-D15).
 *
 * **Подтверждённая модель обновления.** Экран показывает только то, что выдал DataStore;
 * нажатие превращается в команду [SettingMutation] и уходит в [SettingsWriter]. До
 * эмиссии DataStore строка показывает прежнее значение — оптимистичного локального
 * состояния нет, откатывать нечего.
 *
 * **Команды без цикла.** Команды создаются только в [onEvent]; эмиссии DataStore их не
 * порождают. `UserPreferencesRepository` здесь только читается.
 *
 * **Владение записью.** Собственных корутин записи у ViewModel нет: `submit` не
 * `suspend`, команду исполняет очередь приложения, поэтому очистка ViewModel (уход со
 * Settings) принятую команду не отменяет.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    preferences: UserPreferencesRepository,
    private val writer: SettingsWriter,
    private val app: AppBuildInfo,
    private val getInstalledContentVersion: GetInstalledContentVersionUseCase,
) : ViewModel() {

    private val effectChannel = Channel<SettingsEffect>(Channel.BUFFERED)

    /** Ровно один коллектор на стороне UI — на уровне route-контейнера (I5-D24). */
    val effects: Flow<SettingsEffect> = effectChannel.receiveAsFlow()

    /** `null` до первой эмиссии DataStore; изменения других ключей экран не перерисовывают. */
    private val preferencesUi: Flow<PreferencesUi?> = preferences.preferences
        .map<UserPreferences, PreferencesUi?> { it.toUi() }
        .onStart { emit(null) }
        .distinctUntilChanged()

    /** `null` до чтения; одно чтение на подписку. */
    private val contentVersion: Flow<InstalledContentVersion?> = flow {
        emit(null)
        emit(readInstalledContentVersion())
    }

    val uiState: StateFlow<SettingsState> =
        combine(preferencesUi, contentVersion, writer.failedKeys) { prefs, content, failed ->
            SettingsState(
                preferences = prefs,
                about = aboutUi(content),
                writeFailures = failed,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            SettingsState(
                preferences = null,
                about = aboutUi(contentVersion = null),
                writeFailures = writer.failedKeys.value,
            ),
        )

    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.SoundToggled -> writer.submit(SettingMutation.Sound(event.enabled))
            is SettingsEvent.VibrationToggled -> writer.submit(SettingMutation.Vibration(event.enabled))
            is SettingsEvent.ThemeSelected -> writer.submit(SettingMutation.Theme(event.mode))
            SettingsEvent.SourcesClicked -> effectChannel.trySend(SettingsEffect.OpenSources)
            SettingsEvent.BackClicked -> effectChannel.trySend(SettingsEffect.NavigateBack)
            SettingsEvent.ReportClicked -> viewModelScope.launch {
                val report = ReportContext(
                    puzzleId = null,
                    app = app,
                    content = readInstalledContentVersion(),
                )
                effectChannel.send(SettingsEffect.ComposeReport(report))
            }
        }
    }

    private fun aboutUi(contentVersion: InstalledContentVersion?) = AboutUi(
        versionName = app.versionName,
        versionCode = app.versionCode,
        contentVersion = contentVersion,
    )

    /**
     * Отказ чтения — «не установлена», а не падение экрана: репозиторий и так отдаёт
     * значения по умолчанию после `IOException`. Отмена пробрасывается.
     */
    private suspend fun readInstalledContentVersion(): InstalledContentVersion =
        try {
            getInstalledContentVersion()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            InstalledContentVersion.Unknown
        }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        fun UserPreferences.toUi() = PreferencesUi(
            soundEnabled = soundEnabled,
            vibrationEnabled = vibrationEnabled,
            themeMode = themeMode,
        )
    }
}
