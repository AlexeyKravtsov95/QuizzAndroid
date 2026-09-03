package ru.poporyadku.data.db.json

import kotlinx.serialization.Serializable

/**
 * Форма JSON-колонки `puzzles.sources_json` (ITERATION_4_DESIGN.md, **I4-D17**).
 *
 * Отдельна от `SourceDto` по той же причине, что [StoredCard]. `accessedAt` остаётся
 * строкой: экран источников показывает её как есть, сравнений по ней нет (§9.4).
 */
@Serializable
data class StoredSource(
    val sourceId: String,
    val title: String,
    val kind: String,
    val url: String? = null,
    val reference: String? = null,
    val accessedAt: String,
    val note: String? = null,
)
