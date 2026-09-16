package ru.poporyadku.ui.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.poporyadku.domain.model.FeedbackSettings

/**
 * `I5-F1`, `I6-F1` — `FeedbackPolicy` (ITERATION_5_DESIGN.md, §3.14, §4.7, §10.1, I5-D22;
 * ITERATION_6_DESIGN.md, §6.2, I6-D14).
 *
 * Пять состояний настроек × все cue: решение об отдаче принимается здесь целиком, без
 * Android, поэтому таблица проверяется перечислением, а не через ViewModel.
 *
 * `CardGrabbed` беззвучен **всегда** (I6-D14), поэтому таблица звука проверяется для двух
 * звучащих cue отдельно от него: утверждения `I5-F1` для `CardMoved`/`AnswerAccepted` при
 * этом не меняются.
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

        SOUNDING_CUES.forEach { cue ->
            assertEquals(
                "$cue при включённом только звуке",
                FeedbackRequest(cue, playSound = true, performHaptic = false),
                FeedbackPolicy.requestFor(cue, settings),
            )
        }
    }

    /**
     * `I6-F1`. У захвата звука нет никогда: при включённом только звуке отдавать нечем —
     * эффекта не существует, а не «запрос с двумя `false`».
     */
    @Test
    fun `I6-F1 card grabbed is silent, so sound only gives no request`() {
        val settings = FeedbackSettings.Known(soundEnabled = true, vibrationEnabled = false)

        assertNull(FeedbackPolicy.requestFor(FeedbackCue.CardGrabbed, settings))
    }

    /** `I6-F1`. Включённая вибрация даёт захвату только тактильный канал при любом звуке. */
    @Test
    fun `I6-F1 card grabbed asks for haptic only, whatever the sound setting is`() {
        listOf(true, false).forEach { soundEnabled ->
            val settings =
                FeedbackSettings.Known(soundEnabled = soundEnabled, vibrationEnabled = true)

            assertEquals(
                "звук=$soundEnabled",
                FeedbackRequest(FeedbackCue.CardGrabbed, playSound = false, performHaptic = true),
                FeedbackPolicy.requestFor(FeedbackCue.CardGrabbed, settings),
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

        SOUNDING_CUES.forEach { cue ->
            val request = FeedbackPolicy.requestFor(cue, settings)

            assertEquals(
                "$cue при включённых каналах",
                FeedbackRequest(cue, playSound = true, performHaptic = true),
                request,
            )
        }
    }

    private companion object {
        /** Cue со звуковым ресурсом: таблица `I5-F1` относится ровно к ним. */
        val SOUNDING_CUES = FeedbackCue.entries - FeedbackCue.CardGrabbed
    }
}
