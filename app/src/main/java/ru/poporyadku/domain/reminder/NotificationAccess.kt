package ru.poporyadku.domain.reminder

/**
 * Может ли приложение показать уведомление (ITERATION_6_DESIGN.md, §8.2, I6-D36).
 *
 * Доменный статус вместо `Boolean`: причин отказа три, и каждая ведёт пользователя
 * своим путём — runtime-запрос, настройки приложения, настройки канала. С булевым
 * значением экран не смог бы предложить ничего, кроме бесполезного повторного запроса.
 *
 * Отдельного значения «отказано навсегда» нет намеренно: система не даёт его честно
 * отличить, а решение после любого исхода принимается **перечитанным** статусом
 * (I6-D36).
 */
enum class NotificationAvailability {
    /** Показ разрешён. */
    Allowed,

    /**
     * API 33+ и `POST_NOTIFICATIONS` не выдано. На API 26–32 невозможен по построению:
     * runtime-разрешения на уведомления там нет.
     */
    RuntimePermissionMissing,

    /** Уведомления приложения выключены целиком — runtime-запрос бесполезен. */
    AppNotificationsDisabled,

    /** Канал `daily_reminder` существует и заглушён (`IMPORTANCE_NONE`). */
    ChannelDisabled,
}

/** Какой системный экран открывать пользователю (ITERATION_6_DESIGN.md, §8.2). */
enum class NotificationSettingsTarget { App, Channel }

/**
 * Идентификатор канала напоминания (ITERATION_6_DESIGN.md, §10.1, I6-D34).
 *
 * Живёт в домене, а не в `notifications`, потому что его знают двое: реализация канала и
 * граница внешних действий `ui/platform`, открывающая системные настройки **этого**
 * канала. Иначе `ui` пришлось бы импортировать `notifications` вопреки правилу
 * зависимостей (§9.1), а дублирование строки в двух пакетах молча разошлось бы.
 *
 * Стабилен навсегда: переименование создаёт новый канал и теряет выбор пользователя.
 */
const val REMINDER_CHANNEL_ID: String = "daily_reminder"

/**
 * Куда ведёт действие «Открыть настройки уведомлений»; `null` — вести некуда, доступ
 * уже есть. `RuntimePermissionMissing` ведёт в настройки приложения: после отказа в
 * системном диалоге разрешение выдаётся только там.
 */
val NotificationAvailability.settingsTarget: NotificationSettingsTarget?
    get() = when (this) {
        NotificationAvailability.Allowed -> null
        NotificationAvailability.RuntimePermissionMissing,
        NotificationAvailability.AppNotificationsDisabled,
        -> NotificationSettingsTarget.App
        NotificationAvailability.ChannelDisabled -> NotificationSettingsTarget.Channel
    }

/**
 * Статус доступа к уведомлениям. Реализация — `notifications/AndroidNotificationAccess`.
 *
 * Не `suspend` и не бросает: это быстрое чтение системного состояния, которое вызывают
 * и из `ON_START` экрана, и из worker'а перед показом.
 */
interface NotificationAccess {
    fun availability(): NotificationAvailability
}
