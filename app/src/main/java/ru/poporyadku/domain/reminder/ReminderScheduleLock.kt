package ru.poporyadku.domain.reminder

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex

/**
 * Один замок на все записи планировщика в процессе (ITERATION_6_DESIGN.md, §9.3, §9.4,
 * I6-D26, I6-D29).
 *
 * Синхронизация настроек, перепланирование из worker'а и запасной путь приёмника пишут
 * одну и ту же уникальную работу. Под общим `Mutex` они выполняются последовательно,
 * поэтому worker видит работу, которую уже поставила синхронизация, и не добавляет в
 * цепочку вторую ожидающую (`ScheduleMode.AfterCurrent`).
 *
 * `javax.inject` — не Android: доменный слой остаётся чистым Kotlin.
 */
@Singleton
class ReminderScheduleLock @Inject constructor() {
    val mutex: Mutex = Mutex()
}
