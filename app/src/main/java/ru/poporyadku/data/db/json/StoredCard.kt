package ru.poporyadku.data.db.json

import kotlinx.serialization.Serializable

/**
 * Форма JSON-колонки `puzzles.cards_json` (ITERATION_4_DESIGN.md, **I4-D17**).
 *
 * Это НЕ asset-DTO. Отдельный тип нужен потому, что отозванные головоломки остаются
 * в `puzzles` навсегда ради архива (`CONTENT_MODEL.md` §7): вырасти формату ассетов —
 * и их payload остался бы в старой форме, а архив за прошлые дни перестал бы
 * открываться. Форма хранения обязана меняться отдельно от формата поставки.
 *
 * **Правило, вытекающее отсюда:** эта форма — часть контракта схемы Room, хотя SQLite
 * видит там просто `TEXT`. Её изменение требует настоящей миграции, переписывающей
 * колонку, а не «просто добавим поле в DTO».
 *
 * `disputed` хранится ЯВНО: умолчание формата применяется при разборе ассетов,
 * в хранилище значение всегда записано.
 */
@Serializable
data class StoredCard(
    val cardId: String,
    val title: String,
    val subtitle: String? = null,
    val sortValue: Double,
    val displayValue: String,
    val note: String? = null,
    val sourceIds: List<String>,
    val disputed: Boolean,
)
