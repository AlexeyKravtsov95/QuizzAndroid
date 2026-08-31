package ru.poporyadku.core.model

// CONTENT_MODEL.md, §4 и ARCHITECTURE.md, §3 (таблица `puzzles`).
// Маппер PuzzleEntity ↔ Puzzle не реализуется в PR 2A (ITERATION_2_DESIGN.md, D-19):
// cards и sources хранятся в базе как JSON-строки, их разбор требует kotlinx-serialization,
// которая подключается в итерации 4.
data class Puzzle(
    val puzzleId: String,
    val packId: String,
    val category: Category,
    val prompt: String,
    val sortKey: String,
    val sortDirection: SortDirection,
    val directionLabel: String,
    val cards: List<Card>,
    val correctOrder: List<String>,
    val explanation: String,
    val sources: List<Source>,
    val difficulty: Int,
    val retiredIn: Int?,
    val contentVersion: Int,
) {
    // CONTENT_MODEL.md, §4: источник, подтверждающий значение одной или нескольких карточек.
    data class Source(
        val sourceId: String,
        val title: String,
        val kind: String,
        val url: String?,
        val reference: String?,
        val accessedAt: String,
        val note: String?,
    )
}
