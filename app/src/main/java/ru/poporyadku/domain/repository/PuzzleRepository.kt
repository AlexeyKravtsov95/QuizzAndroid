package ru.poporyadku.domain.repository

import ru.poporyadku.core.model.Puzzle

/**
 * Головоломки активного контента (ITERATION_3_DESIGN.md, I3-D2).
 *
 * Интерфейс живёт в domain, реализация — в data. В итерации 3 её роль исполняет
 * временный источник литералов; в итерации 4 он заменяется реализацией поверх Room
 * без единой правки в use case, ViewModel и экранах.
 */
interface PuzzleRepository {

    /** null — головоломки с таким id нет в активном контенте. */
    suspend fun getPuzzle(puzzleId: String): Puzzle?
}
