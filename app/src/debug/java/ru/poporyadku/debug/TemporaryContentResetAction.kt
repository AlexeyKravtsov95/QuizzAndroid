package ru.poporyadku.debug

import javax.inject.Inject
import ru.poporyadku.R
import ru.poporyadku.domain.model.TodayFailureKind
import ru.poporyadku.ui.home.HomeErrorRecoveryAction

/**
 * Мост «`Home.Error` → [TemporaryContentReset]» (ITERATION_3_DESIGN.md, I3-D47, I3-D48).
 *
 * Живёт в `src/debug` и в release не компилируется: набор действий там пуст, и
 * `HomeScreen` не рисует ничего дополнительного.
 *
 * Применимо **только** к [TodayFailureKind.ContentConflict]: сброс базы к обычной
 * ошибке чтения отношения не имеет, и при `Generic` дескриптор отфильтровывается.
 */
class TemporaryContentResetAction @Inject constructor(
    private val reset: TemporaryContentReset,
) : HomeErrorRecoveryAction {

    override val id: String = ACTION_ID

    override fun isApplicableTo(kind: TodayFailureKind): Boolean =
        kind == TodayFailureKind.ContentConflict

    override val labelRes: Int = R.string.debug_reset_temporary_content

    /** Текст говорит ровно то, что делает действие: стирается весь прогресс. */
    override val confirmationRes: Int = R.string.debug_reset_temporary_content_message

    override suspend fun perform() {
        reset.perform()
    }

    companion object {
        /** Стабильный идентификатор: он же едет в `HomeEvent.RecoveryConfirmed`. */
        const val ACTION_ID = "temporary_content_reset"
    }
}
