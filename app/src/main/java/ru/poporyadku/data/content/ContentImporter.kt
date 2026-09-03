package ru.poporyadku.data.content

import androidx.room.withTransaction
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import ru.poporyadku.core.model.SLOTS_PER_DAY
import ru.poporyadku.data.content.dto.PackHeader
import ru.poporyadku.data.content.dto.ParsedPack
import ru.poporyadku.data.content.mapper.toEntity
import ru.poporyadku.data.content.validation.ContentValidator
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.data.db.dao.AssignmentDao
import ru.poporyadku.data.db.dao.DailySetDao
import ru.poporyadku.data.db.dao.PuzzleDao
import ru.poporyadku.di.ActivePack
import ru.poporyadku.di.StorageJson
import ru.poporyadku.domain.content.ContentInstallException
import ru.poporyadku.domain.content.ContentInstaller
import ru.poporyadku.domain.repository.UserPreferencesRepository

/**
 * Импорт пакета из `assets` в Room (ITERATION_4_DESIGN.md, §10).
 *
 * В продуктовый граф НЕ привязан: до PR 4D `ContentModule` связывает
 * `TemporaryContentInstaller`, и этот класс вызывается только тестами.
 *
 * **Форма кода — часть гарантий, а не стиль:**
 *
 * * весь метод — тело одного `mutex.withLock`; `@Singleton` обязателен, иначе Hilt
 *   создавал бы экземпляр на инъекцию и мьютекс перестал бы что-либо сериализовать;
 * * `header` — кэш СОДЕРЖИМОГО АССЕТОВ, а не вывода о базе (**I4-D10**). Ассеты
 *   неизменны в пределах жизни процесса: они лежат внутри APK, а обновление APK
 *   убивает процесс. На вопрос «установлено ли» отвечает база, и отвечает на КАЖДОМ
 *   вызове, и ни одного поля с выводом о её состоянии в классе нет (**I3-D41**);
 * * ровно ДВЕ точки `db.withTransaction`: подтверждение быстрого пути (read-only)
 *   и запись; конфликт бросается ВНУТРИ второй, поэтому откат гарантирован формой кода;
 * * исключения здесь только бросаются и никогда не перехватываются: перехват означал бы
 *   решение о чужом отказе, принятое там, где о нём ничего не известно.
 *   `withTransaction` откатывает Room при отмене, а `withContext(NonCancellable)`
 *   шага 6 отмену не глотает — он лишь не даёт прерваться уже начатой записи отметки.
 */
@Singleton
class ContentImporter @Inject constructor(
    private val db: AppDatabase,
    private val puzzleDao: PuzzleDao,
    private val setDao: DailySetDao,
    private val assignmentDao: AssignmentDao,
    private val reader: ContentPackReader,
    private val validator: ContentValidator,
    private val prefs: UserPreferencesRepository,
    @StorageJson private val storageJson: Json,
    @ActivePack private val activePackId: String,
) : ContentInstaller {

    /** Сериализует вызовы внутри процесса. */
    private val mutex = Mutex()

    /** КЭШ АССЕТОВ, не вывода о базе (**I4-D10**). */
    private var header: PackHeader? = null

    override suspend fun ensureInstalled() = mutex.withLock {

        // ── 1. Заголовок: manifest.json → daily-sets-*.json. Один раз на процесс (§4.7).
        val head = header ?: reader.readHeader(activePackId).also { header = it }

        // ── 2. Отметка читается ВНУТРИ lock: иначе два вызова могли бы принять решение
        //      по разным снимкам DataStore.
        val stored = prefs.preferences.first()
        val sameContent = stored.storedContentVersion == head.manifest.contentVersion &&
            stored.storedContentFingerprint == head.fingerprint

        // ── 3. ЕДИНСТВЕННЫЙ ранний выход — и только после подтверждения БАЗОЙ (§10.4).
        //      Совпадения отметки недостаточно: полная очистка базы отладочным
        //      действием опустошает Room и не трогает DataStore.
        if (sameContent && db.withTransaction { isInstalled(head) }) return@withLock

        // ── 4. Тело пакета читается ТОЛЬКО здесь. Разбор, валидация и маппинг — ВНЕ
        //      транзакции записи: сериализация карточных списков это десятки
        //      миллисекунд чистого CPU, и держать на них блокировку записи базы незачем.
        val pack = reader.readBody(head)
        validator.validate(pack)
        val puzzleRows = pack.puzzles.map { it.toEntity(pack.manifest, storageJson) }
        val setRows = pack.sets.map { it.toEntity(pack.manifest.packId) }

        // ── 5. Одна транзакция: обнаружение конфликта и запись.
        db.withTransaction {
            val conflict = detectConflict(pack)
            if (conflict != null) throw conflict

            puzzleDao.upsertAll(puzzleRows)                          // НИ ОДНОГО DELETE
            setDao.deleteOutsideRange(activePackId, pack.setCount)   // единственный DELETE
            setDao.upsertAll(setRows)
        }

        // ── 6. DataStore — ТОЛЬКО после успешного commit. NonCancellable сужает окно
        //      «база записана, отметки нет» до смерти процесса; корректность при этом
        //      держится не на нём, а на идемпотентности повтора (§10.6).
        withContext(NonCancellable) {
            prefs.setInstalledContent(head.manifest.contentVersion, head.fingerprint)
        }
    }

    /**
     * Дешёвое подтверждение состояния базы — четыре предиката, и выполняться они
     * обязаны ОДНОВРЕМЕННО (§10.3). Любое «нет» — полный импорт.
     *
     * Три запроса от размера истории не зависят. Третий зависит, но выполняется ПОСЛЕ
     * второго, а тот уже гарантировал отсутствие назначений вне диапазона: назначений
     * в пакете не больше `setCount`, на дату приходится не более трёх попыток, поэтому
     * соединение ограничено сверху `3 × setCount` независимо от того, сколько лет
     * человек играет.
     */
    private suspend fun isInstalled(head: PackHeader): Boolean {
        // (1) ожидаемые индексы И точный состав каждого ожидаемого набора
        if (setDao.byPack(activePackId) != head.expectedSetRows) return false

        // (2) нет назначений вне диапазона пакета
        if (assignmentDao.countOutsideRange(activePackId, head.setCount) > 0) return false

        // (3) нет БЛОКИРУЮЩИХ расхождений между сыгранными puzzleId и составом слота.
        //     Легальная историческая замена отозванной расхождением не считается (§10.3.1).
        if (assignmentDao.countBlockingPlayedPuzzleMismatches(
                activePackId,
                head.manifest.contentVersion,
            ) > 0
        ) {
            return false
        }

        // (4) головоломки текущих наборов существуют, имеют текущую версию И АКТИВНЫ
        return setDao.countSetsWithMissingPuzzles(
            activePackId,
            head.manifest.contentVersion,
        ) == 0
    }

    /**
     * Полная диагностика конфликта (§3.3). Выполняется только на пути импорта и только
     * внутри транзакции записи, поэтому её результат нельзя «увидеть и не откатить».
     *
     * Проверяются три независимых свидетельства: A — назначения вне диапазона;
     * B — сохранённый состав назначенного набора; C — фактически сыгранные головоломки.
     */
    private suspend fun detectConflict(pack: ParsedPack): ContentInstallException.Conflict? {
        val stale = assignmentDao.setIndexesOutsideRange(activePackId, pack.setCount)
        val assigned = assignmentDao.assignedSets(activePackId)
        val storedSets = setDao.byPack(activePackId).associateBy { it.setIndex }
        val played = assignmentDao.playedPuzzles(activePackId)

        val changed = sortedSetOf<Int>()

        // B: сохранённый состав. Висячее назначение внутри диапазона, у которого строки
        // daily_sets нет, конфликтом НЕ является: доказательства иного состава не
        // существует, и осмысленное действие ровно одно — записать корректный состав.
        for (row in assigned) {
            val expected = pack.setAt(row.setIndex) ?: continue // вне диапазона — уже в stale
            val storedRow = storedSets[row.setIndex] ?: continue
            val storedIds = listOf(storedRow.puzzleId1, storedRow.puzzleId2, storedRow.puzzleId3)
            if (!isRetirementReplacement(pack, storedIds, expected.puzzleIds)) {
                changed += row.setIndex
            }
        }

        // C: фактически сыгранные головоломки. Сравнение ПОСЛОТОВОЕ: попытка записана
        // как (local_date, slot_index, puzzle_id), и головоломка, переехавшая из слота 0
        // в слот 1, для пользователя не «та же самая на месте».
        for (attempt in played) {
            val expected = pack.setAt(attempt.setIndex) ?: continue
            // Слот вне 0..2 штатно возникнуть не может (UNIQUE(local_date, slot_index)
            // и SLOTS_PER_DAY = 3), но индексировать список по нему нельзя: сравнивать
            // не с чем, и это конфликт, а не IndexOutOfBoundsException.
            if (attempt.slotIndex !in 0 until SLOTS_PER_DAY) {
                changed += attempt.setIndex
                continue
            }
            val expectedId = expected.puzzleIds[attempt.slotIndex]
            if (attempt.puzzleId != expectedId &&
                !isRetirementSlot(pack, attempt.puzzleId, expectedId)
            ) {
                changed += attempt.setIndex
            }
        }

        if (stale.isEmpty() && changed.isEmpty()) return null

        val blockedIndexes = stale.toSet() + changed
        val blocked = assigned
            .filter { it.setIndex in blockedIndexes }
            .map { LocalDate.parse(it.localDate) }
            .distinct()
            .sorted()

        return ContentInstallException.Conflict(
            packId = activePackId,
            staleSetIndexes = stale.distinct().sorted(),
            changedSetIndexes = changed.toList(),
            blockedDates = blocked,
        )
    }

    /**
     * Единственное допустимое расхождение состава — ПОСЛОТОВАЯ замена отозванной
     * головоломки (`CONTENT_MODEL.md` §7, **I4-D4**).
     *
     * Сравнение идёт по позициям, а не по множествам. Наивная форма
     * `old.filterNot { it in new }.all { isRetired(it) }` сравнивает множества:
     * у перестановки `[A,B,C] → [B,A,C]` разность пуста, `all { }` на пустом списке
     * истинна, и подмена состава прошла бы молча — при том что пользователь сыграл `A`
     * в слоте 0, а после импорта в слоте 0 стоит `B`.
     */
    private fun isRetirementReplacement(
        pack: ParsedPack,
        old: List<String>,
        new: List<String>,
    ): Boolean {
        if (old == new) return true
        if (old.size != new.size) return false

        val changedSlots = old.indices.filter { old[it] != new[it] }
        // Пустым быть не может: списки той же длины и не равны. Условие выписано явно,
        // потому что именно на пустом списке all { } молча вернул бы true.
        if (changedSlots.isEmpty()) return false

        // Ни один ID, оставшийся в наборе, не может занять другую позицию: это
        // перестановка, а не замена. Отсекает и смешанный случай «одну отозванную
        // заменили, другую активную переставили».
        if (new.filterIndexed { i, id -> i in changedSlots && id in old }.isNotEmpty()) return false

        return changedSlots.all { slot -> isRetirementSlot(pack, old[slot], new[slot]) }
    }

    /**
     * Послотовое правило для одной позиции: старый ID обязан остаться в пакете
     * и быть отозванным, новый — существовать и быть АКТИВНЫМ.
     *
     * Головоломка, которая осталась в наборе, но в другом слоте, сюда не попадает:
     * она нашлась бы активной, и первое условие было бы ложным. Отозванная «из
     * будущего» до этого места не доходит — её отвергает `R18C` ещё до транзакции.
     */
    private fun isRetirementSlot(pack: ParsedPack, oldId: String, newId: String): Boolean {
        val retiredOld = pack.puzzleById(oldId)?.retiredIn != null
        val activeNew = pack.puzzleById(newId)?.let { it.retiredIn == null } == true
        return retiredOld && activeNew
    }
}
