package ru.poporyadku.ui.puzzleresult

import ru.poporyadku.ui.report.ReportContext

/**
 * Одноразовые эффекты экрана результата (ITERATION_3_DESIGN.md, I3-D25, I3-D49;
 * ITERATION_5_DESIGN.md, §4.4).
 *
 * Доставляются через `Channel`: после поворота экрана переход не повторяется.
 *
 * Архивный режим отправляет ТОЛЬКО [NavigateBack] (и [NavigateHome] на невалидном
 * маршруте): переходы в игровой маршрут `puzzle/{slotIndex}`, к следующему слоту и в
 * сессионный итог в нём не существуют ни при каком исходе — игру прошлого дня архив не
 * запускает (I5-D10, `I5-V17`).
 */
sealed interface PuzzleResultEffect {

    /** Только сессия: CTA слотов 0–1 и редирект пропущенного слота. */
    data class NavigateToNextSlot(val slotIndex: Int) : PuzzleResultEffect

    /** Только сессия: CTA последнего слота и редирект пропущенного последнего слота. */
    data object NavigateToRecap : PuzzleResultEffect

    /** Только сессия: попытки нет — слот ещё не сыгран. */
    data class NavigateToPuzzle(val slotIndex: Int) : PuzzleResultEffect

    /** Сессионная «Назад» и невалидный маршрут. */
    data object NavigateHome : PuzzleResultEffect

    /**
     * Только архив: `popBackStack()` к архивному итогу — CTA «К итогу дня», «Назад» в
     * шапке, а также исходы загрузки без кадра (`Skipped`, `NoAttempt`).
     *
     * [isRedirect] различает эти два источника для route-контейнера: переход по нажатию
     * выполняется, только пока запись экрана — текущая (второе быстрое нажатие
     * отбрасывается), а редирект без нажатия этой проверкой не ограничивается
     * (ITERATION_5_DESIGN.md, §6.11, I5-D24).
     */
    data class NavigateBack(val isRedirect: Boolean) : PuzzleResultEffect

    /**
     * «Сообщить о неточности» (ITERATION_5_DESIGN.md, §3.12, I5-D18): данные письма без
     * Android-типов. Шаблоны читает из ресурсов и письмо открывает через `ExternalApps`
     * route-контейнер — и только с текущей записи, как любое действие по нажатию.
     */
    data class ComposeReport(val context: ReportContext) : PuzzleResultEffect
}
