package ru.poporyadku.ui.archive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.poporyadku.domain.model.ArchiveDay
import ru.poporyadku.domain.model.Statistics
import ru.poporyadku.domain.usecase.GetArchiveUseCase
import ru.poporyadku.domain.usecase.GetStatisticsUseCase

/**
 * ViewModel архива (ITERATION_5_DESIGN.md, §3.4, §3.6, §6.1, §8.5; ADR-017).
 *
 * **Пагинация без Paging 3.** Разведка страницы ([GetArchiveUseCase.probe]) только двигает
 * нижнюю границу и говорит, есть ли продолжение; показываемые строки всегда берутся из
 * наблюдаемого окна `local_date >= граница` ([GetArchiveUseCase.observeWindow]). Окно
 * ограничено датой, а не числом строк: новый день сверху ничего не выталкивает, и дублей
 * на стыке страниц нет.
 *
 * **Отказы не смешиваются** (I5-D25). Отказ первой разведки — `Error`; отказ следующей
 * страницы — строки на месте и `LoadMoreFailed`, повтор с той же границы; отказ
 * перечитывания окна или статистики после показа — последние удачные данные на месте и
 * `RefreshFailed`; отказ статистики до первого показа — `Error`. Упавший Room-`Flow`
 * завершается, поэтому «Повторить» и `ON_START` переподписывают поток через [generation].
 * `CancellationException` пробрасывается всегда и ошибкой не становится.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ArchiveViewModel @Inject constructor(
    private val archive: GetArchiveUseCase,
    getStatistics: GetStatisticsUseCase,
) : ViewModel() {

    /** Что наблюдается и можно ли подгружать дальше. */
    private sealed interface Pager {
        data object InitialProbe : Pager
        data object InitialFailed : Pager
        data class Showing(
            /** `null` — продолжения нет, наблюдаются все дни. */
            val lowerBound: LocalDate?,
            val hasMore: Boolean,
            val more: More,
        ) : Pager
    }

    private enum class More { Idle, Loading, Failed }

    /**
     * Ключ окна. Обёртка нужна, чтобы «наблюдать все дни» (`Bound(null)`) отличалось от
     * «окно ещё не начато» (ключа нет вовсе).
     */
    private data class Bound(val date: LocalDate?)

    /** Исход одной эмиссии окна или статистики (`WindowResult`/`StatsResult` из §6.1). */
    private sealed interface Emission<out T> {
        /** Окно ещё не начато: первая разведка не завершилась. */
        data object NotStarted : Emission<Nothing>
        data class Good<T>(val value: T) : Emission<T>
        data object Failed : Emission<Nothing>
    }

    /** Последнее удачное значение и признак «последняя эмиссия — отказ». */
    private data class Last<T : Any>(val good: T?, val latestFailed: Boolean)

    /**
     * Держит последнее удачное значение между эмиссиями и между подписками: ни отказ
     * перечитывания, ни переподписка (`generation`, `WhileSubscribed`) его не стирают, а
     * новая подписка начинает с него, а не с пустоты.
     */
    private class LastGood<T : Any> {
        var current: Last<T> = Last(good = null, latestFailed = false)
            private set

        fun track(emissions: Flow<Emission<T>>): Flow<Last<T>> = flow {
            emit(current)
            emissions.collect { emission ->
                current = when (emission) {
                    Emission.NotStarted -> current
                    is Emission.Good -> Last(emission.value, latestFailed = false)
                    Emission.Failed -> current.copy(latestFailed = true)
                }
                emit(current)
            }
        }
    }

    private val pager = MutableStateFlow<Pager>(Pager.InitialProbe)

    /** Поколение подписок: упавший Room-`Flow` завершается, и переподписаться можно только новой подпиской. */
    private val generation = MutableStateFlow(0)

    /** `ON_START`: пересчитать статистику с новым `today` без новой записи в базу. */
    private val refresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val effectChannel = Channel<ArchiveEffect>(Channel.BUFFERED)

    /** Ровно один коллектор на стороне UI — на уровне route-контейнера (I5-D24). */
    val effects: Flow<ArchiveEffect> = effectChannel.receiveAsFlow()

    private val lastWindow = LastGood<List<ArchiveDay>>()
    private val lastStatistics = LastGood<Statistics>()

    /** Ключ окна текущего пейджера; `null` — окно ещё не начато. */
    private val bound: Flow<Bound?> = pager.map { (it as? Pager.Showing)?.let { showing -> Bound(showing.lowerBound) } }

    /** Окно: новая подписка только при смене границы или поколения. */
    private val window: Flow<Emission<List<ArchiveDay>>> =
        combine(bound, generation) { bound, gen -> bound to gen }
            .distinctUntilChanged()
            .flatMapLatest { (bound, _) ->
                if (bound == null) {
                    flowOf(Emission.NotStarted)
                } else {
                    archive.observeWindow(bound.date)
                        .onEach { rows -> if (rows.isEmpty() && bound.date != null) onWindowEmptied() }
                        .map<List<ArchiveDay>, Emission<List<ArchiveDay>>> { Emission.Good(it) }
                        .catchNonCancellation { emit(Emission.Failed) }
                }
            }

    private val statistics: Flow<Emission<Statistics>> = generation.flatMapLatest {
        getStatistics(refresh)
            .map<Statistics, Emission<Statistics>> { Emission.Good(it) }
            .catchNonCancellation { emit(Emission.Failed) }
    }

    val uiState: StateFlow<ArchiveState> =
        combine(pager, lastWindow.track(window), lastStatistics.track(statistics)) { p, w, s -> reduce(p, w, s) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ArchiveState.Loading)

    init {
        probeInitial()
    }

    /**
     * `ON_START` из route-контейнера (как у Home, I3-D14): перечитывает `today` для серий
     * и возобновляет упавшее перечитывание окна или статистики.
     */
    fun onScreenStarted() {
        refresh.tryEmit(Unit)
        if (lastWindow.current.latestFailed || lastStatistics.current.latestFailed) {
            generation.update { it + 1 }
        }
    }

    fun onEvent(event: ArchiveEvent) {
        when (event) {
            is ArchiveEvent.DayClicked -> effectChannel.trySend(ArchiveEffect.OpenDay(event.localDate))
            ArchiveEvent.EndReached -> loadMore()
            ArchiveEvent.RetryClicked -> retry()
            ArchiveEvent.BackClicked -> effectChannel.trySend(ArchiveEffect.NavigateBack)
            ArchiveEvent.ToTodayClicked -> effectChannel.trySend(ArchiveEffect.NavigateHome)
        }
    }

    private fun probeInitial() {
        pager.value = Pager.InitialProbe
        viewModelScope.launch {
            try {
                val page = archive.probe(before = null)
                pager.value = Pager.Showing(page.nextLowerBound, page.hasMore, More.Idle)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pager.value = Pager.InitialFailed
            }
        }
    }

    /**
     * Принимается только из `Showing(hasMore, Idle|Failed)`: повторный `EndReached` во
     * время `Loading` игнорируется, а без продолжения подгрузка не запускается.
     */
    private fun loadMore() {
        val showing = pager.value as? Pager.Showing ?: return
        if (!showing.hasMore || showing.more == More.Loading) return
        val before = showing.lowerBound ?: return // hasMore ⇒ граница есть
        val loading = showing.copy(more = More.Loading)
        pager.value = loading
        viewModelScope.launch {
            try {
                val page = archive.probe(before)
                // Окно переключается на новую границу: старые строки остаются в окне
                // (>= новая граница), новые верхние тоже — пропуска и дубля нет. Результат
                // применяется, только если пейджер не сбросили, пока шла разведка.
                pager.update { current ->
                    if (current == loading) Pager.Showing(page.nextLowerBound, page.hasMore, More.Idle) else current
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Граница та же: повтор прочитает ту же страницу, показанное не сбрасывается.
                pager.update { current -> if (current == loading) loading.copy(more = More.Failed) else current }
            }
        }
    }

    private fun retry() {
        when (val current = pager.value) {
            Pager.InitialFailed -> {
                generation.update { it + 1 }
                probeInitial()
            }

            is Pager.Showing -> {
                generation.update { it + 1 } // переподписка окна и статистики
                if (current.more == More.Failed) loadMore() // та же граница
            }

            Pager.InitialProbe -> Unit // уже идёт
        }
    }

    /** Окно с нижней границей опустело — база очищена при открытом архиве (debug-сброс). */
    private fun onWindowEmptied() {
        probeInitial()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /** Чистая функция: только аргументы, ни состояния, ни часов (I5-V1…I5-V9). */
        fun reduce(p: Pager, w: Last<List<ArchiveDay>>, s: Last<Statistics>): ArchiveState = when (p) {
            Pager.InitialProbe -> ArchiveState.Loading
            Pager.InitialFailed -> ArchiveState.Error
            is Pager.Showing -> reduceShowing(p, w, s)
        }

        fun reduceShowing(p: Pager.Showing, w: Last<List<ArchiveDay>>, s: Last<Statistics>): ArchiveState {
            val rows = w.good
            val stats = s.good
            val refreshFailed = w.latestFailed || s.latestFailed
            return when {
                // Ещё нет ни одной удачной эмиссии окна или статистики.
                rows == null || stats == null -> if (refreshFailed) ArchiveState.Error else ArchiveState.Loading
                rows.isEmpty() && stats.playedDays == 0 -> ArchiveState.Empty
                else -> ArchiveState.Content(
                    statistics = stats.toUi(),
                    items = rows.map { it.toUi() },
                    paging = pagingOf(p, refreshFailed),
                )
            }
        }

        fun pagingOf(p: Pager.Showing, refreshFailed: Boolean): ArchivePaging = when {
            refreshFailed -> ArchivePaging.RefreshFailed
            p.more == More.Loading -> ArchivePaging.LoadingMore
            p.more == More.Failed -> ArchivePaging.LoadMoreFailed
            p.hasMore -> ArchivePaging.CanLoadMore
            else -> ArchivePaging.EndReached
        }

        fun ArchiveDay.toUi() = ArchiveItem(
            localDate = localDate,
            dayNumber = dayNumber,
            totalScore = totalScore,
            completedCount = completedCount,
            isComplete = isComplete,
        )

        fun Statistics.toUi() = ArchiveStatistics(
            playedDays = playedDays,
            averageTenths = averageTenths,
            bestDayScore = bestDayScore,
            currentStreak = streaks.current,
            bestStreak = streaks.best,
        )

        /** Ловит `Exception`, но сначала пробрасывает `CancellationException` (I3-D43). */
        fun <T> Flow<T>.catchNonCancellation(onFailure: suspend FlowCollector<T>.() -> Unit): Flow<T> =
            catch { error ->
                if (error is CancellationException || error !is Exception) throw error
                onFailure()
            }
    }
}
