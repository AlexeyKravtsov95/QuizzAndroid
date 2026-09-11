package ru.poporyadku.ui.archive

import java.time.LocalDate

/**
 * Одноразовые эффекты архива (ITERATION_5_DESIGN.md, §4.2, I5-D24).
 *
 * `Channel(BUFFERED)`, один коллектор в route-контейнере; навигацию он выполняет, только
 * пока запись архива — текущая: второе быстрое нажатие отбрасывается.
 */
sealed interface ArchiveEffect {
    /** `recap/{date}?origin=archive`. */
    data class OpenDay(val localDate: LocalDate) : ArchiveEffect

    /** `popBackStack()` на существующий Home. */
    data object NavigateBack : ArchiveEffect

    /** `popBackStack(HOME, false)` — существующий Home, а не второй его экземпляр. */
    data object NavigateHome : ArchiveEffect
}
