package ru.poporyadku.ui.recap

import java.time.LocalDate
import ru.poporyadku.core.model.Category

/** Заголовок экрана: «Сегодня» либо дата этого дня (COMPONENTS.md, `AppTopBar`). */
sealed interface DayRecapTitle {
    data object Today : DayRecapTitle
    data class Date(val localDate: LocalDate) : DayRecapTitle
}

/**
 * Строка итога в экранной модели (ITERATION_3_DESIGN.md, I3-D37).
 *
 * Оба варианта существуют уже в PR 3C: `SlotOutcome` — sealed-тип, и экран обязан
 * обработать их оба в момент мержа. Фактический [score] сохраняется в обоих —
 * [Unavailable] не равно «ноль».
 */
sealed interface SlotResultUi {
    val slotIndex: Int

    /** 0..6. */
    val score: Int

    /** Головоломка доступна: показывается `CategoryLabel`. */
    data class Played(
        override val slotIndex: Int,
        override val score: Int,
        val category: Category,
    ) : SlotResultUi

    /** Показывать нечем: вместо категории — «Задание N», счёт из данных. */
    data class Unavailable(
        override val slotIndex: Int,
        override val score: Int,
    ) : SlotResultUi
}

/** Экранная модель итога дня (ITERATION_3_DESIGN.md, раздел 13). */
sealed interface DayRecapState {

    data object Loading : DayRecapState

    data class Content(
        val title: DayRecapTitle,
        /** «N из 18». */
        val totalScore: Int,
        val slots: List<SlotResultUi>,
        val currentStreak: Int,
        val bestStreak: Int,
        /** «Этот день установил рекорд» — свойство дня, а не момента (I3-D46). */
        val isRecordUpdated: Boolean,
    ) : DayRecapState

    /** `Error.recapMissing`: «Данные за этот день не сохранились», без кнопки. */
    data object NotFound : DayRecapState
}
