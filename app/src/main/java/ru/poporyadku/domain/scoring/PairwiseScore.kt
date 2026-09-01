package ru.poporyadku.domain.scoring

/**
 * Результат одного перебора шести пар (ITERATION_3_DESIGN.md, I3-D4).
 *
 * [score] и [invertedPairs] — два выхода ОДНОГО прохода, поэтому
 * `score + invertedPairs.size == PairwiseScoreCalculator.MAX_PER_PUZZLE`
 * является свойством кода, а не соглашением между двумя алгоритмами.
 */
data class PairwiseScore(
    /** 0..[PairwiseScoreCalculator.MAX_PER_PUZZLE]. */
    val score: Int,
    /** Размер по построению равен `MAX_PER_PUZZLE - score`. */
    val invertedPairs: List<InvertedPair>,
)
