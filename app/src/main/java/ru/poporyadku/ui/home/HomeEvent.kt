package ru.poporyadku.ui.home

/**
 * События Home (ITERATION_3_DESIGN.md, I3-D47).
 *
 * Перечисляет только то, что порождает нажатие внутри самого экрана и требует работы
 * ViewModel. `ON_START` событием не является: он приходит вызовом
 * [HomeViewModel.onScreenStarted] с уровня route-контейнера (I3-D14). Переходы в
 * «Архив» и «Настройки» — чистая навигация без участия ViewModel и приходят на экран
 * отдельными callback-параметрами.
 */
sealed interface HomeEvent {

    /** Основная кнопка экрана. Смысл зависит от состояния — таблица в [HomeViewModel]. */
    data object PrimaryAction : HomeEvent

    /** «Повторить» на `Error`. Во время восстановления игнорируется. */
    data object RetryClicked : HomeEvent

    /**
     * Отправляется ПОСЛЕ подтверждения в диалоге; сам диалог — локальное состояние
     * Composable. `generation` копируется из [HomeState.Error], на котором диалог
     * открыт: пока пользователь читает предупреждение, состояние могло смениться, и
     * старое подтверждение обязано быть отклонено — даже если новое состояние снова
     * `ContentConflict`.
     */
    data class RecoveryConfirmed(val actionId: String, val generation: Long) : HomeEvent
}
