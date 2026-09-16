package ru.poporyadku.ui.settings

import ru.poporyadku.domain.reminder.NotificationSettingsTarget
import ru.poporyadku.ui.report.ReportContext

/**
 * Одноразовые эффекты настроек (ITERATION_5_DESIGN.md, §4.5, I5-D24). Выполняет их
 * ровно один lifecycle-aware коллектор route-контейнера и только с текущей записи
 * бэкстека.
 */
sealed interface SettingsEffect {
    data object NavigateBack : SettingsEffect
    data object OpenSources : SettingsEffect

    /** Общий канал без привязки к головоломке: `context.puzzleId == null`. */
    data class ComposeReport(val context: ReportContext) : SettingsEffect

    /**
     * Запустить системный запрос `POST_NOTIFICATIONS` (ITERATION_6_DESIGN.md, §8.2).
     * Создаётся **только** при `RuntimePermissionMissing`: при выключенных уведомлениях
     * приложения или заглушённом канале запрос ничего не изменил бы.
     */
    data object RequestNotificationPermission : SettingsEffect

    /** Открыть системные настройки уведомлений приложения или канала. */
    data class OpenNotificationSettings(val target: NotificationSettingsTarget) : SettingsEffect
}
