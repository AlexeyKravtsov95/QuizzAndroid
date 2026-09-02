package ru.poporyadku.ui.home

import androidx.annotation.StringRes
import ru.poporyadku.domain.model.TodayFailureKind

/**
 * Действие восстановления после отказа расчёта Home (ITERATION_3_DESIGN.md, I3-D47).
 *
 * Интерфейс живёт в `src/main`, вклады — только в `src/debug`: в release-графе набор
 * пуст (`@Multibinds` без `@IntoSet`), и никакой заглушки и ни одного nullable-binding
 * для этого не требуется.
 *
 * Реализация исполняется **во ViewModel**, а не на экране: в [HomeState] уезжает
 * только [RecoveryActionUi].
 */
interface HomeErrorRecoveryAction {

    /** Стабильный идентификатор; он же едет в [HomeEvent.RecoveryConfirmed]. */
    val id: String

    /** Показывать ли действие при этой причине отказа. */
    fun isApplicableTo(kind: TodayFailureKind): Boolean

    /** Заголовок кнопки. */
    @get:StringRes
    val labelRes: Int

    /** Текст подтверждения в диалоге. */
    @get:StringRes
    val confirmationRes: Int

    /**
     * Необратимое действие. Вызывается ровно один раз на принятое подтверждение;
     * отмена скоупа обязана остаться отменой — [kotlinx.coroutines.CancellationException]
     * из реализации не глотается.
     */
    suspend fun perform()
}
