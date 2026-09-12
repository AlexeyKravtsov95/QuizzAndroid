package ru.poporyadku.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import ru.poporyadku.domain.usecase.GetPlayedSourcesUseCase

/**
 * ViewModel экрана источников (ITERATION_5_DESIGN.md, §3.11, §4.6, §8.5, I5-D17, I5-D25).
 *
 * Список читается один раз при создании и заново только по «Повторить»: источники
 * сыгранных головоломок не меняются, пока экран открыт (новая попытка на этом экране
 * невозможна). `CancellationException` пробрасывается и ошибкой не становится; любой
 * другой отказ чтения или разбора — [SourcesState.Error].
 */
@HiltViewModel
class SourcesViewModel @Inject constructor(
    private val getPlayedSources: GetPlayedSourcesUseCase,
) : ViewModel() {

    private val state = MutableStateFlow<SourcesState>(SourcesState.Loading)
    val uiState: StateFlow<SourcesState> = state.asStateFlow()

    private val effectChannel = Channel<SourcesEffect>(Channel.BUFFERED)

    /** Ровно один коллектор на стороне UI — на уровне route-контейнера (I5-D24). */
    val effects: Flow<SourcesEffect> = effectChannel.receiveAsFlow()

    init {
        load()
    }

    fun onEvent(event: SourcesEvent) {
        when (event) {
            // Только из Error: повторное нажатие во время загрузки второго чтения не запускает.
            SourcesEvent.RetryClicked -> if (state.value == SourcesState.Error) load()
            SourcesEvent.BackClicked -> effectChannel.trySend(SourcesEffect.NavigateBack)
        }
    }

    private fun load() {
        state.value = SourcesState.Loading
        viewModelScope.launch {
            state.value = try {
                val catalog = getPlayedSources()
                if (catalog.isEmpty()) {
                    SourcesState.Empty
                } else {
                    SourcesState.Content(catalog.map { SourceItemUi(key = it.key.text, source = it.source) })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SourcesState.Error
            }
        }
    }
}
