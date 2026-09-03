# ARCHITECTURE.md — «По порядку!»

Архитектура MVP. Статус: утверждено, 2026-08-30.

Ключевое ограничение, определяющее все решения ниже: **проект делает один человек**. Всё, что не окупается в течение одного релиза, не делается.

---

## 1. Структура проекта

### Решение: один Gradle-модуль, разделение по packages

```
app/
  src/main/java/ru/poporyadku/
    PoPoRyadkuApp.kt            // Application, инициализация DI
    MainActivity.kt             // единственная Activity
    di/                         // модули Hilt
    core/
      model/                    // Puzzle, Card, DailySet, PuzzleAttempt, DayResult, Category
      time/                     // ClockProvider, DateProvider
      result/                   // AppResult<T> / типы ошибок
    data/
      content/
        ContentPaths.kt         // ROOT, MANIFEST, закрытый шаблон имени файла (I4-D6)
        ContentSchema.kt        // SUPPORTED_SCHEMA_VERSION
        ContentAssetSource.kt   // интерфейс: байты по проверенному имени
        AssetContentSource.kt   // реализация поверх AssetManager
        ContentPackReader.kt    // байты -> DTO, sha256, конверты, порядок чтения
        ContentImporter.kt      // assets -> база: версия, отпечаток, конфликты
        dto/                    // ManifestDto, PuzzleDto, CardDto, SourceDto, ParsedPack
        validation/             // ContentValidator, ContentViolation — защитный набор
        mapper/                 // ContentEntityMappers: DTO -> PuzzleEntity/DailySetEntity
        temporary/              // фикстура итерации 3; удаляется в PR 4D итерации 4
      db/
        AppDatabase.kt
        dao/                    // PuzzleDao, DailySetDao, AssignmentDao, AttemptDao, DayResultDao
        entity/
        json/                   // StoredCard, StoredSource — форма JSON-колонок (I4-D17)
        mapper/                 // PuzzleMapper, ContentTokens, DailySetMapper, ProgressMappers
      progress/
        ProgressRepository.kt
      prefs/
        UserPreferencesRepository.kt   // DataStore
      repository/
        PuzzleRepositoryImpl.kt
        DailySetRepositoryImpl.kt
    domain/
      repository/               // интерфейсы репозиториев
      usecase/
        GetTodayStateUseCase.kt
        GetPuzzleUseCase.kt
        StartDailySessionUseCase.kt   // фиксирует localDate -> setIndex
        SubmitAnswerUseCase.kt
        GetDayRecapUseCase.kt
        GetArchiveUseCase.kt
        GetStatisticsUseCase.kt
      scoring/
        PairwiseScoreCalculator.kt
        StreakCalculator.kt
      assignment/
        SetAssignmentPolicy.kt        // последовательная выдача наборов
      shuffle/
        DeterministicShuffler.kt
    ui/
      theme/                    // Color, Type, Shape, PoPoRyadkuTheme
      components/               // OrderableCard, DragHandle, MoveButtons, ScoreBadge
      navigation/               // AppNavHost, Destinations
      home/                     // HomeScreen, HomeViewModel, HomeUiState
      puzzle/                   // PuzzleScreen, PuzzleViewModel, PuzzleUiState, PuzzleEvent
      puzzleresult/
      recap/
      archive/
      settings/
      share/                    // ShareCardBuilder, шеринг через Intent
      feedback/                 // ReportInaccuracyIntentBuilder (mailto)
    notifications/
      DailyReminderScheduler.kt
      ReminderWorker.kt
      NotificationChannels.kt
    analytics/
      AnalyticsTracker.kt       // интерфейс
      NoOpAnalyticsTracker.kt   // реализация MVP
      AnalyticsEvent.kt
    monetization/
      ads/
        AdGateway.kt            // интерфейс
        NoOpAdGateway.kt
        AdPlacement.kt
      billing/
        BillingGateway.kt       // интерфейс
        NoOpBillingGateway.kt
        PurchaseState.kt
      entitlements/
        EntitlementsRepository.kt   // «реклама отключена?» — локально
  src/main/assets/content/
  src/test/                     // JVM-тесты
  src/androidTest/              // инструментальные и Compose UI тесты
tools/
  validate-content/             // валидатор контента для CI (Python 3.12)
    validate.py                 // CLI: коды выхода 0/1/2, --sync-manifest, --format json
    contentval/                 // правила: схема, семантика, кросс-файловые, манифест
    schema/                     // три JSON Schema Draft 2020-12
    fixtures/                   // общие фикстуры, expectations.json, shuffle-vectors.json
    tests/                      // pytest self-check валидатора
docs/
gradle/libs.versions.toml       // version catalog
```

### Почему один модуль

Многомодульность в Android даёт две вещи: скорость инкрементальной сборки и принудительные границы. На проекте такого размера (оценочно 6–9 тысяч строк) первое неощутимо, а второе достигается дисциплиной packages. Цена многомодульности — десяток `build.gradle.kts`, дублирование конфигурации, `api/implementation`-гимнастика и постоянный оверхед при каждом рефакторинге. Для соло-проекта это чистый убыток.

**Условие пересмотра:** если появится второй поставляемый артефакт (например, отдельный инструмент для контента, который надо собирать как Android-библиотеку) или чистая сборка станет дольше 2 минут — выделяем `:core:content` первым.

### Правила зависимостей между packages

```
ui  ──▶  domain  ──▶  core.model
              ▲
data ─────────┘  (реализует интерфейсы из domain.repository)

ui  ──▶  analytics / monetization (только интерфейсы)
```

- `ui` **никогда** не обращается к `data` напрямую;
- `domain` не знает про Android SDK (кроме, при необходимости, аннотаций) — это делает его тестируемым на JVM;
- `data` знает про Android (Room, DataStore, assets), но не про Compose;
- `notifications`, `analytics`, `monetization` — периферия, к которой обращаются `ui` и `domain`, но которая не знает о них.

Проверять это в MVP предполагается ревью и здравым смыслом, без Konsist/ArchUnit-тестов.

---

## 2. Разделение ответственности

| Область | Пакет | Отвечает за | Не знает о |
| --- | --- | --- | --- |
| UI | `ui/**` | Compose-экраны, ViewModel, UiState, обработка событий | Room, JSON, assets |
| Доменная логика | `domain/**` | Подсчёт баллов, серия, определение состояния дня, детерминированное перемешивание | Compose, Android SDK |
| Контент | `data/content/**` | Чтение assets, разбор, валидация схемы, импорт в базу, версии | Прогресс пользователя |
| Прогресс | `data/progress/**`, `data/db/**` | Назначения наборов, попытки, результаты дней, серия, настройки | Тексты головоломок |
| Уведомления | `notifications/**` | Планирование напоминания, канал, текст | Правила игры |
| Аналитика | `analytics/**` | Интерфейс событий, no-op реализация | Что именно делает UI |
| Монетизация | `monetization/**` | Интерфейсы рекламы и покупок, локальные entitlements | Всё остальное |

**Ключевая граница — контент против прогресса.** Они лежат в одной базе, но в разных таблицах, и ни одна таблица прогресса не имеет внешнего ключа на таблицу контента (только `puzzleId` и `setIndex` как значения). Это позволяет переимпортировать контент, не касаясь истории пользователя, и переживать отзыв головоломки.

**Вторая граница — какой набор выдать против того, что в наборе.** `SetAssignmentPolicy` решает только «какой `setIndex` положен сегодня» и работает исключительно с таблицей назначений и датами; она ничего не знает о головоломках. Это единственное место, где живёт логика последовательной выдачи, и заменить её на глобальный набор дня в будущем можно, не трогая ничего другого.

---

## 3. Локальное хранение

### Решение: Room + DataStore (Preferences)

| Что | Где | Почему |
| --- | --- | --- |
| Головоломки, наборы | Room | Нужен join с попытками, выборка по `setIndex`, постраничное чтение архива |
| Назначения наборов | Room | Ограничения уникальности несут продуктовые правила выдачи |
| Попытки, результаты дней | Room | Растущий объём, запросы по диапазону дат, агрегаты для статистики |
| Настройки, флаги первого запуска, `contentVersion`, серия-кэш | DataStore Preferences | 10–12 скалярных значений, `Flow` из коробки, никаких схем |

### Почему Room, а не альтернативы

| Вариант | Почему отклонён |
| --- | --- |
| Только DataStore/JSON-файл | Архив за год — это агрегаты и постраничная выборка. Считать средний балл, перечитывая один большой JSON, — заведомый тупик |
| SQLDelight | Отличный инструмент, но его главное преимущество — KMP, а KMP запрещён. Room ближе к платформе, лучше документирован, автогенерация схемы и миграций достаточна |
| Realm / ObjectBox | Внешняя зависимость с собственным рантаймом ради задачи, которую решает SQLite |
| Голый SQLite | Ручной маппинг и ручные миграции — экономия, которая стоит дороже Room |
| Proto DataStore для прогресса | Строгая типизация есть, но запросов нет. Архив без SQL становится линейным перебором |

Room выбирается ещё и потому, что его миграции — единственный механизм, который придётся всерьёз поддерживать годами, и он хорошо изучен.

### Схема базы (первая версия)

```
puzzles
  puzzle_id TEXT PK
  pack_id TEXT
  category TEXT
  prompt TEXT
  sort_key TEXT
  sort_direction TEXT
  direction_label TEXT
  cards_json TEXT           -- сериализованный список карточек
  correct_order TEXT        -- "c2,c1,c3,c4"
  explanation TEXT
  sources_json TEXT
  difficulty INTEGER
  retired_in INTEGER NULL
  content_version INTEGER

daily_sets
  pack_id TEXT              -- PK (pack_id, set_index)
  set_index INTEGER         -- порядковый номер набора в пакете
  puzzle_id_1 TEXT
  puzzle_id_2 TEXT
  puzzle_id_3 TEXT

day_assignments
  local_date TEXT PK        -- ISO yyyy-MM-dd, не более одной записи на дату
  pack_id TEXT
  set_index INTEGER
  assigned_at INTEGER       -- epoch millis
  UNIQUE(pack_id, set_index)

puzzle_attempts
  id INTEGER PK AUTOINCREMENT
  local_date TEXT           -- ISO yyyy-MM-dd
  slot_index INTEGER        -- 0..2, позиция в наборе
  puzzle_id TEXT
  submitted_order TEXT      -- "c1,c3,c2,c4"
  score INTEGER             -- 0..6, число верных пар
  submitted_at INTEGER      -- epoch millis
  UNIQUE(local_date, slot_index)

day_results
  local_date TEXT PK
  total_score INTEGER       -- 0..18
  completed_count INTEGER   -- 0..3
  is_complete INTEGER
  completed_at INTEGER NULL
```

Комментарии:

- `cards_json` и `sources_json` хранятся строками намеренно: к ним нет запросов, они всегда читаются вместе с головоломкой. Три нормализованные таблицы ради этого — лишняя сложность.
- **Форма этих двух колонок — часть контракта схемы, хотя SQLite видит там просто `TEXT`** (`ITERATION_4_DESIGN.md`, **I4-D17**). Их пишут и читают собственные типы `data/db/json/StoredCard` и `StoredSource`, а **не** asset-DTO: отозванные головоломки остаются в `puzzles` навсегда ради архива, и вырасти формату ассетов — их payload остался бы в старой форме, а архив за прошлые дни перестал бы открываться. Отсюда правило: изменение формы `cards_json`/`sources_json` требует настоящей миграции, переписывающей колонку, а не «просто добавим поле в DTO». Разбор колонок выполняет строгий `@StorageJson` — неизвестный ключ в том, что писали мы сами, означает повреждение, а не совместимость.
- **`content_version` — отметка ПОСТАВКИ, а не «версия, в которой головоломка появилась»** (**I4-D15**). Импорт пишет `manifest.contentVersion` всем головоломкам файла, включая неизменившиеся и отозванные, поэтому «все головоломки текущей версии на месте» — точный SQL-предикат, а не эвристика по количеству. Ось отзыва закрыта отдельным полем `retired_in`, и ни один экран не показывает, когда головоломка появилась.
- **Перечисления хранятся токенами формата** (`"geography"`, `"ascending"`), а не именами Kotlin-констант (**I4-D18**): переименование константы не должно требовать миграции данных, а строку в базе можно глазами сравнить с JSON. Единственное место преобразования — явный `when` в обе стороны в `data/db/mapper/ContentTokens.kt`.
- `day_assignments` — сердце последовательной выдачи. Два ограничения уникальности несут смысл продуктовых правил: `local_date` как первичный ключ гарантирует «не более одного нового набора за локальную календарную дату», `UNIQUE(pack_id, set_index)` — что один набор не будет выдан дважды. Оба правила защищены базой, а не только кодом.
- Назначение без единой попытки — **отложенное**: оно не расходует контент и на следующей дате **переносится**, а не дублируется. Перенос — это `UPDATE day_assignments SET local_date = :today, assigned_at = :now WHERE local_date = :pendingDate`; `set_index` остаётся прежним, вторая строка не создаётся, и `UNIQUE(pack_id, set_index)` не нарушается. Наивная вставка новой строки с тем же `set_index` при живой старой упёрлась бы в это ограничение и выдала пользователю ошибку вместо задания — поэтому перенос обязателен и обязан быть транзакционным.
- Отложенное назначение в системе не более одного: новая строка создаётся только когда отложенного нет. Из инварианта следует, что `max(set_index)` по оставшимся (израсходованным) назначениям — корректная база для следующего индекса, и отдельная процедура очистки не нужна.
- `UNIQUE(local_date, slot_index)` — техническая гарантия «одна попытка на слот в день». Повторная запись отбивается базой, а не только логикой.
- `day_results` избыточна относительно `puzzle_attempts` (её можно вычислить), но она делает архив и статистику одним быстрым запросом и даёт явное поле «день завершён» — то, на чём держится серия.
- Дата хранится строкой ISO, а не эпохой: сравнение и группировка по дню в SQL работают напрямую, часовой пояс не участвует.
- Серия **вычисляется** из `day_results` (`StreakCalculator`), а не хранится как счётчик. Счётчик рассинхронизируется при любом краевом случае; читается одна колонка по **всей** истории — строк ровно столько, сколько дней сыграно, и это стоит миллисекунды. Окна нет намеренно: `bestStreak` по окну занижался бы молча (`ITERATION_3_DESIGN.md`, I3-D10). В DataStore кэшируется только для мгновенного показа на `Home` до готовности базы.

---

## 4. Однонаправленный поток данных

### Общий контракт

```
UiState (immutable data class)   ──▶  Composable
Composable  ──(UiEvent)──▶  ViewModel  ──▶  UseCase  ──▶  Repository
ViewModel   ──▶  StateFlow<UiState>
ViewModel   ──▶  Channel<UiEffect>   (навигация, шеринг, тосты)
```

Правила:

- один `StateFlow<UiState>` на экран, собираемый через `collectAsStateWithLifecycle()`;
- `UiState` — `sealed interface` там, где состояния взаимоисключающие (Home), и `data class` с полями там, где они комбинируются (Settings);
- побочные однократные действия (навигация, `Intent` шеринга) идут через `Channel`/`SharedFlow` эффектов, а не через поля состояния;
- Composable-функции не имеют доступа к ViewModel глубже уровня экрана: вложенные компоненты получают состояние и лямбды.

### Игровой экран: состояния и события

```kotlin
// Состояние
sealed interface PuzzleUiState {
    data object Loading : PuzzleUiState

    data class Playing(
        val slotIndex: Int,              // 0..2
        val totalSlots: Int,             // 3
        val category: Category,
        val prompt: String,
        val directionLabel: String,
        val cards: List<CardUi>,         // текущий порядок пользователя
        val draggedCardId: String?,      // null, если ничего не тащат
        val isSubmitEnabled: Boolean,
        val showDragHint: Boolean
    ) : PuzzleUiState

    data object Submitting : PuzzleUiState

    data class Error(val kind: PuzzleErrorKind) : PuzzleUiState
}

data class CardUi(
    val cardId: String,
    val title: String,
    val subtitle: String?,
    val position: Int,                   // 1..4, для semantics
    val canMoveUp: Boolean,
    val canMoveDown: Boolean
)

// События от UI
sealed interface PuzzleEvent {
    data class DragStarted(val cardId: String) : PuzzleEvent
    data class DragMoved(val fromIndex: Int, val toIndex: Int) : PuzzleEvent
    data object DragEnded : PuzzleEvent
    data class MoveUp(val cardId: String) : PuzzleEvent
    data class MoveDown(val cardId: String) : PuzzleEvent
    data class MoveToTop(val cardId: String) : PuzzleEvent      // accessibility action
    data class MoveToBottom(val cardId: String) : PuzzleEvent   // accessibility action
    data object Submit : PuzzleEvent
    data object DragHintDismissed : PuzzleEvent
    data object BackPressed : PuzzleEvent
    data object RetryClicked : PuzzleEvent
}

// Однократные эффекты
sealed interface PuzzleEffect {
    data class NavigateToResult(val slotIndex: Int) : PuzzleEffect
    data object NavigateHome : PuzzleEffect
    data class AnnounceForAccessibility(val message: String) : PuzzleEffect
    data class Haptic(val kind: HapticKind) : PuzzleEffect
}
```

Заметки:

- перестановка карточек — операция над `List<String>` идентификаторов в ViewModel; UI ничего не переставляет сам;
- текущий порядок хранится в `SavedStateHandle` (`currentOrder: String`), поэтому переживает смерть процесса;
- `Submit` вызывает `SubmitAnswerUseCase`, который **сначала** пишет `PuzzleAttempt` и обновляет `day_results`, и только потом ViewModel отправляет `NavigateToResult`. Порядок обратный ломает гарантию «попытка зафиксирована»;
- повторный `Submit` во время `Submitting` игнорируется по состоянию, не по флагу-костылю.

### Подсчёт баллов и серия

Баллы считаются **по парам**, а не по позициям: у четырёх карточек шесть уникальных пар, и каждая пара, стоящая в правильном относительном порядке, даёт 1 балл. Максимум 6 за головоломку и 18 за день.

```kotlin
// domain/scoring
object PairwiseScoreCalculator {

    const val MAX_PER_PUZZLE = 6      // C(4,2)
    const val MAX_PER_DAY = 18
    const val CARDS_PER_PUZZLE = 4

    /**
     * Единственный вход. Ровно один перебор шести пар даёт ОБА выхода:
     * 1 балл за каждую пару, чей относительный порядок совпадает с правильным,
     * и запись о каждой паре, поставленной наоборот. Позиция карточки сама
     * по себе не важна; идентичность карточки — только cardId.
     */
    fun evaluate(submittedOrder: List<String>, correctOrder: List<String>): PairwiseScore
}

data class PairwiseScore(
    val score: Int,                          // 0..MAX_PER_PUZZLE
    val invertedPairs: List<InvertedPair>,   // size == MAX_PER_PUZZLE - score по построению
)

/** Пара нормализована по ПРАВИЛЬНОМУ порядку: correctlyFirst обязана стоять
 *  перед correctlySecond. В список попадают только инвертированные пары,
 *  поэтому у пользователя correctlySecond всегда раньше. Список отсортирован
 *  по позициям в correctOrder — порядок строк детерминирован. */
data class InvertedPair(val correctlyFirst: String, val correctlySecond: String)
```

**Одна функция, а не две** (`ITERATION_3_DESIGN.md`, I3-D4). Раздельные `score()` и `invertedPairs()` были бы двумя алгоритмами на один факт, и равенство `invertedPairs.size == 6 − score` держалось бы на дисциплине, а не на типе. Один перебор делает его свойством кода. Невалидный вход (размер не 4, дубликаты, разные множества `cardId`) отбивается `require`: он не может прийти от пользователя, поэтому это дефект кода или контента, а не состояние экрана (I3-D6).

Каждая перепутанная пара выводится строкой по **единственному** шаблону — «Карточка «{correctlySecond}» должна располагаться **после** карточки «{correctlyFirst}»» (`ITERATION_3_DESIGN.md`, I3-D5). Второго варианта нет: якорь — порядок пользователя, первой в строке называется карточка, которую он поставил раньше, а из нормализованной пары это всегда `correctlySecond`, поэтому форма «должна располагаться **перед**» не используется ни разу и в `strings.xml` не заводится. Слова «выше» и «ниже» в этих строках запрещены: в списке они означают позицию, но в головоломке про высоту, глубину или температуру совпадают с измеряемой величиной и делают объяснение двусмысленным ровно там, где оно нужнее всего. Подробности формулировки — `UX_FLOW.md`, раздел 5.

```kotlin

object StreakCalculator {
    // Серия = число подряд идущих календарных дней с is_complete = true,
    // заканчивающихся сегодня или вчера.
    // Заканчивающихся вчера — потому что сегодняшний день ещё можно завершить,
    // и серию нельзя показывать обнулённой до конца дня.
    fun currentStreak(completedDates: Collection<LocalDate>, today: LocalDate): Int
    fun bestStreak(completedDates: Collection<LocalDate>): Int

    /** Один проход нормализации на оба значения — то, что вызывает приложение. */
    fun streaks(completedDates: Collection<LocalDate>, today: LocalDate): Streaks
}

data class Streaks(val current: Int, val best: Int)
```

Обе функции — чистые, без зависимостей. Это то, что покрывается тестами в первую очередь.

Вход **нормализуется** самой функцией (сортировка и снятие дубликатов), даты в будущем отбрасываются при подсчёте текущей серии и учитываются в лучшей, а `best >= current` выполняется по построению — этого требует `require` в `updateStreakCache` (`ITERATION_3_DESIGN.md`, I3-D11).

**Решение по пропускам принято окончательно:** пропущенный день обнуляет текущую серию полностью. Никаких заморозок, «одного пропуска в неделю» и восстановлений — они добавили бы состояние в `StreakCalculator`, потребовали объяснения пользователю и притащили бы в продукт логику удержания из free-to-play. `bestStreak` при этом сохраняется навсегда и показывается рядом с текущей, чтобы обнуление не читалось как потеря всего достигнутого.

### Свойства парного подсчёта, важные для продукта и тестов

Распределение баллов по всем 24 перестановкам четырёх карточек известно точно и служит эталоном для тестов:

| Баллов | 6 | 5 | 4 | 3 | 2 | 1 | 0 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Перестановок | 1 | 3 | 5 | 6 | 5 | 3 | 1 |

Отсюда следует:

- **Среднее при случайном порядке — ровно 3 из 6** (9 из 18 за день). Это готовая точка отсчёта: результат около 9 означает угадывание, а не игру.
- **0 баллов достигается единственной перестановкой** — полностью обратным порядком. Практически пользователь почти никогда не получает ноль, что и было целью перехода от позиционного подсчёта.
- Метрика устойчива: перестановка двух соседей стоит ровно 1 балл, независимо от того, где в списке она произошла. При позиционном подсчёте сдвиг одной карточки мог обнулить сразу три позиции — это ощущалось несправедливым.

### Назначение набора

`SetAssignmentPolicy` — **чистая синхронная функция** от уже прочитанного снимка, а не класс с DAO и константой `setCount` в конструкторе (`ITERATION_2_DESIGN.md`, D-3/D-4/D-20): `data`-тип в `domain` нарушал бы правило раздела 1, а `setCount` константой был бы несовместим с тем, что число наборов известно только после импорта контента.

```kotlin
// domain/assignment/AssignmentSnapshot.kt
data class AssignmentSnapshot(
    val pendingAssignments: List<DayAssignment>,   // глобально, ≤ 1 по инварианту
    val todayAssignment: DayAssignment?,           // глобально
    val lastAssignedDate: LocalDate?,               // глобально: MAX(local_date)
    val activePackId: String,
    val maxSetIndexInActivePack: Int?,              // pack-scoped
    val setCountInActivePack: Int,                  // pack-scoped, читается из daily_sets
)

// domain/assignment/Decision.kt — решения несут packId явно
sealed interface Decision {
    data class NewSet(val packId: String, val setIndex: Int) : Decision
    data class CarryOver(val packId: String, val setIndex: Int, val fromDate: LocalDate) : Decision
    data class Assigned(val packId: String, val setIndex: Int) : Decision
    data object AwaitingNextDay : Decision
    data object ContentExhausted : Decision
}

// domain/assignment/SetAssignmentPolicy.kt — ни зависимостей, ни suspend, ни ввода-вывода
object SetAssignmentPolicy {
    fun decide(today: LocalDate, snapshot: AssignmentSnapshot): Decision
}
```

Правила, которые реализует политика (полное описание — `UX_FLOW.md`, раздел 9, и `ITERATION_2_DESIGN.md`, D-20):

0. Первым действием проверяет инвариант `require(pendingAssignments.size <= 1)` — нарушение бросает исключение, а не молча берёт первую строку.
1. Есть **отложенное** назначение (ноль попыток, глобально по всем пакетам):
   - его дата равна сегодняшней → `Assigned`, Home показывает `InProgress` и «Продолжить»;
   - его дата в прошлом → `CarryOver`;
   - его дата в будущем (часы переведены назад) → `AwaitingNextDay`, строка не двигается.
2. Отложенного нет, но есть назначение на сегодня (тоже глобально) → `Assigned`.
3. Иначе `today <= lastAssignedDate` (глобально) → `AwaitingNextDay`. Защита от перевода часов назад, включая многократное «туда-обратно» за реальные сутки; переключение активного пакета её не обходит.
4. Иначе `next = maxSetIndexInActivePack + 1`, только в активном пакете; при `next >= setCountInActivePack` → `ContentExhausted`.

Снимок собирается реализацией `DayAssignmentRepository` внутри одной Room-транзакции — политика сама к базе не обращается.

`StartDailySessionUseCase` — `suspend operator fun invoke(): Decision`, без параметра даты; время внедряется в `data`-реализацию репозитория через `ClockProvider` (`core/time`, D-16), а не приходит из UI. Репозиторий исполняет решение при переходе `Home → Puzzle(0)`, в одной транзакции со сборкой снимка:

| Решение | Действие |
| --- | --- |
| `NewSet` | `INSERT` строки `(today, packId, setIndex, now)` |
| `CarryOver` | `UPDATE` существующей строки: `local_date = today`, `assigned_at = now`; `set_index` и `pack_id` не меняются |
| `Assigned`, `AwaitingNextDay`, `ContentExhausted` | ничего |

Перенос идёт **только вперёд**. Набор становится израсходованным не в момент назначения, а в момент первой записанной попытки — именно поэтому «нажал „Играть" и вышел» не стоит пользователю ни одного задания.

---

## 5. Coroutines и Flow

Применяются там, где есть реальная асинхронность или поток изменений; не применяются как стиль.

| Место | Инструмент |
| --- | --- |
| Room-запросы для экранов | `Flow<T>` из DAO |
| Разовые записи (submit) | `suspend fun` |
| Настройки | `Flow<UserPreferences>` из DataStore |
| Состояние экрана | `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Initial)` |
| Импорт контента | `suspend fun` на `Dispatchers.Default`, вызывается один раз при старте |
| Планирование уведомлений | WorkManager, без корутин в API |

Чего не делаем: не оборачиваем во `Flow` то, что читается один раз; не строим цепочки `combine` из пяти источников там, где хватает одного запроса; не используем `SharedFlow` вместо простого колбэка.

Диспетчеры внедряются (`@IoDispatcher`, `@DefaultDispatcher`), чтобы тесты подставляли `UnconfinedTestDispatcher`.

---

## 6. Уведомления

- Одно ежедневное напоминание, время настраивается, по умолчанию 9:00.
- Реализация — `WorkManager` с `OneTimeWorkRequest`, перепланируемым после каждого срабатывания. Причина выбора против `AlarmManager`: точная минута не важна, а `WorkManager` переживает перезагрузку и не требует `SCHEDULE_EXACT_ALARM` (разрешение, которое на современных версиях Android приходится выпрашивать и которое неуместно для напоминания об игре).
- Worker перед показом проверяет: включено ли напоминание, не завершён ли сегодняшний день, есть ли контент на сегодня. Если день уже пройден — уведомление не показывается.
- Канал уведомлений создаётся при первом запуске, важность `DEFAULT`, без звука по умолчанию.
- Текст нейтральный: «Новые три задания готовы». Без «вы теряете серию».
- Перепланирование при: изменении настройки, завершении дня, `BOOT_COMPLETED` (через `WorkManager` это происходит автоматически).

---

## 7. Аналитика

В MVP внешнего SDK нет. Есть интерфейс и no-op реализация, чтобы точки вызова появились сразу и не пришлось искать их потом.

```kotlin
interface AnalyticsTracker {
    fun track(event: AnalyticsEvent)
}

sealed interface AnalyticsEvent {
    data object DayStarted : AnalyticsEvent
    data class PuzzleSubmitted(val slotIndex: Int, val score: Int, val category: Category) : AnalyticsEvent
    data class DayCompleted(val totalScore: Int, val streak: Int) : AnalyticsEvent
    data object ResultShared : AnalyticsEvent
    data object ArchiveOpened : AnalyticsEvent
    data class SettingChanged(val key: String) : AnalyticsEvent
}

class NoOpAnalyticsTracker : AnalyticsTracker {
    override fun track(event: AnalyticsEvent) = Unit   // в debug — Log.d
}
```

Правила: события не содержат персональных данных и текстов головоломок; только `puzzleId`, категории и числа.

**Подключение реальной реализации — отдельное решение после релиза MVP, и его нельзя принимать походя.** Пока `NoOpAnalyticsTracker` — единственная реализация, с устройства не уходит ничего, и это часть обещания пользователю. Любой внешний SDK меняет это обещание, а значит требует собственного разбора: какой поставщик, какие поля уходят, что написано в политике конфиденциальности, нужно ли согласие. Пока такое решение не принято, **в MVP нет данных о поведении аудитории** — из этого исходит и раздел о проверке гипотезы в `PRODUCT.md`.

---

## 8. Монетизация: интерфейсы без реализации

Требование — предусмотреть границы, ничего не подключая. Все интерфейсы возвращают «выключено», приложение обязано работать именно в таком виде.

### Два разных поставщика, а не один

Это разделение важно зафиксировать сразу, потому что его легко смешать в разговоре:

| Контур | Чем будет реализован в будущем | Что закрывает |
| --- | --- | --- |
| Покупки | **RuStore Pay SDK** — платёжный SDK магазина | Разовая покупка «убрать рекламу», тематические паки |
| Реклама | **SDK отдельной рекламной сети** (поставщик выбирается позже) | Полноэкранная реклама после итога дня, rewarded за подсказку и тренировочный режим |

Рекламный контур **не является частью RuStore SDK**, и называть его «рекламой RuStore» неверно: магазин отвечает за платежи и дистрибуцию, показ рекламы обеспечивает независимая сеть со своим договором, своей политикой данных и своим жизненным циклом версий. Смешение этих двух вещей в одном абзаце документации приводит к тому, что в коде они оказываются в одном модуле и начинают тянуть зависимости друг друга.

Практические следствия для архитектуры: `AdGateway` и `BillingGateway` — независимые интерфейсы в разных пакетах, без общих типов; отключение одного не влияет на другой; выбор рекламной сети можно отложить, не блокируя подключение покупок.

```kotlin
// monetization/ads
enum class AdPlacement {
    DAY_COMPLETED_INTERSTITIAL,   // не чаще 1 раза в сутки, только после итога дня
    HINT_REWARDED,                // необязательная подсказка
    PRACTICE_REWARDED             // тренировочный режим
}

sealed interface AdResult {
    data object NotAvailable : AdResult      // реклама выключена или не загрузилась
    data object Shown : AdResult             // interstitial показан
    data object Rewarded : AdResult          // rewarded досмотрен
    data object Dismissed : AdResult         // закрыт до награды
}

interface AdGateway {
    /** Можно ли показать здесь и сейчас: учитывает entitlements и частотные лимиты. */
    suspend fun isAvailable(placement: AdPlacement): Boolean
    suspend fun show(placement: AdPlacement): AdResult
}

class NoOpAdGateway : AdGateway {
    override suspend fun isAvailable(placement: AdPlacement) = false
    override suspend fun show(placement: AdPlacement) = AdResult.NotAvailable
}
```

```kotlin
// monetization/billing
enum class ProductId { REMOVE_ADS, PACK_HISTORY, PACK_SCIENCE }

sealed interface PurchaseState {
    data object NotPurchased : PurchaseState
    data object Purchased : PurchaseState
    data object Pending : PurchaseState
    data class Failed(val reason: String) : PurchaseState
}

interface BillingGateway {
    fun purchases(): Flow<Map<ProductId, PurchaseState>>
    suspend fun purchase(productId: ProductId): PurchaseState
    suspend fun restore()
    suspend fun isAvailable(): Boolean          // магазин доступен на устройстве
}

class NoOpBillingGateway : BillingGateway {
    override fun purchases() = flowOf(emptyMap<ProductId, PurchaseState>())
    override suspend fun purchase(productId: ProductId) = PurchaseState.Failed("disabled")
    override suspend fun restore() = Unit
    override suspend fun isAvailable() = false
}
```

```kotlin
// monetization/entitlements
interface EntitlementsRepository {
    val adsRemoved: Flow<Boolean>
    val unlockedPacks: Flow<Set<String>>

    /** Перечитать состояние у BillingGateway и обновить кэш. */
    suspend fun refresh()
}
```

**Источник истины по покупкам — `BillingGateway`, то есть магазин.** Локальное хранилище — только кэш последнего ответа магазина, и это различие принципиально:

- `refresh()` вызывается при старте приложения и после каждой успешной покупки; результат `BillingGateway.purchases()` перезаписывает кэш;
- кэш существует ради двух вещей: приложение работает офлайн (магазин недоступен — права остаются) и интерфейс не мигает «реклама включена → выключена» в первые секунды после запуска;
- **кэш не может создать право, которого магазин никогда не подтверждал.** Он только продлевает во времени уже полученный ответ. Права, появившиеся в кэше без подтверждения покупки, — дефект;
- если магазин сообщил, что покупки нет, кэш обновляется на «нет», а не сохраняет прежнее значение «есть»;
- восстановление покупок (`restore()`) — обычный путь на новом устройстве, а не аварийный: локального прогресса и аккаунтов нет, поэтому единственный носитель права — магазин.

Из-за отсутствия backend серверной валидации чеков не будет никогда: доверяем ответу SDK магазина на устройстве и принимаем это как ограничение проекта.

### Правила, зафиксированные архитектурно

1. **Ни один экран не зависит от `AdGateway` напрямую.** Единственная точка вызова interstitial — `DayRecapViewModel`, после того как итог дня уже отрисован. Реклама никогда не блокирует переход и никогда не показывается до или между заданиями.
2. **Частотный лимит живёт в приложении, а не в рекламной сети.** `AdFrequencyPolicy` хранит `lastInterstitialDate` в DataStore и отказывает при повторе в тот же день. Полагаться на настройки сети нельзя.
3. **`isAvailable() == false` — нормальный, а не аварийный путь.** Весь UI строится так, будто рекламы нет; появление рекламы ничего не добавляет к состоянию экрана, кроме одного показа.
4. **Подсказки и тренировочный режим в MVP не реализуются вовсе** — ни бесплатно, ни за рекламу. `AdPlacement` перечисляет их как будущие точки, но соответствующего UI нет. Это защищает от ситуации, когда бесплатная подсказка стала привычкой, а потом её убрали за рекламу.
5. **Право «убрать рекламу» читается через `EntitlementsRepository`, но принадлежит магазину.** Локальный кэш обслуживает офлайн и первый кадр, источником истины не является.
6. **Сборка без SDK обязана компилироваться и работать.** Проверяется тем, что в MVP реальных реализаций нет вообще: работоспособность no-op-контура — это и есть релиз.
7. **Подсказки и тренировочный режим в MVP отсутствуют как функциональность.** `AdPlacement` перечисляет их как будущие точки, но ни бесплатной, ни платной реализации нет.

Подключение реальных SDK начинается только после утверждения этой архитектуры и сверки с актуальной документацией — отдельно для RuStore Pay SDK (покупки) и отдельно для выбранной рекламной сети (показ рекламы). Это два независимых решения, и делать их одновременно не требуется.

---

## 9. Стратегия тестирования

Пирамида смещена вниз: у соло-проекта UI-тесты дороги в поддержке.

### Уровень 1 — чистые JVM-тесты (основа, ~70% усилий)

Без Android, без Robolectric, мгновенные:

- `PairwiseScoreCalculator.evaluate()` — все 24 перестановки четырёх карточек с проверкой против эталонного распределения `1, 3, 5, 6, 5, 3, 1`; правильный порядок = 6; обратный порядок = 0; перестановка соседей = ровно −1 в любой позиции списка; `invertedPairs` содержит столько пар, сколько баллов потеряно, — на всех перестановках сразу;
- `StreakCalculator` — непрерывная серия; пропуск дня; серия, заканчивающаяся вчера; серия, заканчивающаяся сегодня; пустая история; один день; смена года;
- `SetAssignmentPolicy` — первый запуск даёт набор 0; повторное открытие в тот же день даёт тот же набор; пропуск недели даёт следующий по порядку, а не восьмой; перевод часов назад даёт `AwaitingNextDay`; «туда-обратно» за одни реальные сутки не выдаёт второй набор; исчерпание пакета даёт `ContentExhausted`;
- **перенос отложенного назначения** — четыре обязательных сценария на in-memory Room, потому что здесь ломается наивная реализация:
  1. в день 1 нажата «Играть», попыток нет → создана строка `(день 1, setIndex = N)`;
  2. в день 2 старт сессии переносит **ту же строку** на день 2: `set_index` остался `N`, строк по-прежнему одна, `UNIQUE(pack_id, set_index)` не нарушен, исключения нет;
  3. повторный запуск в день 2 возвращает то же назначение `N` и не создаёт нового;
  4. если в день 1 была сделана **одна попытка**, назначение не переносится: в день 2 выдаётся `N + 1`, а день 1 остаётся в архиве незавершённым;
  плюс инвариант: отложенное назначение в базе не более одного в любой момент;
- `DeterministicShuffler` — один и тот же `puzzleId` даёт один и тот же порядок; порядок не совпадает с правильным (иначе задание бессмысленно);
- `ContentValidator` — все 21 правило из `CONTENT_MODEL.md`, каждое с падающим примером; отдельное внимание правилам покрытия источниками (11–17);
- разбор JSON — корректный файл, файл с неизвестным полем, файл с отсутствующим обязательным полем, неподдерживаемая `schemaVersion`.

### Уровень 2 — тесты ViewModel и репозиториев

`kotlinx-coroutines-test` + фейковые репозитории (рукописные, не MockK — их пять штук и они проще моков):

- `PuzzleViewModel`: перестановки меняют состояние; `Submit` пишет попытку ровно один раз; повторный `Submit` игнорируется; восстановление порядка из `SavedStateHandle`; открытие уже отвеченной головоломки даёт редирект;
- `HomeViewModel`: переходы `Ready → InProgress → Completed`; `AwaitingNextDay` при переводе часов назад; `ContentExhausted` после последнего набора; смена даты пересчитывает состояние;
- `ProgressRepository` — на in-memory Room (`Room.inMemoryDatabaseBuilder`, тест JVM через Robolectric — решено `ITERATION_2_DESIGN.md`, D-2): уникальность `(local_date, slot_index)`, уникальность `local_date` и `(pack_id, set_index)` в `day_assignments`, корректность агрегатов `day_results` при максимуме 18.

### Уровень 3 — инструментальные тесты (минимум, только критичное)

- миграции Room (`MigrationTestHelper`) — начиная с первой реальной миграции. **Итерация 4 её не приносит** (`ITERATION_4_DESIGN.md`, **I4-D1**): схема версии 1 уже содержит всю модель контента, версия не повышается, пустая миграция 1→2 «чтобы завести хелпер» запрещена — она была бы подписью под ложным утверждением «схема изменилась». Вместе с первым реальным изменением схемы подключается и `androidx.room:room-testing`;
- импорт контента из настоящих assets: все головоломки разобраны, все наборы на месте, ссылочная целостность цела;
- один сквозной Compose-тест «полный день»: пройти три задания, увидеть итог 18/18 (порядок задаётся программно через кнопки перемещения, не жестами);
- accessibility-проверка: у каждой карточки есть `contentDescription` и custom actions; экран проходится без единого жеста перетаскивания.

### Чего не тестируем

Пиксельную вёрстку, анимации, поведение перетаскивания жестами (проверяется вручную на устройстве), no-op-реализации монетизации.

### CI

GitHub Actions, `.github/workflows/ci.yml`, на каждый push в `main` и на каждый pull request. Фактические шаги, а не обещание:

| Шаг | Команда | С какого PR |
| --- | --- | --- |
| Python для валидатора контента | `actions/setup-python@v6`, `python-version: "3.12"`, кэш pip по `tools/validate-content/requirements.txt` | 4A |
| Установка валидатора | `pip install --require-hashes -r tools/validate-content/requirements.txt` | 4A |
| Self-check валидатора | `python3 -m pytest tools/validate-content/tests -q` | 4A |
| Сборка debug | `./gradlew assembleDebug` | 2A |
| Компиляция инструментальных тестов | `./gradlew assembleDebugAndroidTest` | 2A |
| Lint | `./gradlew lint` | 1 |
| Unit-тесты | `./gradlew testDebugUnitTest` | 1 |
| Release unit-тесты | `./gradlew testReleaseUnitTest` | 2B |
| Release APK | `./gradlew :app:assembleRelease` | 2C |

Валидатор — **автономный инструмент на Python**, а не Gradle-задача (`ITERATION_4_DESIGN.md`, **I4-D8**): он не тянет AGP, не удлиняет Android-шаги и позволяет проверить контент, не собирая приложение. Установка идёт только через lock-файл с хешами: `--require-hashes` отвергает файл, в котором хоть одна зависимость не пинована, поэтому «поехавшая» версия `jsonschema` даст падение установки, а не тихую смену поведения.

**Прямой проверки настоящего пакета в CI пока нет.** Шаг `python3 tools/validate-content/validate.py app/src/main/assets/content` добавляется в PR 4C-1 — вместе с самим каталогом assets, которого до него не существует; флаги `--expect-sets 35 --expect-puzzles 105` (критерий релиза) добавляются в PR 4D. До этого CI проверяет валидатор на синтетических фикстурах: 21 правило, буквенные подкоды и `M01`–`M10`.

Инструментальные тесты на эмуляторе в CI не запускаются — только компилируются: эмулятор в CI для соло-проекта необязательная роскошь, а ручной прогон перед релизом остаётся обязательным.

---

## 10. Версии инструментов

Проверено по официальной документации 29.08.2026. Фиксируется в `gradle/libs.versions.toml`, конкретные значения перепроверяются в момент создания проекта (итерация 1).

| Компонент | Версия на 29.08.2026 | Источник |
| --- | --- | --- |
| Android Gradle Plugin | 9.3.0 (июль 2026) | developer.android.com/build/releases/about-agp |
| Gradle | 9.5.0 (минимум для AGP 9.3) | там же |
| Kotlin | 2.4.10 (14.07.2026) | kotlinlang.org/docs/releases.html |
| Compose BOM | 2026.08.00 (compose.ui / foundation 1.12.0) | developer.android.com/jetpack/compose/bom/bom-mapping |
| Material 3 | 1.4.0 стабильная | developer.android.com/jetpack/androidx/releases/compose |
| compileSdk / targetSdk | 37 (Android 17) | developer.android.com/about/versions/17/setup-sdk |

Room, DataStore, Navigation Compose, Hilt, WorkManager, kotlinx-serialization — версии подбираются на итерации 1 по их страницам релизов и фиксируются там же. Заранее их выписывать нет смысла: между этим документом и созданием проекта пройдёт время.

**Актуальные зафиксированные версии всех компонентов — `VERSIONS.md`**, вместе с первичным источником и датой проверки каждой. Там же — `kotlinx-serialization`, подключённая к `app` в PR 4B итерации 4 вместе с плагином компилятора `org.jetbrains.kotlin.plugin.serialization` (версия плагина следует версии Kotlin проекта; совместимость со встроенным Kotlin AGP 9 проверена эмпирически — `assembleDebug` и `assembleRelease`). Там же — версии инструментария, не входящего в Android-сборку: Python 3.12, `jsonschema`, `referencing` и `pytest` для `tools/validate-content/` (итерация 4, **I4-D8**). Python-зависимости фиксируются собственным lock-файлом `tools/validate-content/requirements.txt` с точными версиями и sha256 и **не подключаются к Gradle**: валидатор контента — автономный инструмент авторинга, а не зависимость `app`.

### minSdk: рекомендация 26 (Android 8.0)

Аргументы:

1. **Покрытие.** API 26+ — около 96% активных устройств (apilevels.com, данные апреля 2026). Разница с API 24 — доли процента: за неё платить архитектурными неудобствами нерационально.
2. **`java.time` без десугаринга.** Вся продуктовая логика построена на `LocalDate` и `ChronoUnit.DAYS`. На API 26+ это платформенный API. На API 24–25 потребовался бы core library desugaring — рабочий, но лишний слой, который на соло-проекте нужно помнить и настраивать.
3. **Каналы уведомлений** существуют нативно, без ветвлений в коде.
4. **Адаптивные иконки** — базовое требование к внешнему виду в 2026 году.
5. **Аудитория RuStore.** Российский парк устройств смещён к среднему сегменту, но Android 7 и ниже — это телефоны 2016–2017 годов, на которых Compose-приложение с анимациями и так будет работать плохо. Целевая аудитория 20–45 лет с интересом к контенту — не этот сегмент.

**Альтернативы:** minSdk 24 (+~0.5% устройств, ценой десугаринга) — если после релиза статистика RuStore покажет заметную долю Android 7, понизить можно без изменения архитектуры. minSdk 28 или 29 (минус ~3–5% при почти нулевом выигрыше) — неоправданно.

**Это рекомендация, а не окончательное решение.** Финально фиксируется на итерации 1 после сверки с актуальной на тот момент статистикой RuStore Console, если она доступна.

### Прочие технические решения

- **Single-activity**, `MainActivity` с `enableEdgeToEdge()`, Navigation Compose;
- **DI: Hilt.** Альтернатива — ручной граф в `Application`: для 15–20 зависимостей это реально и даже быстрее собирается. Hilt выбран за интеграцию с ViewModel (`hiltViewModel()`) и WorkManager, которая иначе пишется руками и потом всё равно повторяет Hilt. Koin отклонён: ошибки графа в рантайме вместо компиляции;
- **Сериализация: kotlinx-serialization** — родная для Kotlin, работает без рефлексии, `ignoreUnknownKeys` для совместимости вперёд;
- **Обфускация:** R8 в release, `minifyEnabled true`, правила для kotlinx-serialization и Room;
- **Локализация:** единственный язык — русский, но все строки в `strings.xml` без исключений.

---

## 11. Design Gate — обязательный этап до создания Android-проекта

### Правило

**Разработчик или coding-агент, пишущий код, не выбирает визуальный язык.** Палитра, шрифты и их начертания, скругления, тени, шкала отступов, размеры, иконки, длительности и кривые анимаций приходят из утверждённых дизайн-документов и берутся оттуда буквально.

Причина простая и проверяемая на опыте: если эти решения не приняты заранее, они принимаются по одному, в момент написания каждого экрана, и приложение получает пять слегка разных синих, три радиуса скругления и четыре шкалы отступов. Разобрать это потом дороже, чем договориться сразу. Требование «стиль современной уютной энциклопедии, не казино и не детская обучалка» невозможно выполнить импровизацией: оно про согласованность, а согласованность — это документ, а не намерение.

Второе следствие правила: **если нужного значения в токенах нет, код не придумывает его.** Работа останавливается, значение добавляется в `DESIGN_TOKENS.md`, и только потом продолжается реализация. Захардкоженный цвет или размер, которого нет в токенах, — это дефект, а не мелочь.

### Обязательные артефакты

Все пять создаются и утверждаются **до** итерации 1 (создания Android-проекта).

**1. `DESIGN_PRINCIPLES.md` — почему**

Словесное описание визуального языка: что означает «уютная энциклопедия» в решениях, а не в эпитетах. Что мы делаем и чего не делаем (антипримеры: казино, телешоу, детская обучалка). Отношение к плотности, к пустому месту, к иллюстрациям, к тону подписей. Правила иерархии: что на экране главное, что второстепенное. Принципы анимации: что анимируется, что нет, зачем.

Этот документ — то, к чему апеллируют при спорах о частностях. Без него токены превращаются в произвольный набор чисел.

**2. `DESIGN_TOKENS.md` — чем**

Полный перечень именованных значений, готовых к переносу в код один в один:

- цвета: полные светлая и тёмная схемы Material 3 (все роли — `primary`, `onPrimary`, `surface`, `surfaceVariant`, `outline` и остальные), с проверенным контрастом ≥ 4.5:1 для текста в обеих темах;
- типографика: семейство с полной кириллицей, начертания, полная шкала Material 3 (`displayLarge` … `labelSmall`) с размерами, высотой строки и трекингом;
- шкала отступов (единая, например 4/8/12/16/24/32);
- радиусы скругления по ролям (карточка, кнопка, диалог);
- уровни теней;
- длительности и кривые анимаций;
- размеры сенсорных целей.

Требование к формату: каждое значение имеет имя, и в коде используется имя. Никаких «примерно такой синий».

**3. `COMPONENTS.md` — из чего**

Инвентарь компонентов с состояниями и правилами. Для каждого — размеры, отступы, какие токены использует, что происходит при увеличенном шрифте и длинном тексте.

Компоненты делятся на две группы, и требования к ним разные.

**Основные — проверяются способом, закреплённым за их реальным местом появления.** Компонент, который появляется в одном из четырёх базовых состояний B2 (`Home.Ready`, `Puzzle.Playing`, `PuzzleResult`, `DayRecap`), обязан присутствовать хотя бы на одном из 12 артбордов, собранных из этих состояний: карточка задания на Home; упорядочиваемая карточка (`OrderableCard`) во всех состояниях — обычное, захваченное, результат «в верном порядке», результат «участвует в перепутанной паре», недоступная кнопка перемещения; кнопки перемещения; основная и второстепенная кнопки; бейдж счёта; строка серии; строка перепутанной пары; блок «Источники» в свёрнутом виде. Часть компонентов, «основных» по функции, но реально появляющихся не в одном из этих четырёх состояний — `ThreeStepProgress` (`Home.InProgress`, `Archive row`), `DragEducationHint` (первый показ `Puzzle.Playing`), раскрытый `SourceRow` (внутри `SourcesBlock.expanded`), `NotificationOptInDialog` (модальный слой над `DayRecap`) — проверяется не макетом, а полноценным state sheet той же строгости; `ShareCard` (не Compose-компонент, категория «появляется на артборде» к нему неприменима) — невизуальной format specification. Точное распределение — в `COMPONENTS.md`, раздел «Классификация: 12 артбордов B2 и state sheets». Ни один из этой группы не получает отдельный полноэкранный артборд сверх уже зафиксированных 12 — это вопрос способа проверки, а не пятого макета. Компонент, для которого не выполнено ни одно из двух требований (появление на одном из 12 артбордов или state sheet/specification), не считается спроектированным: его поведение в реальном окружении — рядом с другими элементами, при длинном тексте, при 200% — никем не проверено.

**Вспомогательные — достаточно отдельных state sheets в `COMPONENTS.md`.** Это компоненты Archive и Settings (строка списка архива, строка настройки, блок статистики) и служебные состояния (`Loading`-скелетоны, `Empty`, `Error`). Отдельных макетов экранов они не требуют: Archive и Settings собираются из уже утверждённых элементов, а служебные состояния появляются на разных экранах и осмысленны именно как самостоятельные листы состояний, а не как часть одной композиции. Рисовать ради них четыре дополнительных экрана — трата, которая для соло-проекта не окупается.

State sheet — это лист с компонентом во всех его состояниях: размеры, отступы, поведение при длинном тексте и при 200% шрифта. Требования к проработке те же, что у основных компонентов; отличается только форма подачи.

**4. Четыре утверждённых макета**

Ровно четыре экрана, в светлой и тёмной теме:

| Макет | Почему именно он |
| --- | --- |
| Home, состояние `Ready` | Задаёт тон продукта и первое впечатление |
| Puzzle, состояние `Playing` | Главный экран взаимодействия; здесь решается вопрос плотности и размера карточек |
| PuzzleResult | Самый сложный по содержанию: порядок, значения, перепутанные пары, объяснение, источники |
| DayRecap | Итог, серия и карточка для отправки |

Archive и Settings макетов не требуют: они собираются из компонентов, утверждённых в `COMPONENTS.md`. Тратить на них отдельный дизайн для соло-проекта не окупается.

**Шрифт 200% требует отдельных вариантов макетов, а не пометки «проверено».** При двукратном шрифте меняется не размер текста, а компоновка: карточка перестаёт быть одной строкой, кнопки уезжают за экран, счёт и подпись перестают помещаться рядом. Что именно должно произойти — перенос на две строки, вертикальная раскладка кнопок, сокращённая формулировка перепутанной пары — это дизайнерское решение, и принимать его должен макет, а не разработчик в итерации 6.

Поэтому к четырём макетам добавляются **четыре варианта при шрифте 200%** (достаточно одной темы — тема не влияет на компоновку). Маленький экран 320 dp проверяется на тех же вариантах без отдельных артефактов: если раскладка выдержала 200%, она выдержит и 320 dp.

Итого комплект макетов: 4 экрана × 2 темы + 4 варианта при 200% = 12 артборд.

**5. `UI_REVIEW_CHECKLIST.md` — как проверить**

Чек-лист приёмки каждого экрана, применяемый в итерациях 3, 5 и особенно 6. Пункты: все цвета и размеры взяты из токенов; светлая и тёмная тема; шрифт 200%; edge-to-edge и системные отступы; контраст; сенсорные цели ≥ 48 dp; описания и custom actions для TalkBack; ни одна информация не передана только цветом; уважение системной настройки «убрать анимацию»; поведение при длинном тексте; маленький и большой экран; ландшафт.

### Критерии выхода из Design Gate

Этап считается пройденным, когда:

- все пять артефактов существуют и утверждены;
- в `DESIGN_TOKENS.md` нет пустых мест: у каждой роли цвета, каждого уровня типографики и каждого радиуса есть значение;
- контраст проверен инструментом, а не на глаз;
- **каждый компонент проверен тем способом, который закреплён в таблице классификации `COMPONENTS.md`: либо на одном из четырёх базовых макетов, либо полноценным state sheet, либо невизуальной specification для `ShareCard`**;
- **каждый вспомогательный компонент (Archive, Settings, `Loading`, `Empty`, `Error`) описан отдельным state sheet** в `COMPONENTS.md` — отдельные макеты экранов для них не требуются;
- на макетах нет элементов, которых нет в инвентаре;
- **готовы четыре варианта макетов при шрифте 200%**, и ни в одном компоновка не ломается; 320 dp проверен на них же;
- шрифт выбран с проверенной кириллицей и понятной лицензией на распространение в составе приложения.

Пока критерии не выполнены, Android-проект не создаётся.

### Что Design Gate не делает

Не проектирует поведение и состояния — это `UX_FLOW.md`. Не выбирает библиотеки и архитектуру. Не требует дизайн-системы промышленного масштаба: пяти документов и четырёх макетов достаточно, и раздувать этот этап так же вредно, как пропустить его.

---

## 12. ADR — ключевые решения

### ADR-001. Один Gradle-модуль вместо многомодульности

**Контекст.** Проект ~6–9 тыс. строк, один разработчик, один поставляемый артефакт.
**Решение.** Один модуль `app`, границы задаются структурой packages.
**Альтернативы.** Модули по слоям (`:core`, `:data`, `:domain`, `:ui`); модули по фичам.
**Последствия.** Плюс: минимум конфигурации, простой рефакторинг. Минус: границы не проверяются компилятором, дисциплина держится на ревью.
**Пересмотр.** Появление второго артефакта или чистая сборка > 2 минут.

### ADR-002. Room для контента и прогресса, DataStore для настроек

**Контекст.** Нужны архив с агрегатами, join контента с попытками, растущая история.
**Решение.** Room (SQLite) — данные; DataStore Preferences — скаляры и флаги.
**Альтернативы.** Только DataStore/JSON (нет запросов); SQLDelight (его преимущество — KMP, который запрещён); Realm/ObjectBox (лишний рантайм).
**Последствия.** Плюс: SQL, миграции, `Flow`, знакомый инструмент. Минус: KSP в сборке, необходимость писать миграции.

### ADR-003. Контент импортируется из assets в базу, а не читается на лету

**Контекст.** 105 головоломок в JSON внутри APK; архив требует join с прогрессом.
**Решение.** Разовый импорт при первом запуске, при росте `contentVersion` и при смене **отпечатка** пакета.
**Альтернативы.** Читать JSON при каждом обращении и держать в памяти — работает для 105 задач, но ломается на архиве и на будущих паках.
**Последствия.** Плюс: единый источник для запросов, готовность к нескольким пакам. Минус: данные дублируются (APK + база), нужен код импорта и миграции контента.

**Фактический алгоритм** (`ITERATION_4_DESIGN.md`, §10; реализован в PR 4B итерации 4):

1. **Заголовок** — `manifest.json` и файл наборов, один раз на процесс. Кэшируется только содержимое ассетов: они лежат внутри APK, а обновление APK убивает процесс. Вывода о состоянии базы в процессе не кэшируется никогда.
2. **Отметка** — `storedContentVersion` **и** `storedContentFingerprint` читаются внутри общего мьютекса; отпечаток — `sha256` байтов манифеста, и он считается всегда, включая release.
3. **Ранний выход разрешён только после подтверждения БАЗОЙ** — четырьмя read-only предикатами: состав всех наборов точно равен ожидаемому, назначений вне диапазона пакета нет, блокирующих расхождений между сыгранными `puzzle_id` и составом слота нет, головоломки наборов существуют, имеют текущую версию и активны. Совпадения одной отметки недостаточно: отладочная очистка базы не трогает DataStore, и без подтверждения приложение показывало бы «контент кончился» при полной отметке.
4. **Тело пакета** (`puzzles-*.json`, ~300 КБ) читается ТОЛЬКО когда импорт действительно нужен. Разбор, защитная валидация и маппинг выполняются **вне** транзакции: держать на десятках миллисекунд сериализации блокировку записи SQLite незачем.
5. **Одна транзакция записи**: полная диагностика конфликта, затем `puzzles.upsertAll`, единственный `DELETE` по `daily_sets` вне диапазона и `daily_sets.upsertAll`. Конфликт бросается **внутри** транзакции, поэтому откат гарантирован формой кода.
6. **Отметка пишется после commit** — одной операцией, `NonCancellable`.

**Что импорт не делает никогда:** не удаляет и не изменяет `day_assignments`, `puzzle_attempts`, `day_results`; не удаляет ни одной строки `puzzles` (на них может ссылаться архив); не трогает строки других `pack_id`. Единственное допустимое расхождение состава уже назначенного набора — **послотовая** замена отозванной головоломки; перестановка тех же идентификаторов конфликтом является.

### ADR-004. Правильный порядок хранится явно, хотя выводится из значений

**Контекст.** Ошибка в контенте необратима после релиза.
**Решение.** Хранить и `sortValue`, и `correctOrder`; сверять их в CI-валидаторе.
**Альтернативы.** Хранить только значения (компактнее, но опечатка проходит незамеченной); хранить только порядок (тогда нечего показать в результате и нечем доказать однозначность).
**Последствия.** Плюс: опечатки ловятся автоматически, значения показываются пользователю. Минус: избыточность в данных, дисциплина при редактировании.

### ADR-005. Серия вычисляется, а не хранится счётчиком

**Контекст.** Смена часового пояса, пропущенные дни, перезапуск, откат приложения.
**Решение.** `StreakCalculator` считает серию из `day_results` при каждом обращении, по **полной истории завершённых дат**, а не по окну последних записей: для текущей серии окна хватило бы, но лучшая серия может целиком лежать за его границей и была бы занижена молча, причём незаметно для пользователя (`ITERATION_3_DESIGN.md`, I3-D10). Читается одна колонка `local_date` при `is_complete = 1`.
**Роль кэша.** Кэш в DataStore (`StreakCache`) — только для мгновенного показа на `Home`, и источником истины он не становится по построению: единственный писатель пишет то, что сам только что посчитал из `day_results` одной атомарной операцией `updateStreakCache(current, best, date)`; расходящихся сеттеров нет. Ни один экран не строит серию из кэша.
**Альтернативы.** Инкрементируемый счётчик — быстрее, но рассинхронизируется на любом краевом случае, и восстановить его нечем. Окно ~60 записей — дешевле на бумаге, но ломает `bestStreak` без единого наблюдаемого признака.
**Последствия.** Плюс: серия всегда согласована с историей, и лучшая серия не исчезает со временем. Минус: запрос по всей истории при каждом открытии Home — сотни строк одной колонки за годы игры.

### ADR-006. WorkManager вместо AlarmManager для напоминания

**Контекст.** Одно необязательное напоминание в день, точность до минуты не важна.
**Решение.** `WorkManager` с перепланируемым `OneTimeWorkRequest`.
**Альтернативы.** `AlarmManager` с `setExactAndAllowWhileIdle` — требует разрешения на точные будильники, что несоразмерно задаче; `setInexactRepeating` — меньше контроля над проверками перед показом.
**Последствия.** Плюс: переживает перезагрузку, без дополнительных разрешений. Минус: время показа может сдвинуться на несколько минут, а на агрессивных прошивках — сильнее.

### ADR-007. Монетизация — только интерфейсы с no-op реализациями

**Контекст.** Требование не подключать SDK, но сохранить возможность. При этом покупки и реклама придут от **разных поставщиков**: покупки — из RuStore Pay SDK, показ рекламы — из SDK отдельной рекламной сети.
**Решение.** Два независимых интерфейса в разных пакетах — `BillingGateway` (покупки) и `AdGateway` (реклама) — плюс `EntitlementsRepository` и `NoOp*`-реализации. Единственная точка interstitial — `DayRecapViewModel`; частотный лимит живёт в приложении. Источник истины по правам — `BillingGateway`; локальное хранилище только кэширует его ответ.
**Альтернативы.** Не проектировать вовсе (потом придётся трогать экраны); подключить SDK сразу (запрещено и преждевременно); свести оба контура к одному интерфейсу «монетизация» — короче на бумаге, но связывает независимых поставщиков и заставляет выбирать рекламную сеть одновременно с подключением платежей.
**Последствия.** Плюс: релиз MVP не зависит ни от одного SDK; покупки можно подключить, не выбрав рекламную сеть, и наоборот; реклама добавляется в одной точке. Минус: интерфейсы могут не совпасть с реальными API — на этапе подключения возможен адаптер, отдельный для каждого поставщика. Второй минус: без backend чеки проверяются только ответом SDK на устройстве.

### ADR-008. Hilt вместо ручного DI

**Контекст.** 15–20 зависимостей, ViewModel и Worker нуждаются во внедрении.
**Решение.** Hilt.
**Альтернативы.** Ручной граф в `Application` (реально, но `ViewModelProvider.Factory` и `WorkerFactory` придётся писать самому); Koin (ошибки в рантайме).
**Последствия.** Плюс: `hiltViewModel()`, `HiltWorker`, проверка графа при компиляции. Минус: KSP и время сборки.

### ADR-009. minSdk 26

**Контекст.** RuStore-аудитория, зависимость логики от `java.time`.
**Решение.** minSdk 26 (Android 8.0), compileSdk/targetSdk 37.
**Альтернативы.** 24 (+~0.5% устройств ценой десугаринга); 28+ (минус ~3–5% без выигрыша).
**Последствия.** Плюс: `java.time`, каналы уведомлений и адаптивные иконки без ветвлений. Минус: отсекается Android 7. Понижение до 24 возможно позже без изменения архитектуры.

### ADR-010. Перемешивание карточек детерминировано по `puzzleId`

**Контекст.** У одной головоломки стартовый порядок карточек должен быть **воспроизводимым**: одинаковым при каждом открытии, после перезапуска приложения и на любом устройстве. (Утверждение «дневной набор одинаковый у всех» здесь неприменимо: с последовательной выдачей наборов, ADR-013, разные пользователи проходят разные наборы в одну дату. Воспроизводимость — свойство головоломки, а не дня.)
**Решение.** **Собственный стабильный shuffle**, записанный в нашем файле целиком (`ITERATION_3_DESIGN.md`, I3-D7 — I3-D9): seed = FNV-1a 64 по UTF-8-байтам `puzzleId`, генератор — SplitMix64, перестановка — Fisher–Yates. Seed зависит **только** от `puzzleId`: ни дата, ни `packId`, ни `setIndex`, ни локаль, ни время в него не входят. Валидатор проверяет, что стартовый порядок не совпадает с правильным; сам шаффлер совпадение **не «чинит»** — ни цикла, ни поправки, иначе правило валидатора превратилось бы в тавтологию (I3-D8).
**Альтернативы.** `Random(seed = puzzleId.hashCode())` — формула `String.hashCode()` задана спецификацией Java, но контракт «наш стартовый порядок зависит от чужой формулы» нигде не проверяется и теряется от любой правки ключа; `kotlin.random.Random(seed)` не даёт **документированной** гарантии стабильности алгоритма между версиями stdlib; `java.util.Random.nextInt(bound)` тянет за собой поведение конкретной реализации JDK. Случайное перемешивание при каждом открытии — стартовый порядок менялся бы после перезапуска, а проверка «стартовый порядок ≠ правильный» стала бы непроверяемой в CI; хранение стартового порядка в контенте — лишнее поле, которое надо синхронизировать с `correctOrder` вручную.
**Последствия.** Плюс: одна головоломка ведёт себя одинаково всегда, на любом устройстве и в любой версии JVM — всё вычисление это целочисленная арифметика `Long`; воспроизводимость в тестах и в валидаторе; отсутствие «удачных раздач». Контракт зафиксирован тестовыми векторами `I3-H2`, выписанными литералами: изменение алгоритма ломает тест первым. Минус: шесть строк своего кода вместо одной строки чужого API.

**Как правило 10 проверяется в CI (итерация 4, I4-D26).** Валидатор контента — автономный инструмент на Python, поэтому алгоритм существует в проекте в двух реализациях: Kotlin в `domain/shuffle/` и порт в `tools/validate-content/contentval/shuffle.py`. Это единственное место дублирования алгоритма во всём проекте, и оно закрыто **общими векторами** `tools/validate-content/fixtures/shuffle-vectors.json`: литералы сняты с настоящего Kotlin-кода и включают три идентификатора контракта `I3-H2`, кириллический идентификатор, идентификатор предельной длины и пару, различающуюся одним байтом, — набор, на котором расходятся реализации с ошибками в знаковой арифметике, логическом сдвиге и переполнении 64 бит. Порт обязан явно маскировать каждую 64-битную операцию (`& 0xFFFFFFFFFFFFFFFF`), потому что целые в Python неограничены, а `Long` в Kotlin переполняется штатно.

Python-половина сверки с векторами выполнена в PR 4A; JVM-половина — прогон тех же векторов настоящим `DeterministicShuffler` (`I4-P4`) и проверка правила 10 по настоящему пакету (`I4-C3`) — относится к PR 4B и 4C-1. До тех пор векторы остаются снимком Kotlin-поведения, а не автоматически подтверждаемым равенством.

### ADR-011. Прошлые дни нельзя переигрывать

**Контекст.** 35 наборов на старте; понятия «результат дня» и «серия» должны оставаться осмысленными.
**Решение.** Архив только для просмотра; попытка одна.
**Альтернативы.** Разрешить переигрывать архив — расходует контент за неделю и обесценивает результат; тренировочный режим по архивным наборам — **решено не делать в MVP**: он потребовал бы второй модели прогресса (баллы, не влияющие на статистику и серию), отдельных состояний в архиве и объяснения пользователю, зачем существуют два вида результатов.
**Последствия.** Плюс: честный дневной результат, экономия контента, одна модель прогресса. Минус: у пользователя нет способа «поиграть ещё» — это принятый риск, снимаемый выпуском новых пакетов, а не режимом внутри MVP.

### ADR-012. Парный подсчёт баллов вместо позиционного

**Контекст.** Требовалась метрика, которая различает «почти угадал» и «не понял», не наказывая за сдвиг всего списка на одну позицию.
**Решение.** 1 балл за каждую из шести пар карточек в правильном относительном порядке. Максимум 6 за головоломку, 18 за день.
**Альтернативы.** Позиционный подсчёт (1 балл за карточку на своём месте, максимум 4 и 12) — проще объясняется одной фразой, но резко нелинеен: сдвиг одной карточки может обнулить три позиции, и пользователь, понявший задачу почти правильно, получает результат почти как у угадывающего. Полный зачёт «всё или ничего» — ещё жёстче и для спокойной игры не годится.
**Последствия.** Плюс: устойчивая метрика, ноль практически недостижим, известная точка отсчёта — 9 из 18 при случайном порядке, распределение по 24 перестановкам задаёт готовый эталон для тестов. Минус: правило не самоочевидно и требует однократного объяснения в UI (`hasSeenScoringHint` и перечень перепутанных пар на экране результата); максимум «18» менее круглый, чем «12».

### ADR-013. Последовательная выдача наборов вместо привязки к календарю

**Контекст.** При жёсткой привязке `setIndex` к календарной дате пользователь, установивший приложение на 20-й день, получал не 35 дней контента, а 15, и начинал с середины последовательности, минуя самые выверенные первые наборы.
**Решение.** Наборы выдаются по порядку от первого, каждому пользователю независимо. Назначение `localDate → setIndex` фиксируется при начале сессии; не более одного нового набора за локальную дату; пропущенный день не расходует контент. Логика изолирована в `SetAssignmentPolicy`.
**Альтернативы.** Глобальная привязка к дате (даёт общий набор дня и сравнимость результатов, но наказывает всех поздних установщиков — то есть большинство). Гибрид «догоняющей выдачи» — сложен и объясним пользователю только через backend.
**Последствия.** Плюс: 35 активных дней для каждого независимо от даты установки, первые наборы видят все, пропуски не сжигают контент, защита от перевода часов сводится к одному сравнению дат. Минус: **у двух людей в одну дату разные задания, поэтому шеринг теряет повод к сравнению** — это главная цена решения, зафиксированная в `PRODUCT.md`. Второй минус: все пользователи упираются в конец контента одинаково, на 36-й активный день, что делает требование к объёму контента перед публичным релизом жёстче. Глобальный набор дня явно вынесен за пределы MVP; его возвращение потребует backend и изменения только `SetAssignmentPolicy`.

### ADR-014. Design Gate перед созданием Android-проекта

**Контекст.** Требование «стиль современной уютной энциклопедии» невыполнимо, если визуальные решения принимаются по одному в момент написания каждого экрана.
**Решение.** Обязательный этап до итерации 1: `DESIGN_PRINCIPLES.md`, `DESIGN_TOKENS.md`, `COMPONENTS.md`, четыре утверждённых макета, `UI_REVIEW_CHECKLIST.md`. Код берёт значения из токенов и не изобретает недостающие: при отсутствии значения работа останавливается и токен добавляется.
**Альтернативы.** Проектировать по ходу — быстрее стартует и даёт разнобой, который дорого разбирать. Полноценная дизайн-система — избыточна для соло-проекта на шесть экранов.
**Последствия.** Плюс: один визуальный язык с первого экрана, ревью по чек-листу вместо споров о вкусе, проблема «поехало при шрифте 200%» ловится на макете, а не в итерации 6. Минус: отложенный старт кода и запрет на импровизацию, который придётся соблюдать дисциплиной.

### ADR-015. Импорт контента атомарен; DataStore пишется после commit Room

**Контекст.** Отметка «контент установлен» живёт в DataStore, а сам контент — в Room. Это два хранилища с разными жизненными циклами, и порядок записи между ними определяет, какое из двух рассогласований возможно.

**Решение.** Граница проходит **между Room и DataStore и всегда в одну сторону**: Room — источник истины о контенте, DataStore — производная отметка. Вся запись контента выполняется в одной транзакции; отметка (`contentVersion` + отпечаток, одной операцией) пишется **после** её успешного commit, в `withContext(NonCancellable)`.

**Альтернативы.** (а) Писать отметку до транзакции или внутри неё — создаёт состояние «отметка есть, данных нет», в котором ранний выход возвращает успех при пустой базе; это ровно тот класс ошибок, ради которого существует подтверждение базой. (б) Хранить отметку в Room отдельной таблицей — делает её неотличимой от данных, которые она подтверждает, и стоит миграции ради одного скаляра, тогда как скаляры принадлежат DataStore (ADR-002).

**Последствия.** Возможно ровно одно рассогласование — «база записана, отметка не успела»: процесс умер между commit и записью. Оно **безопасно**: следующий вызов не находит отметки и повторяет импорт, `upsert` идемпотентен, дублей не возникает, конфликт не появляется (составы уже совпадают). Обратное рассогласование невозможно по построению. `NonCancellable` сужает окно до смерти процесса, но корректность держится не на нём, а на идемпотентности повтора. Цена — один лишний полный импорт в редком сценарии; тест `I4-I17` фиксирует, что повтор успешен и не создаёт дублей.
