package ru.poporyadku.domain.assignment

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import ru.poporyadku.core.model.DayAssignment

// ITERATION_2_DESIGN.md, §4: P1–P9, P12–P15. Чистый JVM: AssignmentSnapshot строится
// конструктором, decide() синхронна.
class SetAssignmentPolicyTest {

    private val today = LocalDate.of(2026, 9, 10)

    private fun assignment(date: LocalDate, packId: String = "core-ru", setIndex: Int = 0) =
        DayAssignment(localDate = date, packId = packId, setIndex = setIndex, assignedAt = 0L)

    private fun snapshot(
        pending: List<DayAssignment> = emptyList(),
        todayAssignment: DayAssignment? = null,
        lastAssignedDate: LocalDate? = null,
        activePackId: String = "core-ru",
        maxSetIndexInActivePack: Int? = null,
        setCountInActivePack: Int = 5,
    ) = AssignmentSnapshot(
        pendingAssignments = pending,
        todayAssignment = todayAssignment,
        lastAssignedDate = lastAssignedDate,
        activePackId = activePackId,
        maxSetIndexInActivePack = maxSetIndexInActivePack,
        setCountInActivePack = setCountInActivePack,
    )

    @Test
    fun `P1 - empty snapshot with content gives the first set`() {
        val decision = SetAssignmentPolicy.decide(today, snapshot())
        assertEquals(Decision.NewSet("core-ru", 0), decision)
    }

    @Test
    fun `P2 - assignment for today gives Assigned with the same index`() {
        val decision = SetAssignmentPolicy.decide(
            today,
            snapshot(todayAssignment = assignment(today, setIndex = 3), lastAssignedDate = today),
        )
        assertEquals(Decision.Assigned("core-ru", 3), decision)
    }

    @Test
    fun `P3 - skipping a week gives N + 1, not N + 7`() {
        val decision = SetAssignmentPolicy.decide(
            today,
            snapshot(lastAssignedDate = today.minusWeeks(1), maxSetIndexInActivePack = 4, setCountInActivePack = 10),
        )
        assertEquals(Decision.NewSet("core-ru", 5), decision)
    }

    @Test
    fun `P4 - today before lastAssignedDate gives AwaitingNextDay`() {
        val decision = SetAssignmentPolicy.decide(
            today,
            snapshot(lastAssignedDate = today.plusDays(1)),
        )
        assertEquals(Decision.AwaitingNextDay, decision)
    }

    @Test
    fun `P5 - there and back within the same real day does not create a second set`() {
        val decision = SetAssignmentPolicy.decide(
            today,
            snapshot(todayAssignment = assignment(today, setIndex = 2), lastAssignedDate = today),
        )
        assertEquals(Decision.Assigned("core-ru", 2), decision)
    }

    @Test
    fun `P6 - exceeding setCount gives ContentExhausted`() {
        val decision = SetAssignmentPolicy.decide(
            today,
            snapshot(lastAssignedDate = today.minusDays(1), maxSetIndexInActivePack = 4, setCountInActivePack = 5),
        )
        assertEquals(Decision.ContentExhausted, decision)
    }

    @Test
    fun `P7 - pending assignment in the future gives AwaitingNextDay`() {
        val decision = SetAssignmentPolicy.decide(
            today,
            snapshot(pending = listOf(assignment(today.plusDays(1)))),
        )
        assertEquals(Decision.AwaitingNextDay, decision)
    }

    @Test
    fun `P8 - empty content pack gives ContentExhausted`() {
        val decision = SetAssignmentPolicy.decide(
            today,
            snapshot(lastAssignedDate = today.minusDays(1), setCountInActivePack = 0),
        )
        assertEquals(Decision.ContentExhausted, decision)
    }

    @Test
    fun `P9 - two pending assignments throw instead of silently picking the first`() {
        assertThrows(IllegalArgumentException::class.java) {
            SetAssignmentPolicy.decide(
                today,
                snapshot(
                    pending = listOf(
                        assignment(today.minusDays(2), packId = "pack-a"),
                        assignment(today.minusDays(1), packId = "pack-b"),
                    ),
                ),
            )
        }
    }

    @Test
    fun `P12 - pending assignment of another pack is carried over, not replaced by a new set`() {
        val decision = SetAssignmentPolicy.decide(
            today,
            snapshot(
                pending = listOf(assignment(today.minusDays(1), packId = "pack-a", setIndex = 3)),
                activePackId = "pack-b",
            ),
        )
        assertEquals(Decision.CarryOver("pack-a", 3, today.minusDays(1)), decision)
    }

    @Test
    fun `P13 - switching the active pack does not bypass the forward-only guard`() {
        val decision = SetAssignmentPolicy.decide(
            today,
            snapshot(
                lastAssignedDate = today,
                activePackId = "pack-b",
                maxSetIndexInActivePack = null,
                setCountInActivePack = 5,
            ),
        )
        assertEquals(Decision.AwaitingNextDay, decision)
    }

    @Test
    fun `P14 - today's assignment of another pack is returned as Assigned`() {
        val decision = SetAssignmentPolicy.decide(
            today,
            snapshot(
                todayAssignment = assignment(today, packId = "pack-a", setIndex = 1),
                lastAssignedDate = today,
                activePackId = "pack-b",
            ),
        )
        assertEquals(Decision.Assigned("pack-a", 1), decision)
    }

    @Test
    fun `P15 - the other pack's max index does not affect a fresh active pack`() {
        val decision = SetAssignmentPolicy.decide(
            today,
            snapshot(
                lastAssignedDate = today.minusDays(1),
                activePackId = "pack-b",
                maxSetIndexInActivePack = null,
                setCountInActivePack = 5,
            ),
        )
        assertEquals(Decision.NewSet("pack-b", 0), decision)
    }
}
