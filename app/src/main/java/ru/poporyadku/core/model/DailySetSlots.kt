package ru.poporyadku.core.model

// ITERATION_3_DESIGN.md, I3-D33 / §8: единое имя slotIndex во всех слоях и единственное
// место, где отображение «слот 0,1,2 → плоское поле puzzleId1..3» существует. Прямое
// обращение к puzzleId1/2/3 из use case запрещено: три копии этого when разъедутся.

/** Слотов в дне ровно три (ARCHITECTURE.md, §3, таблица `daily_sets`). */
const val SLOTS_PER_DAY = 3

/**
 * Головоломка набора по слоту.
 *
 * @throws IllegalArgumentException если [slotIndex] вне 0..2 — это дефект вызывающего
 * кода, а не доменный случай: диапазон слота проверяется раньше и типизированно.
 */
fun DailySet.puzzleIdAt(slotIndex: Int): String {
    require(slotIndex in 0 until SLOTS_PER_DAY) { "slotIndex вне 0..${SLOTS_PER_DAY - 1}: $slotIndex" }
    return when (slotIndex) {
        0 -> puzzleId1
        1 -> puzzleId2
        else -> puzzleId3
    }
}
