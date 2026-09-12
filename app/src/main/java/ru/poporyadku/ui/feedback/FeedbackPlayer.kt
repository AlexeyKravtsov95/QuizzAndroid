package ru.poporyadku.ui.feedback

/**
 * Исполнитель решённой отдачи (ITERATION_5_DESIGN.md, §8.2).
 *
 * Своей политики не имеет: что играть и чем — уже решено [FeedbackPolicy] во ViewModel.
 * Единственная продуктовая реализация — [AndroidFeedbackPlayer]; навигационные и
 * Compose-тесты подставляют свою через `LocalFeedbackPlayerOverride`.
 */
fun interface FeedbackPlayer {
    fun play(request: FeedbackRequest)
}

/**
 * Звуковой канал отдачи — узкая граница между [AndroidFeedbackPlayer] и загруженным
 * банком звуков.
 *
 * Существует, чтобы «флаги запроса соблюдаются» (`I5-F2`) проверялось без настоящего
 * звукового пула: `SoundCues` и `SoundCueBank` реализуют этот же интерфейс, тест —
 * счётчик вызовов. Второй абстракции звука она не вводит — у неё один метод и оба продуктовых
 * звена уже его имеют.
 */
fun interface SoundCuePlayer {
    fun play(cue: FeedbackCue)
}
