package ru.poporyadku.ui.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import ru.poporyadku.domain.reminder.NotificationAccess
import ru.poporyadku.domain.reminder.NotificationAvailability
import ru.poporyadku.domain.reminder.settingsTarget
import ru.poporyadku.domain.repository.SettingsWriter
import ru.poporyadku.domain.repository.UserPreferencesRepository
import ru.poporyadku.domain.usecase.GetInstalledContentVersionUseCase
import ru.poporyadku.ui.report.ReportContext

/**
 * ViewModel настроек (ITERATION_5_DESIGN.md, §3.9, §3.10, §4.5, §6.9, I5-D15;
 * ITERATION_6_DESIGN.md, §8.1, §8.2, I6-D36…I6-D39).
 *
 * **Подтверждённая модель обновления.** Экран показывает только то, что выдал DataStore;
 * нажатие превращается в команду [SettingMutation] и уходит в [SettingsWriter]. До
 * эмиссии DataStore строка показывает прежнее значение — оптимистичного локального
 * состояния нет, откатывать нечего.
 *
 * **Команды без цикла.** Команды создаются только в [onEvent] и в [onScreenStarted] при
 * `pendingEnable && Allowed`; эмиссии DataStore их не порождают.
 * `UserPreferencesRepository` здесь только читается.
 *
 * **Планировщика здесь нет.** ViewModel про WorkManager не знает: она пишет настройку, а
 * работу ставит `ReminderScheduleObserver`, увидевший записанное значение (I6-D29).
 *
 * **Статус доступа — не `Boolean`.** Решение после системного диалога и после возврата
 * из настроек принимается **перечитанным** `availability()`, а не результатом callback'а:
 * callback `true` вполне сочетается с выключенными уведомлениями приложения или
 * заглушённым каналом (I6-D36).
 *
 * **Намерение `pendingEnable`** («включил, доступа ещё нет») живёт в `SavedStateHandle`:
 * оно обязано пережить пересоздание во время системного диалога и уход в системные
 * настройки — и исчезнуть вместе с экраном, потому что долговечным согласием оно не
 * является (им является сам `reminderEnabled`, I6-D40).
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    preferences: UserPreferencesRepository,
    private val writer: SettingsWriter,
    private val app: AppBuildInfo,
    private val getInstalledContentVersion: GetInstalledContentVersionUseCase,
    private val notificationAccess: NotificationAccess,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val effectChannel = Channel<SettingsEffect>(Channel.BUFFERED)

    /** Ровно один коллектор на стороне UI — на уровне route-контейнера (I5-D24). */
    val effects: Flow<SettingsEffect> = effectChannel.receiveAsFlow()

    /**
     * Статус доступа. Перечитывается на каждом `ON_START` и после ответа системы —
     * подписки на него не существует: система об изменении не уведомляет.
     */
    private val availability = MutableStateFlow(notificationAccess.availability())

    /** `null` до первой эмиссии DataStore; изменения других ключей экран не перерисовывают. */
    private val preferencesUi: Flow<PreferencesUi?> = preferences.preferences
        .map<UserPreferences, PreferencesUi?> { it.toUi() }
        .onStart { emit(null) }
        .distinctUntilChanged()

    /**
     * Намерение «включить, когда появится доступ» — потоком, а не полем: подсказка под
     * строкой обязана появиться сразу после нажатия, когда статус доступа не изменился и
     * сам по себе эмиссии не даст. `SavedStateHandle.getStateFlow` переживает
     * пересоздание экрана вместе с остальным его состоянием.
     */
    private val pendingEnableFlow: StateFlow<Boolean> =
        savedStateHandle.getStateFlow(KEY_PENDING_ENABLE, false)

    /**
     * Строки напоминания: подтверждённые настройки × перечитанный статус доступа ×
     * намерение. Три источника, поэтому собираются отдельно от остальных настроек.
     */
    private val reminderUi: Flow<ReminderUi?> = combine(
        preferences.preferences
            .map<UserPreferences, ReminderPrefs?> { ReminderPrefs(it.reminderEnabled, it.reminderTime) }
            .onStart { emit(null) }
            .distinctUntilChanged(),
        availability,
        pendingEnableFlow,
    ) { prefs, access, pending -> prefs?.toUi(access, pending) }

    /** `null` до чтения; одно чтение на подписку. */
    private val contentVersion: Flow<InstalledContentVersion?> = flow {
        emit(null)
        emit(readInstalledContentVersion())
    }

    val uiState: StateFlow<SettingsState> =
        combine(
            preferencesUi,
            reminderUi,
            contentVersion,
            writer.failedKeys,
        ) { prefs, reminder, content, failed ->
            SettingsState(
                preferences = prefs,
                reminder = reminder,
                about = aboutUi(content),
                writeFailures = failed,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            SettingsState(
                preferences = null,
                reminder = null,
                about = aboutUi(contentVersion = null),
                writeFailures = writer.failedKeys.value,
            ),
        )

    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.SoundToggled -> writer.submit(SettingMutation.Sound(event.enabled))
            is SettingsEvent.VibrationToggled -> writer.submit(SettingMutation.Vibration(event.enabled))
            is SettingsEvent.ThemeSelected -> writer.submit(SettingMutation.Theme(event.mode))
            is SettingsEvent.ReminderToggled -> onReminderToggled(event.enabled)
            is SettingsEvent.ReminderTimeChosen -> writer.submit(SettingMutation.ReminderTime(event.time))
            SettingsEvent.NotificationPermissionResult -> onPermissionAnswered()
            SettingsEvent.OpenNotificationSettingsClicked -> openSystemSettings(refreshStatus())
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

    /**
     * `ON_START` экрана (ITERATION_6_DESIGN.md, §8.1, §8.2).
     *
     * Единственная запись отсюда — исполнение **ранее выраженного** намерения, когда
     * доступ наконец появился. Флаг сбрасывается **до** команды, поэтому повторный
     * `ON_START` второй записи не даёт. Отзыв доступа не пишет ничего: пользователь
     * напоминание не выключал, и переписывать его намерение молча нельзя.
     */
    fun onScreenStarted() {
        val access = refreshStatus()
        if (pendingEnable && access == NotificationAvailability.Allowed) {
            pendingEnable = false
            writer.submit(SettingMutation.ReminderEnabled(true))
        }
    }

    private fun onReminderToggled(enabled: Boolean) {
        if (!enabled) {
            // Выключение доступно всегда и ни от чего не зависит.
            pendingEnable = false
            writer.submit(SettingMutation.ReminderEnabled(false))
            return
        }

        when (val access = refreshStatus()) {
            NotificationAvailability.Allowed -> {
                pendingEnable = false
                writer.submit(SettingMutation.ReminderEnabled(true))
            }

            // Только здесь системный диалог имеет смысл. Записи нет: включать то, что
            // не сможет показаться, — значит показать включённый переключатель без
            // уведомлений.
            NotificationAvailability.RuntimePermissionMissing -> {
                pendingEnable = true
                effectChannel.trySend(SettingsEffect.RequestNotificationPermission)
            }

            // Уведомления приложения или канал выключены: runtime-запрос бесполезен —
            // система его даже не покажет. Единственный работающий путь — настройки.
            NotificationAvailability.AppNotificationsDisabled,
            NotificationAvailability.ChannelDisabled,
            -> {
                pendingEnable = true
                openSystemSettings(access)
            }
        }
    }

    /**
     * Ответ системы на запрос разрешения. Булево значение callback'а не используется:
     * статус перечитывается заново (I6-D36). Постоянный отказ отдельно не распознаётся —
     * после него статус просто остаётся `RuntimePermissionMissing`, и пользователю
     * показывается путь в системные настройки.
     */
    private fun onPermissionAnswered() {
        val access = refreshStatus()
        if (access == NotificationAvailability.Allowed && pendingEnable) {
            pendingEnable = false
            writer.submit(SettingMutation.ReminderEnabled(true))
        }
    }

    private fun openSystemSettings(access: NotificationAvailability) {
        val target = access.settingsTarget ?: return
        effectChannel.trySend(SettingsEffect.OpenNotificationSettings(target))
    }

    /** Перечитать статус доступа и обновить состояние экрана. */
    private fun refreshStatus(): NotificationAvailability =
        notificationAccess.availability().also { availability.value = it }

    /** Намерение «включить, когда появится доступ»; переживает пересоздание экрана. */
    private var pendingEnable: Boolean
        get() = savedStateHandle[KEY_PENDING_ENABLE] ?: false
        set(value) {
            savedStateHandle[KEY_PENDING_ENABLE] = value
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

    /** Подтверждённые DataStore значения напоминания до соединения со статусом доступа. */
    private data class ReminderPrefs(val enabled: Boolean, val time: LocalTime)

    private fun ReminderPrefs.toUi(access: NotificationAvailability, pending: Boolean): ReminderUi {
        val allowed = access == NotificationAvailability.Allowed
        return ReminderUi(
            // I6-D37: показанное состояние — намерение И доступ.
            enabledShown = enabled && allowed,
            time = time,
            unavailability = access.takeIf { !allowed },
            // Подсказка нужна тому, кто напоминание хотел: либо уже записал намерение,
            // либо ждёт доступа после нажатия.
            showPermissionHint = !allowed && (enabled || pending),
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val KEY_PENDING_ENABLE = "settings.pendingEnable"

        fun UserPreferences.toUi() = PreferencesUi(
            soundEnabled = soundEnabled,
            vibrationEnabled = vibrationEnabled,
            themeMode = themeMode,
        )
    }
}
