package ru.poporyadku.notifications

/**
 * Имена уникальных работ и ключи их данных (ITERATION_6_DESIGN.md, §9.3, I6-D26, I6-D27).
 *
 * Имена стабильны навсегда: переименование оставило бы у пользователей работу под старым
 * именем, которую никто больше не заменяет и не отменяет.
 */
internal object ReminderWorkNames {

    /** Работа самого напоминания. Одна на процесс и на устройство — дублей не бывает. */
    const val DAILY_REMINDER = "daily_reminder"

    /** Долговечное продолжение события смены времени или часового пояса. */
    const val REMINDER_RESYNC = "reminder_resync"

    /** Тег работы напоминания — для диагностики и ручных проверок. */
    const val TAG_REMINDER = "reminder"

    /** ISO `yyyy-MM-dd`. */
    const val KEY_TARGET_DATE = "targetDate"

    /** `Int` 0..1439. */
    const val KEY_MINUTE_OF_DAY = "minuteOfDay"
}
