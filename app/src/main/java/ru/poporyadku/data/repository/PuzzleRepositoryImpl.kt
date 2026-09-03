package ru.poporyadku.data.repository

import javax.inject.Inject
import kotlinx.serialization.json.Json
import ru.poporyadku.core.model.Puzzle
import ru.poporyadku.data.db.dao.PuzzleDao
import ru.poporyadku.data.db.mapper.toDomain
import ru.poporyadku.di.StorageJson
import ru.poporyadku.domain.repository.PuzzleRepository

/**
 * Настоящая реализация [PuzzleRepository] поверх Room (ITERATION_4_DESIGN.md, §8.6).
 *
 * В продуктовый граф НЕ привязана: до PR 4D `ContentModule` связывает
 * `TemporaryPuzzleRepository`, и эта реализация используется только тестами.
 *
 * Отозванная головоломка читается наравне с активной: `retiredIn` — пометка контента,
 * а не признак «строки нет», и архив обязан её открывать (`CONTENT_MODEL.md` §7).
 */
class PuzzleRepositoryImpl @Inject constructor(
    private val dao: PuzzleDao,
    @StorageJson private val json: Json,
) : PuzzleRepository {

    override suspend fun getPuzzle(puzzleId: String): Puzzle? =
        dao.getById(puzzleId)?.toDomain(json)
}
