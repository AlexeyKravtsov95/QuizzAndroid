package ru.poporyadku.ui.puzzleresult

/**
 * Одноразовые эффекты экрана результата (ITERATION_3_DESIGN.md, I3-D25, I3-D49).
 *
 * Доставляются через `Channel`: после поворота экрана переход не повторяется.
 */
sealed interface PuzzleResultEffect {

    /** CTA слотов 0–1 и редирект пропущенного слота. */
    data class NavigateToNextSlot(val slotIndex: Int) : PuzzleResultEffect

    /** CTA последнего слота и редирект пропущенного последнего слота. */
    data object NavigateToRecap : PuzzleResultEffect

    /** Попытки нет: слот ещё не сыгран. */
    data class NavigateToPuzzle(val slotIndex: Int) : PuzzleResultEffect

    data object NavigateHome : PuzzleResultEffect
}
