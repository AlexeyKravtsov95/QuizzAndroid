package ru.poporyadku.ui.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.poporyadku.domain.model.FeedbackSettings

/**
 * `I5-F1` — `FeedbackPolicy` (ITERATION_5_DESIGN.md, §3.14, §4.7, §10.1, I5-D22).
 *
 * Пять состояний настроек × оба cue: решение об отдаче принимается здесь целиком, без
 * Android, поэтому таблица проверяется перечислением, а не через ViewModel.
 */
class FeedbackPolicyTest {

    /** Настройки ещё не прочитаны — отдачи нет ни для одного cue. */
    @Test
    fun `I5-F1 unknown settings give no request`() {
        FeedbackCue.entries.forEach { cue ->
            assertNull("$cue при Unknown", FeedbackPolicy.requestFor(cue, FeedbackSettings.Unknown))
        }
    }

    /** Оба канала выключены — эффекта не существует, а не «запрос с двумя false». */
    @Test
    fun `I5-F1 both channels off give no request`() {
        val settings = FeedbackSettings.Known(soundEnabled = false, vibrationEnabled = false)

        FeedbackCue.entries.forEach { cue ->
            assertNull("$cue при выключенных каналах", FeedbackPolicy.requestFor(cue, settings))
        }
    }

    /** Только звук: haptic не запрашивается. */
    @Test
    fun `I5-F1 sound only asks for sound`() {
        val settings = FeedbackSettings.Known(soundEnabled = true, vibrationEnabled = false)

        FeedbackCue.entries.forEach { cue ->
            assertEquals(
                "$cue при включённом только звуке",
                FeedbackRequest(cue, playSound = true, performHaptic = false),
                FeedbackPolicy.requestFor(cue, settings),
            )
        }
    }

    /** Только вибрация: звук не запрашивается. */
    @Test
    fun `I5-F1 vibration only asks for haptic`() {
        val settings = FeedbackSettings.Known(soundEnabled = false, vibrationEnabled = true)

        FeedbackCue.entries.forEach { cue ->
            assertEquals(
                "$cue при включённой только вибрации",
                FeedbackRequest(cue, playSound = false, performHaptic = true),
                FeedbackPolicy.requestFor(cue, settings),
            )
        }
    }

    /** Оба включены — оба флага `true`, и cue передаётся без подмены. */
    @Test
    fun `I5-F1 both channels on ask for both`() {
        val settings = FeedbackSettings.Known(soundEnabled = true, vibrationEnabled = true)

        FeedbackCue.entries.forEach { cue ->
            val request = FeedbackPolicy.requestFor(cue, settings)

            assertEquals(
                "$cue при включённых каналах",
                FeedbackRequest(cue, playSound = true, performHaptic = true),
                request,
            )
        }
    }
}
