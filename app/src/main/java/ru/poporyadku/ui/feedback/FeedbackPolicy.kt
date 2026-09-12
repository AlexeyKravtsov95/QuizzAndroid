package ru.poporyadku.ui.feedback

import ru.poporyadku.domain.model.FeedbackSettings

/**
 * Единственное место, где решается, звучать ли отдаче (ITERATION_5_DESIGN.md, §3.14,
 * §4.7, I5-D22).
 *
 * Чистая функция без Android: поэтому все комбинации настроек проверяются JVM-тестом
 * `I5-F1`, а ViewModel-тесты видят решение прямо в эффекте, не подставляя ни звуковой
 * пул, ни `View`.
 */
object FeedbackPolicy {

    /**
     * @return `null`, если отдавать нечем: настройки ещё не прочитаны
     * ([FeedbackSettings.Unknown]) или оба канала выключены. Иначе — запрос с флагами
     * ровно по настройкам.
     */
    fun requestFor(cue: FeedbackCue, settings: FeedbackSettings): FeedbackRequest? =
        when (settings) {
            // «Ещё не прочитано» — не «выключено» и не «включено»: до первой эмиссии
            // DataStore отдачи нет, иначе первая перестановка звучала бы по умолчанию,
            // а не по выбору пользователя.
            FeedbackSettings.Unknown -> null

            is FeedbackSettings.Known ->
                if (!settings.soundEnabled && !settings.vibrationEnabled) {
                    null
                } else {
                    FeedbackRequest(
                        cue = cue,
                        playSound = settings.soundEnabled,
                        performHaptic = settings.vibrationEnabled,
                    )
                }
        }
}
