package ru.poporyadku.ui.feedback

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Исполнение отдачи на Android (ITERATION_5_DESIGN.md, §3.14, §8.2).
 *
 * Тактильный канал — только `View.performHapticFeedback`: системного вибромотора
 * напрямую не касается ни одна строка, поэтому соответствующее разрешение не нужно и в
 * манифест не добавлено, а константы дают системный короткий отклик вместо выдуманной
 * длительности. `FLAG_IGNORE_GLOBAL_SETTING` не передаётся, поэтому отключённая в
 * системе тактильная отдача отключает и нашу — это цель, а не побочный эффект.
 *
 * Порядок каналов — haptic, затем звук: тактильный отклик мгновенный, а воспроизведение
 * ставит поток на микширование, и обратный порядок сделал бы вибрацию заметно позднее
 * нажатия.
 */
class AndroidFeedbackPlayer(
    private val sounds: SoundCuePlayer,
    private val view: View,
) : FeedbackPlayer {

    override fun play(request: FeedbackRequest) {
        // Каждый канал — только по своему флагу: выключенный звук не отменяет вибрацию,
        // и наоборот.
        if (request.performHaptic) view.performHapticFeedback(hapticFor(request.cue))
        if (request.playSound) sounds.play(request.cue)
    }

    private fun hapticFor(cue: FeedbackCue): Int = when (cue) {
        // Щелчок «шага» — ровно то, чем система озвучивает перемещение в списке.
        FeedbackCue.CardMoved -> HapticFeedbackConstants.CLOCK_TICK

        // CONFIRM появился в API 30; на 26–29 ближайший системный отклик подтверждения —
        // VIRTUAL_KEY. Своей длительности не задаём ни там, ни там.
        FeedbackCue.AnswerAccepted ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.VIRTUAL_KEY
            }
    }
}
