package ru.poporyadku.ui.report

/**
 * `mailto:`-URI по RFC 6068 (ITERATION_5_DESIGN.md, §3.12, §6.7, I5-D18).
 *
 * `URLEncoder` не используется: он кодирует HTML-форму, а не URI, и дал бы `+` вместо
 * пробела — почтовые клиенты показывают его буквально.
 *
 * - строки переводятся в байты UTF-8, и каждый байт вне `A–Z a–z 0–9 - . _ ~` становится
 *   `%HH` в верхнем регистре; поэтому `&`, `=`, `?`, `#`, `%`, `+` и пробел (`%20`)
 *   кодируются всегда, и никакое значение не может разорвать структуру URI;
 * - в адресе дополнительно сохраняется `@`;
 * - каждый LF тела становится CRLF (`%0D%0A`) — переводом строки RFC 6068.
 */
object MailtoUri {

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private const val ADDRESS_EXTRA = "@"
    private const val HEX_DIGITS = "0123456789ABCDEF"

    fun of(draft: MailDraft): String =
        "mailto:" + encode(draft.to, keep = ADDRESS_EXTRA) +
            "?subject=" + encode(draft.subject) +
            "&body=" + encode(toCrlf(draft.body))

    /** Каждый LF → CR LF; уже стоящий CR LF не удваивается. */
    private fun toCrlf(text: String): String = text.replace("\r\n", "\n").replace("\n", "\r\n")

    private fun encode(text: String, keep: String = ""): String = buildString {
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val value = byte.toInt() and BYTE_MASK
            val char = value.toChar()
            // Сохраняются только ASCII-символы разрешённого набора; байты ≥ 0x80 (UTF-8
            // кириллицы) в этот набор не входят по построению.
            if (value < ASCII_LIMIT && (char in UNRESERVED || char in keep)) {
                append(char)
            } else {
                append('%')
                append(HEX_DIGITS[value shr NIBBLE_BITS])
                append(HEX_DIGITS[value and NIBBLE_MASK])
            }
        }
    }

    private const val BYTE_MASK = 0xFF
    private const val ASCII_LIMIT = 0x80
    private const val NIBBLE_BITS = 4
    private const val NIBBLE_MASK = 0x0F
}
