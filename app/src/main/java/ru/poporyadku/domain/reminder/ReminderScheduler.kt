package ru.poporyadku.domain.reminder

import java.util.UUID

/**
 * Как ставится работа напоминания (ITERATION_6_DESIGN.md, §9.3, I6-D26).
 */
sealed interface ScheduleMode {

    /**
     * Обычная синхронизация: новые настройки важнее уже поставленной работы —
     * ожидающая и выполняющаяся заменяются.
     */
    data object Replace : ScheduleMode

    /**
     * Перепланирование из выполняющегося worker'а: следующая работа ставится **после**
     * текущей и не отменяет её. Если в цепочке уже есть ожидающая работа, отличная от
     * [workId], её поставила синхронизация с более свежими настройками — второй
     * ожидающей работы не появляется.
     */
    data class AfterCurrent(val workId: UUID) : ScheduleMode
}

/**
 * Планировщик напоминания (ITERATION_6_DESIGN.md, §9.1, §9.3, I6-D24).
 *
 * Доменная граница над планировщиком работ: `domain` не знает ни его пакета, ни
 * `android.*`. Единственная реализация — `notifications/WorkManagerReminderScheduler`.
 *
 * **Обе операции возвращаются только после подтверждённой записи** планировщика (у
 * реализации — `Operation.await()`), а при отказе записи бросают. Fire-and-forget здесь
 * запрещён: «поставили и не знаем» ничем не отличается от «не поставили».
 */
interface ReminderScheduler {

    /** Поставить работу на [trigger]. Возвращается после записи операции. */
    suspend fun schedule(trigger: ReminderTrigger, mode: ScheduleMode)

    /** Снять работу напоминания. Возвращается после записи операции. */
    suspend fun cancel()
}
