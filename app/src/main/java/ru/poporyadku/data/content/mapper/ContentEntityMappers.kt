package ru.poporyadku.data.content.mapper

import kotlinx.serialization.json.Json
import ru.poporyadku.data.content.dto.DailySetDto
import ru.poporyadku.data.content.dto.ManifestDto
import ru.poporyadku.data.content.dto.PuzzleDto
import ru.poporyadku.data.db.entity.DailySetEntity
import ru.poporyadku.data.db.entity.PuzzleEntity
import ru.poporyadku.data.db.json.StoredCard
import ru.poporyadku.data.db.json.StoredSource

/**
 * Asset-DTO → строки Room (ITERATION_4_DESIGN.md, §9.4).
 *
 * Три вещи, которые здесь происходят и нигде больше:
 *
 * * `packId` и `contentVersion` берутся ИЗ МАНИФЕСТА — в объекте головоломки их нет
 *   по формату (**I4-D15**);
 * * `category` и `sortDirection` сохраняются **токенами формата**, а не именами
 *   Kotlin-констант (**I4-D18**); перевод в перечисления — работа `PuzzleMapper`;
 * * `cards`/`sources` переходят в собственные типы хранения [StoredCard]/[StoredSource],
 *   а не в asset-DTO (**I4-D17**).
 *
 * Не переносятся намеренно: `volatility`, `verifiedAt`, `verifiedBy` (объект
 * головоломки) и `packTitle` (манифест) — потребителя нет, проверка в CI (**I4-D16**).
 *
 * @param json ДОЛЖЕН быть `@StorageJson`: колонку пишет форма хранения, и терпимость
 * к неизвестным ключам ей не нужна.
 */
fun PuzzleDto.toEntity(manifest: ManifestDto, json: Json): PuzzleEntity = PuzzleEntity(
    puzzleId = puzzleId,
    packId = manifest.packId,
    category = category,
    prompt = prompt,
    sortKey = sortKey,
    sortDirection = sortDirection,
    directionLabel = directionLabel,
    cardsJson = json.encodeToString(cards.map { it.toStored() }),
    // Тот же формат, что у PuzzleAttempt.submittedOrder: "c2,c1,c3,c4".
    correctOrder = correctOrder.joinToString(","),
    explanation = explanation,
    sourcesJson = json.encodeToString(sources.map { it.toStored() }),
    difficulty = difficulty,
    retiredIn = retiredIn,
    contentVersion = manifest.contentVersion,
)

/** Порядок головоломок в наборе — часть контракта: `puzzleIds[0..2] → puzzle_id_1..3`. */
fun DailySetDto.toEntity(packId: String): DailySetEntity = DailySetEntity(
    packId = packId,
    setIndex = setIndex,
    puzzleId1 = puzzleIds[0],
    puzzleId2 = puzzleIds[1],
    puzzleId3 = puzzleIds[2],
)

/** Порядок карточек сохраняется таким же, как в assets. */
private fun ru.poporyadku.data.content.dto.CardDto.toStored(): StoredCard = StoredCard(
    cardId = cardId,
    title = title,
    subtitle = subtitle,
    sortValue = sortValue,
    displayValue = displayValue,
    note = note,
    sourceIds = sourceIds,
    // Умолчание формата уже применено при разборе: в хранилище значение явное.
    disputed = disputed,
)

private fun ru.poporyadku.data.content.dto.SourceDto.toStored(): StoredSource = StoredSource(
    sourceId = sourceId,
    title = title,
    kind = kind,
    url = url,
    reference = reference,
    accessedAt = accessedAt,
    note = note,
)
