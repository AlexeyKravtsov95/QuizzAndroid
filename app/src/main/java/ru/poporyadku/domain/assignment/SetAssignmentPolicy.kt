package ru.poporyadku.domain.assignment

import java.time.LocalDate

// ITERATION_2_DESIGN.md, D-3 / D-20. Чистая синхронная функция: ни зависимостей,
// ни suspend, ни ввода-вывода. Ничего не знает о головоломках.
object SetAssignmentPolicy {

    fun decide(today: LocalDate, snapshot: AssignmentSnapshot): Decision {
        require(snapshot.pendingAssignments.size <= 1) {
            "нарушен инвариант: отложенных назначений ${snapshot.pendingAssignments.size}, " +
                "даты ${snapshot.pendingAssignments.map { it.localDate }}, " +
                "пакеты ${snapshot.pendingAssignments.map { it.packId }}"
        }

        // 1. Отложенное назначение — какому бы пакету оно ни принадлежало — разбирается первым.
        val pending = snapshot.pendingAssignments.firstOrNull()
        if (pending != null) {
            return when {
                pending.localDate == today -> Decision.Assigned(pending.packId, pending.setIndex)
                today < pending.localDate -> Decision.AwaitingNextDay
                else -> Decision.CarryOver(pending.packId, pending.setIndex, pending.localDate)
            }
        }

        // 2. Назначение на сегодня (уже израсходованное) — тоже любого пакета.
        snapshot.todayAssignment?.let { return Decision.Assigned(it.packId, it.setIndex) }

        // 3. Глобальная защита от перевода даты назад.
        val last = snapshot.lastAssignedDate
        if (last != null && today <= last) return Decision.AwaitingNextDay

        // 4. Новый набор берётся из активного пакета и только из него.
        val next = (snapshot.maxSetIndexInActivePack ?: -1) + 1
        if (next >= snapshot.setCountInActivePack) return Decision.ContentExhausted
        return Decision.NewSet(snapshot.activePackId, next)
    }
}
