package ru.poporyadku.domain.scoring

/**
 * Парный подсчёт баллов (ARCHITECTURE.md, §4, ADR-012; ITERATION_3_DESIGN.md, I3-D4).
 *
 * Единственный публичный вход — [evaluate]. Двух независимых функций «счёт» и
 * «список ошибок» не существует: они считали бы один факт двумя алгоритмами, и
 * равенство `invertedPairs.size == 6 - score` держалось бы на дисциплине.
 */
object PairwiseScoreCalculator {

    /** C(4,2) — число уникальных пар четырёх карточек. */
    const val MAX_PER_PUZZLE = 6

    /** 3 головоломки в дне × [MAX_PER_PUZZLE]. */
    const val MAX_PER_DAY = 18

    /** Карточек в головоломке (CONTENT_MODEL.md, §4). */
    const val CARDS_PER_PUZZLE = 4

    /**
     * Один проход по шести парам: каждая пара попадает ровно в одну ветку,
     * поэтому `score + invertedPairs.size == MAX_PER_PUZZLE` всегда.
     *
     * I3-D6: невалидный вход — дефект кода или контента, а не состояние экрана,
     * поэтому [require], а не sealed-результат.
     */
    fun evaluate(submittedOrder: List<String>, correctOrder: List<String>): PairwiseScore {
        require(submittedOrder.size == CARDS_PER_PUZZLE) {
            "submittedOrder: ожидалось $CARDS_PER_PUZZLE карточек, получено ${submittedOrder.size}"
        }
        require(correctOrder.size == CARDS_PER_PUZZLE) {
            "correctOrder: ожидалось $CARDS_PER_PUZZLE карточек, получено ${correctOrder.size}"
        }
        require(submittedOrder.toSet().size == CARDS_PER_PUZZLE) {
            "submittedOrder содержит повторяющиеся cardId: $submittedOrder"
        }
        require(correctOrder.toSet().size == CARDS_PER_PUZZLE) {
            "correctOrder содержит повторяющиеся cardId: $correctOrder"
        }
        require(submittedOrder.toSet() == correctOrder.toSet()) {
            "множества cardId различаются: submittedOrder=$submittedOrder, correctOrder=$correctOrder"
        }

        val rank = correctOrder.withIndex().associate { (index, cardId) -> cardId to index }

        var score = 0
        val inverted = ArrayList<InvertedPair>(MAX_PER_PUZZLE)
        for (i in submittedOrder.indices) {
            for (j in i + 1 until submittedOrder.size) {
                val a = rank.getValue(submittedOrder[i])
                val b = rank.getValue(submittedOrder[j])
                if (a < b) {
                    score++
                } else {
                    // Нормализация по правильному порядку: раньше должна была
                    // стоять та карточка, которую пользователь поставил второй.
                    inverted += InvertedPair(
                        correctlyFirst = submittedOrder[j],
                        correctlySecond = submittedOrder[i],
                    )
                }
            }
        }

        // I3-D4: порядок строк детерминирован — по позициям в correctOrder, чтобы
        // объяснение читалось сверху вниз в том же порядке, что карточки над ним.
        val ordered = inverted.sortedWith(
            compareBy({ rank.getValue(it.correctlyFirst) }, { rank.getValue(it.correctlySecond) })
        )
        return PairwiseScore(score = score, invertedPairs = ordered)
    }
}
