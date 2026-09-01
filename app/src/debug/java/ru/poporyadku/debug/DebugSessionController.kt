package ru.poporyadku.debug

import java.time.LocalDate
import javax.inject.Inject
import ru.poporyadku.core.time.DebugClockProvider
import ru.poporyadku.domain.assignment.Decision
import ru.poporyadku.domain.repository.DayAssignmentRepository

// ITERATION_2_DESIGN.md, раздел 6 / D-16: подставляет дату через DebugClockProvider,
// а не через параметр продуктового API. Вызывает только публичные peek()/startSession()
// репозитория — ни одного debug-метода в DayAssignmentRepository нет и не появляется.
//
// ITERATION_3_DESIGN.md, I3-D16 (PR 3B): репозиторий возвращает DecisionContext.
// Внешний контракт контроллера сохранён — наружу по-прежнему выдаётся Decision:
// момент и зона решения отладочному экрану не нужны, а протаскивать DecisionContext
// в DebugUiState значило бы менять инструмент ради формы чужого возврата.
class DebugSessionController @Inject constructor(
    private val clock: DebugClockProvider,
    private val assignments: DayAssignmentRepository,
) {
    suspend fun peekAt(date: LocalDate): Decision {
        clock.setDate(date)
        return assignments.peek().decision
    }

    suspend fun startSessionAt(date: LocalDate): Decision {
        clock.setDate(date)
        return assignments.startSession().decision
    }

    /** Возврат к системным, динамическим часам. */
    fun resetClock() = clock.reset()
}
