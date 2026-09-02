package ru.poporyadku.ui.puzzleresult

/**
 * События экрана результата.
 *
 * Основное действие — «Дальше» для слотов 0–1 и «К итогу дня» для последнего.
 * «Поделиться» и «Сообщить о неточности» относятся к итерации 5, и точки для них здесь
 * не резервируются.
 *
 * [BackPressed] — кнопка «Назад» в `AppTopBar`, которую COMPONENTS.md требует на этом
 * экране. Она ведёт туда же, куда системная «назад», — на Home, а не в отвеченную
 * головоломку. Системную «назад» экран не перехватывает: бэкстек уже приводит её на
 * Home сам, и защищать здесь нечего — в отличие от `Puzzle`, где перехват защищает запись.
 */
sealed interface PuzzleResultEvent {
    data object PrimaryAction : PuzzleResultEvent
    data object BackPressed : PuzzleResultEvent
}
