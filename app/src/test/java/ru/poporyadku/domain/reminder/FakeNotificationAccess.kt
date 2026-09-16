package ru.poporyadku.domain.reminder

/**
 * Управляемый статус доступа к уведомлениям (ITERATION_6_DESIGN.md, §8.2).
 *
 * Подставляется во `SettingsViewModel`, `ReminderPromptViewModel` и
 * `EvaluateReminderUseCase` вместо Android-реализации. Считает обращения: правило
 * «решение принимается **перечитанным** статусом, а не булевым результатом callback'а»
 * проверяется в том числе тем, что перечитывание вообще произошло.
 */
internal class FakeNotificationAccess(
    var value: NotificationAvailability = NotificationAvailability.Allowed,
) : NotificationAccess {

    /** Сколько раз статус перечитывали. */
    var reads: Int = 0
        private set

    override fun availability(): NotificationAvailability {
        reads++
        return value
    }
}
