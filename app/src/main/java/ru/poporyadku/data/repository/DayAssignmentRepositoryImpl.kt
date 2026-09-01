package ru.poporyadku.data.repository

import androidx.room.withTransaction
import java.time.LocalDate
import javax.inject.Inject
import ru.poporyadku.core.model.DayAssignment
import ru.poporyadku.core.time.ClockProvider
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.data.db.dao.AssignmentDao
import ru.poporyadku.data.db.dao.DailySetDao
import ru.poporyadku.data.db.entity.DayAssignmentEntity
import ru.poporyadku.data.db.mapper.toDomain
import ru.poporyadku.di.ActivePack
import ru.poporyadku.domain.assignment.AssignmentSnapshot
import ru.poporyadku.domain.assignment.Decision
import ru.poporyadku.domain.assignment.DecisionContext
import ru.poporyadku.domain.assignment.SetAssignmentPolicy
import ru.poporyadku.domain.repository.DayAssignmentRepository

// ITERATION_2_DESIGN.md, §4. Сборка снимка и исполнение решения — в одной
// Room-транзакции; момент времени читается ровно один раз до каждой транзакции.
class DayAssignmentRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val dao: AssignmentDao,
    private val sets: DailySetDao,
    private val clock: ClockProvider,
    @ActivePack private val activePackId: String,
) : DayAssignmentRepository {

    override suspend fun peek(): DecisionContext {
        val time = clock.now() // один Instant, до транзакции
        val decision = db.withTransaction {
            SetAssignmentPolicy.decide(time.localDate, snapshot(time.localDate))
        }
        // I3-D40: контекст собирается из ТОГО ЖЕ снимка, что видела политика, —
        // повторного обращения к часам после решения не появляется.
        return DecisionContext(decision, time)
    }

    override suspend fun startSession(): DecisionContext {
        val time = clock.now() // один Instant, до транзакции
        val decision = db.withTransaction {
            val decision = SetAssignmentPolicy.decide(time.localDate, snapshot(time.localDate))
            when (decision) {
                is Decision.NewSet -> dao.insert(
                    DayAssignmentEntity(
                        localDate = time.localDate.toString(),
                        packId = decision.packId, // из решения, не из поля класса
                        setIndex = decision.setIndex,
                        assignedAt = time.epochMillis,
                    )
                )

                is Decision.CarryOver -> {
                    val rows = dao.carryOver(
                        packId = decision.packId, // пакет переносимой строки (D-20)
                        pendingDate = decision.fromDate.toString(),
                        today = time.localDate.toString(),
                        now = time.epochMillis,
                    )
                    check(rows == 1) { "перенос затронул $rows строк" } // откат транзакции
                }

                is Decision.Assigned,
                Decision.AwaitingNextDay,
                Decision.ContentExhausted -> Unit // ни одной записи
            }
            decision
        }
        return DecisionContext(decision, time)
    }

    /** Только чтение (I3-D16): собственной транзакции не требует и ничего не создаёт. */
    override suspend fun getAssignment(localDate: LocalDate): DayAssignment? =
        dao.byDate(localDate.toString())?.toDomain()

    /**
     * Пять чтений одного согласованного состояния. Вызывается только внутри транзакции.
     * Дата приходит параметром: часы внутри транзакции не читаются ни разу.
     */
    private suspend fun snapshot(today: LocalDate): AssignmentSnapshot = AssignmentSnapshot(
        pendingAssignments = dao.pendingAssignments().map { it.toDomain() }, // глобально
        todayAssignment = dao.byDate(today.toString())?.toDomain(), // глобально
        lastAssignedDate = dao.lastAssignedDate()?.let(LocalDate::parse), // глобально
        activePackId = activePackId,
        maxSetIndexInActivePack = dao.maxSetIndex(activePackId), // pack-scoped
        setCountInActivePack = sets.countSets(activePackId), // pack-scoped
    )
}
