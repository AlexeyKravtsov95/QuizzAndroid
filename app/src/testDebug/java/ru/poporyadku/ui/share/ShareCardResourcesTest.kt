package ru.poporyadku.ui.share

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.poporyadku.R

/**
 * `ShareCardResources` с настоящими ресурсами — ITERATION_5_DESIGN.md, §3.13, `I5-H10`.
 *
 * Проверяется именно связка «ресурс → билдер»: шаблоны, формы склонения и ссылка берутся
 * из `strings.xml` и `distribution.xml`, а не из литералов теста.
 */
@RunWith(RobolectricTestRunner::class)
class ShareCardResourcesTest {

    private val resources = ApplicationProvider.getApplicationContext<Application>().resources

    /** `I5-H10`. Карточка дня 12 со счётом 6/4/5 и серией 6 — на настоящих ресурсах. */
    @Test
    fun `I5-H10 the card is built from real resources`() {
        val card = resources.shareCardText(
            ShareCardInput(dayNumber = 12, slotScores = listOf(6, 4, 5), streakDays = 6),
        )
        val lines = card.lines()

        assertEquals(7, lines.size)
        assertEquals("По порядку! · День 12", lines[0])
        assertEquals("15/18", lines[1])
        assertEquals("Серия: 6 дней", lines[5])
        assertEquals(resources.getString(R.string.share_app_url), lines[6])
    }

    /** `I5-H10`. Седьмая строка — в точности `share_app_url`, и это альфа-значение O5-6. */
    @Test
    fun `I5-H10 the seventh line is exactly the share_app_url resource`() {
        val url = resources.getString(R.string.share_app_url)

        assertEquals(
            "альфа-значение O5-6; в итерации 7 заменяется реальной landing-страницей",
            "https://poporyadku.invalid/",
            url,
        )
        val card = resources.shareCardText(
            ShareCardInput(dayNumber = 1, slotScores = listOf(0, 0, 0), streakDays = 1),
        )
        assertEquals(url, card.lines()[6])
        assertTrue("ссылка занимает ровно одну строку", url.lines().size == 1)
    }

    /** `I5-H10`. Склонение серии в карточке — своё правило, а не `plurals` локали. */
    @Test
    fun `I5-H10 the streak line declines the day word from resources`() {
        val forms = listOf(1, 2, 5, 21).map { streak ->
            resources.shareCardText(
                ShareCardInput(dayNumber = 3, slotScores = listOf(1, 2, 3), streakDays = streak),
            ).lines()[5]
        }

        assertEquals(
            listOf("Серия: 1 день", "Серия: 2 дня", "Серия: 5 дней", "Серия: 21 день"),
            forms,
        )
    }
}
