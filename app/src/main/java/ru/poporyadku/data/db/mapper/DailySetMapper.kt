package ru.poporyadku.data.db.mapper

import ru.poporyadku.core.model.DailySet
import ru.poporyadku.data.db.entity.DailySetEntity

// ITERATION_2_DESIGN.md, D-19: плоские поля, без JSON — маппер разрешён в PR 2A.

fun DailySetEntity.toDomain(): DailySet = DailySet(
    packId = packId,
    setIndex = setIndex,
    puzzleId1 = puzzleId1,
    puzzleId2 = puzzleId2,
    puzzleId3 = puzzleId3,
)

fun DailySet.toEntity(): DailySetEntity = DailySetEntity(
    packId = packId,
    setIndex = setIndex,
    puzzleId1 = puzzleId1,
    puzzleId2 = puzzleId2,
    puzzleId3 = puzzleId3,
)
