package ru.poporyadku.data.content.dto

import kotlinx.serialization.Serializable

/**
 * Источник головоломки (ITERATION_4_DESIGN.md, §5.3).
 *
 * `url` **или** `reference` — правило схемы (`anyOf`, код `R17`), а не формы DTO:
 * оба поля необязательны, и отсутствие обоих ловится в CI, а не отказом разбора.
 */
@Serializable
data class SourceDto(
    val sourceId: String,
    val title: String,
    val kind: String,
    val url: String? = null,
    val reference: String? = null,
    val accessedAt: String,
    val note: String? = null,
)
