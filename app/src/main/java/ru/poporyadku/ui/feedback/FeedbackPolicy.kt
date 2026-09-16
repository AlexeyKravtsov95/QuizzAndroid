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
     * ([FeedbackSettings.Unknown]), оба канала выключены или у cue нет ни одного
     * доступного канала (беззвучный `CardGrabbed` при выключенной вибрации). Иначе —
     * запрос с флагами ровно по настройкам.
     */
    fun requestFor(cue: FeedbackCue, settings: FeedbackSettings): FeedbackRequest? =
        when (settings) {
            // «Ещё не прочитано» — не «выключено» и не «включено»: до первой эмиссии
            // DataStore отдачи нет, иначе первая перестановка звучала бы по умолчанию,
            // а не по выбору пользователя.
            FeedbackSettings.Unknown -> null

            is FeedbackSettings.Known -> {
                val playSound = settings.soundEnabled && cue.hasSound
                val performHaptic = settings.vibrationEnabled
                if (!playSound && !performHaptic) {
                    null
                } else {
                    FeedbackRequest(
                        cue = cue,
                        playSound = playSound,
                        performHaptic = performHaptic,
                    )
                }
            }
        }

    /**
     * У захвата звука нет никогда (ITERATION_6_DESIGN.md, §6.1, I6-D14) — и это решается
     * здесь, а не исполнителем: иначе «беззвучный cue» держался бы на том, что для него
     * не завели файл, и первый же добавленный ресурс включил бы звук молча.
     */
    private val FeedbackCue.hasSound: Boolean
        get() = this != FeedbackCue.CardGrabbed
}
