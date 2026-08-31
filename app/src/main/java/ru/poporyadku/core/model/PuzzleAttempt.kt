package ru.poporyadku.core.model

import java.time.LocalDate

// ARCHITECTURE.md, §3 (таблица `puzzle_attempts`).
// UNIQUE(localDate, slotIndex) — не более одной попытки на слот в день.
data class PuzzleAttempt(
    val id: Long,
    val localDate: LocalDate,
    val slotIndex: Int,
    val puzzleId: String,
    val submittedOrder: List<String>,
    val score: Int,
    val submittedAt: Long,
)
