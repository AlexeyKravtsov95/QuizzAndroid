package ru.poporyadku.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// ARCHITECTURE.md, §3. cards_json/sources_json — TEXT, без TypeConverter; их разбор
// приходит в итерации 4 вместе с kotlinx-serialization (ITERATION_2_DESIGN.md, D-19).
@Entity(tableName = "puzzles")
data class PuzzleEntity(
    @PrimaryKey
    @ColumnInfo(name = "puzzle_id")
    val puzzleId: String,
    @ColumnInfo(name = "pack_id")
    val packId: String,
    @ColumnInfo(name = "category")
    val category: String,
    @ColumnInfo(name = "prompt")
    val prompt: String,
    @ColumnInfo(name = "sort_key")
    val sortKey: String,
    @ColumnInfo(name = "sort_direction")
    val sortDirection: String,
    @ColumnInfo(name = "direction_label")
    val directionLabel: String,
    @ColumnInfo(name = "cards_json")
    val cardsJson: String,
    @ColumnInfo(name = "correct_order")
    val correctOrder: String,
    @ColumnInfo(name = "explanation")
    val explanation: String,
    @ColumnInfo(name = "sources_json")
    val sourcesJson: String,
    @ColumnInfo(name = "difficulty")
    val difficulty: Int,
    @ColumnInfo(name = "retired_in")
    val retiredIn: Int?,
    @ColumnInfo(name = "content_version")
    val contentVersion: Int,
)
