package ru.poporyadku.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Приёмник смены часового пояса и ручного перевода часов (ITERATION_6_DESIGN.md, §9.5,
 * I6-D30).
 *
 * Максимально тонкий: единственная его работа — продлить жизнь процесса через
 * `goAsync()` и отдать событие [ReminderBroadcastHandler]. Ни расчётов, ни обращений к
 * WorkManager здесь нет.
 *
 * `finish()` вызывает обработчик — **только после долговечного результата**. Отменяющего
 * таймаута нет: событие, «обработанное» без записи, было бы потеряно вместе с процессом.
 *
 * Оба действия входят в список исключений из ограничения неявных broadcast, поэтому
 * приёмник из манифеста получает их и при незапущенном приложении; `MainActivity` для
 * этого не нужна.
 */
class ReminderTimeChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        ReminderEntryPoint.of(context)
            .broadcastHandler()
            .handle(intent.action, onFinished = pending::finish)
    }
}
