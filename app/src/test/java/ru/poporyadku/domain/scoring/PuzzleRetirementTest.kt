package ru.poporyadku.domain.scoring

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.poporyadku.domain.model.InstalledContentVersion

/**
 * `PuzzleRetirement` — ITERATION_5_DESIGN.md, §3.8, `I5-R1`.
 *
 * Все шесть строк таблицы истинности: отзыв — сравнение `retiredIn` с версией, а не
 * наличие поля; при неизвестной установленной версии опорой служит отметка поставки
 * строки.
 */
class PuzzleRetirementTest {

    private data class Row(
        val retiredIn: Int?,
        val installed: InstalledContentVersion,
        val rowContentVersion: Int,
        val expected: Boolean,
        val why: String,
    )

    private val table = listOf(
        Row(null, InstalledContentVersion.Known(2), 2, expected = false, why = "не отзывалась"),
        Row(2, InstalledContentVersion.Known(2), 2, expected = true, why = "отозвана в установленной версии"),
        Row(2, InstalledContentVersion.Known(1), 2, expected = false, why = "откат: версия 1 отзыва не знает"),
        Row(1, InstalledContentVersion.Known(2), 2, expected = true, why = "отозвана раньше установленной"),
        Row(2, InstalledContentVersion.Unknown, 2, expected = true, why = "запасное значение — версия строки"),
        Row(3, InstalledContentVersion.Unknown, 2, expected = false, why = "предикат не выдумывает отзыв"),
    )

    @Test
    fun `I5-R1 all six rows of the truth table`() {
        assertEquals("таблица §3.8 — ровно шесть строк", 6, table.size)
        table.forEach { row ->
            assertEquals(
                "retiredIn=${row.retiredIn}, installed=${row.installed}, row=${row.rowContentVersion}: ${row.why}",
                row.expected,
                PuzzleRetirement.isRetired(row.retiredIn, row.rowContentVersion, row.installed),
            )
        }
    }

    @Test
    fun `I5-R1 the boundary is inclusive - retiredIn equal to the reference version retires`() {
        assertEquals(true, PuzzleRetirement.isRetired(5, rowContentVersion = 1, InstalledContentVersion.Known(5)))
        assertEquals(false, PuzzleRetirement.isRetired(6, rowContentVersion = 1, InstalledContentVersion.Known(5)))
    }
}
