package ru.poporyadku.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ARCHITECTURE.md, §3. local_date — глобальный PK: не более одного нового набора
// за календарную дату (ITERATION_2_DESIGN.md, D-20). UNIQUE(pack_id, set_index) —
// один набор не выдаётся дважды.
@Entity(
    tableName = "day_assignments",
    indices = [Index(value = ["pack_id", "set_index"], unique = true)],
)
data class DayAssignmentEntity(
    @PrimaryKey
    @ColumnInfo(name = "local_date")
    val localDate: String,
    @ColumnInfo(name = "pack_id")
    val packId: String,
    @ColumnInfo(name = "set_index")
    val setIndex: Int,
    @ColumnInfo(name = "assigned_at")
    val assignedAt: Long,
)
