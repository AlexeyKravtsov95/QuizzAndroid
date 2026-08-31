package ru.poporyadku.data.db.mapper

import java.time.LocalDate
import ru.poporyadku.core.model.DayAssignment
import ru.poporyadku.core.model.DayResult
import ru.poporyadku.core.model.PuzzleAttempt
import ru.poporyadku.data.db.entity.DayAssignmentEntity
import ru.poporyadku.data.db.entity.DayResultEntity
import ru.poporyadku.data.db.entity.PuzzleAttemptEntity

// ITERATION_2_DESIGN.md, §3: даты — LocalDate.toString() / LocalDate.parse(), без
// TypeConverter. Никакого JSON — submittedOrder хранится как "c1,c3,c2,c4" (D-19 не
// запрещает это: запрет касается только cards_json/sources_json).

fun DayAssignmentEntity.toDomain(): DayAssignment = DayAssignment(
    localDate = LocalDate.parse(localDate),
    packId = packId,
    setIndex = setIndex,
    assignedAt = assignedAt,
)

fun DayAssignment.toEntity(): DayAssignmentEntity = DayAssignmentEntity(
    localDate = localDate.toString(),
    packId = packId,
    setIndex = setIndex,
    assignedAt = assignedAt,
)

fun PuzzleAttemptEntity.toDomain(): PuzzleAttempt = PuzzleAttempt(
    id = id,
    localDate = LocalDate.parse(localDate),
    slotIndex = slotIndex,
    puzzleId = puzzleId,
    submittedOrder = submittedOrder.split(","),
    score = score,
    submittedAt = submittedAt,
)

fun PuzzleAttempt.toEntity(): PuzzleAttemptEntity = PuzzleAttemptEntity(
    id = id,
    localDate = localDate.toString(),
    slotIndex = slotIndex,
    puzzleId = puzzleId,
    submittedOrder = submittedOrder.joinToString(","),
    score = score,
    submittedAt = submittedAt,
)

fun DayResultEntity.toDomain(): DayResult = DayResult(
    localDate = LocalDate.parse(localDate),
    totalScore = totalScore,
    completedCount = completedCount,
    isComplete = isComplete,
    completedAt = completedAt,
)

fun DayResult.toEntity(): DayResultEntity = DayResultEntity(
    localDate = localDate.toString(),
    totalScore = totalScore,
    completedCount = completedCount,
    isComplete = isComplete,
    completedAt = completedAt,
)
