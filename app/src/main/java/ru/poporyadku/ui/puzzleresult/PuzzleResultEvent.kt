package ru.poporyadku.ui.puzzleresult

/**
 * События экрана результата.
 *
 * Основное действие — «Дальше» для слотов 0–1 и «К итогу дня» для последнего; в
 * архивном режиме — всегда «К итогу дня», назад к архивному итогу.
 *
 * [ReportClicked] — «Сообщить о неточности» рядом с источниками (ITERATION_5_DESIGN.md,
 * §3.12, §4.4): и в сессии, и в архиве, только при показанном результате.
 *
 * [BackPressed] — кнопка «Назад» в `AppTopBar`, которую COMPONENTS.md требует на этом
 * экране. Она ведёт туда же, куда системная «назад»: в сессии — на Home, а не в
 * отвеченную головоломку; в архиве — к архивному итогу. Системную «назад» экран не
 * перехватывает: бэкстек в обоих режимах приводит её туда же сам, и защищать здесь
 * нечего — в отличие от `Puzzle`, где перехват защищает запись.
 */
sealed interface PuzzleResultEvent {
    data object PrimaryAction : PuzzleResultEvent
    data object BackPressed : PuzzleResultEvent
    data object ReportClicked : PuzzleResultEvent
}
