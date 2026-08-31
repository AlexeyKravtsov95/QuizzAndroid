package ru.poporyadku.core.model

// CONTENT_MODEL.md, §4: одна карточка головоломки.
data class Card(
    val cardId: String,
    val title: String,
    val subtitle: String?,
    val sortValue: Double,
    val displayValue: String,
    val note: String?,
    val sourceIds: List<String>,
    val disputed: Boolean,
)
