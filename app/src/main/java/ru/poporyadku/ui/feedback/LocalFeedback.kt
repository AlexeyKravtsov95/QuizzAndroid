package ru.poporyadku.ui.feedback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

/**
 * Звуки, предоставленные экранам (ITERATION_5_DESIGN.md, §8.2).
 *
 * Продукт: `MainActivity` отдаёт свой `@ActivityRetainedScoped` `SoundCues` — один на
 * Activity, переживающий поворот. Значение по умолчанию — молчание: композиция без
 * провайдера (превью, Compose-тест экрана, навигационный тест на собственном
 * `setContent`) не обязана поднимать звуковой пул, и её отсутствие не должно ронять
 * экран.
 */
val LocalSoundCues = staticCompositionLocalOf<SoundCuePlayer> { SoundCuePlayer { } }

/**
 * Тестовая замена исполнителя целиком (`I5-N8`): навигационные и Compose-тесты
 * подставляют `RecordingFeedbackPlayer` и проверяют вызовы, не трогая ни настоящий звук,
 * ни тактильную отдачу.
 */
val LocalFeedbackPlayerOverride = staticCompositionLocalOf<FeedbackPlayer?> { null }

/**
 * Исполнитель отдачи для route-контейнера: подставленный тестом либо
 * [AndroidFeedbackPlayer] поверх предоставленных звуков и `View` текущего экрана.
 */
@Composable
fun rememberFeedbackPlayer(): FeedbackPlayer {
    val override = LocalFeedbackPlayerOverride.current
    val sounds = LocalSoundCues.current
    val view = LocalView.current
    return remember(override, sounds, view) {
        override ?: AndroidFeedbackPlayer(sounds, view)
    }
}
