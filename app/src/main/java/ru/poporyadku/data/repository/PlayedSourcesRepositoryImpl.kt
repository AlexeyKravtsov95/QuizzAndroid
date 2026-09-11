package ru.poporyadku.data.repository

import javax.inject.Inject
import kotlinx.serialization.json.Json
import ru.poporyadku.data.db.dao.PuzzleDao
import ru.poporyadku.data.db.mapper.decodeStoredSources
import ru.poporyadku.di.StorageJson
import ru.poporyadku.domain.repository.PlayedSourcesRepository
import ru.poporyadku.domain.repository.PuzzleSources

/**
 * Источники сыгранных головоломок поверх Room (ITERATION_5_DESIGN.md, §3.11, §5.3).
 *
 * Зависимостей ровно две — DAO и строгий `@StorageJson`: источника байтов пакета здесь нет,
 * поэтому прочитать assets этот репозиторий не может по построению.
 *
 * Неразбираемый `sources_json` любой сыгранной головоломки — повреждение того, что писали
 * мы сами (I4-D17): чтение бросает целиком. Частичный список с молча выброшенной
 * головоломкой скрыл бы дефект, который сломал бы и её результат в архиве.
 */
class PlayedSourcesRepositoryImpl @Inject constructor(
    private val dao: PuzzleDao,
    @StorageJson private val json: Json,
) : PlayedSourcesRepository {

    override suspend fun getPlayedPuzzleSources(): List<PuzzleSources> =
        dao.playedSources().map { row ->
            PuzzleSources(
                puzzleId = row.puzzleId,
                sources = decodeStoredSources(json, row.sourcesJson),
            )
        }
}
