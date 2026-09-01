package ru.poporyadku.domain.assignment

import java.time.Instant
import java.time.LocalDate
import ru.poporyadku.core.time.TimeSnapshot

/**
 * Решение политики выдачи вместе с моментом, в который оно принято
 * (ITERATION_3_DESIGN.md, I3-D40, I3-D16).
 *
 * Заменяет пару «решение + отдельный вопрос часам „а какое сегодня число?"»: дата,
 * момент и зона выведены из ОДНОГО [java.time.Clock], прочитанного репозиторием ровно
 * один раз до транзакции. Второго обращения к часам после решения не существует.
 */
data class DecisionContext(
    val decision: Decision,
    val time: TimeSnapshot,
) {
    val localDate: LocalDate
        get() = time.localDate

    /** Начало следующей локальной даты — момент, когда может появиться новый набор. */
    val nextLocalDateStartsAt: Instant
        get() = time.localDate
            .plusDays(1)
            .atStartOfDay(time.zone)
            .toInstant()
}
