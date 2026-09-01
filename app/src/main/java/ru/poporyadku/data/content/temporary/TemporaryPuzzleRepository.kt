package ru.poporyadku.data.content.temporary

import javax.inject.Inject
import ru.poporyadku.core.model.Puzzle
import ru.poporyadku.domain.repository.PuzzleRepository

/**
 * Временная реализация [PuzzleRepository] поверх литералов [BundledPuzzles]
 * (ITERATION_3_DESIGN.md, I3-D1, I3-D2).
 *
 * Живёт в `src/main`, а не в `src/debug`: релизная сборка обязана быть играбельной,
 * а реализация в debug оставила бы граф Hilt релиза без привязки.
 *
 * Таблицу `puzzles` не читает и не пишет: маппер `PuzzleEntity ↔ Puzzle` отложен
 * до итерации 4 (ITERATION_2_DESIGN.md, D-19). Ни assets, ни JSON, ни `contentVersion`.
 */
class TemporaryPuzzleRepository @Inject constructor() : PuzzleRepository {

    override suspend fun getPuzzle(puzzleId: String): Puzzle? =
        BundledPuzzles.puzzles.firstOrNull { it.puzzleId == puzzleId }
}
