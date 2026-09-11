package ru.poporyadku.ui.settings

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.cash.turbine.test
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.poporyadku.core.model.AppBuildInfo
import ru.poporyadku.core.model.ThemeMode
import ru.poporyadku.data.prefs.SettingsWriteQueue
import ru.poporyadku.domain.model.InstalledContentVersion
import ru.poporyadku.domain.model.SettingKey
import ru.poporyadku.domain.model.SettingMutation
import ru.poporyadku.domain.usecase.GetInstalledContentVersionUseCase
import ru.poporyadku.ui.report.ReportContext

/**
 * `SettingsViewModel` вместе с НАСТОЯЩЕЙ `SettingsWriteQueue` на `TestScope`
 * (ITERATION_5_DESIGN.md, §3.9, §5.5, §6.9, §10.3): `I5-V19`…`I5-V24`, `I5-V27`.
 *
 * Отдельного тестового файла очереди нет по дизайну (§9, PR 5C): очередь проверяется там,
 * где её видит пользователь, — через ViewModel и подтверждённое состояние экрана.
 * Worker очереди живёт в собственном scope «приложения» (`SupervisorJob` на планировщике
 * `TestScope`): как и в продукте, он не дочерний ни для ViewModel, ни для теста, а
 * ViewModel — в `Dispatchers.Main`, подменённом тем же планировщиком. Порядок и моменты
 * применения команд полностью управляемы; после теста scope отменяется.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    /** Job-ы scope «приложения» каждой созданной очереди — отменяются после теста. */
    private val applicationJobs = mutableListOf<Job>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        applicationJobs.forEach { it.cancel() }
        Dispatchers.resetMain()
    }

    // --- I5-V19 ------------------------------------------------------------------------

    /**
     * `I5-V19`. До первой эмиссии DataStore `preferences == null`, версия приложения — сразу,
     * версия контента — `null` до чтения; затем значения DataStore и `Known`.
     */
    @Test
    fun `I5-V19 preferences are null until DataStore emits then show the stored values`() = runTest(dispatcher) {
        val prefs = ControllablePreferences(initial = null)
        val viewModel = settings(prefs)
        observe(viewModel)
        runCurrent()

        viewModel.uiState.value.let { state ->
            assertNull("DataStore ещё не эмитил", state.preferences)
            assertEquals(AboutUi(APP.versionName, APP.versionCode, contentVersion = null), state.about)
            assertEquals(emptySet<SettingKey>(), state.writeFailures)
        }

        prefs.emit(ControllablePreferences.defaults(soundEnabled = false, themeMode = ThemeMode.DARK, contentVersion = 4))
        advanceUntilIdle()

        viewModel.uiState.value.let { state ->
            assertEquals(PreferencesUi(soundEnabled = false, vibrationEnabled = true, themeMode = ThemeMode.DARK), state.preferences)
            assertEquals(InstalledContentVersion.Known(4), state.about.contentVersion)
            assertEquals("9.9.9", state.about.versionName)
            assertEquals(99L, state.about.versionCode)
        }
    }

    /** `I5-V19`. Без отпечатка установленного контента версия — `Unknown`. */
    @Test
    fun `I5-V19 content version is unknown without an installed fingerprint`() = runTest(dispatcher) {
        val prefs = ControllablePreferences(ControllablePreferences.defaults(contentVersion = 0, fingerprint = null))
        val viewModel = settings(prefs)
        observe(viewModel)
        advanceUntilIdle()

        assertEquals(InstalledContentVersion.Unknown, viewModel.uiState.value.about.contentVersion)
    }

    // --- I5-V20 ------------------------------------------------------------------------

    /**
     * `I5-V20`. Сеттеры вызываются ровно столько раз, сколько команд принято, в порядке
     * `submit`; до эмиссии DataStore экран показывает прежнее значение (оптимистичного
     * обновления нет); последующие эмиссии DataStore команд не порождают.
     */
    @Test
    fun `I5-V20 commands apply in submit order without optimism and emissions create none`() = runTest(dispatcher) {
        val prefs = ControllablePreferences()
        val viewModel = settings(prefs, queue(prefs))
        observe(viewModel)
        advanceUntilIdle()

        prefs.holdWrites()
        viewModel.onEvent(SettingsEvent.SoundToggled(false))
        viewModel.onEvent(SettingsEvent.ThemeSelected(ThemeMode.DARK))
        viewModel.onEvent(SettingsEvent.VibrationToggled(false))
        advanceUntilIdle()

        // Команды приняты, первая ждёт edit — экран показывает только подтверждённое.
        assertEquals(PreferencesUi(true, true, ThemeMode.SYSTEM), viewModel.uiState.value.preferences)
        assertEquals("одна команда в работе, остальные в очереди", listOf(SettingMutation.Sound(false)), prefs.calls)

        prefs.releaseWrites()
        advanceUntilIdle()

        assertEquals(
            listOf(SettingMutation.Sound(false), SettingMutation.Theme(ThemeMode.DARK), SettingMutation.Vibration(false)),
            prefs.calls,
        )
        assertEquals(PreferencesUi(false, false, ThemeMode.DARK), viewModel.uiState.value.preferences)

        // Эмиссии DataStore — и чужого ключа, и того же значения — команд не порождают.
        repeat(EXTRA_EMISSIONS) { day ->
            prefs.emit(prefs.current!!.copy(lastSeenDate = LocalDate.of(2026, 9, 1 + day)))
            advanceUntilIdle()
        }
        assertEquals(3, prefs.calls.size)
        assertEquals(emptySet<SettingKey>(), viewModel.uiState.value.writeFailures)
    }

    // --- I5-V21 ------------------------------------------------------------------------

    /** `I5-V21` (а). Запись звука упала, следующая запись темы успешна → `{Sound}`. */
    @Test
    fun `I5-V21 a sound failure survives a later successful theme write`() = runTest(dispatcher) {
        val prefs = ControllablePreferences()
        val viewModel = settings(prefs, queue(prefs))
        observe(viewModel)
        advanceUntilIdle()

        prefs.failNext(SettingKey.Sound)
        viewModel.onEvent(SettingsEvent.SoundToggled(false))
        viewModel.onEvent(SettingsEvent.ThemeSelected(ThemeMode.LIGHT))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(setOf(SettingKey.Sound), state.writeFailures)
        // Значения — из DataStore: звук не изменился, тема применилась.
        assertEquals(PreferencesUi(soundEnabled = true, vibrationEnabled = true, themeMode = ThemeMode.LIGHT), state.preferences)
    }

    /** `I5-V21` (б). Запись звука успешна, запись темы упала → `{Theme}`. */
    @Test
    fun `I5-V21 a theme failure after a successful sound write marks only theme`() = runTest(dispatcher) {
        val prefs = ControllablePreferences()
        val viewModel = settings(prefs, queue(prefs))
        observe(viewModel)
        advanceUntilIdle()

        prefs.failNext(SettingKey.Theme)
        viewModel.onEvent(SettingsEvent.SoundToggled(false))
        viewModel.onEvent(SettingsEvent.ThemeSelected(ThemeMode.DARK))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(setOf(SettingKey.Theme), state.writeFailures)
        assertEquals(PreferencesUi(soundEnabled = false, vibrationEnabled = true, themeMode = ThemeMode.SYSTEM), state.preferences)
    }

    /**
     * `I5-V21` (в, г). Обе записи упали → `{Sound, Theme}`, ни одна не потеряна; повторная
     * успешная запись звука снимает только `Sound`, `Theme` остаётся.
     */
    @Test
    fun `I5-V21 failures are independent per key and only a success of the same key clears one`() = runTest(dispatcher) {
        val prefs = ControllablePreferences()
        val viewModel = settings(prefs, queue(prefs))
        observe(viewModel)
        advanceUntilIdle()

        prefs.failNext(SettingKey.Sound)
        prefs.failNext(SettingKey.Theme)
        viewModel.onEvent(SettingsEvent.SoundToggled(false))
        viewModel.onEvent(SettingsEvent.ThemeSelected(ThemeMode.DARK))
        advanceUntilIdle()
        assertEquals(setOf(SettingKey.Sound, SettingKey.Theme), viewModel.uiState.value.writeFailures)
        assertEquals(PreferencesUi(true, true, ThemeMode.SYSTEM), viewModel.uiState.value.preferences)

        // Успех вибрации (другой ключ) не снимает ничего.
        viewModel.onEvent(SettingsEvent.VibrationToggled(false))
        advanceUntilIdle()
        assertEquals(setOf(SettingKey.Sound, SettingKey.Theme), viewModel.uiState.value.writeFailures)

        // Повтор звука успешен — снимается только Sound.
        viewModel.onEvent(SettingsEvent.SoundToggled(false))
        advanceUntilIdle()
        assertEquals(setOf(SettingKey.Theme), viewModel.uiState.value.writeFailures)
        assertEquals(PreferencesUi(false, false, ThemeMode.SYSTEM), viewModel.uiState.value.preferences)
    }

    // --- I5-V22 ------------------------------------------------------------------------

    /** `I5-V22`. `ThemeSelected(DARK)` → запись; после эмиссии выбрана «Тёмная». */
    @Test
    fun `I5-V22 selecting a theme writes it and shows it after the emission`() = runTest(dispatcher) {
        val prefs = ControllablePreferences()
        val viewModel = settings(prefs, queue(prefs))
        observe(viewModel)
        advanceUntilIdle()

        viewModel.onEvent(SettingsEvent.ThemeSelected(ThemeMode.DARK))
        advanceUntilIdle()

        assertEquals(listOf(SettingMutation.Theme(ThemeMode.DARK)), prefs.calls)
        assertEquals(ThemeMode.DARK, viewModel.uiState.value.preferences?.themeMode)
    }

    /**
     * `I5-V22`. Два быстрых выбора — `DARK`, затем `LIGHT` — до обработки первого: в
     * репозиторий уходят `DARK`, затем `LIGHT`; в DataStore остаётся `LIGHT`, экран
     * показывает «Светлая».
     */
    @Test
    fun `I5-V22 two quick theme choices apply in order and the last one wins`() = runTest(dispatcher) {
        val prefs = ControllablePreferences()
        val viewModel = settings(prefs, queue(prefs))
        observe(viewModel)
        advanceUntilIdle()

        prefs.holdWrites()
        viewModel.onEvent(SettingsEvent.ThemeSelected(ThemeMode.DARK))
        viewModel.onEvent(SettingsEvent.ThemeSelected(ThemeMode.LIGHT))
        advanceUntilIdle()
        assertEquals(ThemeMode.SYSTEM, viewModel.uiState.value.preferences?.themeMode)

        prefs.releaseWrites()
        advanceUntilIdle()

        assertEquals(
            listOf(SettingMutation.Theme(ThemeMode.DARK), SettingMutation.Theme(ThemeMode.LIGHT)),
            prefs.calls,
        )
        assertEquals(ThemeMode.LIGHT, prefs.current?.themeMode)
        assertEquals(ThemeMode.LIGHT, viewModel.uiState.value.preferences?.themeMode)
    }

    // --- I5-V23 ------------------------------------------------------------------------

    /** `I5-V23`. `ReportClicked` → ровно один `ComposeReport(puzzleId = null, версии)`. */
    @Test
    fun `I5-V23 report click emits exactly one general ComposeReport`() = runTest(dispatcher) {
        val prefs = ControllablePreferences(ControllablePreferences.defaults(contentVersion = 2, fingerprint = "f"))
        val viewModel = settings(prefs)

        viewModel.effects.test {
            viewModel.onEvent(SettingsEvent.ReportClicked)
            assertEquals(
                SettingsEffect.ComposeReport(
                    ReportContext(puzzleId = null, app = APP, content = InstalledContentVersion.Known(2)),
                ),
                awaitItem(),
            )
            expectNoEvents()
        }
        assertTrue("письмо ничего не пишет в настройки", prefs.calls.isEmpty())
    }

    // --- I5-V24 ------------------------------------------------------------------------

    /**
     * `I5-V24`. Команда отправлена, ViewModel очищена (`ViewModelStore.clear()`) до
     * обработки → очередь приложения применяет запись; новый экземпляр ViewModel видит
     * значение и прежние `writeFailures`.
     */
    @Test
    fun `I5-V24 a submitted command survives clearing the ViewModel`() = runTest(dispatcher) {
        val prefs = ControllablePreferences()
        val queue = queue(prefs)

        // Отказ темы до ухода с экрана: он принадлежит очереди, а не экрану.
        prefs.failNext(SettingKey.Theme)
        queue.submit(SettingMutation.Theme(ThemeMode.DARK))
        advanceUntilIdle()

        val store = ViewModelStore()
        val first = ViewModelProvider.create(store, factory(prefs, queue))[SettingsViewModel::class]
        observe(first)
        advanceUntilIdle()

        prefs.holdWrites()
        first.onEvent(SettingsEvent.SoundToggled(false))
        advanceUntilIdle()
        assertEquals("команда принята, edit ещё не завершён", true, prefs.current?.soundEnabled)

        store.clear() // уход со Settings: viewModelScope отменён
        assertFalse(first.viewModelScope.isActive)

        prefs.releaseWrites()
        advanceUntilIdle()
        assertEquals("очередь приложения применила запись", false, prefs.current?.soundEnabled)
        assertTrue("отменённых записей нет", prefs.cancelled.isEmpty())

        val second = settings(prefs, queue)
        observe(second)
        advanceUntilIdle()
        assertEquals(false, second.uiState.value.preferences?.soundEnabled)
        assertEquals(setOf(SettingKey.Theme), second.uiState.value.writeFailures)
    }

    /**
     * `I5-V24`. Отмена scope приложения во время `edit` пробрасывает `CancellationException`
     * и ошибкой ключа не становится.
     */
    @Test
    fun `I5-V24 cancelling the application scope during an edit is not a key failure`() = runTest(dispatcher) {
        val prefs = ControllablePreferences()
        val applicationJob = SupervisorJob()
        val queue = SettingsWriteQueue(prefs, CoroutineScope(applicationJob + dispatcher))

        prefs.holdWrites()
        queue.submit(SettingMutation.Sound(false))
        runCurrent()
        assertEquals(listOf(SettingMutation.Sound(false)), prefs.calls)

        applicationJob.cancel()
        runCurrent()

        assertEquals("edit прерван отменой", listOf(SettingMutation.Sound(false)), prefs.cancelled)
        assertEquals(emptySet<SettingKey>(), queue.failedKeys.value)
        assertEquals("значение не записано", true, prefs.current?.soundEnabled)

        // submit не бросает и после отмены scope: команда просто не будет применена.
        queue.submit(SettingMutation.Vibration(false))
        runCurrent()
        assertEquals(1, prefs.calls.size)
    }

    // --- I5-V27 ------------------------------------------------------------------------

    /** `I5-V27`. `SourcesClicked` → один `OpenSources`; `BackClicked` → один `NavigateBack`. */
    @Test
    fun `I5-V27 sources and back clicks emit exactly one effect each`() = runTest(dispatcher) {
        val prefs = ControllablePreferences()
        val viewModel = settings(prefs)

        viewModel.effects.test {
            viewModel.onEvent(SettingsEvent.SourcesClicked)
            assertEquals(SettingsEffect.OpenSources, awaitItem())
            viewModel.onEvent(SettingsEvent.BackClicked)
            assertEquals(SettingsEffect.NavigateBack, awaitItem())
            expectNoEvents()
        }
        assertTrue(prefs.calls.isEmpty())
    }

    // --- Инфраструктура -----------------------------------------------------------------

    /** Настоящая очередь; worker — в scope «приложения» на планировщике теста. */
    private fun queue(prefs: ControllablePreferences) = SettingsWriteQueue(prefs, applicationScope())

    private fun applicationScope(): CoroutineScope {
        val job = SupervisorJob()
        applicationJobs += job
        return CoroutineScope(job + dispatcher)
    }

    private fun settings(
        prefs: ControllablePreferences,
        writer: SettingsWriteQueue = queue(prefs),
    ) = SettingsViewModel(
        preferences = prefs,
        writer = writer,
        app = APP,
        getInstalledContentVersion = GetInstalledContentVersionUseCase(prefs),
    )

    private fun factory(prefs: ControllablePreferences, writer: SettingsWriteQueue) = viewModelFactory {
        initializer {
            SettingsViewModel(
                preferences = prefs,
                writer = writer,
                app = APP,
                getInstalledContentVersion = GetInstalledContentVersionUseCase(prefs),
            )
        }
    }

    /** `WhileSubscribed`: состояние считается, только пока экран подписан. */
    private fun TestScope.observe(viewModel: SettingsViewModel) {
        backgroundScope.launch { viewModel.uiState.collect { } }
    }

    private companion object {
        val APP = AppBuildInfo(versionName = "9.9.9", versionCode = 99)
        const val EXTRA_EMISSIONS = 5
    }
}
