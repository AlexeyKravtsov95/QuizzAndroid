package ru.poporyadku.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ru.poporyadku.data.db.entity.DayResultEntity

// Фундамент схемы (PR 2A). Пересчёт из puzzle_attempts в той же транзакции, что запись
// попытки (D-5), — ProgressRepositoryImpl, PR 2B.
@Dao
interface DayResultDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(result: DayResultEntity)

    @Query("SELECT * FROM day_results WHERE local_date = :localDate")
    suspend fun getByDate(localDate: String): DayResultEntity?

    @Query("SELECT * FROM day_results WHERE local_date = :localDate")
    fun observeByDate(localDate: String): Flow<DayResultEntity?>

    // PR 2B, ProgressRepository: чтение диапазона ISO-дат для архива/статистики.
    @Query("SELECT * FROM day_results WHERE local_date BETWEEN :from AND :to ORDER BY local_date")
    suspend fun getRange(from: String, to: String): List<DayResultEntity>
}
