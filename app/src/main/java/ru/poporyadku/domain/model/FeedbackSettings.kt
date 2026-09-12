package ru.poporyadku.domain.model

/**
 * Настройки звуковой и тактильной отдачи (ITERATION_5_DESIGN.md, §3.14, §4.7, I5-D22).
 *
 * [Unknown] — «ещё не прочитано», а не «выключено»: до первой эмиссии DataStore отдачи
 * нет вовсе. Отдельный вариант, а не `null`-поля, потому что «настройки неизвестны» и
 * «оба канала выключены» дают одинаковое поведение по разным причинам, и различать их
 * обязан тип, а не комментарий.
 */
sealed interface FeedbackSettings {

    data object Unknown : FeedbackSettings

    data class Known(
        val soundEnabled: Boolean,
        val vibrationEnabled: Boolean,
    ) : FeedbackSettings
}
