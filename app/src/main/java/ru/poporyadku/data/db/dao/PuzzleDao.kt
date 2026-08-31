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
}
