package ru.poporyadku.domain.repository

import ru.poporyadku.core.model.Puzzle

/** Источники одной сыгранной головоломки (ITERATION_5_DESIGN.md, §5.3). */
data class PuzzleSources(
    val puzzleId: String,
    val sources: List<Puzzle.Source>,
)

/**
 * Источники сыгранных головоломок (ITERATION_5_DESIGN.md, §3.11, §5.3, I5-D17).
 *
 * Данные — только Room: assets повторно не читаются, сеть не используется.
 */
interface PlayedSourcesRepository {

    /**
     * Источники каждой сыгранной (не пропущенной) головоломки, по возрастанию `puzzleId`.
     * Отозванные входят; попытка на отсутствующую головоломку список не ломает.
     * Повреждённый `sources_json` бросает — частичный список не возвращается.
     */
    suspend fun getPlayedPuzzleSources(): List<PuzzleSources>
}
