package ru.poporyadku.ui.recap

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.format.DateTimeParseException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import ru.poporyadku.domain.reminder.GetReminderPromptEligibilityUseCase
import ru.poporyadku.domain.reminder.NotificationAccess
import ru.poporyadku.domain.reminder.NotificationAvailability
import ru.poporyadku.domain.usecase.AcceptReminderPromptUseCase
import ru.poporyadku.domain.usecase.MarkReminderPromptShownUseCase
import ru.poporyadku.ui.navigation.Destinations
import ru.poporyadku.ui.navigation.RouteOrigin

/** Единственный эффект предложения (ITERATION_6_DESIGN.md, §8.3). */
sealed interface ReminderPromptEffect {

    /**
     * Запустить системный запрос `POST_NOTIFICATIONS`. Создаётся **только** после
     * успешного `acceptReminderPrompt` и только при `RuntimePermissionMissing`.
     */
    data object RequestNotificationPermission : ReminderPromptEffect
}

/**
 * Шаг продолжения после записанного согласия (ITERATION_6_DESIGN.md, §8.3).
 *
 * Хранится в `SavedStateHandle` и **не является границей согласия**: согласие — это
 * запись в DataStore. Шаг отвечает на единственный вопрос «успели ли мы запустить
 * системный диалог», чтобы после пересоздания не запросить разрешение дважды.
 */
internal enum class PromptStep {
    /** Переход записан, системный запрос ещё не запускался. */
    AcceptedAwaitingRequest,

    /** Route-контейнер сообщил, что системный диалог запущен. */
    RequestLaunched,
}

/**
 * Предложение включить напоминание на итоге дня (ITERATION_6_DESIGN.md, §8.3, I6-D40).
 *
 * **Отдельная ViewModel, а не поля `DayRecapViewModel`.** Итог дня по-прежнему не
 * инжектирует настройки и не пишет DataStore (I5-D31): путь загрузки итога обязан
 * оставаться чтением. Диалог — слой route-контейнера поверх экрана.
 *
 * **Согласие долговечно до любого эффекта.** «Да» выполняет один подтверждаемый переход
 * DataStore ([AcceptReminderPromptUseCase]) и только после его успеха создаёт системный
 * запрос. Обратный порядок означал бы разрешение, выданное под согласие, которое не
 * сохранилось.
 *
 * **Недоступность уведомлений согласие не отбрасывает.** При `AppNotificationsDisabled`
 * и `ChannelDisabled` запрос не запускается, но напоминание в DataStore включено: в
 * «Настройках» переключатель показан выключенным с подсказкой и путём в системные
 * настройки, и после разблокировки напоминание заработает без повторного согласия.
 */
@HiltViewModel
class ReminderPromptViewModel @Inject constructor(
    private val getEligibility: GetReminderPromptEligibilityUseCase,
    private val acceptPrompt: AcceptReminderPromptUseCase,
    private val markShown: MarkReminderPromptShownUseCase,
    private val notificationAccess: NotificationAccess,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val visible = MutableStateFlow(false)

    /** Показывать ли диалог. */
    val isVisible: StateFlow<Boolean> = visible.asStateFlow()

    private val effectChannel = Channel<ReminderPromptEffect>(Channel.BUFFERED)

    /** Ровно один коллектор на стороне UI — route-контейнер итога дня. */
    val effects: Flow<ReminderPromptEffect> = effectChannel.receiveAsFlow()

    private val routeArgs: RouteArgs? = parseRoute(
        rawDate = savedStateHandle.get<String>(Destinations.ARG_DATE),
        rawOrigin = savedStateHandle.get<String>(Destinations.ARG_ORIGIN),
    )

    init {
        resume()
    }

    /**
     * «Да, в 9:00».
     *
     * 1) диалог скрывается сразу — повторного нажатия не будет;
     * 2) переход DataStore **ожидается**;
     * 3) только после успеха — шаг продолжения и решение по перечитанному статусу.
     *
     * Ошибка перехода: DataStore не записал ни одного из трёх значений (переход
     * атомарен), разрешение не запрашивается, итог дня не затронут. Предложение может
     * появиться в следующий раз — честное следствие незаписанного согласия.
     */
    fun onAccept() {
        visible.value = false
        viewModelScope.launch {
            try {
                acceptPrompt()
            } catch (e: CancellationException) {
                // Уход с итога во время перехода: эффекта нет, записал ли DataStore —
                // решает он сам, и оба исхода описаны таблицей восстановления §8.3.
                throw e
            } catch (e: Exception) {
                return@launch
            }
            step = PromptStep.AcceptedAwaitingRequest
            continueAfterConsent()
        }
    }

    /**
     * «Не нужно» и системная «назад». Отметка подтверждаемая; напоминание не включается,
     * системного запроса нет.
     */
    fun onDecline() {
        visible.value = false
        viewModelScope.launch {
            try {
                markShown()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Отметка не записалась — предложение появится снова. Это лучше, чем
                // считать полученным ответ, которого в хранилище нет.
            }
        }
    }

    /** Route-контейнер сообщил, что системный диалог запущен. */
    fun onPermissionRequestLaunched() {
        step = PromptStep.RequestLaunched
    }

    /**
     * Система ответила на запрос. Записей нет ни при каком исходе — согласие уже
     * сохранено; булев результат callback'а не используется (I6-D36).
     */
    fun onPermissionResult() {
        step = null
    }

    /**
     * Первая загрузка и восстановление после смерти процесса (таблица §8.3).
     *
     * Если шаг продолжения сохранён, согласие уже записано: диалога нет, и остаётся
     * только не потерять и не удвоить системный запрос.
     */
    private fun resume() {
        when (step) {
            // Переход записан, запрос ещё не запускался — продолжаем с того же места.
            PromptStep.AcceptedAwaitingRequest -> viewModelScope.launch { continueAfterConsent() }

            // Запрос уже запущен: результат придёт восстановленному route-контейнеру
            // через ActivityResultRegistry. Второго запроса быть не должно.
            PromptStep.RequestLaunched -> Unit

            null -> checkEligibility()
        }
    }

    private fun checkEligibility() {
        val args = routeArgs ?: return
        // Архивный итог — никогда: предложение принадлежит только что завершённому дню.
        if (args.origin != RouteOrigin.Session) return
        viewModelScope.launch {
            visible.value = try {
                getEligibility(args.date)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Отказ чтения условий: диалога нет, итог дня не затронут.
                false
            }
        }
    }

    /**
     * Что делать после записанного согласия. Решение — по **перечитанному** статусу:
     * `Allowed` — работу запланирует коллектор настроек, делать нечего;
     * `RuntimePermissionMissing` — ровно один системный запрос;
     * уведомления приложения или канал выключены — запроса нет, путь через «Настройки».
     */
    private fun continueAfterConsent() {
        when (notificationAccess.availability()) {
            NotificationAvailability.RuntimePermissionMissing ->
                effectChannel.trySend(ReminderPromptEffect.RequestNotificationPermission)

            NotificationAvailability.Allowed,
            NotificationAvailability.AppNotificationsDisabled,
            NotificationAvailability.ChannelDisabled,
            -> step = null
        }
    }

    private var step: PromptStep?
        get() = savedStateHandle.get<String>(KEY_STEP)?.let { raw ->
            runCatching { PromptStep.valueOf(raw) }.getOrNull()
        }
        set(value) {
            savedStateHandle[KEY_STEP] = value?.name
        }

    /** Аргументы маршрута итога дня — те же, что читает `DayRecapViewModel`. */
    private data class RouteArgs(val date: LocalDate, val origin: RouteOrigin)

    private fun parseRoute(rawDate: String?, rawOrigin: String?): RouteArgs? {
        val origin = RouteOrigin.fromRouteToken(rawOrigin) ?: return null
        val date = try {
            rawDate?.let { LocalDate.parse(it) }
        } catch (e: DateTimeParseException) {
            null
        } ?: return null
        return RouteArgs(date, origin)
    }

    private companion object {
        const val KEY_STEP = "reminderPrompt.step"
    }
}
