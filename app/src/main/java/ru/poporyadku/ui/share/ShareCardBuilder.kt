package ru.poporyadku.ui.share

import java.util.Locale

/**
 * Вход карточки шеринга (ITERATION_5_DESIGN.md, §3.13, §6.6, I5-D20).
 *
 * Что нельзя передать, того в карточке и не будет: ни даты, ни категории, ни `prompt`,
 * ни `title`, ни `correctOrder` — spoiler-free гарантируется структурой входа, а не
 * дисциплиной вызывающего. Общий счёт дня тоже не принимается: его считает билдер, и
 * противоречивому total просто неоткуда взяться.
 *
 * @param dayNumber `setIndex + 1`, ≥ 1 — личный прогресс, а не календарная дата.
 * @param slotScores ровно три значения, каждое 0..6, в порядке `slotIndex` 0, 1, 2.
 * @param streakDays серия ЭТОГО дня (I5-D9), ≥ 0.
 */
data class ShareCardInput(
    val dayNumber: Int,
    val slotScores: List<Int>,
    val streakDays: Int,
)

/**
 * Тексты карточки — из ресурсов, не из литералов билдера (§3.13).
 *
 * Формы «день»/«дня»/«дней» приходят сюда же: выбор формы — чистое правило
 * [RussianPlural], но сами слова остаются в `strings.xml`.
 */
data class ShareCardStrings(
    /** «По порядку! · День %1$d». */
    val headerTemplate: String,

    /** «Серия: %1$d %2$s». */
    val streakTemplate: String,
    val dayOne: String,
    val dayFew: String,
    val dayMany: String,
)

/**
 * Форма русского существительного при числе (§3.13, I5-D20).
 *
 * Своё правило, а не Android `plurals`: `plurals` выбирает форму по локали **устройства**,
 * и на английской локали доступны только `one`/`other` — карточка «21 день» ушла бы
 * получателю как «21 дней». Текст, уходящий наружу, не должен зависеть от языка чужого
 * телефона. Экранные `plurals` этим правилом не заменяются.
 */
enum class RussianPlural {
    /** 1, 21, 101 — «день». */
    One,

    /** 2..4, 22..24 — «дня». */
    Few,

    /** 0, 5..20, 25, 111 — «дней». */
    Many;

    companion object {
        /** @param n неотрицательное число дней; отрицательное отклоняется. */
        fun of(n: Int): RussianPlural {
            require(n >= 0) { "число дней не может быть отрицательным: $n" }
            val mod10 = n % 10
            val mod100 = n % 100
            return when {
                mod10 == 1 && mod100 != 11 -> One
                mod10 in 2..4 && mod100 !in 12..14 -> Few
                else -> Many
            }
        }
    }
}

/**
 * Текст карточки дня (ITERATION_5_DESIGN.md, §3.13, §6.6;
 * `docs/design/b2/state-sheets/share-card-format.md`).
 *
 * Чистый Kotlin: ни одного платформенного типа, ни Compose, ни чтения строковых
 * ресурсов, ни запуска внешних приложений. Строки читает адаптер ресурсов карточки,
 * системный выбор приложения открывает `ExternalApps.shareText`, а билдер знает только
 * текст — и ни одного названия магазина: ссылка приходит параметром и попадает в
 * результат без изменений (I5-D21).
 */
object ShareCardBuilder {

    /** U+1F7E9 LARGE GREEN SQUARE — одна верная пара. */
    const val FILLED = "🟩"

    /** U+2B1C WHITE LARGE SQUARE — одна неверная пара. */
    const val EMPTY = "⬜"

    /**
     * Ровно семь строк, без завершающего перевода строки.
     *
     * @param appUrl ссылка на приложение из ресурса; билдер её не строит и не меняет.
     * @throws IllegalArgumentException если вход не соответствует формату карточки.
     */
    fun build(
        input: ShareCardInput,
        strings: ShareCardStrings,
        appUrl: String,
    ): String {
        require(input.dayNumber >= MIN_DAY_NUMBER) { "номер дня начинается с 1: ${input.dayNumber}" }
        require(input.slotScores.size == SLOT_COUNT) {
            "в дне ровно $SLOT_COUNT результата: ${input.slotScores.size}"
        }
        require(input.slotScores.all { it in 0..PAIRS_PER_PUZZLE }) {
            "счёт головоломки — 0..$PAIRS_PER_PUZZLE: ${input.slotScores}"
        }
        require(input.streakDays >= 0) { "серия не бывает отрицательной: ${input.streakDays}" }
        require(appUrl.isNotBlank()) { "ссылка на приложение обязательна" }
        require(appUrl.startsWith(HTTPS_PREFIX)) { "ссылка на приложение обязана быть https" }
        require(appUrl.lines().size == 1) { "ссылка на приложение занимает ровно одну строку" }

        // Счёт дня считается здесь и только здесь: во вход его передать нечем.
        val total = input.slotScores.sum()
        val rows = input.slotScores.map { score ->
            FILLED.repeat(score) + EMPTY.repeat(PAIRS_PER_PUZZLE - score)
        }
        val dayWord = when (RussianPlural.of(input.streakDays)) {
            RussianPlural.One -> strings.dayOne
            RussianPlural.Few -> strings.dayFew
            RussianPlural.Many -> strings.dayMany
        }

        // Locale.ROOT: цифры и разделители не зависят от локали устройства.
        return listOf(
            String.format(Locale.ROOT, strings.headerTemplate, input.dayNumber),
            "$total/$MAX_TOTAL_SCORE",
            rows[0],
            rows[1],
            rows[2],
            String.format(Locale.ROOT, strings.streakTemplate, input.streakDays, dayWord),
            appUrl,
        ).joinToString(separator = LINE_BREAK)
    }

    /** Пар в одной головоломке, они же символы в строке результата. */
    const val PAIRS_PER_PUZZLE = 6

    /** Головоломок в дне, они же строки результата. */
    const val SLOT_COUNT = 3

    /** Максимум дня: `SLOT_COUNT × PAIRS_PER_PUZZLE`. */
    const val MAX_TOTAL_SCORE = SLOT_COUNT * PAIRS_PER_PUZZLE

    private const val MIN_DAY_NUMBER = 1
    private const val HTTPS_PREFIX = "https://"
    private const val LINE_BREAK = "\n"
}
