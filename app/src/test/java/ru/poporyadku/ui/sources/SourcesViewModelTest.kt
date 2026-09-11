package ru.poporyadku.ui.sources

import app.cash.turbine.test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import ru.poporyadku.core.model.Puzzle
import ru.poporyadku.domain.repository.PlayedSourcesRepository
import ru.poporyadku.domain.repository.PuzzleSources
import ru.poporyadku.domain.usecase.GetPlayedSourcesUseCase

/**
 * `SourcesViewModel` — `I5-V25` и навигационная часть `I5-V27` (ITERATION_5_DESIGN.md,
 * §3.11, §4.6, §8.5, I5-D25).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourcesViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: ScriptedRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = ScriptedRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** `I5-V25`. `Loading → Empty`, когда сыгранных головоломок нет. */
    @Test
    fun `I5-V25 loading then empty`() = runTest(dispatcher) {
        repository.next += { emptyList() }
        val viewModel = SourcesViewModel(GetPlayedSourcesUseCase(repository))

        assertEquals(SourcesState.Loading, viewModel.uiState.value)
        advanceUntilIdle()
        assertEquals(SourcesState.Empty, viewModel.uiState.value)
    }

    /**
     * `I5-V25`. `Loading → Content`: дубликаты слиты, порядок каталога, ключ строки —
     * строковая форма ключа дедупликации.
     */
    @Test
    fun `I5-V25 loading then content with catalog keys`() = runTest(dispatcher) {
        repository.next += {
            listOf(
                PuzzleSources("p-001", listOf(source("s1", "Бета", url = URL), source("s2", "Альфа", reference = "Т. 1"))),
                PuzzleSources("p-002", listOf(source("s1", "Бета", url = URL))),
            )
        }
        val viewModel = SourcesViewModel(GetPlayedSourcesUseCase(repository))
        advanceUntilIdle()

        val content = viewModel.uiState.value as SourcesState.Content
        assertEquals(listOf("Альфа", "Бета"), content.items.map { it.source.title })
        assertEquals(listOf("ref:4:Т. 1Альфа", "url:$URL"), content.items.map { it.key })
    }

    /** `I5-V25`. Отказ чтения → `Error`; «Повторить» читает заново → `Content`. */
    @Test
    fun `I5-V25 failure then retry`() = runTest(dispatcher) {
        repository.next += { throw IllegalStateException("sources_json повреждён") }
        repository.next += { listOf(PuzzleSources("p-001", listOf(source("s1", "Альфа", url = URL)))) }
        val viewModel = SourcesViewModel(GetPlayedSourcesUseCase(repository))
        advanceUntilIdle()
        assertEquals(SourcesState.Error, viewModel.uiState.value)

        viewModel.onEvent(SourcesEvent.RetryClicked)
        assertEquals(SourcesState.Loading, viewModel.uiState.value)
        advanceUntilIdle()

        assertEquals(1, (viewModel.uiState.value as SourcesState.Content).items.size)
        assertEquals(2, repository.reads)
    }

    /** «Повторить» принимается только из `Error`: во время загрузки и в `Content` чтения нет. */
    @Test
    fun `retry outside of error does not read again`() = runTest(dispatcher) {
        repository.next += { listOf(PuzzleSources("p-001", listOf(source("s1", "Альфа", url = URL)))) }
        val viewModel = SourcesViewModel(GetPlayedSourcesUseCase(repository))
        viewModel.onEvent(SourcesEvent.RetryClicked) // Loading
        advanceUntilIdle()
        viewModel.onEvent(SourcesEvent.RetryClicked) // Content
        advanceUntilIdle()

        assertEquals(1, repository.reads)
    }

    /** `CancellationException` чтения пробрасывается и `Error` не становится. */
    @Test
    fun `cancellation is not an error`() = runTest(dispatcher) {
        repository.next += { throw CancellationException("экран закрыт") }
        val viewModel = SourcesViewModel(GetPlayedSourcesUseCase(repository))
        advanceUntilIdle()

        assertEquals(SourcesState.Loading, viewModel.uiState.value)
    }

    /** `I5-V27`. `BackClicked` → ровно один `NavigateBack`. */
    @Test
    fun `I5-V27 back emits exactly one NavigateBack`() = runTest(dispatcher) {
        repository.next += { emptyList() }
        val viewModel = SourcesViewModel(GetPlayedSourcesUseCase(repository))

        viewModel.effects.test {
            viewModel.onEvent(SourcesEvent.BackClicked)
            assertEquals(SourcesEffect.NavigateBack, awaitItem())
            expectNoEvents()
        }
    }

    private class ScriptedRepository : PlayedSourcesRepository {
        val next = ArrayDeque<() -> List<PuzzleSources>>()
        var reads = 0
            private set

        override suspend fun getPlayedPuzzleSources(): List<PuzzleSources> {
            reads++
            return next.removeFirst().invoke()
        }
    }

    private fun source(sourceId: String, title: String, url: String? = null, reference: String? = null) =
        Puzzle.Source(sourceId, title, "encyclopedia", url, reference, "2026-08-20", null)

    private companion object {
        const val URL = "https://e.test/beta"
    }
}
