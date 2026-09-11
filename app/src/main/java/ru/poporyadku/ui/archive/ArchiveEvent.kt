package ru.poporyadku.ui.archive

import java.time.LocalDate

/** События экрана архива (ITERATION_5_DESIGN.md, §4.2). */
sealed interface ArchiveEvent {
    /** Нажатие строки дня. */
    data class DayClicked(val localDate: LocalDate) : ArchiveEvent

    /** Футер `CanLoadMore` появился в композиции. */
    data object EndReached : ArchiveEvent

    /** «Повторить» — `Error`, `LoadMoreFailed`, `RefreshFailed`. */
    data object RetryClicked : ArchiveEvent

    /** «Назад» в шапке. */
    data object BackClicked : ArchiveEvent

    /** `Empty` → «К заданию дня». */
    data object ToTodayClicked : ArchiveEvent
}
