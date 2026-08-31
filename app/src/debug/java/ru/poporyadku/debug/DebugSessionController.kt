package ru.poporyadku.debug

import java.time.LocalDate
import javax.inject.Inject
import ru.poporyadku.core.time.DebugClockProvider
import ru.poporyadku.domain.assignment.Decision
import ru.poporyadku.domain.repository.DayAssignmentRepository

// ITERATION_2_DESIGN.md, раздел 6 / D-16: подставляет дату через DebugClockProvider,
// а не через параметр продуктового API. Вызывает только публичные peek()/startSession()
// репозитория — ни одного debug-метода в DayAssignmentRepository нет и не появляется.
class DebugSessionController @Inject constructor(
    private val clock: DebugClockProvider,
    private val assignments: DayAssignmentRepository,
) {
    suspend fun peekAt(date: LocalDate): Decision {
        clock.setDate(date)
        return assignments.peek()
    }

    suspend fun startSessionAt(date: LocalDate): Decision {
        clock.setDate(date)
        return assignments.startSession()
    }

    /** Возврат к системным, динамическим часам. */
    fun resetClock() = clock.reset()
}
