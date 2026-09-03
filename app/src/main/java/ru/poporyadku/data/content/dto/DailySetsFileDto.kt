package ru.poporyadku.data.content.dto

import kotlinx.serialization.Serializable

/** Конверт `daily-sets-*.json` (ITERATION_4_DESIGN.md, §4.3). */
@Serializable
data class DailySetsFileDto(
    val schemaVersion: Int,
    val packId: String,
    val sets: List<DailySetDto>,
)
