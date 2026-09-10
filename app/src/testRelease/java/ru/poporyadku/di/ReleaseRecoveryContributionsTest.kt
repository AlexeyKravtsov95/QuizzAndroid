package ru.poporyadku.di

import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Release-часть `I4-V2` (ITERATION_4_DESIGN.md, §11.3, п. 3; ITERATION_3_DESIGN.md, I3-D47):
 * в release-сборке набор действий восстановления пуст, потому что его единственный вклад
 * и само действие сброса существуют только в `src/debug`.
 *
 * Живёт в `src/testRelease` и запускается `testReleaseUnitTest`: classpath здесь — классы
 * release-варианта. Если сброс или его Hilt-модуль однажды переедут в `src/main`, имя
 * найдётся и тест упадёт. Зеркальная проверка присутствия тех же имён в debug —
 * `ContentResetActionTest` (`src/testDebug`): без неё этот тест мог бы проверять имена,
 * которых нет нигде.
 */
class ReleaseRecoveryContributionsTest {

    @Test
    fun `release variant has no recovery contribution`() {
        for (name in DEBUG_ONLY_RECOVERY_CLASSES) {
            assertThrows("$name не должен попадать в release", ClassNotFoundException::class.java) {
                Class.forName(name)
            }
        }
    }

    private companion object {
        val DEBUG_ONLY_RECOVERY_CLASSES = listOf(
            "ru.poporyadku.di.DebugHomeRecoveryModule",
            "ru.poporyadku.debug.ContentResetAction",
            "ru.poporyadku.debug.ContentReset",
        )
    }
}
