package ru.poporyadku.ui.report

import android.content.res.Resources
import ru.poporyadku.R

/**
 * Шаблоны письма из ресурсов (ITERATION_5_DESIGN.md, §4.9). Единственное место, где
 * письмо касается `Resources`: читает их route-контейнер при сборе эффекта
 * `ComposeReport`, а ViewModel ни `Context`, ни `Resources` не держит.
 */
fun Resources.reportTemplates(): ReportTemplates = ReportTemplates(
    recipient = getString(R.string.feedback_email),
    subjectPuzzle = getString(R.string.report_subject_puzzle),
    subjectGeneral = getString(R.string.report_subject_general),
    linePuzzle = getString(R.string.report_line_puzzle),
    lineAppVersion = getString(R.string.report_line_app),
    lineContentVersion = getString(R.string.report_line_content),
    lineContentVersionUnknown = getString(R.string.report_line_content_unknown),
    prompt = getString(R.string.report_prompt),
)

/** Черновик письма для [context] по шаблонам ресурсов. */
fun Resources.reportDraft(context: ReportContext): MailDraft =
    ReportMailComposer.compose(context, reportTemplates())
