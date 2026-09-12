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
import ru.poporyadku.core.time.DateProvider
import ru.poporyadku.domain.usecase.GetDayRecapUseCase
import ru.poporyadku.ui.navigation.Destinations
import ru.poporyadku.ui.navigation.RouteOrigin
import ru.poporyadku.ui.share.ShareCardInput

/**
 * ViewModel итога дня (ITERATION_3_DESIGN.md, §13, I3-D51; ITERATION_5_DESIGN.md, §6.8,
 * I5-D8, I5-D25, I5-D31).
 *
 * Зависимости — только чтение: [GetDayRecapUseCase] (Room), [DateProvider] и
 * `SavedStateHandle`. `UserPreferencesRepository` не инжектируется намеренно: путь
 * загрузки итога не содержит ни одной записи DataStore, поэтому готовый `Content`
 * нечему заменить на `NotFound` (флаг первого дня ставит `SubmitAnswerUseCase`,
 * `StreakCache` — расчёт Home).
 *
 * `today` берётся из [DateProvider] и только из него, ровно один раз на загрузку, и
 * нужен только заголовку сессионного варианта. `localDate` приходит **только** из
 * аргумента маршрута и никогда не подменяется текущей датой. Вариант экрана — только
 * из `origin`: отсутствует → сессия, [Destinations.ORIGIN_ARCHIVE] → архив.
 */
@HiltViewModel
class DayRecapViewModel @Inject constructor(
    private val getDayRecap: GetDayRecapUseCase,
    private val dateProvider: DateProvider,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val state = MutableStateFlow<DayRecapState>(DayRecapState.Loading)
    val uiState: StateFlow<DayRecapState> = state.asStateFlow()

    private val effectChannel = Channel<DayRecapEffect>(Channel.BUFFERED)

    /** Ровно один коллектор на стороне UI — на уровне route-контейнера (I3-D25). */
    val effects: Flow<DayRecapEffect> = effectChannel.receiveAsFlow()

    /** `null` — маршрут невалиден: день не показывается, а не подменяется. */
    private val routeArgs: RouteArgs? = parseRoute(
        rawDate = savedStateHandle.get<String>(Destinations.ARG_DATE),
        rawOrigin = savedStateHandle.get<String>(Destinations.ARG_ORIGIN),
    )

    init {
        load()
    }

    fun onEvent(event: DayRecapEvent) {
        val args = routeArgs ?: return
        when (event) {
            DayRecapEvent.PrimaryClicked, DayRecapEvent.BackClicked -> effectChannel.trySend(
                when (args.origin) {
                    RouteOrigin.Session -> DayRecapEffect.NavigateHome
                    RouteOrigin.Archive -> DayRecapEffect.NavigateBack
                },
            )

            is DayRecapEvent.SlotClicked -> {
                val content = state.value as? DayRecapState.Content ?: return
                val slot = content.slots.getOrNull(event.slotIndex) as? SlotResultUi.Played ?: return
                // Сессионный вариант и не-Played — ничего: попытки нет, показать нечего,
                // а открыть можно было бы только игру прошлого дня.
                if (!slot.isOpenable) return
                effectChannel.trySend(DayRecapEffect.OpenResult(slot.slotIndex, args.date))
            }

            DayRecapEvent.ShareClicked -> {
                val content = state.value as? DayRecapState.Content ?: return
                // Кнопки у незавершённого дня нет; проверка повторяется здесь, потому что
                // событие может прийти и от чего-то, кроме этой кнопки.
                if (!content.canShare) return
                // Внутренне противоречивое состояние (нет серии или есть NotPlayed при
                // canShare) карточкой не показывается: эффекта просто нет.
                val scores = content.shareScores() ?: return
                val streakDays = content.streakDays ?: return
                effectChannel.trySend(
                    DayRecapEffect.Share(
                        ShareCardInput(
                            dayNumber = content.dayNumber,
                            slotScores = scores,
                            streakDays = streakDays,
                        ),
                    ),
                )
            }
        }
    }

    /**
     * Три счёта в порядке слотов 0..2 или `null`, если карточке нечего показать.
     *
     * `Unavailable` в карточку входит: попытка была, счёт из данных — это не «не сыграно».
     * `NotPlayed` не входит: символа «не сыграно» в формате из семи строк нет (§3.13).
     */
    private fun DayRecapState.Content.shareScores(): List<Int>? {
        if (slots.size != SLOT_COUNT) return null
        return slots.sortedBy { it.slotIndex }.map { slot ->
            when (slot) {
                is SlotResultUi.Played -> slot.score
                is SlotResultUi.Unavailable -> slot.score
                is SlotResultUi.NotPlayed -> return null
            }
        }
    }

    private fun load() {
        val args = routeArgs
        if (args == null) {
            // Неизвестный origin или неразбираемая дата: вариант не угадывается, база не
            // читается, «сегодня» не подставляется.
            state.value = DayRecapState.NotFound(RouteOrigin.Session)
            return
        }
        viewModelScope.launch {
            // Экранную ошибку может дать только чтение дня и его преобразование.
            val next: DayRecapState = try {
                val today = dateProvider.today()
                getDayRecap(args.date).toDayRecapState(today, args.origin)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Данных дня нет или они непригодны: recapMissing, процесс не падает.
                DayRecapState.NotFound(args.origin)
            }
            state.value = next
            // После публикации состояния записей нет: готовый Content ничем не заменяется.
        }
    }

    private data class RouteArgs(val date: LocalDate, val origin: RouteOrigin)

    private companion object {
        /** Головоломок в дне — столько же строк результата в карточке. */
        const val SLOT_COUNT = 3
    }

    private fun parseRoute(rawDate: String?, rawOrigin: String?): RouteArgs? {
        val origin = RouteOrigin.fromRouteToken(rawOrigin) ?: return null
        if (rawDate.isNullOrBlank()) return null
        val date = try {
            Destinations.parseDate(rawDate)
        } catch (e: DateTimeParseException) {
            return null
        }
        return RouteArgs(date, origin)
    }
}
