package ru.poporyadku.core.model

import java.time.LocalDate

// ARCHITECTURE.md, §3 (таблица `day_assignments`).
// localDate — глобальный первичный ключ: не более одного назначения на календарную дату
// во всей системе, вне зависимости от пакета (ITERATION_2_DESIGN.md, D-20).
data class DayAssignment(
    val localDate: LocalDate,
    val packId: String,
    val setIndex: Int,
    val assignedAt: Long,
)
