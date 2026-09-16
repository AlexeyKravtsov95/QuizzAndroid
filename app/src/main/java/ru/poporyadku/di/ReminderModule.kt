package ru.poporyadku.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.poporyadku.domain.reminder.NotificationAccess
import ru.poporyadku.domain.reminder.ReminderNotifier
import ru.poporyadku.domain.reminder.ReminderScheduler
import ru.poporyadku.notifications.AndroidNotificationAccess
import ru.poporyadku.notifications.AndroidReminderNotifier
import ru.poporyadku.notifications.ReminderResyncRequests
import ru.poporyadku.notifications.UniqueWorkOperations
import ru.poporyadku.notifications.WorkManagerOperations
import ru.poporyadku.notifications.WorkManagerReminderScheduler
import ru.poporyadku.notifications.WorkManagerResyncRequests

/**
 * Привязки напоминания (ITERATION_6_DESIGN.md, §9.1, I6-D24).
 *
 * Единственное место, где доменные интерфейсы `domain/reminder` встречаются со своими
 * Android-реализациями из `notifications`. Сам `domain/reminder` про `ru.poporyadku.di`
 * не знает: жизненный цикл процесса живёт только в `notifications`.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class ReminderModule {

    @Binds
    abstract fun reminderScheduler(impl: WorkManagerReminderScheduler): ReminderScheduler

    @Binds
    abstract fun reminderNotifier(impl: AndroidReminderNotifier): ReminderNotifier

    @Binds
    abstract fun notificationAccess(impl: AndroidNotificationAccess): NotificationAccess

    @Binds
    abstract fun uniqueWorkOperations(impl: WorkManagerOperations): UniqueWorkOperations

    @Binds
    abstract fun reminderResyncRequests(impl: WorkManagerResyncRequests): ReminderResyncRequests
}
