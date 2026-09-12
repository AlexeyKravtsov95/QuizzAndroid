package ru.poporyadku.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.poporyadku.core.model.ThemeMode

/** `I5-T1` (ITERATION_5_DESIGN.md, §3.9, §4.8, I5-D16): три режима × две системные темы. */
class ThemeResolutionTest {

    @Test
    fun `I5-T1 every theme mode resolves against both system themes`() {
        val expected = mapOf(
            (ThemeMode.SYSTEM to false) to false,
            (ThemeMode.SYSTEM to true) to true,
            (ThemeMode.LIGHT to false) to false,
            (ThemeMode.LIGHT to true) to false,
            (ThemeMode.DARK to false) to true,
            (ThemeMode.DARK to true) to true,
        )
        assertEquals("все шесть сочетаний", ThemeMode.entries.size * 2, expected.size)

        expected.forEach { (input, dark) ->
            val (mode, systemDark) = input
            assertEquals(
                "$mode при системной ${if (systemDark) "тёмной" else "светлой"}",
                dark,
                mode.resolveDark(systemDark),
            )
        }
    }
}
