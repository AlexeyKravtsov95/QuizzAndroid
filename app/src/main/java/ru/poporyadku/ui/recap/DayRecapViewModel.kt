package ru.poporyadku.ui.recap

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.format.DateTimeParseException
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import ru.poporyadku.core.time.DateProvider
import ru.poporyadku.domain.repository.UserPreferencesRepository
import ru.poporyadku.domain.usecase.DayRecapResult
import ru.poporyadku.domain.usecase.GetDayRecapUseCase
import ru.poporyadku.ui.navigation.Destinations

/**
 * ViewModel итога дня (ITERATION_3_DESIGN.md, раздел 13, I3-D51).
 *
 * `today` берётся из [DateProvider] и только из него: экрану нужна одна `LocalDate` —
 * обратного отсчёта здесь нет, `Instant` не вычитается, зона в расчёте не участвует.
 * Снимок часов с моментом и зоной был бы избыточен, а системная дата нигде,
 * кроме [DateProvider], не читается.
 *
 * `localDate` приходит **только** из аргумента маршрута и никогда не подменяется
 * текущей датой: смена системной даты при открытом экране не меняет просматриваемый
 * день — итог дня это свойство дня, а не момента. Минутного тикера здесь нет.
 */
@HiltViewModel
class DayRecapViewModel @Inject constructor(
    private val getDayRecap: GetDayRecapUseCase,
    private val preferences: UserPreferencesRepository,
    private val dateProvider: DateProvider,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val state = MutableStateFlow<DayRecapState>(DayRecapState.Loading)
    val uiState: StateFlow<DayRecapState> = state.asStateFlow()

    private val effectChannel = Channel<DayRecapEffect>(Channel.BUFFERED)

    /** Ровно один коллектор на стороне UI — на уровне route-контейнера (I3-D25). */
    val effects: Flow<DayRecapEffect> = effectChannel.receiveAsFlow()

    /** Аргумент отсутствует или не разбирается — день не показывается, а не подменяется. */
    private val routeDate: LocalDate? =
        parseRouteDate(savedStateHandle.get<String>(Destinations.ARG_DATE))

    init {
        load()
    }

    fun onEvent(event: DayRecapEvent) {
        when (event) {
            DayRecapEvent.DoneClicked -> effectChannel.trySend(DayRecapEffect.NavigateHome)
        }
    }

    private fun load() {
        val localDate = routeDate
        if (localDate == null) {
            state.value = DayRecapState.NotFound
            return
        }
        viewModelScope.launch {
            // `today` читается РОВНО ОДИН РАЗ на загрузку, вместе с вызовом use case:
            // иначе заголовок и серия относились бы к разным моментам.
            val today = dateProvider.today()
            val result = getDayRecap(localDate = localDate, today = today)
            state.value = result.toDayRecapState(today)

            // Флаг понадобится итерации 6 (условие показа запроса на уведомления);
            // сам запрос POST_NOTIFICATIONS в итерации 3 не выполняется.
            if (result is DayRecapResult.Content && result.isComplete) {
                preferences.setHasCompletedFirstDay(true)
            }
        }
    }

    private fun parseRouteDate(raw: String?): LocalDate? =
        if (raw.isNullOrBlank()) {
            null
        } else {
            try {
                Destinations.parseDate(raw)
            } catch (e: DateTimeParseException) {
                null
            }
        }
}
