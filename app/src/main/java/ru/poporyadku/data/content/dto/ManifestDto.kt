package ru.poporyadku.data.content.dto

import kotlinx.serialization.Serializable

/**
 * `manifest.json` (ITERATION_4_DESIGN.md, §4.2).
 *
 * Asset-DTO принадлежат только `data/content/dto`: ни в domain, ни в JSON-колонках Room
 * они не появляются (**I4-D17**). `packTitle` объявлен потому, что он есть в формате,
 * и НЕ переносится в Room (**I4-D16**).
 */
@Serializable
data class ManifestDto(
    val schemaVersion: Int,
    val contentVersion: Int,
    val packId: String,
    val packTitle: String,
    val setCount: Int,
    val puzzleCount: Int,
    val files: List<ManifestFileDto>,
)
