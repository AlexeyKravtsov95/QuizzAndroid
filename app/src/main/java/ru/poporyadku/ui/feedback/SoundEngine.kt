package ru.poporyadku.ui.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Узкая граница над `SoundPool` (ITERATION_5_DESIGN.md, §8.2).
 *
 * Нужна ровно для одного: порядок «listener → load → callback → play» и однократное
 * освобождение — логика, а не свойство платформы, и живёт она в [SoundCueBank], который
 * проверяется фейком без Android (`I5-F2`).
 */
interface SoundEngine {

    fun setOnLoadComplete(listener: (sampleId: Int, status: Int) -> Unit)

    /** @return идентификатор сэмпла; `0` — загрузка не начата (контракт `SoundPool.load`). */
    fun load(rawResId: Int): Int

    fun play(sampleId: Int)

    fun release()
}

/**
 * Единственное место в `src/main`, где существует `SoundPool`.
 *
 * `USAGE_ASSISTANCE_SONIFICATION` + `CONTENT_TYPE_SONIFICATION` — звук интерфейса, а не
 * музыка и не уведомление: система направляет его в системный поток и микширует с чужим
 * воспроизведением, не прерывая его.
 */
class SoundPoolEngine(private val context: Context) : SoundEngine {

    // Шаг 1 порядка §8.2: сам пул. Инициализатор свойства выполняется раньше любого
    // вызова методов, поэтому listener и load ниже работают с готовым пулом.
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    override fun setOnLoadComplete(listener: (Int, Int) -> Unit) =
        pool.setOnLoadCompleteListener { _, sampleId, status -> listener(sampleId, status) }

    override fun load(rawResId: Int): Int = pool.load(context, rawResId, LOAD_PRIORITY)

    override fun play(sampleId: Int) {
        pool.play(sampleId, VOLUME, VOLUME, PRIORITY, NO_LOOP, NORMAL_RATE)
    }

    override fun release() = pool.release()

    private companion object {
        /** Двух хватает: cue не длиннее 112 мс, третьего одновременного звука нет. */
        const val MAX_STREAMS = 2

        /** Документация `SoundPool.load`: значение не используется, но 1 — принятое. */
        const val LOAD_PRIORITY = 1

        const val VOLUME = 1f
        const val PRIORITY = 0
        const val NO_LOOP = 0
        const val NORMAL_RATE = 1f
    }
}
