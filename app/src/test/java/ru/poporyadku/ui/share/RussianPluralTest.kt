package ru.poporyadku.ui.share

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `RussianPlural` — ITERATION_5_DESIGN.md, §3.13, §6.6, `I5-H7`.
 *
 * Правило собственное, а не Android `plurals`: карточка уходит наружу, и её грамматика не
 * должна зависеть от локали чужого телефона.
 */
class RussianPluralTest {

    /** `I5-H7`. Обязательный набор значений из раздела 13.3 дизайна. */
    @Test
    fun `I5-H7 the required numbers get their russian forms`() {
        val expected = mapOf(
            0 to RussianPlural.Many, // 0 дней
            1 to RussianPlural.One, // 1 день
            2 to RussianPlural.Few, // 2 дня
            5 to RussianPlural.Many, // 5 дней
            11 to RussianPlural.Many, // 11 дней
            12 to RussianPlural.Many, // 12 дней
            21 to RussianPlural.One, // 21 день
            22 to RussianPlural.Few, // 22 дня
            25 to RussianPlural.Many, // 25 дней
            111 to RussianPlural.Many, // 111 дней
        )

        for ((number, form) in expected) {
            assertEquals("форма для $number", form, RussianPlural.of(number))
        }
    }

    /** Те же значения в готовых словах — как их увидит получатель карточки. */
    @Test
    fun `I5-H7 the required numbers read correctly with the resource words`() {
        val words = mapOf(
            RussianPlural.One to "день",
            RussianPlural.Few to "дня",
            RussianPlural.Many to "дней",
        )
        val phrases = listOf(0, 1, 2, 5, 11, 12, 21, 22, 25, 111).map { "$it ${words.getValue(RussianPlural.of(it))}" }

        assertEquals(
            listOf(
                "0 дней", "1 день", "2 дня", "5 дней", "11 дней",
                "12 дней", "21 день", "22 дня", "25 дней", "111 дней",
            ),
            phrases,
        )
    }

    /** Границы правила: десятки, сотни и вторая сотня целиком. */
    @Test
    fun `I5-H7 the rule holds across tens and hundreds`() {
        assertEquals(RussianPlural.One, RussianPlural.of(101))
        assertEquals(RussianPlural.Few, RussianPlural.of(103))
        assertEquals(RussianPlural.Many, RussianPlural.of(100))
        // 11..14 — исключение: Many при любой последней цифре.
        for (number in 11..14) {
            assertEquals("$number — исключение второй десятки", RussianPlural.Many, RussianPlural.of(number))
        }
        for (number in 112..114) {
            assertEquals("$number — то же исключение в сотне", RussianPlural.Many, RussianPlural.of(number))
        }
    }

    /** Отрицательное число дней отклоняется: серии длиной −1 не бывает. */
    @Test
    fun `I5-H7 a negative number is rejected`() {
        for (number in listOf(-1, -11, -21)) {
            try {
                RussianPlural.of(number)
                throw AssertionError("$number обязан быть отклонён")
            } catch (expected: IllegalArgumentException) {
                // Ожидаемо.
            }
        }
    }
}
