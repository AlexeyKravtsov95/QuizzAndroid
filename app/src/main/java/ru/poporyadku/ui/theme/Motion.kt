package ru.poporyadku.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing

/** DESIGN_TOKENS.md §6.8 — кривые Material 3, сверены с текущей спецификацией. */
object MotionEasing {
    val standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val standardDecelerate: Easing = CubicBezierEasing(0f, 0f, 0f, 1f)
    val standardAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    val emphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val linear: Easing = LinearEasing
}

/** DESIGN_TOKENS.md §6.8 — длительности переходов, миллисекунды. */
object MotionDuration {
    /** motion.duration.short */
    const val short = 100

    /** motion.duration.medium */
    const val medium = 200

    /** motion.duration.long */
    const val long = 300

    /** motion.duration.exit */
    const val exit = 200

    /** motion.stagger.resultReveal — сдвиг между строками PuzzleResult. */
    const val staggerResultReveal = 60
}

/**
 * DESIGN_TOKENS.md §6.8 — именованные длительности и амплитуда DragEducationHint,
 * зафиксированные UX_FLOW.md и перенесённые без изменений.
 */
object DragHintMotion {
    /** dragHint.wobbleDuration, мс. */
    const val wobbleDurationMs = 300

    /** dragHint.wobbleAmplitude, градусы поворота. */
    const val wobbleAmplitudeDegrees = 4f

    /** dragHint.autoHideDelay, мс. */
    const val autoHideDelayMs = 4000

    /** dragHint.submitLockDuration, мс. */
    const val submitLockDurationMs = 600
}

/** DESIGN_TOKENS.md §6.8 — прочие именованные motion-токены вне общей шкалы. */
object MotionRotation {
    /** motion.rotation.chevronExpand — угол поворота шеврона SourcesBlock, градусы. */
    const val chevronExpandDegrees = 180f
}
