package ru.poporyadku.domain.usecase

/** Итог приёма ответа или пропуска (ITERATION_3_DESIGN.md, §9). */
sealed interface SubmitResult {

    /** Попытка записана этим вызовом. [kind] выведен из того, что реально сохранено. */
    data class Recorded(val slotIndex: Int, val score: Int, val kind: AttemptKind) : SubmitResult

    /**
     * Попытка по этому слоту уже была (повтор или проигранная гонка): не перезаписана.
     * [kind] — вид ПОБЕДИВШЕЙ записи, прочитанной из базы, а не того, что отправляли мы
     * (I3-D45).
     */
    data class AlreadyClosed(val slotIndex: Int, val kind: AttemptKind) : SubmitResult

    data class Failure(val kind: PuzzleErrorKind) : SubmitResult
}
