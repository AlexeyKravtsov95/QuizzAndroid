package ru.poporyadku.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// ARCHITECTURE.md, §3. Пересчитывается из puzzle_attempts, а не инкрементируется (D-5).
@Entity(tableName = "day_results")
data class DayResultEntity(
    @PrimaryKey
    @ColumnInfo(name = "local_date")
    val localDate: String,
    @ColumnInfo(name = "total_score")
    val totalScore: Int,
    @ColumnInfo(name = "completed_count")
    val completedCount: Int,
    @ColumnInfo(name = "is_complete")
    val isComplete: Boolean,
    @ColumnInfo(name = "completed_at")
    val completedAt: Long?,
)
