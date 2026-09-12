package ru.poporyadku.ui.settings

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
}
