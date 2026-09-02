package ru.poporyadku.ui.home

import androidx.annotation.StringRes

/**
 * UI-дескриптор действия восстановления (ITERATION_3_DESIGN.md, I3-D47).
 *
 * Всё, что нужно, чтобы нарисовать кнопку и диалог, — и ничего сверх того. Ни одной
 * `suspend`-функции и ни одной ссылки на реализацию: иначе экран мог бы запустить
 * доменное действие мимо ViewModel, а [HomeState] перестал бы сравниваться по значению.
 */
data class RecoveryActionUi(
    /** Стабильный идентификатор; он же едет в [HomeEvent.RecoveryConfirmed]. */
    val id: String,
    @StringRes val labelRes: Int,
    @StringRes val confirmationRes: Int,
)
