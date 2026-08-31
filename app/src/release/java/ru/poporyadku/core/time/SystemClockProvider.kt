package ru.poporyadku.core.time

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

// ITERATION_2_DESIGN.md, D-16. Единственная реализация ClockProvider/DateProvider в
// release: без методов записи, без изменяемого поля. Зона читается на каждом обращении,
// а не кэшируется, — иначе смена часового пояса устройства осталась бы невидимой до
// перезапуска процесса.
@Singleton
class SystemClockProvider @Inject constructor() : ClockProvider, DateProvider {

    override fun clock(): Clock = Clock.system(ZoneId.systemDefault())

    override fun today(): LocalDate = now().localDate
}
