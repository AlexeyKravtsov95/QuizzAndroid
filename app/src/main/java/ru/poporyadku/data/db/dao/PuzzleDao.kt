package ru.poporyadku.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ru.poporyadku.data.db.entity.PuzzleEntity

// Фундамент схемы (PR 2A). Импорт контента (upsert по puzzleId — CONTENT_MODEL.md, §7)
// и маппер Puzzle приходят в итерации 4 (ITERATION_2_DESIGN.md, D-19).
@Dao
interface PuzzleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(puzzles: List<PuzzleEntity>)

    @Query("SELECT * FROM puzzles WHERE puzzle_id = :puzzleId")
    suspend fun getById(puzzleId: String): PuzzleEntity?

    // ITERATION_4_DESIGN.md, PR 4B: read-only счётчик для тестов импорта.
    // DELETE по puzzles не существует ни здесь, ни где-либо ещё в src/main:
    // головоломка, на которую может ссылаться история, не удаляется никогда
    // (CONTENT_MODEL.md §7, раздел 3.4).
    @Query("SELECT COUNT(*) FROM puzzles WHERE pack_id = :packId")
    suspend fun countByPack(packId: String): Int

    /** Проекция строки источников: разбор `sources_json` — в `PlayedSourcesRepositoryImpl`. */
    data class PlayedSourcesRow(
        val puzzleId: String,
        val sourcesJson: String,
    )

    /**
     * Источники сыгранных головоломок (ITERATION_5_DESIGN.md, §3.11, §5.3, I5-D17), только
     * чтение.
     *
     * - `submitted_order <> ''` исключает пропуски: пропуск хранится пустой строкой
     *   (`ProgressMappers`), и его головоломку пользователь не видел;
     * - отозванные входят — `retired_in` не фильтруется: сыгранное остаётся сыгранным;
     * - попытка на отсутствующую строку `puzzles` отсекается самим запросом;
     * - подзапрос `IN` материализуется один раз, `ORDER BY puzzle_id` даёт
     *   детерминированный вход дедупликации.
     */
    @Query(
        """
        SELECT p.puzzle_id AS puzzleId, p.sources_json AS sourcesJson
          FROM puzzles p
         WHERE p.puzzle_id IN (
               SELECT t.puzzle_id
                 FROM puzzle_attempts t
                WHERE t.submitted_order <> ''
         )
         ORDER BY p.puzzle_id
        """
    )
    suspend fun playedSources(): List<PlayedSourcesRow>
}
