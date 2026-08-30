package ru.poporyadku.ui.navigation

/**
 * Маршруты — буквально из UX_FLOW.md, раздел 1 («Карта экранов»). Аргументы навигации,
 * не игровое состояние: puzzleIndex 0..2, date — ISO yyyy-MM-dd.
 */
object Destinations {
    const val ARG_PUZZLE_INDEX = "puzzleIndex"
    const val ARG_DATE = "date"

    /** Сентинел даты для recap/{date}, обозначающий итог сегодняшнего дня (UX_FLOW.md §1, §6). */
    const val TODAY = "today"

    const val HOME = "home"
    const val PUZZLE = "puzzle/{$ARG_PUZZLE_INDEX}"
    const val PUZZLE_RESULT = "puzzle/{$ARG_PUZZLE_INDEX}/result"
    const val RECAP = "recap/{$ARG_DATE}"
    const val ARCHIVE = "archive"
    const val SETTINGS = "settings"

    fun puzzle(puzzleIndex: Int) = "puzzle/$puzzleIndex"
    fun puzzleResult(puzzleIndex: Int) = "puzzle/$puzzleIndex/result"
    fun recap(date: String) = "recap/$date"
}
