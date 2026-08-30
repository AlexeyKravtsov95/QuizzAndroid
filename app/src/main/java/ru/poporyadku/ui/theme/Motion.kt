package ru.poporyadku.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * DESIGN_TOKENS.md §6.8 — один разрешённый набор motion-значений: длительности, кривые,
 * подсказка перетаскивания и поворот шеврона. Компоненты не читают [MotionTokens.Normal]/
 * [MotionTokens.Reduced] напрямую — они получают уже разрешённый набор через
 * [Motion.resolve]/[rememberMotionTokens], поэтому включение системной настройки
 * «убрать анимацию» не может быть забыто в отдельном компоненте.
 */
data class MotionTokens(
    /** motion.duration.short, мс. */
    val durationShort: Int,
    /** motion.duration.medium, мс. */
    val durationMedium: Int,
    /** motion.duration.long, мс. */
    val durationLong: Int,
    /** motion.duration.exit, мс. */
    val durationExit: Int,
    /** motion.stagger.resultReveal — сдвиг между строками PuzzleResult, мс. */
    val staggerResultReveal: Int,
    /** motion.easing.standard */
    val easingStandard: Easing,
    /** motion.easing.standardDecelerate */
    val easingStandardDecelerate: Easing,
    /** motion.easing.standardAccelerate */
    val easingStandardAccelerate: Easing,
    /** motion.easing.emphasizedDecelerate */
    val easingEmphasizedDecelerate: Easing,
    /** dragHint.wobbleDuration, мс. */
    val wobbleDurationMs: Int,
    /** dragHint.wobbleAmplitude, градусы поворота. */
    val wobbleAmplitudeDegrees: Float,
    /**
     * dragHint.autoHideDelay, мс. Часть обучающего таймфрейма, не декоративная анимация —
     * DESIGN_TOKENS.md §6.8 явно требует не сокращать это значение при reduced motion.
     */
    val autoHideDelayMs: Int,
    /**
     * dragHint.submitLockDuration, мс. Та же оговорка, что и у [autoHideDelayMs]:
     * не сокращается при reduced motion.
     */
    val submitLockDurationMs: Int,
    /** motion.rotation.chevronExpand — целевой угол поворота шеврона SourcesBlock, градусы. */
    val chevronExpandDegrees: Float,
)

/**
 * DESIGN_TOKENS.md §6.8 — точка входа для motion-токенов. Ровно два экземпляра:
 * [Normal] и [Reduced]; [resolve] выбирает нужный по системной настройке «убрать анимацию».
 * Ни один компонент не обращается к длительностям/кривым как к отдельным константам —
 * только через разрешённый [MotionTokens].
 */
object Motion {

    val Normal = MotionTokens(
        durationShort = 100,
        durationMedium = 200,
        durationLong = 300,
        durationExit = 200,
        staggerResultReveal = 60,
        easingStandard = CubicBezierEasing(0.2f, 0f, 0f, 1f),
        easingStandardDecelerate = CubicBezierEasing(0f, 0f, 0f, 1f),
        easingStandardAccelerate = CubicBezierEasing(0.3f, 0f, 1f, 1f),
        easingEmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f),
        wobbleDurationMs = 300,
        wobbleAmplitudeDegrees = 4f,
        autoHideDelayMs = 4000,
        submitLockDurationMs = 600,
        chevronExpandDegrees = 180f,
    )

    /**
     * DESIGN_TOKENS.md §6.8, «Reduced motion»: все `motion.duration.*` → 1 мс,
     * `motion.stagger.resultReveal` → 0, все `motion.easing.*` → linear. Исключение —
     * `dragHint.*`: покачивание обнуляется полностью (длительность и амплитуда — 0,
     * не «очень быстрое покачивание»), а `autoHideDelayMs`/`submitLockDurationMs` остаются
     * полноценными — это не декоративная анимация, а часть обучающего таймфрейма.
     */
    val Reduced = MotionTokens(
        durationShort = 1,
        durationMedium = 1,
        durationLong = 1,
        durationExit = 1,
        staggerResultReveal = 0,
        easingStandard = LinearEasing,
        easingStandardDecelerate = LinearEasing,
        easingStandardAccelerate = LinearEasing,
        easingEmphasizedDecelerate = LinearEasing,
        wobbleDurationMs = 0,
        wobbleAmplitudeDegrees = 0f,
        autoHideDelayMs = Normal.autoHideDelayMs,
        submitLockDurationMs = Normal.submitLockDurationMs,
        chevronExpandDegrees = Normal.chevronExpandDegrees,
    )

    /** Чистая функция-резолвер: не завязана на Android/Compose, удобна для unit-тестов. */
    fun resolve(reduceMotion: Boolean): MotionTokens = if (reduceMotion) Reduced else Normal
}

/**
 * Единая точка получения motion-токенов внутри Compose-дерева: читает системную настройку
 * «убрать анимацию» (`Settings.Global.ANIMATOR_DURATION_SCALE == 0`) и возвращает уже
 * разрешённый [MotionTokens] — компонент не должен сам опрашивать систему или выбирать
 * между [Motion.Normal]/[Motion.Reduced].
 */
@Composable
fun rememberMotionTokens(): MotionTokens {
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    return Motion.resolve(reduceMotion)
}
