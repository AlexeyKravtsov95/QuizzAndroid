package ru.poporyadku.ui.feedback

import android.media.AudioManager
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * `I5-F2` — исполнение отдачи (ITERATION_5_DESIGN.md, §3.14, §8.2, §10.5, I5-D22, I5-D23).
 *
 * Две независимые части:
 * - [AndroidFeedbackPlayer]: какая константа тактильной отдачи и на каком API, и что
 *   каждый канал исполняется только по своему флагу. `Vibrator` не участвует нигде;
 * - [SoundCueBank] на фейковом [SoundEngine]: весь порядок «listener → load → callback →
 *   play» и однократное освобождение. Настоящий `SoundPool` здесь не создаётся —
 *   проверяется логика, а не платформа.
 *
 * Беззвучный и вибро-режим проверяются на предикате, который собирает `SoundCues`:
 * `ringerMode == RINGER_MODE_NORMAL` поверх настоящего `AudioManager`.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidFeedbackPlayerTest {

    private lateinit var view: View
    private lateinit var sounds: RecordingSoundCues
    private lateinit var player: AndroidFeedbackPlayer

    @Before
    fun setUp() {
        view = View(ApplicationProvider.getApplicationContext())
        sounds = RecordingSoundCues()
        player = AndroidFeedbackPlayer(sounds, view)
    }

    // --- Тактильные константы ----------------------------------------------------------

    /** Перестановка — системный «щелчок шага» на любом поддерживаемом API. */
    @Test
    fun `I5-F2 card moved performs clock tick`() {
        player.play(FeedbackRequest(FeedbackCue.CardMoved, playSound = false, performHaptic = true))

        assertEquals(HapticFeedbackConstants.CLOCK_TICK, shadowOf(view).lastHapticFeedbackPerformed())
    }

    /** `CONFIRM` существует с API 30 — на 30 и выше используется он. */
    @Test
    @Config(sdk = [Build.VERSION_CODES.R])
    fun `I5-F2 answer accepted performs confirm on api 30`() {
        player.play(FeedbackRequest(FeedbackCue.AnswerAccepted, playSound = false, performHaptic = true))

        assertEquals(HapticFeedbackConstants.CONFIRM, shadowOf(view).lastHapticFeedbackPerformed())
    }

    /** На API 29 `CONFIRM` ещё нет: ближайший системный отклик подтверждения. */
    @Test
    @Config(sdk = [Build.VERSION_CODES.Q])
    fun `I5-F2 answer accepted performs virtual key on api 29`() {
        player.play(FeedbackRequest(FeedbackCue.AnswerAccepted, playSound = false, performHaptic = true))

        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, shadowOf(view).lastHapticFeedbackPerformed())
    }

    // --- Независимость каналов ---------------------------------------------------------

    /**
     * Каждый флаг исполняет ровно свой канал: выключенный звук не отменяет вибрацию, а
     * выключенная вибрация — звук.
     */
    @Test
    fun `I5-F2 each channel follows only its own flag`() {
        player.play(FeedbackRequest(FeedbackCue.CardMoved, playSound = true, performHaptic = false))

        assertEquals("haptic не выполнялся", NO_HAPTIC, shadowOf(view).lastHapticFeedbackPerformed())
        assertEquals(listOf(FeedbackCue.CardMoved), sounds.played)

        player.play(FeedbackRequest(FeedbackCue.AnswerAccepted, playSound = false, performHaptic = true))

        assertEquals(HapticFeedbackConstants.CONFIRM, shadowOf(view).lastHapticFeedbackPerformed())
        assertEquals("второго звука не было", listOf(FeedbackCue.CardMoved), sounds.played)

        player.play(FeedbackRequest(FeedbackCue.AnswerAccepted, playSound = true, performHaptic = true))

        assertEquals(
            listOf(FeedbackCue.CardMoved, FeedbackCue.AnswerAccepted),
            sounds.played,
        )
    }

    // --- Системный профиль звука -------------------------------------------------------

    /**
     * Беззвучный и вибро-режим: звука нет. Проверяется тот самый предикат, который
     * `SoundCues` отдаёт банку, — на настоящем `AudioManager`.
     */
    @Test
    fun `I5-F2 silent and vibrate modes play no sound`() {
        val audio = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService(AudioManager::class.java)
        val engine = FakeSoundEngine()
        val bank = loadedBank(engine, canPlaySound = { audio.ringerMode == AudioManager.RINGER_MODE_NORMAL })

        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        bank.play(FeedbackCue.CardMoved)
        assertTrue("в беззвучном режиме звука нет", engine.played.isEmpty())

        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        bank.play(FeedbackCue.CardMoved)
        assertTrue("в вибро-режиме звука нет", engine.played.isEmpty())

        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        bank.play(FeedbackCue.CardMoved)
        assertEquals(listOf(SAMPLE_MOVE), engine.played)
    }

    // --- Порядок инициализации SoundCueBank --------------------------------------------

    /** Listener зарегистрирован ДО первого `load()` — это порядок, а не «успевает». */
    @Test
    fun `I5-F2 listener is registered before the first load`() {
        val engine = FakeSoundEngine()

        SoundCueBank(engine, canPlaySound = { true }, sounds = SOUNDS)

        assertEquals(
            listOf(FakeSoundEngine.LISTENER, FakeSoundEngine.LOAD, FakeSoundEngine.LOAD),
            engine.calls,
        )
    }

    /** Callback, доставленный синхронно внутри `load()`, не теряется. */
    @Test
    fun `I5-F2 a callback delivered synchronously inside load is not lost`() {
        val engine = FakeSoundEngine(statusInsideLoad = LOAD_SUCCESS)
        val bank = SoundCueBank(engine, canPlaySound = { true }, sounds = SOUNDS)

        bank.play(FeedbackCue.CardMoved)
        bank.play(FeedbackCue.AnswerAccepted)

        assertEquals(listOf(SAMPLE_MOVE, SAMPLE_ACCEPT), engine.played)
    }

    /** Два файла становятся доступными независимо: свой callback — свой cue. */
    @Test
    fun `I5-F2 the two sounds become available independently`() {
        val engine = FakeSoundEngine()
        val bank = SoundCueBank(engine, canPlaySound = { true }, sounds = SOUNDS)

        engine.complete(SAMPLE_ACCEPT)

        bank.play(FeedbackCue.CardMoved)
        assertTrue("первый файл ещё не загружен", engine.played.isEmpty())

        bank.play(FeedbackCue.AnswerAccepted)
        assertEquals(listOf(SAMPLE_ACCEPT), engine.played)

        engine.complete(SAMPLE_MOVE)
        bank.play(FeedbackCue.CardMoved)
        assertEquals(listOf(SAMPLE_ACCEPT, SAMPLE_MOVE), engine.played)
    }

    /** Ненулевой `status` — отказ загрузки: звук не разрешается. */
    @Test
    fun `I5-F2 a non-zero status never enables the cue`() {
        val engine = FakeSoundEngine()
        val bank = SoundCueBank(engine, canPlaySound = { true }, sounds = SOUNDS)

        engine.complete(SAMPLE_MOVE, status = LOAD_FAILURE)
        bank.play(FeedbackCue.CardMoved)

        assertTrue("отказ загрузки звука не разрешает", engine.played.isEmpty())
    }

    /** `load()` вернул 0 — загрузка не начата, и cue не станет доступным никогда. */
    @Test
    fun `I5-F2 load returning zero never enables the cue`() {
        val engine = FakeSoundEngine(ids = listOf(NOT_LOADED, SAMPLE_ACCEPT))
        val bank = SoundCueBank(engine, canPlaySound = { true }, sounds = SOUNDS)

        // Даже callback с успешным статусом на нулевой id ничего не открывает.
        engine.complete(NOT_LOADED)
        bank.play(FeedbackCue.CardMoved)
        assertTrue(engine.played.isEmpty())

        // Второй файл загрузился нормально и работает независимо от первого.
        engine.complete(SAMPLE_ACCEPT)
        bank.play(FeedbackCue.AnswerAccepted)
        assertEquals(listOf(SAMPLE_ACCEPT), engine.played)
    }

    /** До загрузки cue не проигрывается и не ставится в очередь «на потом». */
    @Test
    fun `I5-F2 play before loading is dropped, not queued`() {
        val engine = FakeSoundEngine()
        val bank = SoundCueBank(engine, canPlaySound = { true }, sounds = SOUNDS)

        bank.play(FeedbackCue.CardMoved)
        assertTrue("до загрузки звука нет", engine.played.isEmpty())

        engine.complete(SAMPLE_MOVE)
        assertTrue("отложенной очереди нет: загрузка сама не играет", engine.played.isEmpty())
    }

    // --- Освобождение ------------------------------------------------------------------

    /** `release()` дважды — ровно один `engine.release()`. */
    @Test
    fun `I5-F2 release is executed exactly once`() {
        val engine = FakeSoundEngine()
        val bank = loadedBank(engine)

        bank.release()
        bank.release()

        assertEquals(1, engine.releases)
    }

    /** После `release()` ни `play`, ни поздний callback воспроизведения не дают. */
    @Test
    fun `I5-F2 nothing plays after release`() {
        val engine = FakeSoundEngine()
        val bank = SoundCueBank(engine, canPlaySound = { true }, sounds = SOUNDS)

        bank.release()

        // Поздний callback SoundPool: пул уже освобождён, доступность не возвращается.
        engine.complete(SAMPLE_MOVE)
        bank.play(FeedbackCue.CardMoved)
        bank.play(FeedbackCue.AnswerAccepted)

        assertTrue("после release воспроизведения нет", engine.played.isEmpty())
        assertEquals(1, engine.releases)
    }

    // --- Инфраструктура ----------------------------------------------------------------

    /** Банк с обоими загруженными файлами — исходная точка тестов воспроизведения. */
    private fun loadedBank(
        engine: FakeSoundEngine,
        canPlaySound: () -> Boolean = { true },
    ): SoundCueBank = SoundCueBank(engine, canPlaySound, SOUNDS).also {
        engine.complete(SAMPLE_MOVE)
        engine.complete(SAMPLE_ACCEPT)
    }

    /** Счётчик звукового канала: [AndroidFeedbackPlayer] обязан вызывать его по флагу. */
    private class RecordingSoundCues : SoundCuePlayer {
        val played = mutableListOf<FeedbackCue>()

        override fun play(cue: FeedbackCue) {
            played += cue
        }
    }

    /**
     * Фейковый движок: записывает порядок вызовов и умеет доставить callback синхронно
     * внутри `load()` — случай, который теряется при неверном порядке инициализации.
     */
    private class FakeSoundEngine(
        private val ids: List<Int> = listOf(SAMPLE_MOVE, SAMPLE_ACCEPT),
        private val statusInsideLoad: Int? = null,
    ) : SoundEngine {

        val calls = mutableListOf<String>()
        val played = mutableListOf<Int>()
        var releases = 0
            private set

        private var listener: ((Int, Int) -> Unit)? = null
        private var loads = 0

        override fun setOnLoadComplete(listener: (Int, Int) -> Unit) {
            calls += LISTENER
            this.listener = listener
        }

        override fun load(rawResId: Int): Int {
            calls += LOAD
            val id = ids.getOrElse(loads++) { NOT_LOADED }
            statusInsideLoad?.let { status -> complete(id, status) }
            return id
        }

        override fun play(sampleId: Int) {
            played += sampleId
        }

        override fun release() {
            releases++
        }

        /** Callback `SoundPool.OnLoadCompleteListener`. */
        fun complete(sampleId: Int, status: Int = LOAD_SUCCESS) {
            listener?.invoke(sampleId, status)
        }

        companion object {
            const val LISTENER = "setOnLoadComplete"
            const val LOAD = "load"
        }
    }

    private companion object {
        const val SAMPLE_MOVE = 11
        const val SAMPLE_ACCEPT = 22

        const val LOAD_SUCCESS = 0
        const val LOAD_FAILURE = -1

        /** `SoundPool.load` вернул 0 — загрузка не начата. */
        const val NOT_LOADED = 0

        /** `ShadowView`: тактильной отдачи не было. */
        const val NO_HAPTIC = -1

        /** Порядок ключей фиксирован: первый `load` — перестановка, второй — ответ. */
        val SOUNDS = mapOf(
            FeedbackCue.CardMoved to 1_000,
            FeedbackCue.AnswerAccepted to 2_000,
        )
    }
}
