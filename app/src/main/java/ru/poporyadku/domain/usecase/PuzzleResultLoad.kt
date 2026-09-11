package ru.poporyadku.domain.usecase

import ru.poporyadku.core.model.Puzzle
import ru.poporyadku.core.model.PuzzleAttempt
import ru.poporyadku.domain.scoring.PairwiseScore

/**
 * Итог восстановления экрана результата (ITERATION_3_DESIGN.md, I3-D49).
 *
 * ДОМЕННЫЙ тип: экранная модель результата живёт рядом со своим экраном, и возврат её
 * отсюда развернул бы стрелку зависимостей ровно так же, как импорт состояния Home.
 */
sealed interface PuzzleResultLoad {

    /** Есть что показать: ответ с непустым порядком. */
    data class Content(
        val slotIndex: Int,
        val puzzle: Puzzle,
        val attempt: PuzzleAttempt,
        val scored: PairwiseScore,
        /**
         * Головоломка отозвана относительно установленной версии контента
         * (ITERATION_5_DESIGN.md, §3.8, I5-D11). Результат при этом полный: пометку
         * показывает экран, а не use case.
         */
        val isRetired: Boolean,
    ) : PuzzleResultLoad

    /** Слот закрыт пропуском: показывать нечего, экран обязан перенаправить (I3-D45). */
    data class Skipped(val slotIndex: Int) : PuzzleResultLoad

    /**
     * Попытки нет: слот ещё не сыгран. Сессионный экран возвращается на Puzzle,
     * архивный — назад к итогу: игру прошлого дня архив не запускает (I5-D10).
     */
    data class NoAttempt(val slotIndex: Int) : PuzzleResultLoad

    data class Failure(val kind: PuzzleErrorKind) : PuzzleResultLoad
}
