package ru.poporyadku.ui.report

import ru.poporyadku.core.model.AppBuildInfo
import ru.poporyadku.domain.model.InstalledContentVersion

/**
 * Всё, что может попасть в письмо «Сообщить о неточности» (ITERATION_5_DESIGN.md, §3.12,
 * §4.9, I5-D18). Других полей у входа нет: ни счёта, ни серии, ни дат, ни истории, ни
 * идентификаторов устройства — отсутствие лишних данных гарантирует сигнатура.
 *
 * Собирает ViewModel; шаблоны и адрес читает из ресурсов route-контейнер при сборе
 * эффекта.
 */
data class ReportContext(
    /** `null` — общий канал из настроек; иначе — `puzzleId` из попытки. */
    val puzzleId: String?,
    val app: AppBuildInfo,
    val content: InstalledContentVersion,
)

/** Черновик письма: получатель, тема и тело. Отправляет его пользователь сам. */
data class MailDraft(
    val to: String,
    val subject: String,
    val body: String,
)
