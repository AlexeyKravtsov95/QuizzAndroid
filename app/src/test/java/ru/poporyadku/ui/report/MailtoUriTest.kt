package ru.poporyadku.ui.report

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.poporyadku.core.model.AppBuildInfo
import ru.poporyadku.domain.model.InstalledContentVersion

/**
 * `mailto:` по RFC 6068 (ITERATION_5_DESIGN.md, §3.12, §6.7): `I5-E5`.
 *
 * Обратное декодирование — `java.net.URI`: он раскрывает `%HH` как байты UTF-8, поэтому
 * совпадение исходных строк доказывает и кодировку, и отсутствие потерь.
 */
class MailtoUriTest {

    /** `I5-E5`. Посимвольный эталон простого письма: пробел `%20`, LF → `%0D%0A`, `@` адреса цел. */
    @Test
    fun `I5-E5 exact encoding of a small draft`() {
        val uri = MailtoUri.of(MailDraft(to = "a.b-c_d~e@example.test", subject = "a b", body = "x\ny\n"))

        assertEquals("mailto:a.b-c_d~e@example.test?subject=a%20b&body=x%0D%0Ay%0D%0A", uri)
    }

    /** `I5-E5`. Кириллица — UTF-8 `%D0%…` в ВЕРХНЕМ регистре; `+` вместо пробела не бывает. */
    @Test
    fun `I5-E5 cyrillic is utf-8 percent encoded in upper case`() {
        val uri = MailtoUri.of(MailDraft(to = "x@y.z", subject = "Да ё", body = ""))

        // «Д» = D0 94, «а» = D0 B0, пробел, «ё» = D1 91.
        assertEquals("mailto:x@y.z?subject=%D0%94%D0%B0%20%D1%91&body=", uri)
        assertFalse(uri.contains("+"))
        val escapes = Regex("%[0-9A-Fa-f]{2}").findAll(uri).map { it.value }.toList()
        assertTrue(escapes.all { it == it.uppercase() })
    }

    /** `I5-E5`. `& = ? # % +` в теме и теле кодируются всегда и не ломают структуру URI. */
    @Test
    fun `I5-E5 reserved characters are always encoded`() {
        val tricky = "a&b=c?d#e%f+g/h:i@j"
        val uri = MailtoUri.of(MailDraft(to = "x@y.z", subject = tricky, body = tricky))

        assertEquals(
            "mailto:x@y.z?subject=a%26b%3Dc%3Fd%23e%25f%2Bg%2Fh%3Ai%40j" +
                "&body=a%26b%3Dc%3Fd%23e%25f%2Bg%2Fh%3Ai%40j",
            uri,
        )
        // Ровно один «?» и один «&» — разделители самого URI.
        assertEquals(1, uri.count { it == '?' })
        assertEquals(1, uri.count { it == '&' })
        assertFalse(uri.contains('#'))
        assertEquals("«=» только после имён параметров", 2, uri.count { it == '=' })
    }

    /** `I5-E5`. Только `A–Z a–z 0–9 - . _ ~` остаются как есть — в теме и в теле. */
    @Test
    fun `I5-E5 only unreserved characters stay literal`() {
        val ascii = (0x20..0x7E).map { it.toChar() }.joinToString("")
        val uri = MailtoUri.of(MailDraft(to = "x@y.z", subject = ascii, body = ""))
        val subject = uri.substringAfter("?subject=").substringBefore("&body=")

        val literal = subject.replace(Regex("%[0-9A-F]{2}"), "")
        assertEquals(UNRESERVED, literal)
    }

    /**
     * `I5-E5`. Обратное декодирование `java.net.URI` даёт исходные тему и тело — тело с
     * CRLF вместо LF — для обоих настоящих писем, включая кириллицу и перевод строки в конце.
     */
    @Test
    fun `I5-E5 decoding with java net URI restores subject and body`() {
        val drafts = listOf(
            ReportContext("geo-vysota-gor-007", APP, InstalledContentVersion.Known(3)),
            ReportContext(null, APP, InstalledContentVersion.Unknown),
        ).map { ReportMailComposer.compose(it, TEMPLATES) } +
            MailDraft(to = "x@y.z", subject = "a&b=c?d#e%f+g ё", body = "строка 1\nстрока 2 & 3\n")

        drafts.forEach { draft ->
            val uri = MailtoUri.of(draft)
            val parsed = URI(uri)
            assertEquals("mailto", parsed.scheme)

            val raw = parsed.rawSchemeSpecificPart
            val address = raw.substringBefore('?')
            val query = raw.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }

            assertEquals(draft.to, decode(address))
            assertEquals(setOf("subject", "body"), query.keys)
            assertEquals(draft.subject, decode(query.getValue("subject")))
            assertEquals(draft.body.replace("\n", "\r\n"), decode(query.getValue("body")))
        }
    }

    /** Раскрывает `%HH` средствами `java.net.URI` — независимо от кодировщика под тестом. */
    private fun decode(component: String): String = URI("x:$component").schemeSpecificPart

    private companion object {
        const val UNRESERVED = "-.0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz~"
        val APP = AppBuildInfo(versionName = "1.0.0", versionCode = 12)
        val TEMPLATES = ReportTemplates(
            recipient = "feedback@example.test",
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
