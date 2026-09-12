package ru.poporyadku.ui.feedback

import android.content.Context
import android.media.AudioManager
import dagger.hilt.android.ActivityRetainedLifecycle
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityRetainedScoped
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import ru.poporyadku.R

/**
 * Порядок и состояния звуковой отдачи (ITERATION_5_DESIGN.md, §3.14, §8.2).
 *
 * Про Android и Hilt не знает ничего — только про [SoundEngine], поэтому весь порядок
 * проверяется фейковым движком (`I5-F2`).
 *
 * **Почему порядок гарантирован, а не «успевает».** Kotlin выполняет инициализаторы
 * свойств и блок `init` в порядке объявления: [loaded] и [released] созданы до `init`, а
 * [sampleIds] присваивается внутри `init` уже ПОСЛЕ `setOnLoadComplete`. Поэтому
 * callback, пришедший сколь угодно рано — даже синхронно внутри `load()`, — находит и
 * listener, и коллекцию. Предположение «файл в 4 КБ не успеет загрузиться» не
 * используется.
 *
 * Очереди нет намеренно: cue до загрузки просто не звучит. Отложенный звук пришёл бы
 * после того, как пользователь уже ушёл с экрана, и подтверждал бы не то действие.
 *
 * @param canPlaySound системный профиль звука; вызывается на каждый [play], а не один раз
 * при создании: беззвучный режим включают посреди сессии.
 */
class SoundCueBank(
    private val engine: SoundEngine,
    private val canPlaySound: () -> Boolean,
    sounds: Map<FeedbackCue, Int>,
) : SoundCuePlayer {

    // Шаг 2: коллекция загруженных ID. Потокобезопасные примитивы — потому что callback
    // движка приходит на Looper потока, создавшего пул, а play вызывается из
    // композиции; совпадение этих потоков в продукте не должно быть условием
    // корректности (§8.2).
    private val loaded: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val released = AtomicBoolean(false)

    private val sampleIds: Map<FeedbackCue, Int>

    init {
        // Шаг 3: listener ДО первого load().
        engine.setOnLoadComplete { sampleId, status ->
            // Ненулевой status — отказ загрузки: такой сэмпл не разрешается никогда.
            // Поздний callback после release() доступности тоже не возвращает.
            if (status == LOAD_SUCCESS && !released.get()) loaded += sampleId
        }
        // Шаг 4: только теперь загрузка обоих файлов. Каждый становится доступным своим
        // callback — независимо от второго.
        sampleIds = sounds.mapValues { (_, rawResId) -> engine.load(rawResId) }
    }

    override fun play(cue: FeedbackCue) {
        if (released.get()) return
        val sampleId = sampleIds[cue] ?: return
        // Шаг 5: `load()` вернул 0 — загрузка не начата, сэмпла не существует;
        // отсутствие в `loaded` — callback со status == 0 ещё не приходил.
        if (sampleId == NOT_LOADED || sampleId !in loaded) return
        // Беззвучный и вибро-режим — явная проверка, а не надежда на маршрутизацию
        // потока: в вибро-режиме системный поток не обязан быть заглушён.
        if (!canPlaySound()) return
        engine.play(sampleId)
    }

    /** Шаг 6: ровно один `engine.release()`, сколько бы раз ни вызвали. */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        loaded.clear()
        engine.release()
    }

    private companion object {
        /** Callback загрузки: 0 — успех. */
        const val LOAD_SUCCESS = 0

        /** [SoundEngine.load] вернул 0 — загрузка не начата. */
        const val NOT_LOADED = 0
    }
}

/**
 * Владелец звуков на время жизни экранов (ITERATION_5_DESIGN.md, §3.14, §8.2).
 *
 * `@ActivityRetainedScoped`, а не `@Singleton` и не экранный объект:
 * - процессный синглтон держал бы нативные ресурсы звукового пула и в фоне, без точки
 *   освобождения;
 * - экранный пул пришлось бы освобождать в `onDispose`, а `AnswerAccepted` звучит именно
 *   в момент ухода с `Puzzle` на `PuzzleResult` — звук обрывался бы на себе самом;
 * - при повороте экрана компонент жив, поэтому звуки не перегружаются.
 *
 * Держит только `ApplicationContext`: ссылки на Activity здесь нет, и утечь ей нечем.
 */
@ActivityRetainedScoped
class SoundCues @Inject constructor(
    @ApplicationContext context: Context,
    lifecycle: ActivityRetainedLifecycle,
) : SoundCuePlayer {

    private val audio = context.getSystemService(AudioManager::class.java)

    private val bank = SoundCueBank(
        engine = SoundPoolEngine(context),
        // `null` сервиса (неполная среда) означает «профиль неизвестен» — тогда молчим,
        // а не играем в беззвучном режиме.
        canPlaySound = { audio?.ringerMode == AudioManager.RINGER_MODE_NORMAL },
        sounds = mapOf(
            FeedbackCue.CardMoved to R.raw.feedback_move,
            FeedbackCue.AnswerAccepted to R.raw.feedback_accept,
        ),
    )

    init {
        // Окончательное уничтожение компонента — не поворот экрана.
        lifecycle.addOnClearedListener { bank.release() }
    }

    override fun play(cue: FeedbackCue) = bank.play(cue)
}
