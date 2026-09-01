package ru.poporyadku.domain.repository

import java.time.LocalDate
import ru.poporyadku.core.model.DayAssignment
import ru.poporyadku.domain.assignment.DecisionContext

// ITERATION_2_DESIGN.md, D-16 / D-20 / D-21 и ITERATION_3_DESIGN.md, I3-D16.
// Инвариант «в релизной сборке нет ни одного вызова, которым можно задать дату»
// относится к ЗАПИСИ: peek() и startSession() даты не принимают, назначение создаётся
// только на дату из ClockProvider. getAssignment(localDate) дату принимает, но ничего
// не пишет — подделать им назначение нельзя.
interface DayAssignmentRepository {
    /** Home, только чтение. Решение и момент, в который оно принято. */
    suspend fun peek(): DecisionContext

    /** Home → Puzzle(первый неотвеченный), фиксация решения. */
    suspend fun startSession(): DecisionContext

    /** Только чтение: какой набор был выдан на эту дату. Ничего не создаёт. */
    suspend fun getAssignment(localDate: LocalDate): DayAssignment?
}
