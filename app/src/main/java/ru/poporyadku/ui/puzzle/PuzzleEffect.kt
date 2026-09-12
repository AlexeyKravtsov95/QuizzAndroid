package ru.poporyadku.ui.puzzle

import ru.poporyadku.ui.feedback.FeedbackRequest

/**
 * Одноразовые эффекты игрового экрана (ITERATION_3_DESIGN.md, I3-D25).
 *
 * Доставляются через `Channel`: элемент не реплеится, поэтому поворот экрана не
 * повторяет навигацию по уже пройденному маршруту.
 */
sealed interface PuzzleEffect {

    data class NavigateToResult(val slotIndex: Int) : PuzzleEffect

    /** Только пропуск: слоты 0 и 1 (I3-D28, I3-D45). */
    data class NavigateToNextSlot(val slotIndex: Int) : PuzzleEffect

    /** Только пропуск последнего слота (I3-D45). */
    data object NavigateToRecap : PuzzleEffect

    data object NavigateHome : PuzzleEffect

    /**
     * «{Название} перемещён на позицию N из 4» — но **структурой**, а не готовой фразой.
     *
     * Локализованный текст собирает route-контейнер из `strings.xml`: ViewModel не
     * держит ни `Context`, ни `Resources`, и хардкоженная русская строка в ней сделала бы
     * перевод экрана невозможным.
     */
    data class AnnounceCardMoved(
        val cardTitle: String,
        val position: Int,
        val totalPositions: Int,
    ) : PuzzleEffect

    /**
     * Звуковая и тактильная отдача (ITERATION_5_DESIGN.md, §3.14, §4.7, I5-D22).
     *
     * Решение «звучать ли и чем» уже принято `FeedbackPolicy` по настройкам
     * пользователя: эффекта с обоими выключенными каналами не бывает, и исполнитель
     * своей политики не имеет. Тем же `Channel`, что навигация, — поэтому поворот,
     * перекомпозиция и повторная подписка отдачу не повторяют, а в `PuzzleUiState` её
     * нет вовсе и восстановление состояния её не порождает.
     */
    data class Feedback(val request: FeedbackRequest) : PuzzleEffect
}
