package ru.poporyadku.data.content.dto

import kotlinx.serialization.Serializable

/** Конверт `puzzles-*.json` (ITERATION_4_DESIGN.md, §4.3, **I4-D5**). */
@Serializable
data class PuzzlesFileDto(
    val schemaVersion: Int,
    val packId: String,
    val puzzles: List<PuzzleDto>,
)
