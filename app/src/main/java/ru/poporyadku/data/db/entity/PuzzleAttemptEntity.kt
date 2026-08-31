package ru.poporyadku.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ARCHITECTURE.md, §3. UNIQUE(local_date, slot_index) — не более одной попытки на слот в день.
@Entity(
    tableName = "puzzle_attempts",
    indices = [Index(value = ["local_date", "slot_index"], unique = true)],
)
data class PuzzleAttemptEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "local_date")
    val localDate: String,
    @ColumnInfo(name = "slot_index")
    val slotIndex: Int,
    @ColumnInfo(name = "puzzle_id")
    val puzzleId: String,
    @ColumnInfo(name = "submitted_order")
    val submittedOrder: String,
    @ColumnInfo(name = "score")
    val score: Int,
    @ColumnInfo(name = "submitted_at")
    val submittedAt: Long,
)
