package ru.poporyadku.domain.repository

import ru.poporyadku.core.model.DailySet

// ITERATION_2_DESIGN.md, PR 2B: чтение состава набора по (packId, setIndex).
interface DailySetRepository {
    suspend fun getSet(packId: String, setIndex: Int): DailySet?
}
