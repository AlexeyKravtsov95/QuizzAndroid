package ru.poporyadku.ui.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.poporyadku.core.model.AppBuildInfo
import ru.poporyadku.domain.model.InstalledContentVersion

/**
 * Письмо «Сообщить о неточности» (ITERATION_5_DESIGN.md, §3.12, §6.7, §10.1):
 * `I5-E1`…`I5-E4`. Шаблоны — дословно строки ресурсов раздела 13.3; тело и тема
 * сравниваются с эталоном посимвольно.
 */
class ReportMailComposerTest {

    // --- I5-E1 -------------------------------------------------------------------------

    /** `I5-E1`. Письмо с `PuzzleResult`: тема и тело посимвольно по шаблону §3.12. */
    @Test
    fun `I5-E1 puzzle report matches the template character by character`() {
        val draft = ReportMailComposer.compose(
            ReportContext(puzzleId = PUZZLE_ID, app = APP, content = InstalledContentVersion.Known(3)),
            TEMPLATES,
        )

        assertEquals(EMAIL, draft.to)
        assertEquals("По порядку! — неточность в задании geo-vysota-gor-007", draft.subject)
        assertEquals(
            "Задание: geo-vysota-gor-007\n" +
                "Версия приложения: 1.0.0 (12)\n" +
                "Версия контента: 3\n" +
                "\n" +
                "Опишите, что не так:\n",
            draft.body,
        )
    }

    // --- I5-E2 -------------------------------------------------------------------------

    /** `I5-E2`. Письмо из настроек: без строки «Задание:», тема «…сообщение о неточности». */
    @Test
    fun `I5-E2 general report has no puzzle line and the general subject`() {
        val draft = ReportMailComposer.compose(
            ReportContext(puzzleId = null, app = APP, content = InstalledContentVersion.Known(1)),
            TEMPLATES,
        )

        assertEquals(EMAIL, draft.to)
        assertEquals("По порядку! — сообщение о неточности", draft.subject)
        assertEquals(
            "Версия приложения: 1.0.0 (12)\n" +
                "Версия контента: 1\n" +
                "\n" +
                "Опишите, что не так:\n",
            draft.body,
        )
        assertFalse(draft.body.contains("Задание:"))
    }

    // --- I5-E3 -------------------------------------------------------------------------

    /**
     * `I5-E3`. В теле только `puzzleId` и версии: тело целиком равно эталону (выше), в нём
     * нет ни одной ISO-даты, слова «серия», «из 18», «из 6», счёта или истории. Вход
     * [ReportContext] других полей не имеет — лишнему данному неоткуда взяться.
     */
    @Test
    fun `I5-E3 the body carries nothing but the puzzle id and the versions`() {
        val bodies = listOf(
            ReportContext(PUZZLE_ID, APP, InstalledContentVersion.Known(3)),
            ReportContext(null, APP, InstalledContentVersion.Unknown),
        ).map { ReportMailComposer.compose(it, TEMPLATES) }

        bodies.forEach { draft ->
            val text = draft.subject + "\n" + draft.body
            assertFalse("ISO-дата: $text", Regex("""\d{4}-\d{2}-\d{2}""").containsMatchIn(text))
            FORBIDDEN.forEach { word -> assertFalse("«$word» в письме: $text", text.contains(word, ignoreCase = true)) }
            // Строк ровно столько, сколько в шаблоне: 3 или 4 содержательных + пустая + приглашение.
            val lines = draft.body.removeSuffix("\n").split("\n")
            assertTrue(lines.size in 4..5)
            assertEquals("", lines[lines.size - 2])
            assertEquals("Опишите, что не так:", lines.last())
        }
        // Поля экземпляра входа — ровно три (статический `$stable` добавляет Compose-компилятор).
        val instanceFields = ReportContext::class.java.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()
        assertEquals(setOf("puzzleId", "app", "content"), instanceFields)
    }

    // --- I5-E4 -------------------------------------------------------------------------

    /** `I5-E4`. Неизвестная версия контента → «Версия контента: не установлена». */
    @Test
    fun `I5-E4 unknown content version is spelled out`() {
        val draft = ReportMailComposer.compose(
            ReportContext(puzzleId = PUZZLE_ID, app = APP, content = InstalledContentVersion.Unknown),
            TEMPLATES,
        )

        assertEquals(
            "Задание: geo-vysota-gor-007\n" +
                "Версия приложения: 1.0.0 (12)\n" +
                "Версия контента: не установлена\n" +
                "\n" +
                "Опишите, что не так:\n",
            draft.body,
        )
    }

    /** Большой код сборки печатается без разделителей разрядов (`Locale.ROOT`). */
    @Test
    fun `version code is printed without grouping`() {
        val draft = ReportMailComposer.compose(
            ReportContext(null, AppBuildInfo("2.10.3", 1_234_567L), InstalledContentVersion.Known(12)),
            TEMPLATES,
        )

        assertTrue(draft.body.startsWith("Версия приложения: 2.10.3 (1234567)\nВерсия контента: 12\n"))
    }

    private companion object {
        const val EMAIL = "feedback@example.test"
        const val PUZZLE_ID = "geo-vysota-gor-007"
        val APP = AppBuildInfo(versionName = "1.0.0", versionCode = 12)
        val FORBIDDEN = listOf("серия", "из 18", "из 6", "счёт", "балл", "история")

        /** Дословно строки ресурсов (ITERATION_5_DESIGN.md, раздел 13.3). */
        val TEMPLATES = ReportTemplates(
            recipient = EMAIL,
            subjectPuzzle = "По порядку! — неточность в задании %1\$s",
            subjectGeneral = "По порядку! — сообщение о неточности",
            linePuzzle = "Задание: %1\$s",
            lineAppVersion = "Версия приложения: %1\$s (%2\$d)",
            lineContentVersion = "Версия контента: %1\$d",
            lineContentVersionUnknown = "Версия контента: не установлена",
            prompt = "Опишите, что не так:",
        )
    }
}
