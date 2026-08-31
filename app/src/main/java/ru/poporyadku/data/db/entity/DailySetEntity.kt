package ru.poporyadku.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

// ARCHITECTURE.md, §3. Составной первичный ключ (pack_id, set_index) —
// CONTENT_MODEL.md, §7: "upsert наборов по (packId, setIndex)".
@Entity(tableName = "daily_sets", primaryKeys = ["pack_id", "set_index"])
data class DailySetEntity(
    @ColumnInfo(name = "pack_id")
    val packId: String,
    @ColumnInfo(name = "set_index")
    val setIndex: Int,
    @ColumnInfo(name = "puzzle_id_1")
    val puzzleId1: String,
    @ColumnInfo(name = "puzzle_id_2")
    val puzzleId2: String,
    @ColumnInfo(name = "puzzle_id_3")
    val puzzleId3: String,
)
