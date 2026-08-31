package ru.poporyadku.domain.assignment

import java.time.LocalDate
import ru.poporyadku.core.model.DayAssignment

// ITERATION_2_DESIGN.md, D-3 / D-20. Три поля глобальны для всех пакетов, три —
// только про activePackId.
data class AssignmentSnapshot(
    /** Глобально по всем пакетам. ≤ 1 по инварианту; читаем до 2, чтобы нарушение было видно. */
    val pendingAssignments: List<DayAssignment>,
    /** Глобально: на дату приходится не более одного назначения — это PK таблицы. */
    val todayAssignment: DayAssignment?,
    /** Глобально: MAX(local_date) по всем пакетам. */
    val lastAssignedDate: LocalDate?,
    /** Пакет, из которого выдаётся следующий набор. */
    val activePackId: String,
    /** Только по активному пакету: MAX(set_index) WHERE pack_id = activePackId. */
    val maxSetIndexInActivePack: Int?,
    /** Только по активному пакету: COUNT(*) FROM daily_sets WHERE pack_id = activePackId. */
    val setCountInActivePack: Int,
)
