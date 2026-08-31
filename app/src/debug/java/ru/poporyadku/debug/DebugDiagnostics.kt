package ru.poporyadku.debug

import androidx.room.withTransaction
import java.time.LocalDate
import javax.inject.Inject
import ru.poporyadku.core.time.DebugClockProvider
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.data.db.dao.AssignmentDao
import ru.poporyadku.data.db.dao.DailySetDao
import ru.poporyadku.data.db.entity.DayAssignmentEntity
import ru.poporyadku.di.ActivePack

// ITERATION_2_DESIGN.md, D-21. Снимок для отладочного экрана — отдельное read-only
// чтение, выполненное ПОСЛЕ решения политики, а не тот AssignmentSnapshot, который
// видела политика. Продуктовый DayAssignmentRepository никакого API для этого не
// получает.
data class DebugAssignmentView(
    val today: LocalDate,
    val activePackId: String,
    val pending: List<DayAssignmentEntity>,
    val todayAssignment: DayAssignmentEntity?,
    val lastAssignedDate: String?,
    val maxSetIndexInActivePack: Int?,
    val setCountInActivePack: Int,
    val nextSetIndex: Int,
)

class DebugDiagnostics @Inject constructor(
    private val db: AppDatabase,
    private val dao: AssignmentDao,
    private val sets: DailySetDao,
    private val clock: DebugClockProvider,
    @ActivePack private val activePackId: String,
) {
    /** Собственная транзакция — снимок внутренне согласован, но это отдельное
     *  чтение, а не внутреннее состояние политики (D-21). */
    suspend fun read(): DebugAssignmentView = db.withTransaction {
        val today = clock.today()
        val max = dao.maxSetIndex(activePackId)
        DebugAssignmentView(
            today = today,
            activePackId = activePackId,
            pending = dao.pendingAssignments(),
            todayAssignment = dao.byDate(today.toString()),
            lastAssignedDate = dao.lastAssignedDate(),
            maxSetIndexInActivePack = max,
            setCountInActivePack = sets.countSets(activePackId),
            nextSetIndex = (max ?: -1) + 1,
        )
    }
}
