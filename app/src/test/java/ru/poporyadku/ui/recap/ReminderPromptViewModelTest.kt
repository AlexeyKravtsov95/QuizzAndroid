package ru.poporyadku.ui.recap

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import java.time.LocalDate
import java.time.LocalTime
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.poporyadku.core.model.DayResult
import ru.poporyadku.domain.reminder.FakeNotificationAccess
import ru.poporyadku.domain.reminder.GetReminderPromptEligibilityUseCase
import ru.poporyadku.domain.reminder.NotificationAvailability
import ru.poporyadku.domain.repository.ProgressRepository
import ru.poporyadku.domain.usecase.AcceptReminderPromptUseCase
import ru.poporyadku.domain.usecase.MarkReminderPromptShownUseCase
import ru.poporyadku.ui.navigation.Destinations
import ru.poporyadku.ui.settings.ControllablePreferences
import ru.poporyadku.ui.settings.SettingsViewModel

/**
 * `I6-V15` — предложение включить напоминание на итоге дня (ITERATION_6_DESIGN.md, §8.3,
 * I6-D40), включая таблицу восстановления после смерти процесса.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReminderPromptViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val today = LocalDate.of(2026, 9, 16)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- Условия показа -------------------------------------------------------------------

    /** `I6-V15`. Сессионный итог завершённого дня, флага нет, напоминание выключено — показ. */
    @Test
    fun `I6-V15 a completed session recap shows the prompt`() = runTest(dispatcher) {
        val world = world()

        advanceUntilIdle()

        assertTrue(world.viewModel.isVisible.value)
    }

    /** `I6-V15`. Архивный итог — никогда: предложение принадлежит только что завершённому дню. */
    @Test
    fun `I6-V15 an archived recap never shows the prompt`() = runTest(dispatcher) {
        val world = world(origin = Destinations.ORIGIN_ARCHIVE)

        advanceUntilIdle()

        assertFalse(world.viewModel.isVisible.value)
    }

    /** `I6-V15`. Незавершённый день — не показывать. */
    @Test
    fun `I6-V15 an incomplete day does not show the prompt`() = runTest(dispatcher) {
        val world = world(dayResult = dayResult(completedCount = 2, isComplete = false))

        advanceUntilIdle()

        assertFalse(world.viewModel.isVisible.value)
    }

    /** `I6-V15`. Уже отвечали или напоминание уже включено — не показывать. */
    @Test
    fun `I6-V15 an answered prompt or an enabled reminder does not show again`() = runTest(dispatcher) {
        val answered = world(
            preferences = ControllablePreferences(
                ControllablePreferences.defaults(notificationPromptShown = true),
            ),
        )
        val enabled = world(
            preferences = ControllablePreferences(
                ControllablePreferences.defaults(reminderEnabled = true),
            ),
        )
        advanceUntilIdle()

        assertFalse("флаг уже стоит", answered.viewModel.isVisible.value)
        assertFalse("напоминание уже включено", enabled.viewModel.isVisible.value)
    }

    /** `I6-V15`. Отказ чтения условий: диалога нет, итог дня не затронут. */
    @Test
    fun `I6-V15 a failing eligibility read hides the prompt`() = runTest(dispatcher) {
        val world = world(progressFailure = IllegalStateException("база недоступна"))

        advanceUntilIdle()

        assertFalse(world.viewModel.isVisible.value)
    }

    // --- «Да, в 9:00»: согласие раньше любого эффекта --------------------------------------

    /**
     * `I6-V15`. Пока переход DataStore не завершён, **нет ни эффекта запроса, ни шага** в
     * `SavedStateHandle`: системное разрешение не запрашивается под несохранённое согласие.
     */
    @Test
    fun `I6-V15 nothing happens until the consent transition completes`() = runTest(dispatcher) {
        val world = world(access = FakeNotificationAccess(NotificationAvailability.RuntimePermissionMissing))
        advanceUntilIdle()
        world.preferences.holdWrites()

        world.viewModel.effects.test {
            world.viewModel.onAccept()
            advanceUntilIdle()

            assertFalse("диалог скрыт сразу", world.viewModel.isVisible.value)
            expectNoEvents()
            assertEquals("переход не завершён — шага нет", null, world.savedState.get<String>(KEY_STEP))

            world.preferences.releaseWrites()
            advanceUntilIdle()

            assertEquals(ReminderPromptEffect.RequestNotificationPermission, awaitItem())
        }
    }

    /** `I6-V15`. После перехода в хранилище одновременно все три значения. */
    @Test
    fun `I6-V15 the consent writes all three values at once`() = runTest(dispatcher) {
        val world = world()
        advanceUntilIdle()

        world.viewModel.onAccept()
        advanceUntilIdle()

        val stored = world.preferences.current!!
        assertTrue(stored.notificationPromptShown)
        assertTrue(stored.reminderEnabled)
        assertEquals(LocalTime.of(9, 0), stored.reminderTime)
        assertEquals(listOf(LocalTime.of(9, 0)), world.preferences.acceptCalls)
    }

    /** `I6-V15`. `Allowed` — эффектов нет: работу запланирует коллектор настроек. */
    @Test
    fun `I6-V15 an allowed access needs no effect at all`() = runTest(dispatcher) {
        val world = world()
        advanceUntilIdle()

        world.viewModel.effects.test {
            world.viewModel.onAccept()
            advanceUntilIdle()

            expectNoEvents()
        }
        assertEquals("шаг очищен", null, world.savedState.get<String>(KEY_STEP))
    }

    /**
     * `I6-V15`. Уведомления приложения или канал выключены: запроса нет, но согласие **не
     * отброшено** — в «Настройках» виден выключенный переключатель, подсказка и путь.
     */
    @Test
    fun `I6-V15 blocked notifications keep the consent and offer the settings path`() =
        runTest(dispatcher) {
            val cases = listOf(
                NotificationAvailability.AppNotificationsDisabled,
                NotificationAvailability.ChannelDisabled,
            )

            cases.forEach { status ->
                val world = world(access = FakeNotificationAccess(status))
                advanceUntilIdle()

                world.viewModel.effects.test {
                    world.viewModel.onAccept()
                    advanceUntilIdle()
                    expectNoEvents()
                }

                // Согласие сохранено.
                assertTrue("$status: напоминание включено", world.preferences.current!!.reminderEnabled)

                // «Настройки» на том же хранилище показывают намерение и путь.
                val settings = settingsOver(world.preferences, FakeNotificationAccess(status))
                backgroundScope.launch { settings.uiState.collect { } }
                advanceUntilIdle()
                val reminder = settings.uiState.value.reminder!!
                assertFalse("$status: переключатель показан выключенным", reminder.enabledShown)
                assertTrue("$status: подсказка", reminder.showPermissionHint)
                assertEquals(status, reminder.unavailability)
            }
        }

    /** `I6-V15`. Упавший переход: хранилище не изменено, запроса нет, диалог скрыт. */
    @Test
    fun `I6-V15 a failing consent writes nothing and requests nothing`() = runTest(dispatcher) {
        val world = world(access = FakeNotificationAccess(NotificationAvailability.RuntimePermissionMissing))
        advanceUntilIdle()
        world.preferences.failNextAccept()

        world.viewModel.effects.test {
            world.viewModel.onAccept()
            advanceUntilIdle()

            expectNoEvents()
        }
        assertFalse(world.preferences.current!!.reminderEnabled)
        assertFalse(world.preferences.current!!.notificationPromptShown)
        assertFalse(world.viewModel.isVisible.value)
    }

    /** `I6-V15`. «Не нужно»: только отметка, без запроса и без включения напоминания. */
    @Test
    fun `I6-V15 declining marks the prompt without enabling anything`() = runTest(dispatcher) {
        val world = world(access = FakeNotificationAccess(NotificationAvailability.RuntimePermissionMissing))
        advanceUntilIdle()

        world.viewModel.effects.test {
            world.viewModel.onDecline()
            advanceUntilIdle()

            expectNoEvents()
        }
        assertEquals(listOf(true), world.preferences.promptShownCalls)
        assertTrue(world.preferences.current!!.notificationPromptShown)
        assertFalse(world.preferences.current!!.reminderEnabled)
        assertFalse(world.viewModel.isVisible.value)
    }

    // --- Смерть процесса (таблица §8.3) ---------------------------------------------------

    /**
     * `I6-V15` (1). Смерть до завершения перехода: согласия в хранилище нет — при
     * следующем подходящем итоге предложение показывается снова, запроса нет.
     */
    @Test
    fun `I6-V15 death before the commit leaves no consent and shows the prompt again`() =
        runTest(dispatcher) {
            val preferences = ControllablePreferences()
            val world = world(preferences = preferences)
            advanceUntilIdle()
            preferences.holdWrites()
            world.viewModel.onAccept()
            advanceUntilIdle()

            // «Процесс умер»: SavedStateHandle нового экземпляра пуст, запись не дошла.
            val revived = world(preferences = preferences, savedState = SavedStateHandle())
            advanceUntilIdle()

            assertFalse(preferences.current!!.reminderEnabled)
            assertTrue("предложение показывается снова", revived.viewModel.isVisible.value)
        }

    /**
     * `I6-V15` (2). Смерть сразу после перехода, задача восстановлена с шагом
     * `AcceptedAwaitingRequest`: диалога нет, и запрос делается **ровно один**.
     */
    @Test
    fun `I6-V15 a restored AcceptedAwaitingRequest step requests the permission exactly once`() =
        runTest(dispatcher) {
            val preferences = ControllablePreferences(
                ControllablePreferences.defaults(reminderEnabled = true, notificationPromptShown = true),
            )
            val savedState = SavedStateHandle(mapOf(KEY_STEP to STEP_ACCEPTED))

            val world = world(
                preferences = preferences,
                savedState = savedState,
                access = FakeNotificationAccess(NotificationAvailability.RuntimePermissionMissing),
            )

            world.viewModel.effects.test {
                advanceUntilIdle()

                assertFalse("диалога нет", world.viewModel.isVisible.value)
                assertEquals(ReminderPromptEffect.RequestNotificationPermission, awaitItem())
                expectNoEvents()
            }
        }

    /** `I6-V15` (2). Тот же шаг при `Allowed` — ничего не делает и очищается. */
    @Test
    fun `I6-V15 a restored step with access does nothing`() = runTest(dispatcher) {
        val savedState = SavedStateHandle(mapOf(KEY_STEP to STEP_ACCEPTED))
        val world = world(
            preferences = ControllablePreferences(
                ControllablePreferences.defaults(reminderEnabled = true, notificationPromptShown = true),
            ),
            savedState = savedState,
        )

        world.viewModel.effects.test {
            advanceUntilIdle()
            expectNoEvents()
        }
        assertFalse(world.viewModel.isVisible.value)
        assertEquals(null, savedState.get<String>(KEY_STEP))
    }

    /**
     * `I6-V15` (2б). Холодный старт без `SavedStateHandle`: ни диалога, ни автоматического
     * запроса — путь только через «Настройки», и так для каждого из четырёх статусов.
     */
    @Test
    fun `I6-V15 a cold start after the commit neither shows nor requests`() = runTest(dispatcher) {
        NotificationAvailability.entries.forEach { status ->
            val world = world(
                preferences = ControllablePreferences(
                    ControllablePreferences.defaults(reminderEnabled = true, notificationPromptShown = true),
                ),
                savedState = SavedStateHandle(),
                access = FakeNotificationAccess(status),
            )

            world.viewModel.effects.test {
                advanceUntilIdle()
                expectNoEvents()
            }
            assertFalse("$status: диалога нет", world.viewModel.isVisible.value)
        }
    }

    /**
     * `I6-V15` (3). Шаг `RequestLaunched`: повторного запроса нет — результат придёт
     * восстановленному route-контейнеру через `ActivityResultRegistry`.
     */
    @Test
    fun `I6-V15 a restored RequestLaunched step never requests again`() = runTest(dispatcher) {
        val savedState = SavedStateHandle(mapOf(KEY_STEP to STEP_LAUNCHED))
        val world = world(
            preferences = ControllablePreferences(
                ControllablePreferences.defaults(reminderEnabled = true, notificationPromptShown = true),
            ),
            savedState = savedState,
            access = FakeNotificationAccess(NotificationAvailability.RuntimePermissionMissing),
        )

        world.viewModel.effects.test {
            advanceUntilIdle()
            expectNoEvents()

            // Результат доставлен восстановленному контейнеру: записей нет, шаг очищен.
            world.viewModel.onPermissionResult()
            advanceUntilIdle()
            expectNoEvents()
        }
        assertTrue(world.preferences.acceptCalls.isEmpty())
        assertEquals(null, savedState.get<String>(KEY_STEP))
    }

    /** `I6-V15`. Запуск системного диалога фиксируется шагом — для защиты от повтора. */
    @Test
    fun `I6-V15 launching the request records the continuation step`() = runTest(dispatcher) {
        val world = world(access = FakeNotificationAccess(NotificationAvailability.RuntimePermissionMissing))
        advanceUntilIdle()
        world.viewModel.onAccept()
        advanceUntilIdle()

        world.viewModel.onPermissionRequestLaunched()

        assertEquals(STEP_LAUNCHED, world.savedState.get<String>(KEY_STEP))
    }

    /** `I6-V15`. `CancellationException` пробрасывается: уход с итога во время перехода. */
    @Test
    fun `I6-V15 cancellation during the transition propagates without an effect`() = runTest(dispatcher) {
        val world = world(access = FakeNotificationAccess(NotificationAvailability.RuntimePermissionMissing))
        advanceUntilIdle()
        world.preferences.holdWrites()
        world.viewModel.onAccept()
        runCurrent()

        // Уход с экрана: viewModelScope отменяется ровно так же, как при очистке ViewModel.
        world.viewModel.viewModelScope.cancel()
        advanceUntilIdle()

        assertEquals(listOf(LocalTime.of(9, 0)), world.preferences.cancelledAccepts)
        assertFalse(world.preferences.current!!.reminderEnabled)
    }

    // --- Инфраструктура -------------------------------------------------------------------

    private class World(
        val viewModel: ReminderPromptViewModel,
        val preferences: ControllablePreferences,
        val savedState: SavedStateHandle,
    )

    private fun world(
        preferences: ControllablePreferences = ControllablePreferences(),
        savedState: SavedStateHandle = SavedStateHandle(),
        access: FakeNotificationAccess = FakeNotificationAccess(),
        origin: String? = null,
        dayResult: DayResult? = dayResult(completedCount = 3, isComplete = true),
        progressFailure: Exception? = null,
    ): World {
        savedState[Destinations.ARG_DATE] = today.toString()
        if (origin != null) savedState[Destinations.ARG_ORIGIN] = origin
        val progress = SingleDayProgress(dayResult, progressFailure)
        val viewModel = ReminderPromptViewModel(
            getEligibility = GetReminderPromptEligibilityUseCase(progress, preferences),
            acceptPrompt = AcceptReminderPromptUseCase(preferences),
            markShown = MarkReminderPromptShownUseCase(preferences),
            notificationAccess = access,
            savedStateHandle = savedState,
        )
        return World(viewModel, preferences, savedState)
    }

    private fun settingsOver(
        preferences: ControllablePreferences,
        access: FakeNotificationAccess,
    ): SettingsViewModel = SettingsViewModel(
        preferences = preferences,
        writer = NoOpWriter,
        app = ru.poporyadku.core.model.AppBuildInfo("1.0.0", 1),
        getInstalledContentVersion =
            ru.poporyadku.domain.usecase.GetInstalledContentVersionUseCase(preferences),
        notificationAccess = access,
        savedStateHandle = SavedStateHandle(),
    )

    private fun dayResult(completedCount: Int, isComplete: Boolean) = DayResult(
        localDate = today,
        totalScore = completedCount * 6,
        completedCount = completedCount,
        isComplete = isComplete,
        completedAt = null,
    )

    /** Прогресс одного дня: только чтение результата. */
    private class SingleDayProgress(
        private val result: DayResult?,
        private val failure: Exception?,
    ) : ProgressRepository {
        override suspend fun getDayResult(localDate: LocalDate): DayResult? {
            failure?.let { throw it }
            return result
        }

        override suspend fun recordAttempt(attempt: ru.poporyadku.core.model.PuzzleAttempt) =
            throw AssertionError("предложение ничего не пишет в прогресс")

        override suspend fun getDayResults(from: LocalDate, to: LocalDate) = emptyList<DayResult>()
        override suspend fun getAttempt(localDate: LocalDate, slotIndex: Int) = null
        override suspend fun getAttempts(localDate: LocalDate) =
            emptyList<ru.poporyadku.core.model.PuzzleAttempt>()
        override suspend fun getAllDayResults() = emptyList<DayResult>()
        override suspend fun getCompletedDates() = emptyList<LocalDate>()
        override fun observeDayResults() = kotlinx.coroutines.flow.flowOf(emptyList<DayResult>())
    }

    /** Очередь записи настроек предложению не нужна: оно пишет подтверждаемо и напрямую. */
    private object NoOpWriter : ru.poporyadku.domain.repository.SettingsWriter {
        override val failedKeys =
            kotlinx.coroutines.flow.MutableStateFlow(emptySet<ru.poporyadku.domain.model.SettingKey>())

        override fun submit(mutation: ru.poporyadku.domain.model.SettingMutation) = Unit
    }

    private companion object {
        const val KEY_STEP = "reminderPrompt.step"
        const val STEP_ACCEPTED = "AcceptedAwaitingRequest"
        const val STEP_LAUNCHED = "RequestLaunched"
    }
}
