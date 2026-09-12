package ru.poporyadku.ui.feedback

/**
 * Решённая отдача: что подтвердить и какими каналами (ITERATION_5_DESIGN.md, §4.7).
 *
 * Флаги считаны из настроек пользователя один раз — в `PuzzleViewModel`; исполнитель их
 * не перепроверяет и своей политики не имеет. Запроса, у которого оба флага `false`, не
 * существует: [FeedbackPolicy] возвращает в этом случае `null`.
 */
data class FeedbackRequest(
    val cue: FeedbackCue,
    val playSound: Boolean,
    val performHaptic: Boolean,
)
