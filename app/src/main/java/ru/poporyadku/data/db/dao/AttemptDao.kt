package ru.poporyadku.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ru.poporyadku.data.db.entity.PuzzleAttemptEntity

// Фундамент схемы (PR 2A). UNIQUE(local_date, slot_index) отбивает повторную запись
// на уровне базы. Пересчёт day_results и проверка доменных диапазонов (D-5, D-17) —
// ProgressRepositoryImpl, PR 2B.
@Dao
interface AttemptDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(attempt: PuzzleAttemptEntity): Long

    @Query("SELECT * FROM puzzle_attempts WHERE local_date = :localDate AND slot_index = :slotIndex")
    suspend fun getByDateAndSlot(localDate: String, slotIndex: Int): PuzzleAttemptEntity?

    @Query("SELECT * FROM puzzle_attempts WHERE local_date = :localDate ORDER BY slot_index")
    suspend fun getByDate(localDate: String): List<PuzzleAttemptEntity>
}
