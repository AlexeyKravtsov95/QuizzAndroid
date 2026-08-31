package ru.poporyadku.domain.repository

import ru.poporyadku.domain.assignment.Decision

// ITERATION_2_DESIGN.md, D-16 / D-20 / D-21: публичный API — ровно два метода, ни один
// не принимает дату и ни один не является debug-методом.
interface DayAssignmentRepository {
    /** Home, только чтение. */
    suspend fun peek(): Decision

    /** Home → Puzzle(0), фиксация решения. */
    suspend fun startSession(): Decision
}
