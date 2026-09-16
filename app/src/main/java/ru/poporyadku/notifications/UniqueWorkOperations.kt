package ru.poporyadku.notifications

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Узкая граница над операциями WorkManager (ITERATION_6_DESIGN.md, §9.3).
 *
 * Существует ради **надёжности**, а не ради абстракции: каждый метод возвращается только
 * после того, как WorkManager записал операцию в свою базу, и бросает, если запись не
 * удалась. Fire-and-forget «поставили и пошли дальше» здесь невозможен по типу — метод
 * `suspend`, и его нечем вызвать «в фон».
 *
 * Побочная польза — подмена фейком в Robolectric-тесте планировщика (`I6-P1`).
 */
internal interface UniqueWorkOperations {

    /** Поставить уникальную работу [name]. Возвращается после подтверждённой записи. */
    suspend fun enqueue(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest)

    /** Снять уникальную работу [name]. Возвращается после подтверждённой записи. */
    suspend fun cancel(name: String)

    /**
     * Идентификаторы работ цепочки [name], которые ещё не выполнялись
     * (`ENQUEUED`/`BLOCKED`). Чтение тоже ожидается: решение «не добавлять вторую
     * ожидающую работу» принимается по факту из базы, а не по догадке.
     */
    suspend fun pendingIds(name: String): List<UUID>
}

/**
 * Единственная реализация поверх настоящего [WorkManager].
 *
 * Экземпляр создаётся **лениво**, при первой операции: его инициализирует собственный
 * `InitializationProvider` WorkManager из итогового манифеста, а обращение к нему на
 * построении графа сломало бы Robolectric-тесты, где провайдера нет (§9.4).
 */
@Singleton
internal class WorkManagerOperations @Inject constructor(
    @ApplicationContext private val context: Context,
) : UniqueWorkOperations {

    private val workManager: WorkManager by lazy { WorkManager.getInstance(context.applicationContext) }

    override suspend fun enqueue(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest) {
        workManager.enqueueUniqueWork(name, policy, request).await()
    }

    override suspend fun cancel(name: String) {
        workManager.cancelUniqueWork(name).await()
    }

    /**
     * Чтение — через `Flow`-вариант WorkManager, а не через `ListenableFuture`:
     * `await()` из `work-runtime-ktx` определён только для `Operation`, и «подождать»
     * будущее пришлось бы самодельным мостом. `first()` даёт то же самое — текущее
     * состояние цепочки из базы WorkManager — штатным API.
     */
    override suspend fun pendingIds(name: String): List<UUID> =
        workManager.getWorkInfosForUniqueWorkFlow(name).first()
            .filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
            .map { it.id }
}
