package ru.poporyadku.domain.assignment

import java.time.LocalDate

// ITERATION_2_DESIGN.md, D-20: решения несут packId явно, чтобы исполнитель не
// подставлял активный пакет вместо пакета переносимой/уже существующей строки.
sealed interface Decision {
    /** Создать строку в активном пакете. */
    data class NewSet(val packId: String, val setIndex: Int) : Decision

    /** Перенести существующую строку. packId и setIndex — исходной строки, не активного пакета. */
    data class CarryOver(val packId: String, val setIndex: Int, val fromDate: LocalDate) : Decision

    /** Назначение на сегодня уже есть. packId — того пакета, которому строка принадлежит. */
    data class Assigned(val packId: String, val setIndex: Int) : Decision

    data object AwaitingNextDay : Decision
    data object ContentExhausted : Decision
}
