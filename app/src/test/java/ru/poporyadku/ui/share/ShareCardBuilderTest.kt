package ru.poporyadku.ui.share

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ShareCardBuilder` — ITERATION_5_DESIGN.md, §3.13, §6.6, `I5-H1`…`I5-H6`, `I5-H8`,
 * `I5-H9`; `docs/design/b2/state-sheets/share-card-format.md`.
 *
 * Тест чистый JVM: у билдера нет ни Android, ни ресурсов, строки подставляются здесь.
 */
class ShareCardBuilderTest {

    // --- I5-H1 / I5-H2 / I5-H3: границы счёта ----------------------------------------

    /** `I5-H1`. Пустой день: «0/18» и три строки из шести `⬜`. */
    @Test
    fun `I5-H1 a zero day is three empty rows`() {
        val lines = build(scores = listOf(0, 0, 0)).lines()

        assertEquals("0/18", lines[SCORE_LINE])
        assertEquals(List(3) { EMPTY.repeat(6) }, lines.subList(FIRST_ROW_LINE, FIRST_ROW_LINE + 3))
    }

    /** `I5-H2`. Одна верная пара: «1/18», зелёный ровно один и ровно в первой строке. */
    @Test
    fun `I5-H2 a single correct pair fills exactly one square`() {
        val lines = build(scores = listOf(1, 0, 0)).lines()

        assertEquals("1/18", lines[SCORE_LINE])
        assertEquals(FILLED + EMPTY.repeat(5), lines[FIRST_ROW_LINE])
        assertEquals(EMPTY.repeat(6), lines[FIRST_ROW_LINE + 1])
        assertEquals(EMPTY.repeat(6), lines[FIRST_ROW_LINE + 2])
    }

    /** `I5-H3`. Идеальный день: «18/18» и три строки из шести `🟩`. */
    @Test
    fun `I5-H3 a perfect day is three filled rows`() {
        val lines = build(scores = listOf(6, 6, 6)).lines()

        assertEquals("18/18", lines[SCORE_LINE])
        assertEquals(List(3) { FILLED.repeat(6) }, lines.subList(FIRST_ROW_LINE, FIRST_ROW_LINE + 3))
    }

    // --- I5-H4: эталон дизайна --------------------------------------------------------

    /**
     * `I5-H4`. Смешанный день 6/4/5, день 12, серия 6 — посимвольно равен эталону
     * раздела 3.13: счёт считает сам билдер, склонение даёт «дней».
     */
    @Test
    fun `I5-H4 the mixed day equals the design reference character by character`() {
        val expected = listOf(
            "По порядку! · День 12",
            "15/18",
            "🟩🟩🟩🟩🟩🟩",
            "🟩🟩🟩🟩⬜⬜",
            "🟩🟩🟩🟩🟩⬜",
            "Серия: 6 дней",
            APP_URL,
        ).joinToString("\n")

        assertEquals(expected, build(dayNumber = 12, scores = listOf(6, 4, 5), streak = 6))
    }

    // --- I5-H5: спойлеров и даты нет ---------------------------------------------------

    /**
     * `I5-H5`. Ровно семь строк без завершающего перевода строки; ни ISO-даты, ни названий
     * месяцев, ни названий категорий. Передать `prompt`, `title` или `correctOrder` в
     * карточку нечем — во входе таких полей нет, и это структурная гарантия.
     */
    @Test
    fun `I5-H5 exactly seven lines with no date and no puzzle data`() {
        val card = build(dayNumber = 12, scores = listOf(6, 4, 5), streak = 6)

        assertEquals(7, card.lines().size)
        assertFalse("завершающего перевода строки нет", card.endsWith("\n"))
        assertFalse("ISO-даты в карточке нет", Regex("""\d{4}-\d{2}-\d{2}""").containsMatchIn(card))
        assertFalse("числовой даты в карточке нет", Regex("""\d{1,2}\.\d{1,2}\.\d{2,4}""").containsMatchIn(card))
        for (month in MONTHS) {
            assertFalse("названия месяцев в карточке нет: $month", card.contains(month, ignoreCase = true))
        }
        for (category in CATEGORIES) {
            assertFalse("названий категорий в карточке нет: $category", card.contains(category, ignoreCase = true))
        }
        // Полей головоломки во входе нет вовсе: конструктор их не принимает.
        val inputFields = ShareCardInput::class.java.declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") }
            .sorted()
        assertEquals(listOf("dayNumber", "slotScores", "streakDays"), inputFields)
    }

    // --- I5-H6: шесть кодовых точек и никаких других emoji ------------------------------

    /**
     * `I5-H6`. Для каждого набора счетов: в строке ровно шесть кодовых точек, зелёных —
     * столько же, сколько баллов, их сумма равна числу второй строки, а вне ASCII,
     * кириллицы и «·» встречаются только `🟩` и `⬜`. Вариационного селектора нет.
     */
    @Test
    fun `I5-H6 every row is six code points and no other emoji appear`() {
        for (scores in listOf(listOf(0, 0, 0), listOf(1, 0, 0), listOf(6, 4, 5), listOf(6, 6, 6), listOf(3, 0, 6))) {
            val card = build(scores = scores, streak = 21)
            val rows = card.lines().subList(FIRST_ROW_LINE, FIRST_ROW_LINE + 3)

            rows.forEachIndexed { index, row ->
                assertEquals(
                    "строка $index набора $scores — ровно шесть кодовых точек: $row",
                    6,
                    row.codePointCount(0, row.length),
                )
                assertEquals(
                    "зелёных в строке $index равно счёту слота",
                    scores[index],
                    row.countOf(FILLED_CODE_POINT),
                )
            }
            assertEquals(
                "сумма зелёных равна числу второй строки",
                card.lines()[SCORE_LINE].substringBefore('/').toInt(),
                rows.sumOf { it.countOf(FILLED_CODE_POINT) },
            )

            assertFalse(
                "U+FE0F (вариационный селектор) в карточке нет",
                card.codePoints().anyMatch { it == VARIATION_SELECTOR },
            )
            val unexpected = card.codePoints().toArray().filterNot { it.isAllowedInCard() }
            assertTrue(
                "в карточке только ASCII, кириллица, «·», 🟩 и ⬜; лишнее: " +
                    unexpected.joinToString { "U+%04X".format(it) },
                unexpected.isEmpty(),
            )
        }
    }

    // --- I5-H8: невалидный вход --------------------------------------------------------

    /** `I5-H8`. Не тот размер набора, счёт вне 0..6, нулевой день и отрицательная серия. */
    @Test
    fun `I5-H8 malformed input is rejected`() {
        assertRejected("два результата") { build(scores = listOf(6, 4)) }
        assertRejected("четыре результата") { build(scores = listOf(6, 4, 5, 3)) }
        assertRejected("отрицательный счёт") { build(scores = listOf(-1, 4, 5)) }
        assertRejected("счёт больше шести") { build(scores = listOf(7, 4, 5)) }
        assertRejected("день 0") { build(dayNumber = 0) }
        assertRejected("отрицательная серия") { build(streak = -1) }
    }

    /** `I5-H8`. Ссылка: пустая, не https и многострочная — отклоняются. */
    @Test
    fun `I5-H8 a malformed url is rejected`() {
        assertRejected("пустая ссылка") { build(url = "") }
        assertRejected("пробельная ссылка") { build(url = "   ") }
        assertRejected("http") { build(url = "http://poporyadku.invalid/") }
        assertRejected("перевод строки в конце") { build(url = "$APP_URL\n") }
        assertRejected("перевод строки внутри") { build(url = "https://poporyadku\n.invalid/") }
    }

    // --- I5-H9: ссылка приходит извне ---------------------------------------------------

    /** `I5-H9`. Ссылка — седьмая строка, посимвольно та, что передали. */
    @Test
    fun `I5-H9 the url is the seventh line unchanged`() {
        val url = "https://poporyadku.example/go?utm=share&x=1#top"
        val card = build(url = url)

        assertEquals(7, card.lines().size)
        assertEquals(url, card.lines()[URL_LINE])
        assertTrue("ссылка встречается в карточке ровно один раз", card.windowed(url.length).count { it == url } == 1)
    }

    /**
     * `I5-H9`. Билдер не знает ни одного магазина и не строит ссылку сам: в его исходнике
     * нет ни `rustore`, ни `appgallery`, а единственный `https://` — проверка входа.
     */
    @Test
    fun `I5-H9 the builder knows no store and builds no url`() {
        val source = builderSource()

        for (store in listOf("rustore", "appgallery", "play.google", "RU_STORE_URL")) {
            assertFalse("билдер не знает магазинов: $store", source.contains(store, ignoreCase = true))
        }
        val httpsLines = source.lines().filter { it.contains("https://") }
        assertEquals(
            "единственное упоминание https:// — константа проверки входа: $httpsLines",
            1,
            httpsLines.size,
        )
        assertTrue(
            "https:// встречается только в константе HTTPS_PREFIX: ${httpsLines.first()}",
            httpsLines.first().contains("HTTPS_PREFIX"),
        )
    }

    // --- Инфраструктура ------------------------------------------------------------------

    private fun build(
        dayNumber: Int = 12,
        scores: List<Int> = listOf(6, 4, 5),
        streak: Int = 6,
        url: String = APP_URL,
    ): String = ShareCardBuilder.build(
        input = ShareCardInput(dayNumber = dayNumber, slotScores = scores, streakDays = streak),
        strings = STRINGS,
        appUrl = url,
    )

    private fun assertRejected(what: String, block: () -> String) {
        try {
            block()
            throw AssertionError("$what обязан быть отклонён")
        } catch (expected: IllegalArgumentException) {
            // Ожидаемо: вход не соответствует формату карточки.
        }
    }

    private fun String.countOf(codePoint: Int): Int = codePoints().filter { it == codePoint }.count().toInt()

    /** ASCII, кириллица, «·», `🟩` и `⬜` — всё, что в карточке вообще допустимо. */
    private fun Int.isAllowedInCard(): Boolean = this < 0x80 ||
        this in 0x0400..0x04FF ||
        this == MIDDLE_DOT ||
        this == FILLED_CODE_POINT ||
        this == EMPTY_CODE_POINT

    /** Исходник билдера — рядом с модулем: тест запускается и из корня, и из `app`. */
    private fun builderSource(): String {
        val relative = "src/main/java/ru/poporyadku/ui/share/ShareCardBuilder.kt"
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.first { it.exists() }.readText()
    }

    private companion object {
        const val FILLED = ShareCardBuilder.FILLED
        const val EMPTY = ShareCardBuilder.EMPTY
        const val APP_URL = "https://poporyadku.invalid/"

        const val SCORE_LINE = 1
        const val FIRST_ROW_LINE = 2
        const val URL_LINE = 6

        const val FILLED_CODE_POINT = 0x1F7E9
        const val EMPTY_CODE_POINT = 0x2B1C
        const val MIDDLE_DOT = 0x00B7
        const val VARIATION_SELECTOR = 0xFE0F

        val STRINGS = ShareCardStrings(
            headerTemplate = "По порядку! · День %1\$d",
            streakTemplate = "Серия: %1\$d %2\$s",
            dayOne = "день",
            dayFew = "дня",
            dayMany = "дней",
        )

        val MONTHS = listOf(
            "январ", "феврал", "март", "апрел", "мая", "июн", "июл",
            "август", "сентябр", "октябр", "ноябр", "декабр",
        )

        /** Все семь названий категорий (`category_*` в `strings.xml`), по корню слова. */
        val CATEGORIES = listOf("Истори", "Географи", "Наук", "Природ", "Культур", "Росси", "Смешанн")
    }
}
