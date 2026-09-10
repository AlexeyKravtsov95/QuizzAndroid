package ru.poporyadku.debug

import javax.inject.Inject
import ru.poporyadku.R
import ru.poporyadku.domain.model.TodayFailureKind
import ru.poporyadku.ui.home.HomeErrorRecoveryAction

/**
 * Мост «`Home.Error` → [ContentReset]» (ITERATION_3_DESIGN.md, I3-D47, I3-D48;
 * ITERATION_4_DESIGN.md, **I4-D20**, §11.3).
 *
 * Живёт в `src/debug` и в release не компилируется: набор действий там пуст, и
 * `HomeScreen` не рисует ничего дополнительного.
 *
 * Применимо **только** к [TodayFailureKind.ContentConflict]. При `Generic` сброс базы
 * к ошибке чтения отношения не имеет, а при `ContentUnusable` связь «пакет сломан →
 * сотрём ваш прогресс» ложна: прогресс ни при чём, помочь может только обновление
 * приложения. В обоих случаях дескриптор отфильтровывается.
 */
class ContentResetAction @Inject constructor(
    private val reset: ContentReset,
) : HomeErrorRecoveryAction {

    override val id: String = ACTION_ID

    override fun isApplicableTo(kind: TodayFailureKind): Boolean =
        kind == TodayFailureKind.ContentConflict

    override val labelRes: Int = R.string.debug_content_reset

    /** Текст говорит ровно то, что делает действие: стирается весь прогресс. */
    override val confirmationRes: Int = R.string.debug_content_reset_message

    override suspend fun perform() {
        reset.perform()
    }

    companion object {
        /** Стабильный идентификатор: он же едет в `HomeEvent.RecoveryConfirmed`. */
        const val ACTION_ID = "content_reset"
    }
}
