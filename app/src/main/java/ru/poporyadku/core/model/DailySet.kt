package ru.poporyadku.core.model

// ARCHITECTURE.md, §3 (таблица `daily_sets`); CONTENT_MODEL.md, §6.
// Составной ключ (packId, setIndex); три головоломки набора хранятся как плоские поля,
// а не список — состав набора фиксирован (ровно 3).
data class DailySet(
    val packId: String,
    val setIndex: Int,
    val puzzleId1: String,
    val puzzleId2: String,
    val puzzleId3: String,
)
