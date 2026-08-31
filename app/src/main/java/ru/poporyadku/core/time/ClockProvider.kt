package ru.poporyadku.core.time

import java.time.Clock

// ITERATION_2_DESIGN.md, D-16. Реализации — variant-specific: системные часы в
// src/release и управляемые часы в src/debug. В src/main реализаций нет.
interface ClockProvider {
    fun clock(): Clock

    /** Единственный способ получить дату и метку времени вместе. */
    fun now(): TimeSnapshot = TimeSnapshot.of(clock())
}
