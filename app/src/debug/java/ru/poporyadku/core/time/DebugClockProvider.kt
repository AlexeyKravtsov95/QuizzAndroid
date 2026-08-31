package ru.poporyadku.core.time

import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

// ITERATION_2_DESIGN.md, D-16. null в AtomicReference означает «не зафиксировано»:
// время идёт системное и динамическое. Этот класс существует только в debug-варианте —
// в release-сборке произвольную дату задать физически нечем.
@Singleton
class DebugClockProvider @Inject constructor() : ClockProvider, DateProvider {

    /** null — «часы не зафиксированы»: время идёт системное и динамическое. */
    private val fixed = AtomicReference<Clock?>(null)

    override fun clock(): Clock = fixed.get() ?: Clock.system(ZoneId.systemDefault())

    override fun today(): LocalDate = now().localDate

    /** Фиксирует выбранную дату в выбранной зоне.
     *  LocalDate.ofInstant(Instant, ZoneId) требует API 34 без desugaring
     *  (minSdk проекта — 26); instant.atZone(zone).toLocalDate() даёт то же
     *  значение и доступен с API 26. */
    fun setDate(date: LocalDate, zone: ZoneId = clock().zone) {
        val instant = date.atTime(LocalTime.NOON).atZone(zone).toInstant()
        val roundTrip = instant.atZone(zone).toLocalDate()
        require(roundTrip == date) {
            "дата не пережила преобразование: выбрана $date, получена $roundTrip в зоне $zone"
        }
        fixed.set(Clock.fixed(instant, zone))
    }

    /** Снимает фиксацию: часы снова системные и динамические. */
    fun reset() = fixed.set(null)
}
