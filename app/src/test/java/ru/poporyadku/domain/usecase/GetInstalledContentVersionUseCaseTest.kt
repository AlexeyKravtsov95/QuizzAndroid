package ru.poporyadku.domain.usecase

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.poporyadku.data.content.FakeUserPreferencesRepository
import ru.poporyadku.domain.model.InstalledContentVersion

/**
 * `GetInstalledContentVersionUseCase` — ITERATION_5_DESIGN.md, §3.8, §5.4, `I5-R5`.
 *
 * Версия известна, только когда рядом записан отпечаток: без него `storedContentVersion` —
 * значение по умолчанию, а не факт установки (I4-D10). Двойник настроек — тот же, что у
 * тестов импортёра: он хранит ровно отметку установленного контента.
 */
class GetInstalledContentVersionUseCaseTest {

    private fun useCase(version: Int, fingerprint: String?) =
        GetInstalledContentVersionUseCase(FakeUserPreferencesRepository(version, fingerprint))

    @Test
    fun `I5-R5 no fingerprint is Unknown even with a positive version`() = runTest {
        assertEquals(InstalledContentVersion.Unknown, useCase(version = 3, fingerprint = null)())
    }

    @Test
    fun `I5-R5 version zero is Unknown even with a fingerprint`() = runTest {
        assertEquals(InstalledContentVersion.Unknown, useCase(version = 0, fingerprint = FINGERPRINT)())
    }

    @Test
    fun `I5-R5 version one with a fingerprint is Known(1)`() = runTest {
        assertEquals(InstalledContentVersion.Known(1), useCase(version = 1, fingerprint = FINGERPRINT)())
    }

    @Test
    fun `a later installed version is reported as is`() = runTest {
        assertEquals(InstalledContentVersion.Known(4), useCase(version = 4, fingerprint = FINGERPRINT)())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `Known never names a version below one`() {
        InstalledContentVersion.Known(0)
    }

    private companion object {
        const val FINGERPRINT = "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0"
    }
}
