package ru.poporyadku.domain.usecase

import ru.poporyadku.core.model.Puzzle

/** Итог загрузки головоломки слота (ITERATION_3_DESIGN.md, §8). */
sealed interface GetPuzzleResult {

    /** Слот открыт и не закрыт: играем. */
    data class Playable(
        val puzzle: Puzzle,
        val slotIndex: Int,
        val setIndex: Int,
        /** `DeterministicShuffler.shuffle(puzzleId, cardIds)` — один и тот же при каждом входе. */
        val startOrder: List<String>,
    ) : GetPuzzleResult

    /** Слот уже закрыт. Куда идти дальше, решает вид ЗАПИСАННОЙ попытки (I3-D45). */
    data class AlreadyClosed(val slotIndex: Int, val kind: AttemptKind) : GetPuzzleResult

    data class Failure(val kind: PuzzleErrorKind) : GetPuzzleResult
}
