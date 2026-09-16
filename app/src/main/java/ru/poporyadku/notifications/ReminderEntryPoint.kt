package ru.poporyadku.notifications

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import ru.poporyadku.domain.reminder.ReminderRun
import ru.poporyadku.domain.reminder.SyncReminderScheduleUseCase

/**
 * Граф для компонентов, которые создаёт система: worker'ов и приёмника
 * (ITERATION_6_DESIGN.md, §9.1, I6-D25).
 *
 * `androidx.hilt:hilt-work` не подключается: `EntryPointAccessors` — API уже
 * подключённого `hilt-android`, и ради двух worker'ов без параметров новая зависимость,
 * свой `WorkerFactory` и свой инициализатор WorkManager не нужны.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ReminderEntryPoint {

    fun reminderRun(): ReminderRun

    fun syncReminderSchedule(): SyncReminderScheduleUseCase

    fun broadcastHandler(): ReminderBroadcastHandler

    companion object {
        fun of(context: Context): ReminderEntryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ReminderEntryPoint::class.java,
        )
    }
}
