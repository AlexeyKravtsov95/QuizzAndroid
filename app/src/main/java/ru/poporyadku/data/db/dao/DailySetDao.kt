package ru.poporyadku.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ru.poporyadku.data.db.entity.DailySetEntity

// Фундамент схемы (PR 2A). upsertAll — CONTENT_MODEL.md, §7 ("upsert наборов по
// (packId, setIndex)"), используется также debug-фикстурой (D-9) в PR 2C.
// countSets — pack-scoped запрос снимка выдачи (ITERATION_2_DESIGN.md, D-4, D-20).
@Dao
interface DailySetDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sets: List<DailySetEntity>)

    @Query("SELECT * FROM daily_sets WHERE pack_id = :packId AND set_index = :setIndex")
    suspend fun getSet(packId: String, setIndex: Int): DailySetEntity?

    @Query("SELECT COUNT(*) FROM daily_sets WHERE pack_id = :packId")
    suspend fun countSets(packId: String): Int

    // ITERATION_3_DESIGN.md, I3-D41 / I3-D50 (PR 3A): сверка состава наборов активного
    // пакета внутри транзакции reconcile.

    /** Какие наборы реально лежат в базе. Второй обязательный SELECT reconcile. */
    @Query("SELECT set_index FROM daily_sets WHERE pack_id = :packId ORDER BY set_index")
    suspend fun setIndexes(packId: String): List<Int>

    /** Единственный DELETE установщика, и бьёт он строго по daily_sets: прогресс
     *  (day_assignments, puzzle_attempts, day_results) не удаляется никогда. */
    @Query("DELETE FROM daily_sets WHERE pack_id = :packId AND set_index NOT IN (:keep)")
    suspend fun deleteOutside(packId: String, keep: List<Int>)

    // Read-only диагностический дамп общего назначения (PR 2C, debug-экран, §8):
    // не debug-тип, архитектуру не меняет.
    @Query("SELECT * FROM daily_sets ORDER BY pack_id, set_index")
    fun observeAll(): Flow<List<DailySetEntity>>
}
