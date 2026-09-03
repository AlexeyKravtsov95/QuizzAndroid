package ru.poporyadku.data.content.dto

import kotlinx.serialization.Serializable

/**
 * Карточка головоломки (ITERATION_4_DESIGN.md, §5.3).
 *
 * [subtitle] и [note] необязательны: отсутствие поля даёт `null` (в хранилище оно
 * останется `null`, а не пустой строкой). [disputed] необязательно с умолчанием
 * `false`, и умолчание применяется **при разборе asset-DTO** — в Room значение
 * всегда явное (§9.4).
 */
@Serializable
data class CardDto(
    val cardId: String,
    val title: String,
    val subtitle: String? = null,
    val sortValue: Double,
    val displayValue: String,
    val note: String? = null,
    val sourceIds: List<String>,
    val disputed: Boolean = false,
)
