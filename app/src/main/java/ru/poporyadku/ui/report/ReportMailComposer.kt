package ru.poporyadku.ui.report

import java.util.Locale
import ru.poporyadku.domain.model.InstalledContentVersion

/**
 * Шаблоны письма — строки ресурсов (ITERATION_5_DESIGN.md, §4.9, раздел 13.3). Чистые
 * значения: композер не знает ни `Context`, ни `Resources`.
 */
data class ReportTemplates(
    /** `feedback_email`. */
    val recipient: String,
    /** «По порядку! — неточность в задании %1$s». */
    val subjectPuzzle: String,
    /** «По порядку! — сообщение о неточности». */
    val subjectGeneral: String,
    /** «Задание: %1$s». */
    val linePuzzle: String,
    /** «Версия приложения: %1$s (%2$d)». */
    val lineAppVersion: String,
    /** «Версия контента: %1$d». */
    val lineContentVersion: String,
    /** «Версия контента: не установлена». */
    val lineContentVersionUnknown: String,
    /** «Опишите, что не так:». */
    val prompt: String,
)

/**
 * Письмо «Сообщить о неточности» (ITERATION_5_DESIGN.md, §3.12, §6.7, I5-D18).
 *
 * Тело — строки через LF, пустая строка перед приглашением, приглашение завершается
 * переводом строки (курсор встаёт на новую строку). В `mailto:` LF превращает в CRLF уже
 * [MailtoUri]. `Locale.ROOT` исключает локальные цифры и разделители устройства.
 */
object ReportMailComposer {

    private const val LINE_BREAK = "\n"

    fun compose(context: ReportContext, templates: ReportTemplates): MailDraft {
        val lines = buildList {
            context.puzzleId?.let { add(templates.linePuzzle.format(Locale.ROOT, it)) }
            add(templates.lineAppVersion.format(Locale.ROOT, context.app.versionName, context.app.versionCode))
            add(
                when (val content = context.content) {
                    is InstalledContentVersion.Known -> templates.lineContentVersion.format(Locale.ROOT, content.version)
                    InstalledContentVersion.Unknown -> templates.lineContentVersionUnknown
                },
            )
            add("")
            add(templates.prompt)
        }
        return MailDraft(
            to = templates.recipient,
            subject = context.puzzleId?.let { templates.subjectPuzzle.format(Locale.ROOT, it) }
                ?: templates.subjectGeneral,
            body = lines.joinToString(LINE_BREAK) + LINE_BREAK,
        )
    }
}
