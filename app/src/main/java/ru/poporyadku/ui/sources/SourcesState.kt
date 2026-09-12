package ru.poporyadku.ui.sources

import ru.poporyadku.core.model.Puzzle

/** Экран источников сыгранных головоломок (ITERATION_5_DESIGN.md, §3.11, §4.6). */
sealed interface SourcesState {
    data object Loading : SourcesState

    /** Ни одной сыгранной (не пропущенной) головоломки. */
    data object Empty : SourcesState

    data class Content(val items: List<SourceItemUi>) : SourcesState

    /** Чтение или разбор не удались: «Повторить». */
    data object Error : SourcesState
}

/**
 * Строка списка. [key] — строковая форма ключа дедупликации: детерминирована, различает
 * источники по URL и по паре `(reference, title)` и служит ключом элемента `LazyColumn`.
 */
data class SourceItemUi(
    val key: String,
    val source: Puzzle.Source,
)
