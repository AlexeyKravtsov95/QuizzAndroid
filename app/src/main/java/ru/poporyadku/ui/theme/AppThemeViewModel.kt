package ru.poporyadku.ui.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import ru.poporyadku.core.model.ThemeMode
import ru.poporyadku.domain.repository.UserPreferencesRepository

/**
 * Выбранная тема для корня приложения (ITERATION_5_DESIGN.md, §3.9, §4.8, I5-D16).
 *
 * `null` — DataStore ещё не эмитил: корень в это время не компонует экраны, поэтому
 * содержимое никогда не рисуется в чужой теме. `Eagerly` — чтение начинается сразу при
 * создании `MainActivity`, а не с первой подпиской. Изменения других настроек тему не
 * перевыдают (`distinctUntilChanged`).
 */
@HiltViewModel
class AppThemeViewModel @Inject constructor(
    preferences: UserPreferencesRepository,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode?> =
        preferences.preferences
            .map { it.themeMode }
            .distinctUntilChanged()
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                null,
            )
}
