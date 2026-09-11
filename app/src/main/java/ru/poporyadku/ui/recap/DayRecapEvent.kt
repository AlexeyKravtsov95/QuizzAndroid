package ru.poporyadku.ui.recap

/**
 * События экрана итога дня (ITERATION_5_DESIGN.md, §4.3).
 *
 * «Поделиться» — PR 5D, реклама и запрос уведомлений — итерации 6 и 7, и точки для них
 * здесь не резервируются (пустой хук — тот же мёртвый код).
 */
sealed interface DayRecapEvent {
    /** Нижняя кнопка: «Готово» в сессии, «Назад» в архиве. */
    data object PrimaryClicked : DayRecapEvent

    /** «Назад» в шапке — существует только у архивного варианта. */
    data object BackClicked : DayRecapEvent

    /** Нажатие строки результата; действие есть только у `Played` архивного итога. */
    data class SlotClicked(val slotIndex: Int) : DayRecapEvent
}
