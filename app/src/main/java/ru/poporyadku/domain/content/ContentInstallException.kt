package ru.poporyadku.domain.content

import java.io.IOException
import java.time.LocalDate

/**
 * Причина отказа установки — ДОМЕННЫЙ тип (ITERATION_3_DESIGN.md, I3-D47;
 * ITERATION_4_DESIGN.md, **I4-D19**).
 *
 * Лежит рядом с [ContentInstaller], поэтому domain не импортирует ничего из data,
 * а use case классифицирует отказ исчерпывающим `when`, не зная про Room.
 *
 * **Ровно четыре варианта, и закрытость не украшение:** появление пятой причины обязано
 * ломать компиляцию `GetTodayStateUseCase`, а не молча уезжать в `Generic`.
 *
 * Варианта «отказ базы» здесь нет намеренно. `ContentImporter` не содержит ни одного
 * `catch`, поэтому обернуть `SQLiteException` он не может, не нарушив собственного
 * правила; вариант, который никто не бросает, был бы мёртвым кодом. Исключения Room
 * летят наружу как есть, попадают в общий `catch (Exception)` use case и дают
 * `TodayFailureKind.Generic` — повторить имеет смысл, разрушительных действий не
 * предлагается.
 */
sealed class ContentInstallException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /**
     * Локальные назначения не позволяют установить пакет. **Ни одна таблица не изменена
     * и DataStore не тронут**: исключение бросается внутри `withTransaction`.
     *
     * @param staleSetIndexes назначения на индексы вне диапазона пакета (свидетельство A).
     * @param changedSetIndexes назначенные наборы, чей состав разошёлся с пакетом, —
     * сохранённая тройка `daily_sets` (B) или фактически сыгранный `puzzleId` (C),
     * за вычетом допустимых послотовых замен отозванных головоломок (**I4-D4**).
     * @param blockedDates даты, на которых стоит прогресс пользователя.
     */
    class Conflict(
        val packId: String,
        val staleSetIndexes: List<Int>,
        val changedSetIndexes: List<Int>,
        val blockedDates: List<LocalDate>,
    ) : ContentInstallException(
        "наборы пакета $packId заняты назначениями $blockedDates: " +
            "вне диапазона $staleSetIndexes, с изменившимся составом $changedSetIndexes"
    )

    /**
     * Пакет внутри APK не соответствует контракту: форма, целостность, счётчики, ссылки.
     * Прогресс пользователя ни при чём, поэтому очистка базы для этой причины
     * не предлагается.
     *
     * @param code стабильный код ПЕРВОГО нарушения — та же строка, что у CLI.
     * @param violations общее число нарушений: «нарушений 7, первое — R19» отличает
     * опечатку от подложенного пакета.
     */
    class BundleInvalid(
        val code: String,
        val violations: Int,
        detail: String,
    ) : ContentInstallException(
        "пакет непригоден: $code (нарушений $violations) — $detail"
    )

    /** Формат пакета новее, чем понимает это приложение (`CONTENT_MODEL.md` §7). */
    class UnsupportedSchema(
        val manifest: Int,
        val supported: Int,
    ) : ContentInstallException(
        "schemaVersion пакета $manifest больше поддерживаемой $supported"
    )

    /** Ассет не прочитался: ввод-вывод, а не содержимое. Повтор имеет смысл. */
    class AssetUnreadable(
        val fileName: String,
        cause: IOException,
    ) : ContentInstallException("ассет '$fileName' не прочитался", cause)
}
