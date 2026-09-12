package ru.poporyadku.ui.sources

/**
 * События экрана источников (ITERATION_5_DESIGN.md, §4.6).
 *
 * Нажатия на строку `link` здесь нет: у него нет ни данных, ни состояния, ни записи —
 * ссылку открывает сама строка через `ExternalApps` (§8.1).
 */
sealed interface SourcesEvent {
    data object RetryClicked : SourcesEvent
    data object BackClicked : SourcesEvent
}
