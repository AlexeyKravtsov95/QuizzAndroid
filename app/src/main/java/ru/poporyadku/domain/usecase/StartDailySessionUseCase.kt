package ru.poporyadku.domain.usecase

import javax.inject.Inject
import ru.poporyadku.domain.assignment.Decision
import ru.poporyadku.domain.repository.DayAssignmentRepository

// ITERATION_2_DESIGN.md, D-16: без параметра даты — время внедряется в репозиторий
// через ClockProvider, use case о нём не знает.
class StartDailySessionUseCase @Inject constructor(
    private val assignments: DayAssignmentRepository,
) {
    suspend operator fun invoke(): Decision = assignments.startSession()
}
