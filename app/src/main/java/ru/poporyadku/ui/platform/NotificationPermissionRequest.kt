package ru.poporyadku.ui.platform

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Запуск системного запроса `POST_NOTIFICATIONS` (ITERATION_6_DESIGN.md, §8.2, I6-D36).
 *
 * Граница между route-контейнером и `ActivityResultRegistry`. Живёт в `ui/platform`, а
 * не во ViewModel: запуск системного диалога — Android-действие, и ViewModel про него
 * знать не должна.
 *
 * **Булев результат callback'а намеренно не передаётся наружу.** Он отвечает только на
 * вопрос «что нажали в диалоге» и молчит о выключенных уведомлениях приложения и о
 * заглушённом канале: callback `true` вполне сочетается с полной невозможностью показа.
 * Поэтому решение всегда принимается перечитанным `NotificationAccess.availability()`, а
 * этот обратный вызов — только сигнал «система ответила, статус можно перечитывать».
 *
 * Результат доставляется и **после пересоздания процесса**: регистрация живёт в
 * `ActivityResultRegistry`, а не в отменённой корутине.
 *
 * На API 26–32 запуск не нужен: runtime-разрешения на уведомления там нет, а
 * `RuntimePermissionMissing` по построению не возникает — вызов остаётся безопасной
 * пустой операцией.
 *
 * @param onResult вызывается после ответа системы; аргументов нет по причине выше.
 * @return функция запуска запроса.
 */
@Composable
fun rememberNotificationPermissionRequest(onResult: () -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ -> onResult() }

    return remember(launcher, onResult) {
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // Запрашивать нечего: статус перечитывается тем же путём, что после
                // настоящего диалога, и экран не зависает в ожидании ответа.
                onResult()
            }
        }
    }
}
