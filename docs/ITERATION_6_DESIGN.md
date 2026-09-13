# ITERATION_6_DESIGN.md — «По порядку!»

Техническое проектирование итерации 6 «Перетаскивание, доступность, визуальная полировка» и напоминаний. Статус: **ревизия 1.1, готова к архитектурному ревью, 2026-09-13.** Документ не утверждён; решения `I6-D*` имеют статус «предложено», решения владельца `O6-1`…`O6-6` (раздел 18) открыты.

Документ дополняет `IMPLEMENTATION_PLAN.md` (итерация 6) и не заменяет его: план говорит **что** делает итерация, этот документ — **как**, **в каком порядке**, **какими типами и контрактами**, и закрывает вопросы, на которые утверждённые документы и текущий код отвечают противоречиво или не отвечают вовсе. Цель — чтобы реализация PR 6A–6D не принимала архитектурных решений внутри Kotlin-кода.

**Что изменено этим Design Gate.** Создан этот файл; статусные правки внесены в `docs/IMPLEMENTATION_PLAN.md` (итерации 5, 6, 7) и `docs/ITERATION_5_DESIGN.md` (ревизия 1.2, статусная). Kotlin-код, XML-ресурсы, `AndroidManifest.xml`, Gradle и version catalog, Room-схема, JSON-контент, CI, изображения и прочие бинарные ресурсы не менялись. `ARCHITECTURE.md`, `UX_FLOW.md`, `COMPONENTS.md`, `DESIGN_TOKENS.md`, `UI_REVIEW_CHECKLIST.md` не переписываются: их будущая синхронизация по PR — раздел 17. Все листинги ниже — проект, а не реализация.

**Основание для утверждений о коде.** Все ссылки проверены чтением файлов на коммите `f96a5618c7174a35293d0b84406e296a6fd4f79d` (merge PR №22, итерация 5E), ветка `main`, рабочее дерево чистое. Прочитаны: `docs/PRODUCT.md`, `docs/UX_FLOW.md`, `docs/ARCHITECTURE.md`, `docs/design/COMPONENTS.md`, `docs/design/DESIGN_PRINCIPLES.md`, `docs/design/DESIGN_TOKENS.md`, `docs/design/UI_REVIEW_CHECKLIST.md`, `docs/IMPLEMENTATION_PLAN.md`, `docs/VERSIONS.md`, `docs/ITERATION_5_DESIGN.md`; из `docs/ITERATION_3_DESIGN.md` и `docs/ITERATION_4_DESIGN.md` — разделы, касающиеся игрового экрана, `SavedStateHandle`, эффектов, открытых вопросов и `I4-C6`. `AGENTS.md` в репозитории отсутствует. Версии и API сверены с `gradle/libs.versions.toml`, `app/build.gradle.kts`, исходниками `navigation-compose-android-2.10.0` из кэша Gradle и `platforms/android-37.0/data/api-versions.xml` локального SDK. Ни один API, которого нет в коде, не предполагается существующим: всё новое перечислено как создаваемое.

---

## Оглавление

1. Scope и non-goals
2. Аудит исходной точки
3. Таблица решений `I6-D*`
4. Единый путь перестановки
5. Перетаскивание
6. Отдача при перетаскивании
7. Доступность
8. Напоминания: пользовательский контракт
9. Напоминания: планирование
10. Android-контракты уведомлений
11. Визуальная полировка, анимации, иконки
12. Архитектурные границы и конкурентность
13. Разбиение на PR 6A–6D
14. Тестовая матрица `I6-*`
15. Ручные проверки и release gate
16. Риски и откат
17. Синхронизация документов по PR
18. Решения владельца и входные данные
19. Changelog документа

---

## 1. Scope и non-goals

### 1.1 Подтверждённая базовая линия

| Утверждение | Чем подтверждено |
| --- | --- |
| PR 5A–5E влиты; PR 5E — PR №22 | `git log`: `f96a561 Merge pull request #22 … iteration-5e-feedback` (2026-09-13); 5A — №18, 5B — №19, 5C — №20, 5D — №21 |
| Итерация 5 технически реализована, автоматика PR 22 зелёная | состояние PR №22; `IMPLEMENTATION_PLAN.md`, итерация 5 (статус синхронизирован этим PR) |
| Ручные `I5-M5` и `I5-M10` **не выполнены** и решением владельца от 2026-09-12 перенесены в обязательный release-readiness gate итерации 7 | решение владельца; `ITERATION_5_DESIGN.md`, ревизия 1.2 |
| `I5-M6` (живой TalkBack экранов итерации 5) не выполнен и находится в том же gate | `IMPLEMENTATION_PLAN.md`, итерация 7 |
| `I4-C6` (замер импорта на физическом устройстве) не выполнен и открыт в release-readiness итерации 7 | `ITERATION_4_DESIGN.md`, «Ход реализации»; `IMPLEMENTATION_PLAN.md`, итерация 7 |
| Перенос ручных проверок не отменяет их и не снижает требований | решение владельца; ни один тест `I6-*` их не заменяет (**I6-D48**) |
| Room-схема версии 1, миграций нет | `AppDatabase.version = 1`, `app/schemas/…/1.json` |
| WorkManager в version catalog есть (`workManager = "2.11.2"`, `androidx-work-runtime-ktx`), к `app` **не подключён** | `gradle/libs.versions.toml`, `app/build.gradle.kts` |
| `minSdk = 26`, `targetSdk = compileSdk = 37` | `app/build.gradle.kts` |
| Разрешений в манифесте нет; `<queries>` — `VIEW http/https`, `SENDTO mailto`; `MainActivity` — `launchMode="singleTop"` | `app/src/main/AndroidManifest.xml` |

### 1.2 Что входит в итерацию 6

1. Жестовое перетаскивание карточек за ручку поверх работающих кнопок ↑/↓, один путь перестановки для кнопок, жеста и accessibility actions.
2. Отдача при захвате и при фактической смене позиции в рамках существующего контракта отдачи итерации 5.
3. Доступность игрового потока: семантика, действия, объявления, порядок фокуса, заголовки, reduced motion, `DragEducationHint`.
4. Напоминание: переключатель и время в настройках, одноразовое предложение на `DayRecap`, запрос `POST_NOTIFICATIONS`, планирование на WorkManager, проверки перед показом, канал и уведомление.
5. Визуальная полировка по аудиту раздела 11: переходы между экранами, появление результата, иконка приложения и уведомления, итоговая матрица UI.

### 1.3 Что не входит (non-goals)

| Не входит | Куда | Почему |
| --- | --- | --- |
| Живой проход TalkBack, реальная вибрация, замеры на слабом устройстве, физическое уведомление | release-readiness итерации 7 | физического устройства нет; раздел 15 |
| Закрытие `I4-C6`, `I5-M5`, `I5-M6`, `I5-M10` | итерация 7 | решения владельца; не имитируются эмулятором |
| Редизайн экранов, новые компоненты вне инвентаря, новые цвета | — | раздел 11 — только расхождения с токенами |
| Точные будильники, `SCHEDULE_EXACT_ALARM`, `AlarmManager` | — | ADR-006 |
| Сеть, аналитика, магазинные SDK, `INTERNET` | вне MVP / итерация 7 | `ARCHITECTURE.md` §7–8 |
| Изменение Room-схемы, новые таблицы | — | **I6-D2** |
| Deep link из уведомления в конкретный экран | — | **I6-D35** |
| Перестановка с клавиатуры сочетаниями клавиш | — | **I6-D23** |
| Рантайм-приёмник смены даты для Home (O-2 итерации 3) | не реализуется | **I6-D31** |
| Рефакторинг дублирующейся логики полей экранов (`BoxWithConstraints` в семи экранах) | — | не визуальный дефект |
| Store listing, скриншоты, подпись | итерация 7 | `IMPLEMENTATION_PLAN.md` |

---

## 2. Аудит исходной точки

### 2.1 Как кнопки меняют порядок сейчас

1. `OrderableCard` (`ui/components/OrderableCard.kt`) получает `OrderableCardControls` с лямбдами; `MoveButton` ↑/↓ вызывают `onMoveUp`/`onMoveDown`; четыре `CustomAccessibilityAction` вызывают `onMoveUp`/`onMoveToTop`/`onMoveDown`/`onMoveToBottom`. Действия предлагаются только применимые; при `controls.enabled == false` (`Submitting.Answer`) и в read-only их нет.
2. `PuzzleScreen` превращает лямбды в `PuzzleEvent.MoveUp/MoveDown/MoveToTop/MoveToBottom(cardId)`.
3. `PuzzleViewModel.onEvent` вызывает приватный `move(cardId, targetIndex: (index, lastIndex) -> Int)` — **единственный** алгоритм перестановки: принимается только из `Playing`, неизвестный `cardId` и цель вне диапазона или равная текущему индексу — выход без изменений.
4. Порядок живёт в `PuzzleBoard.cards: List<CardUi>`; `position`, `canMoveUp`, `canMoveDown` пересчитываются ViewModel.
5. `LazyColumn` в `PuzzleScreen` использует `key = cardId` и `Modifier.animateItem(placementSpec = tween(motion.durationLong, motion.easingStandard))`.

События `DragStarted(cardId)`, `DragMoved(fromIndex, toIndex)`, `DragEnded`, `DragHintDismissed` объявлены в `PuzzleEvent`, но ни один компонент их не отправляет; ViewModel отвечает на них `Unit` (I3-D24). Поля `PuzzleBoard.draggedCardId` (всегда `null`) и `Playing.showDragHint` (всегда `false`) зарезервированы.

### 2.2 Где хранится подтверждённый порядок и когда пишется `SavedStateHandle`

- Подтверждённый порядок — `PuzzleUiState.Playing.board.cards` во `PuzzleViewModel`.
- `SavedStateHandle`: `puzzle.currentOrder` (`cardId` через запятую) и `puzzle.orderPuzzleId`. Оба ключа пишутся **внутри `move()` сразу после** `state.value = …`, при каждой фактической перестановке.
- Восстановление — `orderOf()` при загрузке: порядок принимается, только если `orderPuzzleId` совпадает и множество `cardId` равно множеству карточек; иначе детерминированный стартовый порядок (I3-D26).

### 2.3 Эффекты и однократность

- `Channel<PuzzleEffect>(BUFFERED).receiveAsFlow()`; ровно один lifecycle-aware коллектор в `PuzzleRoute` (`AppNavHost.kt`) под `repeatOnLifecycle(STARTED)`.
- `PuzzleEffect.Feedback(request)` создаётся только в `emitFeedback()`, который вызывается в двух местах: в `move()` после записи `SavedStateHandle` (`CardMoved`) и в `submit()` при `SubmitResult.Recorded(Answered)` (`AnswerAccepted`). `load()`, `orderOf()`, `onSkip()`, отказы, `AlreadyClosed`, `BackPressed` отдачи не порождают.
- В `move()` после `Feedback` отправляется `AnnounceCardMoved(title, position, total)`; route-контейнер собирает фразу `puzzle_card_moved` из ресурсов и вызывает `view.announceForAccessibility(...)`.
- Однократность обеспечена типом (эффект не часть состояния), `Channel` не реплеит, при смерти процесса канала нет.
- `FeedbackPolicy.requestFor(cue, settings)`: `Unknown` или оба канала выключены → `null`. `AndroidFeedbackPlayer`: `CardMoved` → `CLOCK_TICK`, `AnswerAccepted` → `CONFIRM` (API 30+) / `VIRTUAL_KEY`; звук — `SoundCues` → `SoundCueBank.play(cue)`, где `sampleIds.getValue(cue)` бросил бы для cue без звука.
- Исполнитель подменяется в тестах через `LocalFeedbackPlayerOverride` (`DayFlowDriver.RecordingFeedbackPlayer`).

### 2.4 Настройки и ключи DataStore

| Ключ | Поле `UserPreferences` | По умолчанию | Сеттер | Используется сейчас |
| --- | --- | --- | --- | --- |
| `sound_enabled`, `vibration_enabled` | `soundEnabled`, `vibrationEnabled` | `true` | через `SettingsWriteQueue` | Settings, отдача |
| `reminder_enabled` | `reminderEnabled` | `false` | `setReminderEnabled` | **нигде** |
| `reminder_minute_of_day` | `reminderTime: LocalTime` | 540 (09:00); значение вне `0..1439` читается как 540 | `setReminderTime` (`require` диапазона) | **нигде** |
| `has_seen_drag_hint` | `hasSeenDragHint` | `false` | `setHasSeenDragHint` | нигде |
| `has_completed_first_day` | `hasCompletedFirstDay` | `false` | `SubmitAnswerUseCase` (I5-D31) | ни один экран не читает |
| `notification_prompt_shown` | `notificationPromptShown` | `false` | `setNotificationPromptShown` | нигде |
| `last_seen_date` | `lastSeenDate` | `null` | `setLastSeenDate` | нигде в продуктовом коде |

Все ключи, нужные итерации 6, **уже существуют**. `SettingMutation`/`SettingKey` (`domain/model/SettingMutation.kt`) знают только `Sound`, `Vibration`, `Theme`.

### 2.5 Уведомления, WorkManager, зависимости

| Что | Состояние |
| --- | --- |
| `work-runtime-ktx` | в catalog, не подключён |
| `androidx.hilt:hilt-work`, `androidx.work:work-testing`, `androidx.test.uiautomator` | **отсутствуют** и в catalog, и в Gradle |
| Пакет `notifications/` | в дереве `ARCHITECTURE.md` §1 объявлен, в коде **отсутствует** |
| Канал, `NotificationManager`, `POST_NOTIFICATIONS` | нет |
| Приёмники `BroadcastReceiver` (дата, часовой пояс, загрузка) | нет ни одного; `UX_FLOW.md` §9 описывает рантайм-регистрацию, код её не делает (O-2 итерации 3) |
| Иконка приложения | **нет**: у `<application>` нет `android:icon`/`android:roundIcon`, каталогов `mipmap*` нет |
| `Application` | `PoPoRyadkuApp` — пустой `@HiltAndroidApp` |
| `@ApplicationScope CoroutineScope` | есть (`di/AppModule.kt`), единственный потребитель — `SettingsWriteQueue` |

### 2.6 Время

`ClockProvider.clock()`/`now(): TimeSnapshot` (дата, epochMillis, зона из одного `Clock`), `DateProvider.today()`. Release — `SystemClockProvider` (зона читается на каждом обращении), debug — управляемый `DebugClockProvider`; привязки — `ClockModule` в `src/release`/`src/debug`. `DayAssignmentRepository.peek()` — только чтение, одна транзакция, возвращает `DecisionContext(decision, time)`. `GetTodayStateUseCase` **пишет**: `ContentInstaller.ensureInstalled()` (импорт) и `GetStreaksUseCase` (`StreakCache`) — поэтому для фоновой проверки он не годится (**I6-D32**).

### 2.7 Что уже покрыто итерациями 3 и 5

| Пункт плана итерации 6 | Уже есть | Остаётся |
| --- | --- | --- |
| Кнопки ↑/↓ видимы, disabled на краях | I3, `I3-C3` | — |
| `contentDescription` «Позиция N из 4. {Название}» | `cd_card_position`, `I3-C4` | проверка после drag |
| `customActions` вверх/вниз/в начало/в конец, только применимые | I3, `I3-C4`, `I3-C16` | единый путь с drag |
| Объявление после перемещения | `AnnounceCardMoved` | дубль с `liveRegion`, устаревший API, drag |
| Отдача на перемещение, выключаемые каналы | I5-D22, `I5-V29`…`V34` | захват, drag |
| `heading()` у заголовков | `AppTopBar`, `HomeHeader`, `StatisticsBlock`, «Правильный порядок», группы настроек | формулировка задания на `Puzzle` |
| Перепутанные пары отдельными строками, полная форма для TalkBack | `InvertedPairRow` с `contentDescription` полной формы | проверка тестом итерации 6 |
| 320 dp, 200 %, тёмная тема | Compose-тесты всех экранов | новые элементы |
| Ландшафт | тесты Archive, Sources, Settings | Home, Puzzle, PuzzleResult, DayRecap |
| Motion-токены и reduced motion | `ui/theme/Motion.kt` | реактивность, переходы, reveal |
| Настройки звука/вибрации, очередь записи | I5-D15 | напоминание |
| Флаг первого завершённого дня | I5-D31 | потребитель — предложение напоминания |

### 2.8 Тестовая база

| Уровень | Где | Инструменты | В CI |
| --- | --- | --- | --- |
| JVM | `app/src/test` | JUnit 4, coroutines-test, Turbine, фейки | выполняются |
| Robolectric (в т.ч. Room) | `app/src/test` | Robolectric 4.16.1, `sdk=35` | выполняются |
| Compose-экраны | `app/src/testDebug` | Robolectric, `createComposeRule`, `@Config(qualifiers)` | выполняются |
| Инструментальные | `app/src/androidTest` (`AppNavHostTest`, `FullDayFlowTest`, `HardCutoverTest`, `ContentImportTimingTest`) | эмулятор | только компиляция |

Тесты, которые итерация 6 обязана переписать: `I3-C6` «drag handle отсутствует в дереве» (**6A**), «card list is a polite live region» в `PuzzleScreenTest` (**6C**).

### 2.9 Расхождения документов и кода

Каждое закрыто решением `I6-D*` или решением владельца `O6-*`.

| № | Где | Расхождение | Закрытие |
| --- | --- | --- | --- |
| 1 | `ARCHITECTURE.md` §4, `PuzzleEvent.DragMoved(fromIndex, toIndex)` | идентичность карточки — только `cardId`; индексы «откуда» устаревают между кадрами | **I6-D5** |
| 2 | `PuzzleBoard.draggedCardId` в состоянии ViewModel | состояние жеста в ViewModel переживает поворот без пальца — «прилипшая» карточка | **I6-D9** |
| 3 | `UX_FLOW.md` §10, `UI_REVIEW_CHECKLIST.md` («`liveRegion = Polite` на контейнере списка») против `AnnounceCardMoved` | два канала озвучивания одного события | **I6-D17** |
| 4 | `COMPONENTS.md` (`OrderableCard`), `UI_REVIEW_CHECKLIST.md`: «Переместить выше/ниже» | `UX_FLOW.md` §10 и ресурсы `action_move_up/down`: «Переместить вверх/вниз»; приоритет у `UX_FLOW.md` | **I6-D18** |
| 5 | `View.announceForAccessibility` в `AppNavHost.kt`, `SettingsRows.kt` | `api-versions.xml` (compileSdk 37): `deprecated="36"` | **I6-D16** |
| 6 | `ui/theme/Motion.kt`, `rememberMotionTokens` | `remember(context)` читает `ANIMATOR_DURATION_SCALE` один раз и не реагирует на смену настройки | **I6-D20** |
| 7 | `AppNavHost.kt`: `NavHost` без переходов | библиотечные `fadeIn/fadeOut(tween(700))` (`DefaultNavTransitions.android.kt`, navigation-compose 2.10.0) — не токены и не reduced motion | **I6-D43** |
| 8 | `UX_FLOW.md` §5, `DESIGN_PRINCIPLES.md` §9: постепенное появление результата | не реализовано; `staggerResultReveal` не используется | **I6-D44** |
| 9 | `ARCHITECTURE.md` §1: `notifications` — периферия, «не знает» о `domain` | worker обязан проверять день через домен | **I6-D24** |
| 10 | `ARCHITECTURE.md` §6: «перепланирование при завершении дня», «канал при первом запуске» | проверка при срабатывании делает перепланирование на завершение лишним; канал создаётся идемпотентно при старте Activity | **I6-D29**, **I6-D34** |
| 11 | `UX_FLOW.md` §9, O-2 итерации 3: рантайм-приёмник смены даты | в коде нет; foreground-полночь закрыта тикером Home | **I6-D31** |
| 12 | `COMPONENTS.md` (`InvertedPairRow`): шаблон «перед/после» | I3-D5 и ресурсы: шаблон один, «после» | **I6-D47**, раздел 17 |
| 13 | `VERSIONS.md`: DataStore и Turbine «в version catalog, не подключено» | подключены с итерации 2 | раздел 17 (6B) |
| 14 | Критерий `rg -n "android\.permission" app/src/main/AndroidManifest.xml` пуст (итерация 5); критерий итерации 7 «нет ни одного сетевого разрешения» | `POST_NOTIFICATIONS` утверждён продуктом; WorkManager вливает свои разрешения, включая `ACCESS_NETWORK_STATE` | **I6-D41**: критерий итерации 7 уточнён (10.4) |
| 15 | `COMPONENTS.md` (`Settings row`): отказ в разрешении — только строка-подсказка; нет различия причин | после постоянного отказа системный диалог не появляется; при выключенных уведомлениях приложения или канала runtime-запрос бесполезен | **I6-D36**: статус `NotificationAvailability`, подсказка и действие в системные настройки; тексты по причинам — **O6-4** |
| 16 | `DESIGN_PRINCIPLES.md` §6: ровно два компонента с тенью | выбор времени диалогом добавил бы третий | **O6-3** |
| 17 | `ARCHITECTURE.md` §6: «Новые три задания готовы» | текст не подходит дню, где часть заданий уже закрыта | **O6-1**, **O6-2** |
| 18 | `AndroidManifest.xml`: нет иконки приложения | `ARCHITECTURE.md` ADR-009 и план итерации 6 требуют адаптивную иконку | **I6-D46**, входные данные раздела 18.2 |
| 19 | Экраны применяют `safeDrawing` только сверху и снизу | вырез экрана/кнопочная навигация сбоку в ландшафте не проверены | **I6-D47**, `I6-M11` |

---

## 3. Таблица решений `I6-D*`

Статус всех решений — «предложено»: они принимаются вместе с архитектурным утверждением этого документа. Решения, требующие ответа владельца, вынесены в раздел 18 и здесь помечены ссылкой `O6-*`.

| ID | Решение | Отклонённая альтернатива | Раздел |
| --- | --- | --- | --- |
| I6-D1 | Четыре PR: **6A** единый путь перестановки и drag → **6B** напоминания (независим от 6A) → **6C** доступность, reduced motion, `DragEducationHint` (после 6A) → **6D** полировка, переходы, иконки, итоговая матрица UI (после 6A–6C) | один PR; PR «доступность» до drag | 13 |
| I6-D2 | Room-схема и ключи DataStore не меняются: все нужные ключи существуют (2.4) | ключ «последнее показанное напоминание» | 9.7 |
| I6-D3 | Карточка во всех намерениях перестановки адресуется только `cardId` | индексы «откуда/куда» | 4.1 |
| I6-D4 | Единственная функция перестановки — чистая `CardOrder.move(order, cardId, target): List<String>?` в `ui/puzzle`; её вызывают кнопки, custom actions и drag через один метод ViewModel `reorder()` | алгоритм в UI для жеста и в ViewModel для кнопок | 4.2 |
| I6-D5 | Жестовые события несут идентификатор жеста: `DragStarted(cardId, gesture)`, `DragMovedTo(cardId, targetIndex, gesture)`, `DragFinished(cardId, gesture)`; `DragGestureId` выдаёт процессный монотонный счётчик UI; `DragMoved(from, to)` и `DragEnded` удаляются | сохранить контракт I3; различать жесты только по `cardId` | 4.3 |
| I6-D6 | Перестановка при перетаскивании подтверждается ViewModel **на каждом пересечении порога**: состояние ViewModel — источник истины и во время, и после жеста; UI держит только визуальное смещение пальца; `SavedStateHandle` пишется на каждое подтверждение | коммит одним событием при отпускании с визуальной перестановкой в UI | 5.3 |
| I6-D7 | Единственный порог: визуальный центр перетаскиваемой карточки строго пересекает центр соседней карточки; цель — последний сосед, чей центр пересечён (один кадр может пройти несколько позиций — одна перестановка); равенство перестановки не даёт | попадание центра в границы карточки; фиксированное расстояние в dp | 5.3 |
| I6-D8 | Отмена жеста, потеря pointer, уход с экрана = завершение на последнем подтверждённом порядке; отката к порядку до жеста нет | откат при `ACTION_CANCEL` | 5.5 |
| I6-D9 | Сессия перетаскивания во ViewModel — только в памяти (`DragSession(cardId, gesture, startIndex)`); начало с другим `DragGestureId` заменяет сессию целиком, события с чужим `DragGestureId` игнорируются; `PuzzleBoard.draggedCardId` удаляется из состояния, «поднятость» — локальное состояние UI без `rememberSaveable` | хранить `draggedCardId` в `PuzzleUiState`; сессия, опознаваемая одним `cardId` | 4.5 |
| I6-D10 | Захват — только за `DragHandle` (48 × 48 dp), без длинного нажатия, после системного touch slop; жест ручки поглощает указатель, поэтому список не прокручивается; свайп вне ручки прокручивает список | долгое нажатие на карточку | 5.1 |
| I6-D11 | Жест доступен только в `Playing`; в `Submitting.Answer` ручка видна и не принимает ввод; в read-only и `Loading`/`Error` ручки нет; уход из `Playing` отменяет жест | прятать ручку в `Submitting` | 5.2 |
| I6-D12 | Auto-scroll у краёв видимой области списка, скорость пропорциональна заходу в краевую зону; останавливается при отпускании, отмене, невозможности прокрутки и выходе из `Playing`; значения — именованные токены (**O6-6**) | прокрутка с постоянной скоростью; числа в коде | 5.4 |
| I6-D13 | Поднятая карточка: верхний `zIndex`, без placement-анимации, `elevation.dragged` + `opacity.dragStateLayer`; соседи — `animateItem` на `motion.duration.long`/`standard`; reduced motion — токены `Reduced` | масштаб, поворот | 5.6 |
| I6-D14 | Новый `FeedbackCue.CardGrabbed` — только тактильный: `DRAG_START` (API 34+), `GESTURE_START` (30–33), `VIRTUAL_KEY` (26–29); звука у захвата нет | без отдачи на захват; вибрация из UI | 6 |
| I6-D15 | Отдача перетаскивания: `CardGrabbed` один раз на принятый `DragStarted`; `CardMoved` — не более одного на подтверждённую перестановку, тот же контракт, что у кнопок; вся отдача — только эффектом ViewModel | тактильный отклик из `Modifier.pointerInput` | 6 |
| I6-D16 | Объявление: кнопки и custom actions — одно на успешную перестановку; drag — одно при `DragFinished`, если позиция отличается от начальной; исполнение — через границу `AccessibilityAnnouncer` (`ui/platform`), текущая реализация — `announceForAccessibility` с явным подавлением предупреждения об устаревании | объявление на каждое пересечение при drag | 7.3 |
| I6-D17 | `liveRegion` у контейнера списка удаляется: объявление одно — эффектом | оставить оба канала | 7.3 |
| I6-D18 | Подписи custom actions — существующие ресурсы «Переместить вверх/вниз/в начало/в конец»; `COMPONENTS.md` и чек-лист правятся | переименовать ресурсы в «выше/ниже» | 7.2 |
| I6-D19 | Семантика карточки не меняется: один объединённый узел «Позиция N из 4. {Название}» с применимыми действиями; ручка узла не создаёт; `MoveButton` — отдельные узлы | ручка как фокус-стоп | 7.1 |
| I6-D20 | Reduced motion — реактивный `rememberReducedMotion()`: перечитывает `ANIMATOR_DURATION_SCALE` на каждом `ON_RESUME`; все анимации, переходы и reveal берут токены только через него | чтение один раз на `Context` | 7.7 |
| I6-D21 | `DragEducationHint` — в 6C по `COMPONENTS.md`; видимость — `Playing.showDragHint` во ViewModel; таймеры 4000/600 мс — в UI по токенам `dragHint.*`; флаг — fire-and-forget команда `SettingMutation.DragHintSeen` через use case: при потере записи из-за смерти процесса подсказка может появиться ещё раз, и это допустимо | подсказка в 6A; подтверждаемая запись ради безвредного повтора | 7.9 |
| I6-D22 | `heading()` получает и формулировка задания на `Puzzle`; инвентарь заголовков фиксирован таблицей 7.4 | только заголовок шапки | 7.4 |
| I6-D23 | Клавиатура и D-pad: достижимость `MoveButton`, «Проверить» и остальных целей стандартным фокусом Compose; drag с клавиатуры и сочетания клавиш не вводятся | Ctrl+↑/↓ | 7.8 |
| I6-D24 | Напоминания: доменная граница `domain/reminder` (чистые расчёты, интерфейсы `ReminderScheduler`, `ReminderNotifier`, `NotificationAccess`, use cases, `ReminderRun`, `SyncReminderScheduleUseCase`); Android-реализации и оркестрация процесса (`@ApplicationScope`, коллектор, приёмник) — `notifications/`; `notifications` зависит от `domain`, ни `ui`, ни `data` не импортирует; `domain/reminder` не импортирует `ru.poporyadku.di` | Android-код или `@ApplicationScope` в `domain`; worker, вызывающий репозитории напрямую | 9.1 |
| I6-D25 | Подключается `work-runtime-ktx` из catalog (ADR-006; версия перепроверяется по первичному источнику в 6B); `hilt-work` и `work-testing` не подключаются: зависимости worker'а — через Hilt `EntryPoint` | `@HiltWorker` + `hilt-work` | 9.1 |
| I6-D26 | Одна уникальная работа `daily_reminder`, `OneTimeWorkRequest` без ограничений; каждая операция WorkManager ожидается (`Operation.await`); синхронизация — `REPLACE`; перепланирование worker'ом — `APPEND_OR_REPLACE`, только если синхронизация уже не поставила ожидающую работу; все записи планировщика в процессе — под `ReminderScheduleLock`; выключение — `cancelUniqueWork` | `PeriodicWorkRequest`; `KEEP`; операции без ожидания | 9.3 |
| I6-D27 | `WorkRequest` несёт только `targetDate` (ISO) и `minuteOfDay` (Int) | передача настроек, счёта, `puzzleId` | 9.6 |
| I6-D28 | Следующий момент — ближайшее вхождение локального времени строго после «сейчас» в текущей зоне через `ZonedDateTime.of`: пропуск DST сдвигает вперёд на длину разрыва, перекрытие берёт более раннее смещение | фиксированные 24 часа от прошлого срабатывания | 9.2 |
| I6-D29 | Одна операция `SyncReminderScheduleUseCase` (`suspend`, под `ReminderScheduleLock`): перечитывает `reminderEnabled`/`reminderTime`, берёт один `TimeSnapshot`, ожидает `schedule`/`cancel`, возвращает `Scheduled`/`Cancelled`/`Failed`; её вызывают постоянный `ReminderScheduleObserver` (`notifications`, `@ApplicationScope`, старт из `MainActivity.onCreate`), приёмник и worker с неразбираемыми данными | `MutableSharedFlow` сигналов resync; вызовы планировщика из `SettingsViewModel`; запуск из `Application.onCreate` | 9.4 |
| I6-D30 | Приёмник `ReminderTimeChangeReceiver` в манифесте на `TIME_SET` и `TIMEZONE_CHANGED` (исключения из запрета неявных broadcast), `exported="false"`: `goAsync()` → `ReminderBroadcastHandler` выполняет `SyncReminderScheduleUseCase` до конца и вызывает `finish()` только по завершении корутины; холодному процессу `MainActivity` не нужна | сигнал в поток, собираемый только после старта `MainActivity`; только самокоррекция worker'а | 9.4, 9.5 |
| I6-D31 | Рантайм-приёмник смены даты для Home (O-2 итерации 3) не вводится: тикер и `ON_START` уже закрывают сценарии; O-2 закрывается | регистрация приёмников в `HomeRoute` | 9.5 |
| I6-D32 | Worker не вызывает `GetTodayStateUseCase`, `StartDailySessionUseCase`, `ContentInstaller`, запись попыток и сеттеры настроек; решение — только `peek()` + `getDayResult(today)` + чтение настроек и разрешения | переиспользовать `GetTodayStateUseCase` | 9.6 |
| I6-D33 | Показ разрешён, когда решение политики `NewSet`/`CarryOver` или `Assigned` без закрытых слотов сегодня; день в процессе — по **O6-1**; `AwaitingNextDay`, `ContentExhausted`, завершённый день, ошибки — не показывать | показ при любом незавершённом дне | 9.6 |
| I6-D34 | Канал `daily_reminder`, `IMPORTANCE_DEFAULT`, без звука и вибрации, без значка на иконке; уведомление `REMINDER_NOTIFICATION_ID = 1001`, `CATEGORY_REMINDER`, `autoCancel`, `onlyAlertOnce`, `timeoutAfter` до начала следующей локальной даты; канал создаётся идемпотентно в `MainActivity.onCreate` и перед каждым показом | канал при первом запуске один раз | 10 |
| I6-D35 | Нажатие — `PendingIntent` на `MainActivity` с `ACTION_MAIN`/`CATEGORY_LAUNCHER`, `FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT`, постоянный `requestCode`; без deep link: холодный старт — Home, живая задача выводится на передний план как есть; `MainActivity.onStart` снимает показанное напоминание | deep link в `puzzle/*` | 10 |
| I6-D36 | Доступ — доменный статус `NotificationAvailability` (`Allowed`, `RuntimePermissionMissing`, `AppNotificationsDisabled`, `ChannelDisabled`); `POST_NOTIFICATIONS` запрашивается только при `RuntimePermissionMissing` (API 33+); при блокировке приложения или канала — сразу системные настройки нужной цели, подсказка и действие; решение после callback и после возврата принимается по перечитанному статусу, не по булеву результату | `Boolean isAllowed`; `shouldShowRequestPermissionRationale` + флаг «уже спрашивали» | 8.2 |
| I6-D37 | Экран показывает переключатель как `reminderEnabled && availability() == Allowed`, перечитывая статус на `ON_START`; отзыв доступа вне приложения ничего не пишет; единственная запись от `ON_START` — выполнение сохранённого намерения `pendingEnable` при `Allowed` | автоматически записывать `false` при отзыве | 8.1, 8.2 |
| I6-D38 | Время записывается одной командой `SettingMutation.ReminderTime` по подтверждению выбора; форма выбора — **O6-3** | запись на каждое изменение поля | 8.1 |
| I6-D39 | `SettingMutation` расширяется: `ReminderEnabled`, `ReminderTime`, `DragHintSeen`; очередь та же; экран настроек показывает ошибки только ключей `Reminder`, `ReminderTime`; `notificationPromptShown` через очередь **не** пишется | отдельная очередь напоминаний; отметка предложения fire-and-forget | 8.1 |
| I6-D40 | Предложение на `DayRecap` — отдельный `ReminderPromptViewModel` в том же route-контейнере (`DayRecapViewModel` по-прежнему не получает настройки, I5-D31); условие — сессионный вариант, день завершён, `!notificationPromptShown`, `!reminderEnabled`; отметка — подтверждаемая `suspend`-запись `MarkReminderPromptShownUseCase`, системный запрос создаётся только после её успеха; включение — только при перечитанном `Allowed` | поля диалога в `DayRecapViewModel`; `SettingsWriteQueue.submit` перед запросом | 8.3 |
| I6-D41 | Сети нет: ни `INTERNET`, ни локальных сетевых разрешений, ни сетевых вызовов; `ACCESS_NETWORK_STATE`, объявленный WorkManager, остаётся — это normal-разрешение на чтение состояния сети без сетевого доступа; транзитивное разрешение библиотеки без официально документированной гарантии WorkManager не удаляется; итоговый манифест проверяется тестом | удалить `ACCESS_NETWORK_STATE` манифестным слиянием | 10.4 |
| I6-D42 | Все новые пользовательские тексты — ресурсы; формулировки вне утверждённых документов — предложения таблицы 8.6 до подтверждения владельцем | литералы; тексты «по аналогии» | 8.6 |
| I6-D43 | Переходы между экранами задаются явно в `NavHost` через токены: вход `motion.duration.long`/`standardDecelerate`, выход `motion.duration.exit`/`standardAccelerate`; тип перехода — **O6-5** | библиотечный `tween(700)` | 11.3 |
| I6-D44 | Появление результата — фиксированные группы, не более 8 шагов со сдвигом `motion.stagger.resultReveal`; только альфа, семантика доступна с первого кадра, CTA доступна сразу, повтора после пересоздания нет (**O6-5**) | анимация каждой строки без предела | 11.3 |
| I6-D45 | Ни одна анимация не задерживает навигацию, ввод и доступность содержимого; одиночная анимация ≤ `motion.duration.long`, последовательность reveal ≤ 7 × 60 + 100 = 520 мс | — | 11.3 |
| I6-D46 | Иконки: адаптивная иконка приложения (foreground, background, monochrome) и иконка уведомления — векторные ресурсы из входных данных владельца с записанным происхождением; Design Gate ассетов не создаёт | сгенерировать иконку в PR | 11.4 |
| I6-D47 | Полировка ограничена таблицей расхождений 11.1; каждое исправление ссылается на токен или правило; нового визуального языка нет | «приведение к лучшему виду» без аудита | 11.1 |
| I6-D48 | Тесты несут префикс `I6-`; ни один тест итерации 6 не засчитывается вместо `I4-C6`, `I5-M5`, `I5-M6`, `I5-M10` и физических проверок раздела 15 | общие имена | 14 |
| I6-D49 | Ошибки планирования и показа не видны пользователю и не влияют на игровой поток: синхронизация возвращает `Failed` (не успех), `ReminderRun` возвращает ошибки оценки и планировщика раздельно; везде пробрасывается только `CancellationException`, и после отмены нет ни показа, ни перепланирования | ошибка на экране настроек; перепланирование в блоке, выполняемом при отмене | 9.6, 9.8 |
| I6-D50 | Итоговая матрица UI (раздел 11.5) — таблица «экран × условие → тест или ручная проверка», а не отдельный гигантский тест | один instrumented-тест на итерацию | 11.5 |

---

## 4. Единый путь перестановки (PR 6A)

### 4.1 Намерение перестановки (I6-D3)

Каждый источник перестановки — кнопка, custom action, жест — выражается одним и тем же намерением: «карточку `cardId` поставить на цель». Цель описывает, **куда**, а не «откуда»:

```kotlin
// ui/puzzle/CardOrder.kt — чистый Kotlin, ни одного импорта android.* / androidx.compose.*
sealed interface MoveTarget {
    data object Up : MoveTarget            // текущий индекс − 1
    data object Down : MoveTarget          // текущий индекс + 1
    data object First : MoveTarget         // 0
    data object Last : MoveTarget          // lastIndex
    data class Index(val index: Int) : MoveTarget   // абсолютный индекс — только жест
}
```

Абсолютный индекс у жеста выбран потому, что он **идемпотентен**: если UI дважды пришлёт одну и ту же цель до перекомпозиции, вторая команда найдёт карточку уже на месте и ничего не сделает. Относительный «шаг вниз» в той же гонке переставил бы карточку дважды.

### 4.2 Единственная функция перестановки (I6-D4)

```kotlin
object CardOrder {
    /**
     * @return новый порядок, если перестановка меняет позицию карточки; `null`, если
     * действие неприменимо: неизвестный cardId, цель вне 0..lastIndex, цель равна
     * текущему индексу. Порядок остальных карточек сохраняется.
     */
    fun move(order: List<String>, cardId: String, target: MoveTarget): List<String>? {
        val index = order.indexOf(cardId)
        if (index < 0) return null
        val to = when (target) {
            MoveTarget.Up -> index - 1
            MoveTarget.Down -> index + 1
            MoveTarget.First -> 0
            MoveTarget.Last -> order.lastIndex
            is MoveTarget.Index -> target.index
        }
        if (to == index || to !in order.indices) return null
        return order.toMutableList().apply { add(to, removeAt(index)) }
    }
}
```

Инварианты, проверяемые `I6-R1`, `I6-R2`: результат — перестановка входа (то же множество, тот же размер, без дубликатов); перемещённая карточка стоит на цели; относительный порядок остальных сохранён. Функция заменяет лямбду `targetIndex` в текущем `move()`; `PuzzleBoard` строится из результата прежним кодом.

### 4.3 Контракт событий (I6-D5)

```kotlin
/** Идентификатор одного жеста: уникален в пределах процесса, в том числе между экземплярами UI. */
@JvmInline value class DragGestureId(val value: Long)

// ui/puzzle/DragGestureIds.kt — процессный монотонный счётчик; не сбрасывается при пересоздании Activity
object DragGestureIds { private val counter = AtomicLong(0); fun next() = DragGestureId(counter.incrementAndGet()) }

sealed interface PuzzleEvent {
    // Кнопки и custom actions — без изменений имён
    data class MoveUp(val cardId: String) : PuzzleEvent
    data class MoveDown(val cardId: String) : PuzzleEvent
    data class MoveToTop(val cardId: String) : PuzzleEvent
    data class MoveToBottom(val cardId: String) : PuzzleEvent

    // Жест — заменяет DragStarted/DragMoved(from,to)/DragEnded итерации 3
    data class DragStarted(val cardId: String, val gesture: DragGestureId) : PuzzleEvent
    data class DragMovedTo(val cardId: String, val targetIndex: Int, val gesture: DragGestureId) : PuzzleEvent
    /** И отпускание, и отмена, и потеря указателя: поведение одинаково (I6-D8). */
    data class DragFinished(val cardId: String, val gesture: DragGestureId) : PuzzleEvent

    data object DragHintDismissed : PuzzleEvent     // заполняется в 6C
    data object Submit : PuzzleEvent
    data object BackPressed : PuzzleEvent
    data object RetryClicked : PuzzleEvent
    data object SkipClicked : PuzzleEvent
}
```

`DragGestureId` выдаёт UI в `onDragStart` один раз на жест и передаёт во все события этого жеста. Счётчик — процессный объект, а не поле `ReorderDragState`: после пересоздания Activity новый экземпляр UI не может повторить идентификатор прежнего жеста, поэтому запоздалое событие старого жеста не совпадёт с новым. После смерти процесса ViewModel тоже новая и сессии не имеет — совпадение идентификаторов разных процессов значения не имеет. Одного `cardId` для этой защиты недостаточно: тот же пользователь законно тянет ту же карточку второй раз.

`PuzzleEffect` не меняется: `AnnounceCardMoved` и `Feedback` остаются; меняется только набор мест, где они отправляются (4.4). `PuzzleBoard.draggedCardId` удаляется (**I6-D9**); `Playing.showDragHint` остаётся.

### 4.4 ViewModel: одна точка изменения порядка

```kotlin
private enum class MoveOrigin { Button, Drag }
private data class DragSession(val cardId: String, val gesture: DragGestureId, val startIndex: Int)
private var dragSession: DragSession? = null   // только память, не SavedStateHandle (I6-D9)

fun onEvent(event: PuzzleEvent) = when (event) {
    is PuzzleEvent.MoveUp -> reorder(event.cardId, MoveTarget.Up, MoveOrigin.Button)
    is PuzzleEvent.MoveDown -> reorder(event.cardId, MoveTarget.Down, MoveOrigin.Button)
    is PuzzleEvent.MoveToTop -> reorder(event.cardId, MoveTarget.First, MoveOrigin.Button)
    is PuzzleEvent.MoveToBottom -> reorder(event.cardId, MoveTarget.Last, MoveOrigin.Button)
    is PuzzleEvent.DragStarted -> onDragStarted(event.cardId, event.gesture)
    is PuzzleEvent.DragMovedTo -> onDragMovedTo(event.cardId, event.targetIndex, event.gesture)
    is PuzzleEvent.DragFinished -> onDragFinished(event.cardId, event.gesture)
    /* … Submit, Skip, Retry, Back, DragHintDismissed … */
}

/** Единственное место изменения порядка. @return true, если порядок изменился. */
private fun reorder(cardId: String, target: MoveTarget, origin: MoveOrigin): Boolean {
    val playing = state.value as? PuzzleUiState.Playing ?: return false
    if (origin == MoveOrigin.Button) dragSession = null      // кнопка закрывает висящую сессию молча
    val order = playing.board.cards.map { it.cardId }
    val next = CardOrder.move(order, cardId, target) ?: return false
    state.value = playing.copy(board = rebuild(playing.board, next))
    savedStateHandle[KEY_CURRENT_ORDER] = next.joinToString(ORDER_SEPARATOR)
    savedStateHandle[KEY_ORDER_PUZZLE_ID] = playing.board.puzzleId
    emitFeedback(FeedbackCue.CardMoved)                       // ≤ 1 на подтверждённую перестановку
    if (origin == MoveOrigin.Button) announce(cardId, next)   // drag объявляет при завершении
    return true
}

private fun onDragStarted(cardId: String, gesture: DragGestureId) {
    val playing = state.value as? PuzzleUiState.Playing ?: return
    val index = playing.board.cards.indexOfFirst { it.cardId == cardId }
    if (index < 0) return
    if (dragSession?.gesture == gesture) return              // дубликат начала ЭТОГО жеста — без второй отдачи
    // Любая другая сессия (в том числе той же карточки из уничтоженного UI) заменяется целиком:
    // startIndex берётся заново, старый идентификатор больше ничего не закроет.
    dragSession = DragSession(cardId, gesture, index)
    emitFeedback(FeedbackCue.CardGrabbed)
    // 6C: скрыть DragEducationHint, если показана
}

private fun onDragMovedTo(cardId: String, targetIndex: Int, gesture: DragGestureId) {
    val session = dragSession ?: return
    if (session.gesture != gesture || session.cardId != cardId) return   // чужой или устаревший жест
    reorder(cardId, MoveTarget.Index(targetIndex), MoveOrigin.Drag)
}

private fun onDragFinished(cardId: String, gesture: DragGestureId) {
    val session = dragSession ?: return
    if (session.gesture != gesture || session.cardId != cardId) return   // запоздалое завершение старого жеста
    dragSession = null
    val playing = state.value as? PuzzleUiState.Playing ?: return
    val order = playing.board.cards.map { it.cardId }
    if (order.indexOf(cardId) != session.startIndex) announce(cardId, order)
}

private fun onSubmit() {
    dragSession = null                                        // отправляется последний подтверждённый порядок
    /* … прежняя логика итерации 3 … */
}
```

Следствия, каждое проверяется тестом (раздел 14):

| Свойство | Как обеспечено | Тест |
| --- | --- | --- |
| Один алгоритм для кнопок, custom actions и жеста | все зовут `reorder()` → `CardOrder.move` | `I6-V7` |
| Одна перестановка → не более одного `CardMoved` | `emitFeedback` только после ненулевого `move` | `I6-V1`…`I6-V3` |
| Дубликат `DragMovedTo` ничего не делает | абсолютная цель + `move` → `null` | `I6-V3` |
| Во время жеста нет объявлений; при завершении — одно, относительно начала этого жеста | `origin == Drag`; `startIndex` сессии этого `DragGestureId` | `I6-V4`, `I6-V9` |
| Дубликат начала жеста не даёт второй отдачи; новый жест той же карточки — даёт одну | сравнение `DragGestureId`, а не `cardId` | `I6-V5`, `I6-V9` |
| Запоздалые события старого жеста не трогают новую сессию | `gesture` не совпадает — ранний выход | `I6-V9`, `I6-V10` |
| Вне `Playing` жест ничего не меняет и не пишет | ранний выход `reorder`/`onDragStarted` | `I6-V6` |
| Отправка во время жеста отправляет подтверждённый порядок | `onSubmit` читает `board`, сессия сбрасывается | `I6-V8` |
| Восстановление процесса не создаёт отдачи и объявлений | `orderOf()` не зовёт `reorder` | `I6-V9` |

### 4.5 Сессия перетаскивания (I6-D9)

Сессия — `(cardId, DragGestureId, startIndex)` в памяти ViewModel. Она нужна ровно для двух вещей: не отдавать `CardGrabbed` дважды за **один** жест и объявить результат **этого** жеста один раз.

| Правило | Почему |
| --- | --- |
| Сессия не сохраняется в `SavedStateHandle` | после поворота, пересоздания Activity или смерти процесса пальца на экране нет; восстановленная сессия означала бы «поднятую» карточку без жеста |
| `DragStarted` с тем же `DragGestureId` — ничего | повтор начала одного жеста (двойная доставка) |
| `DragStarted` с другим `DragGestureId` — новая сессия, новый `startIndex`, один `CardGrabbed` | новый UI после пересоздания не наследует незавершённую сессию, даже если карточка та же |
| `DragMovedTo`/`DragFinished` принимаются только при совпадении `DragGestureId` и `cardId` | старое завершение не может закрыть новую сессию и объявить результат не того жеста |
| Кнопка, custom action, `Submit` закрывают сессию молча | подтверждённый порядок уже в состоянии; жест, начатый до них, больше не объявляется |
| Незавершённая сессия без новых событий | безвредна: не влияет на порядок, отдачу и объявления других источников |

---

## 5. Перетаскивание (PR 6A)

### 5.1 Жест и конфликт со скроллом (I6-D10)

| Вопрос | Решение |
| --- | --- |
| Где начинается жест | только в зоне `DragHandle`: `size.dragHandle.touchTarget` = 48 × 48 dp внизу индексной зоны `OrderableCard`; глиф — `icon.size.dragHandleGlyph` 16 × 24 dp, `onSurfaceVariant` |
| Длинное нажатие | не требуется: `Modifier.pointerInput(cardId, interactive) { detectDragGestures(onDragStart, onDrag, onDragEnd, onDragCancel) }` на ручке |
| Порог начала | системный `ViewConfiguration.touchSlop` (не токен дизайна, а платформенная константа ввода) |
| Направление | используется только вертикальная составляющая смещения; горизонтальная игнорируется |
| Конфликт со скроллом `LazyColumn` | указатель сначала получает ручка (дочерний узел): после порога она потребляет изменения, и `scrollable` родителя видит потреблённые события и не прокручивает. Свайп по карточке вне ручки и по кнопкам прокручивает список обычным образом |
| Центральная зона карточки | остаётся неинтерактивной (`COMPONENTS.md`) |
| Идентификатор жеста | `onDragStart` берёт `DragGestureIds.next()` и хранит его в `ReorderDragState` до `onDragEnd`/`onDragCancel` (4.3) |
| Мультитач | UI держит не более одной активной сессии: второй `onDragStart`, пока первая не завершена, игнорируется и событий не отправляет |

### 5.2 Доступность жеста по состояниям (I6-D11)

| Состояние | Ручка | Жест | Кнопки ↑/↓ | Custom actions |
| --- | --- | --- | --- | --- |
| `Loading` | нет (скелетон) | — | — | — |
| `Playing` | есть | работает | по `canMoveUp/Down` | применимые |
| `Submitting.Answer` | есть, тот же вид | не принимается: `pointerInput` пересоздаётся по ключу `interactive = false`, активный жест отменяется, `DragFinished` уходит и игнорируется ViewModel (состояние не `Playing`, сессия уже сброшена `onSubmit`) | disabled | отсутствуют |
| `Error`, `Submitting.Skip` | нет (нет «стола») | — | — | — |
| `PuzzleResult` (read-only) | нет в дереве | — | нет в дереве | нет |

Отдельного `disabled`-вида у ручки нет: окно `Submitting` — десятки миллисекунд, а «Проверить» и кнопки перемещения уже показывают блокировку. Это фиксируется в `COMPONENTS.md` (раздел 17).

### 5.3 Визуальное смещение и единственный порог перестановки (I6-D6, I6-D7)

Состояние жеста в UI:

```kotlin
// ui/puzzle/ReorderDragState.kt — Compose-состояние экрана, НЕ saveable
@Stable
class ReorderDragState(private val listState: LazyListState) {
    var draggedCardId: String? by mutableStateOf(null); private set
    var gesture: DragGestureId? = null; private set
    /** Смещение карточки относительно её текущего слота, px. Только визуал. */
    var offsetY: Float by mutableFloatStateOf(0f); private set
    /* start(cardId), drag(deltaY) → targetIndex?, onScrolled(delta), onSlotMoved(), finish() */
}
```

**Единственное правило порога:** перетаскиваемая карточка переходит через соседнюю карточку тогда и только тогда, когда **её визуальный центр строго пересекает центр этой соседней карточки**. Попадание центра в границы карточки или любое фиксированное расстояние в dp не используется.

Обозначения кадра (координаты видимой области списка, px; всё берётся из `listState.layoutInfo.visibleItemsInfo`, только элементы карточек — `prompt` и подсказка исключаются по ключу):

- `i` — текущий подтверждённый индекс перетаскиваемой карточки;
- `C = slotTop(i) + offsetY + height(i) / 2` — визуальный центр перетаскиваемой карточки;
- `center(k) = slotTop(k) + height(k) / 2` — центр карточки, стоящей на подтверждённом индексе `k ≠ i`, в текущей раскладке; для невидимой карточки центр неизвестен.

```kotlin
// ui/puzzle/DragTargetResolver.kt — чистая функция
fun targetIndex(i: Int, lastIndex: Int, draggedCenter: Float, centerOf: (Int) -> Float?): Int {
    var target = i
    // вниз: проходим соседей ниже, пока центр строго ниже их центра
    while (target < lastIndex) {
        val c = centerOf(target + 1) ?: break      // невидимый сосед — стоп до следующего кадра
        if (draggedCenter > c) target++ else break
    }
    if (target != i) return target
    // вверх: симметрично
    while (target > 0) {
        val c = centerOf(target - 1) ?: break
        if (draggedCenter < c) target-- else break
    }
    return target
}
```

| Случай | Поведение алгоритма |
| --- | --- |
| Движение вниз | цель растёт, пока `C > center(target + 1)`; равенство перестановки не даёт |
| Движение вверх | цель уменьшается, пока `C < center(target − 1)` |
| Карточки разной высоты | используются фактические `slotTop` и `height` каждой карточки: высокой соседке нужно пройти до её собственного центра, а не до усреднённого шага |
| Быстрый проход нескольких карточек за кадр | цикл проходит всех соседей, чьи центры пересечены, — одна цель, одно `DragMovedTo`, одна перестановка и одна отдача |
| Крайние позиции | циклы ограничены `0` и `lastIndex`; выше первой и ниже последней цели нет |
| Невидимый сосед | цикл останавливается на нём; после прокрутки (auto-scroll) сосед становится видимым и проверяется в следующем кадре |
| Auto-scroll | прокрутка на `d` сдвигает слот перетаскиваемой карточки на `−d`; UI в том же кадре выполняет `offsetY += d` (карточка остаётся под пальцем) и заново вызывает `targetIndex` |
| Обратное движение без дребезга | см. доказательство ниже |

Кадр:

1. `onDrag(delta)`: `offsetY += delta.y`; при auto-scroll — `offsetY += d` после `scrollBy(d)`.
2. `target = targetIndex(i, lastIndex, C, center)`.
3. Если `target ≠ i` — `onEvent(DragMovedTo(cardId, target, gesture))`.
4. Когда подтверждённый порядок пришёл и слот карточки сдвинулся, UI выполняет `offsetY -= (новый slotTop − прежний slotTop)`: `C` не меняется, карточка не прыгает. До прихода нового порядка шаг 2 даёт ту же цель, а повтор `DragMovedTo` с той же целью — пустая операция (**I6-D3**).

**Почему нет дребезга.** Пусть карточка D высоты `hD` на индексе `i`, соседка N высоты `hN` на `i + 1`, зазор `g`, `top = slotTop(i)`. Перестановка вниз происходит при `C > center_N_до = top + hD + g + hN / 2`. После перестановки N занимает слот `i` и `center_N_после = top + hN / 2`, а `C` не изменился (шаг 4). Обратная перестановка требует `C < top + hN / 2`, то есть смещения вверх не меньше `hD + g` от точки пересечения — больше половины высоты любой карточки. Для движения вверх рассуждение симметрично. Колебание пальца около порога поэтому не даёт чередования перестановок.

| Вопрос | Ответ |
| --- | --- |
| Источник истины во время жеста | `PuzzleUiState.Playing.board.cards` (ViewModel). UI не хранит своего порядка |
| Источник истины после жеста | тот же; UI обнуляет `offsetY`, `draggedCardId`, `gesture` |
| Что временно | только `offsetY` и признак «поднята» |
| Что подтверждено | каждая перестановка, прошедшая `reorder()`: состояние, `SavedStateHandle`, отдача |
| Несколько позиций за жест | да — последовательными подтверждениями, либо одним подтверждением при проходе нескольких центров за кадр |
| Потеря или дублирование карточек | невозможны по построению: UI не создаёт порядка, `CardOrder.move` возвращает перестановку, `LazyColumn` ключуется `cardId` |

### 5.4 Auto-scroll (I6-D12)

| Параметр | Значение |
| --- | --- |
| Когда включается | верх перетаскиваемой карточки заходит в верхнюю краевую зону видимой области списка (с учётом `contentPadding`) или низ — в нижнюю, **и** `listState.canScrollBackward/Forward` |
| Краевая зона | токен `dragAutoScroll.edgeZone` — предлагается как ссылка на `size.touchTarget.min` (48 dp), без нового числа (**O6-6**) |
| Скорость | `v = maxVelocity × (глубина захода / edgeZone)`, глубина ограничена `edgeZone`; токен `dragAutoScroll.maxVelocity` — предлагается выводить из существующих: одна карточка с зазором за `motion.duration.long`, (`size.orderableCard.minHeight` + `spacing.listGap`) / `motion.duration.long` ≈ 413 dp/с (**O6-6**) |
| Реализация | корутина в `LaunchedEffect(draggedCardId)`: `while (активна) { withFrameNanos { dt -> val d = listState.scrollBy(v × dt); offsetY += d } ; пересчитать цель тем же `targetIndex` (5.3) }` |
| Остановка | `draggedCardId == null` (отпускание, отмена); выход из `Playing` (ключ `interactive`); уход из композиции (отмена корутины); `canScroll* == false`; карточка вышла из краевой зоны |
| Короткий список | `canScroll*` ложны — цикл не стартует |
| Прокручиваемый список (320 dp, 200 %, ландшафт) | список прокручивается, пройденные карточки подтверждаются как обычно |
| Reduced motion | не влияет: это функциональная прокрутка по пальцу, а не декоративная анимация |
| Бесконечная прокрутка | исключена: условие `canScroll*` проверяется на каждом кадре, цикл привязан к жизни сессии |

Чистая часть — `DragAutoScroll.velocity(itemTop, itemBottom, viewportStart, viewportEnd, edgeZonePx, maxVelocityPx)` — проверяется на JVM (`I6-R4`).

### 5.5 Отмена, потеря указателя, навигация, поворот, смерть процесса (I6-D8)

| Событие | UI | ViewModel | Итог |
| --- | --- | --- | --- |
| Палец отпущен | `finish()`, `DragFinished(cardId, gesture)` | объявление, если позиция отличается от `startIndex` этого жеста | последний подтверждённый порядок |
| `ACTION_CANCEL`, системный жест, звонок | `onDragCancel` → то же | то же | то же; отката нет |
| Уход с экрана во время жеста (назад, навигация) | композиция уходит, корутины отменяются, `DragFinished` может не дойти | сессия висит в памяти, пока не придёт новый жест, кнопка или `Submit` | порядок в `SavedStateHandle` уже подтверждён |
| Поворот / пересоздание Activity без `DragFinished` | `ReorderDragState` не saveable — карточка не «прилипает»; следующий жест получает новый `DragGestureId` | новый `DragStarted` заменяет висящую сессию: новый `startIndex`, один `CardGrabbed`; запоздалый `DragFinished` старого жеста игнорируется | порядок сохранён, объявление — относительно начала нового жеста |
| Смерть процесса | — | новая ViewModel, сессии нет; восстановление `orderOf()` | последний подтверждённый порядок, отдачи нет |
| Переход в `Submitting` | ключ `interactive` отменяет жест | `onSubmit` сбросил сессию | отправлен подтверждённый порядок |

### 5.6 Внешний вид (I6-D13)

| Элемент | Правило | Токены |
| --- | --- | --- |
| Поднятая карточка | `Modifier.zIndex` выше соседей; `graphicsLayer { translationY = offsetY }`; тень `elevation.dragged`; поверх заливки — `primary` с `opacity.dragStateLayer`; масштаб и поворот не меняются | `elevation.dragged`, `opacity.dragStateLayer` |
| Подъём / опускание | анимация тени и слоя: подъём `motion.duration.medium`/`standardAccelerate`, опускание `motion.duration.medium`/`emphasizedDecelerate` | `MotionTokens` |
| Соседи | `animateItem(placementSpec = tween(durationLong, easingStandard))`; у поднятой карточки `placementSpec = null`, чтобы она не «догоняла» палец анимацией | `motion.duration.long`, `motion.easing.standard` |
| `CardIndex` | меняется вместе с подтверждением, без собственной анимации (`DESIGN_PRINCIPLES.md` §9) | — |
| Reduced motion | все длительности — токены `Reduced` (1 мс, linear); тень и слой всё равно появляются — это состояние, а не декор | `Motion.Reduced` |
| Контраст новых пар | глиф ручки `onSurfaceVariant` на `surfaceContainerLow`: 6,66 : 1 (светлая), 7,43 : 1 (тёмная); текст на поднятой карточке (смесь 16 % `primary` на `surfaceContainerLow`): `onSurface` 11,18 / 11,37, `onSurfaceVariant` 5,10 / 6,64 — пары вносятся в `DESIGN_TOKENS.md` §6.2 в 6A (расчёт по формуле WCAG, этой ревизией) | — |
| Высота карточки при 100 % | ручка встраивается в существующую индексную зону; `size.orderableCard.minHeight` = 112 dp не растёт (`I6-C1`) | — |

`zIndex` — порядок отрисовки, а не визуальное значение: литерал уровня (выше соседей) допускается как структурная константа рядом с `WEIGHT_FILL`, но не как размер или цвет.

---

## 6. Отдача при перетаскивании (PR 6A)

### 6.1 События

| Cue | Когда | Звук | Тактильно | Не возникает |
| --- | --- | --- | --- | --- |
| `CardGrabbed` (новый) | ViewModel принял `DragStarted` в `Playing` для карточки без открытой сессии | **нет** | API 34+: `DRAG_START`; API 30–33: `GESTURE_START`; API 26–29: `VIRTUAL_KEY` | повтор `DragStarted` той же карточки; вне `Playing`; вибрация выключена или настройки `Unknown` |
| `CardMoved` | каждая подтверждённая перестановка, из любого источника | по настройке | `CLOCK_TICK` (без изменений) | цель совпала с позицией; дубликат цели; вне `Playing`; восстановление |
| `AnswerAccepted` | без изменений (I5-D22) | | | |

**Почему новый cue.** `IMPLEMENTATION_PLAN.md` (итерация 6) требует «отклик вибрацией на захват и на смену позиции, если вибрация включена». Захват — не перестановка: отдавать на него `CardMoved` значило бы сообщать о факте, которого не было, и проигрывать звук перемещения до перемещения. Тактильный отклик из UI (`LocalHapticFeedback` в `pointerInput`) дал бы второй путь отдачи мимо настроек и `FeedbackPolicy` — ровно двойную вибрацию, которую нужно исключить. Константы подтверждены `api-versions.xml`: `DRAG_START since="34"`, `GESTURE_START since="30"`.

**Почему без звука.** Отдача подтверждает факт, а не хвалит (`DESIGN_PRINCIPLES.md` §9); захват фактом порядка не является, а звук захвата сразу перед звуком перемещения давал бы двойной щелчок на одно действие.

### 6.2 Изменения типов

```kotlin
enum class FeedbackCue { CardGrabbed, CardMoved, AnswerAccepted }

object FeedbackPolicy {
    fun requestFor(cue: FeedbackCue, settings: FeedbackSettings): FeedbackRequest? = when (settings) {
        FeedbackSettings.Unknown -> null
        is FeedbackSettings.Known -> {
            val sound = settings.soundEnabled && cue.hasSound
            val haptic = settings.vibrationEnabled
            if (!sound && !haptic) null else FeedbackRequest(cue, sound, haptic)
        }
    }
    private val FeedbackCue.hasSound get() = this != FeedbackCue.CardGrabbed
}
```

- `AndroidFeedbackPlayer.hapticFor(CardGrabbed)` — таблица 6.1.
- `SoundCueBank.play(cue)`: `sampleIds[cue] ?: return` вместо `getValue` — cue без звукового ресурса никогда не падает (`I6-F2`); карта звуков не расширяется.
- `PuzzleViewModel` новых зависимостей не получает.

### 6.3 Отсутствие двойной отдачи — правила

1. Единственный вход отдачи — `PuzzleViewModel.emitFeedback`; `rg` в `ui/puzzle` и `ui/components` не находит `performHapticFeedback`, `LocalHapticFeedback`, `HapticFeedbackType` (проверка `I6-K4`).
2. Исполнение — единственный коллектор `PuzzleRoute`, как в итерации 5.
3. Жест не порождает отдачу сам: UI шлёт только события.
4. Кнопочная перестановка сохраняет контракт `CardMoved` без изменений (`I5-V29`…`I5-V34` остаются зелёными).
5. Восстановление, перекомпозиция, поворот: отдачи нет — эффекта в состоянии нет, `orderOf()` не зовёт `reorder()`.

---

## 7. Доступность (PR 6C; семантика ручки — 6A)

Кнопки, жест и TalkBack сходятся в одном контракте: у карточки один доступный узел, у каждого способа перестановки — одно и то же событие ViewModel, у каждой успешной перестановки — одно объявление.

### 7.1 Семантика карточки и ручки (I6-D19)

| Узел | Семантика | Фокус TalkBack |
| --- | --- | --- |
| Содержимое карточки (индексная зона + текст) | `mergeDescendants`, `contentDescription = «Позиция N из 4. {Название}»` (`cd_card_position`), `customActions` — применимые | один фокус-стоп на карточку |
| `DragHandle` | не добавляет ни роли, ни описания, ни действия: `pointerInput` семантики не создаёт; глиф рисуется `Canvas`/`Icon(contentDescription = null)` | нет — перестановка для TalkBack только через действия (`COMPONENTS.md`, `DragHandle`) |
| `MoveButton` ↑ / ↓ | роль `Button`, «Переместить вверх» / «Переместить вниз», `disabled` на краях | отдельные фокус-стопы |
| Отличие от дубля | название карточки читается только в узле карточки; кнопки не повторяют название, ручка не читается вовсе | — |

После каждой подтверждённой перестановки описание всех затронутых карточек обновляется новой позицией (`I6-A1`).

### 7.2 Действия (I6-D18)

| Позиция | «Переместить вверх» | «Переместить в начало» | «Переместить вниз» | «Переместить в конец» |
| --- | --- | --- | --- | --- |
| 1 | нет | нет | есть | есть |
| 2, 3 | есть | есть | есть | есть |
| 4 | есть | есть | нет | нет |
| `Submitting.Answer`, read-only | нет | нет | нет | нет |

Каждое действие шлёт то же событие, что соответствующая кнопка (`MoveUp/MoveDown`) либо `MoveToTop/MoveToBottom`, и проходит `reorder()` (`I6-V7`, `I6-A3`). Возвращаемое значение `CustomAccessibilityAction` — `true` (действие принято экраном); результат перестановки сообщает только объявление.

### 7.3 Объявления (I6-D16, I6-D17)

| Источник | Объявление |
| --- | --- |
| Кнопка, custom action — успешная перестановка | ровно одно: «{Название} перемещён на позицию N из 4» (`puzzle_card_moved`) |
| Кнопка/действие без изменения (край, повтор, не `Playing`) | нет |
| Жест: пересечения порога | нет |
| Жест: `DragFinished`, позиция ≠ начальной | ровно одно, с финальной позицией |
| Жест вернул карточку на исходное место | нет |

**Удаление `liveRegion`.** `PuzzleScreen` сейчас помечает весь `LazyColumn` `liveRegion = Polite`. Живая область объявляет изменения своего поддерева, а при перестановке меняются описания нескольких карточек — TalkBack прочитал бы их поверх явного объявления. Один канал — эффект; `liveRegion` снимается (`I6-A5`), `UX_FLOW.md` §10 и чек-лист правятся (раздел 17).

**Устаревший API.** `View.announceForAccessibility` в `api-versions.xml` compileSdk 37 помечен `deprecated="36"`. Решение 6C:

```kotlin
// ui/platform/AccessibilityAnnouncer.kt
fun interface AccessibilityAnnouncer { fun announce(text: String) }

class ViewAccessibilityAnnouncer(private val view: View) : AccessibilityAnnouncer {
    @Suppress("DEPRECATION") // единственное место вызова; API 36+ помечен устаревшим (ITERATION_6_DESIGN.md, I6-D16)
    override fun announce(text: String) = view.announceForAccessibility(text)
}
val LocalAccessibilityAnnouncerOverride = staticCompositionLocalOf<AccessibilityAnnouncer?> { null }
```

- `PuzzleRoute` и копирование адреса в `SettingsRows` вызывают только границу; `rg -n announceForAccessibility app/src/main` находит один файл (`I6-K5`).
- На эмуляторе API 35 факт отправки `TYPE_ANNOUNCEMENT` ровно один раз проверяет `I6-N5` через `UiAutomation.setOnAccessibilityEventListener` — без TalkBack и новых зависимостей.
- Произносит ли объявление живой TalkBack на API 36+ — проверяется только на физическом устройстве (`I6-M10`, итерация 7). Если там объявление не звучит, запасной путь фиксируется заранее: скрытый от зрения текстовый узел статуса перестановки с `liveRegion = Polite`, обновляемый тем же эффектом. Он не вводится без провала проверки.

### 7.4 Порядок фокуса и заголовки (I6-D22)

Порядок обхода `Puzzle.Playing`: «Назад» → заголовок «Задание N из 3» (heading) → категория → формулировка задания (**heading**, новое) → подпись направления → подсказка (если показана) → [карточка 1 → ↑ → ↓] … [карточка 4 → ↑ → ↓] → «Проверить» (последняя). Порядок задаётся порядком композиции; `traversalIndex` не используется. «Проверить» закреплена вне `LazyColumn` и в дереве семантики идёт после списка.

| Экран | Узлы `heading()` после итерации 6 |
| --- | --- |
| Home | мастхед |
| Puzzle | заголовок `AppTopBar`, формулировка задания |
| PuzzleResult | заголовок `AppTopBar`, «Правильный порядок» |
| DayRecap | заголовок `AppTopBar`; в диалоге напоминания — текст вопроса |
| Archive | заголовок `AppTopBar`, «Статистика» |
| Settings | заголовок `AppTopBar`, заголовки групп (включая новую группу напоминания) |
| Sources | заголовок `AppTopBar` |

### 7.5 Результат без опоры на цвет

- Перепутанные пары — отдельные строки `InvertedPairRow`, каждая — отдельный узел с полной формой «Карточка «A» должна располагаться после карточки «B»» даже при визуальной сокращённой форме; число строк = `6 − score` (`I6-A8`).
- Карточки правильного порядка не различаются цветом; «Всё верно» — текст.
- Отдельного состояния «верно/неверно» у карточки на экране результата нет — и визуального, и семантического.

### 7.6 Цели касания, 200 %, 320 dp, ландшафт, темы

| Требование | Проверка |
| --- | --- |
| Все нажимаемые узлы Puzzle, PuzzleResult, DayRecap (с диалогом), Settings (с напоминанием) ≥ 48 × 48 dp, включая ручку | `I6-A9` на `w320dp-h844dp` при шрифте 200 % |
| Ручка не уменьшается при 200 % и 320 dp | `I6-A9` |
| Подсказка, строки напоминания, диалог не обрезаются при 200 % | `I6-U4` |
| Ландшафт: список Puzzle прокручивается, «Проверить» видна, колонка ≤ `contentMaxWidth` | `I6-U3` |
| Светлая и тёмная темы новых элементов | `I6-U5` |

### 7.7 Reduced motion (I6-D20)

```kotlin
// ui/theme/ReducedMotion.kt
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    var reduced by remember { mutableStateOf(readAnimatorScaleIsZero(context)) }
    LifecycleResumeEffect(context) { reduced = readAnimatorScaleIsZero(context); onPauseOrDispose { } }
    return reduced
}
@Composable fun rememberMotionTokens(): MotionTokens = Motion.resolve(rememberReducedMotion())
```

- Настройка ОС «Убрать анимацию» записывает `ANIMATOR_DURATION_SCALE = 0`; изменение подхватывается на возврате в приложение.
- Затрагиваемые места: перестановка (`animateItem`), подъём карточки, покачивание подсказки, шеврон источников, переходы экранов (6D), reveal результата (6D).
- `dragHint.autoHideDelay` и `dragHint.submitLockDuration` не сокращаются (`DESIGN_TOKENS.md` §6.8).
- Auto-scroll и таймеры логики не зависят от настройки.

### 7.8 Клавиатура и D-pad (I6-D23)

`MoveButton`, «Проверить», «Назад», строки настроек — фокусируемые кликабельные узлы Compose и активируются Enter/центром D-pad без дополнительного кода (`I6-A13`). Перестановка с клавиатуры идёт через кнопки; жест и сочетания клавиш не вводятся — это не «естественная» поддержка текущих компонентов, а новая функция вне MVP.

### 7.9 `DragEducationHint` (I6-D21)

| Вопрос | Решение |
| --- | --- |
| Когда показывается | первое `Playing`, где `hasSeenDragHint == false` (`ObserveDragHintUseCase`, первый прочитанный снимок) |
| Где | элемент `LazyColumn` с ключом `drag_hint` между подписью направления и первой карточкой; сплошная `surfaceContainer`, `shape.extraSmall`, `bodyMedium`/`onSurfaceVariant`, текст «Перетащите за ручку или используйте стрелки» |
| TalkBack | `contentDescription` «Для изменения порядка используйте действия карточки или кнопки перемещения» (`COMPONENTS.md`) |
| Покачивание | первая карточка, один раз, `dragHint.wobbleDuration`/`wobbleAmplitude`; при reduced motion отсутствует |
| Скрытие | первое `reorder()` любого источника или `DragStarted` (ViewModel ставит `showDragHint = false`); либо `DragHintDismissed` от UI-таймера `dragHint.autoHideDelay` |
| Блокировка «Проверить» | UI: `enabled = isSubmitEnabled && !lockActive`, `lockActive` — `LaunchedEffect` на `dragHint.submitLockDuration` от появления подсказки; пересоздание Activity перезапускает таймер (безопасно) |
| Флаг | при показе ViewModel один раз вызывает `MarkDragHintSeenUseCase` → `SettingsWriter.submit(SettingMutation.DragHintSeen)` — fire-and-forget: `submit` не ждёт записи DataStore. Если процесс умер до `edit`, флаг останется `false` и подсказка может появиться ещё один раз; это безвредно (подсказка не блокирует игру дольше 600 мс и не пишет прогресс), поэтому подтверждаемая запись не вводится. После успешной записи повторного показа нет |
| Зависимости ViewModel | +2 use case (`ObserveDragHintUseCase`, `MarkDragHintSeenUseCase`); ни репозиториев, ни часов |

### 7.10 Что чем проверяется

| Свойство | JVM / Compose (Robolectric) | Instrumented (эмулятор) | Живой TalkBack на физическом устройстве (итерация 7) |
| --- | --- | --- | --- |
| Описания, действия, границы | `I6-A1`, `I6-A2` | `I6-N4` | `I6-M10` |
| Действие = тот же путь, что кнопка | `I6-V7`, `I6-A3` | `I6-N4` | — |
| Одно объявление после успеха, ни одного при отказе | `I6-V4`, `I6-A4` | `I6-N5` (`TYPE_ANNOUNCEMENT`) | `I6-M10` (фактически произнесено) |
| Порядок узлов, заголовки | `I6-A6`, `I6-A7` | — | `I6-M10` (реальный порядок обхода) |
| Нет двойного чтения карточки/ручки/кнопок | `I6-A1`, `I6-A5` | — | `I6-M10` |
| Цели касания, 200 %, 320 dp, ландшафт, темы | `I6-A9`, `I6-U3`…`I6-U5` | — | `I6-M11` |
| Reduced motion | `I6-A10`, `I6-A11` | — | `I6-M12` (эмулятор) |
| День только через действия | — | `I6-N4` | `I6-M10` |

Поиск узла семантики в тесте доказывает структуру дерева, но не то, что TalkBack озвучит её в ожидаемом порядке и с ожидаемыми паузами. Поэтому ни один тест этой таблицы не засчитывается вместо `I6-M10` и `I5-M6`.

---

## 8. Напоминания: пользовательский контракт (PR 6B)

### 8.1 Настройки (I6-D37, I6-D38, I6-D39)

Новая группа списка настроек — после «Звук и вибрация», до «Тема» (порядок `DESIGN_PRINCIPLES.md` §3: звук/вибрация/напоминание → тема). Точная композиция и форма выбора времени — **O6-3**; ниже — рекомендуемый вариант.

| Строка | Вид | Поведение |
| --- | --- | --- |
| Заголовок группы «Напоминание» | `titleSmall`, `heading()` | — |
| «Напоминание» | `Settings row` с `Switch`; вся строка — `toggleable(role = Switch)` | значение = `reminderEnabled && availability() == Allowed` (**I6-D37**) |
| «Время» | информационно-навигационная строка: подпись + значение «9:00» (`H:mm`, 24 часа) | видна, только когда переключатель показан включённым; нажатие раскрывает выбор времени (**O6-3**) |
| Подсказка недоступности | `bodySmall` под строкой «Напоминание»: «Разрешите уведомления в настройках системы» (`UX_FLOW.md` §8) + текстовое действие «Открыть настройки уведомлений» с целью `App` или `Channel` по статусу (тексты по причинам — **O6-4**) | видна, когда `(pendingEnable || reminderEnabled) && availability() != Allowed` |
| Ошибка записи | «Не удалось сохранить настройку» под строкой своего ключа (`Reminder`, `ReminderTime`) | как I5-D15 |

| Значение по умолчанию | Источник |
| --- | --- |
| Напоминание выключено | `UserPreferencesRepositoryImpl`: `reminderEnabled ?: false` |
| Время 9:00 | `UX_FLOW.md` §2, §8, §12; `COMPONENTS.md` (`NotificationOptInDialog`); `DEFAULT_REMINDER_MINUTE = 540` в коде |

`SettingsState` получает `reminder: ReminderUi?` (`null` до первой эмиссии — skeleton; поля `enabledShown`, `time`, `unavailability: NotificationAvailability?`), `SettingsEvent` — `ReminderToggled(enabled)`, `ReminderTimeChosen(LocalTime)`, `NotificationPermissionResult`, `OpenNotificationSettingsClicked`; `SettingsEffect` — `RequestNotificationPermission`, `OpenNotificationSettings(target)`. Статус доступа перечитывается на каждом `ON_START` через `SettingsViewModel.onScreenStarted()`. Команды записи создаются только из `onEvent` и из `onScreenStarted()` при `pendingEnable && Allowed` (8.2) — одна команда на ранее выраженное пользователем намерение, без цикла от эмиссий DataStore.

### 8.2 Доступ к уведомлениям и разрешение (I6-D36)

**Доменный статус вместо Boolean.**

```kotlin
// domain/reminder/NotificationAccess.kt
enum class NotificationAvailability { Allowed, RuntimePermissionMissing, AppNotificationsDisabled, ChannelDisabled }
enum class NotificationSettingsTarget { App, Channel }
interface NotificationAccess { fun availability(): NotificationAvailability }

val NotificationAvailability.settingsTarget: NotificationSettingsTarget?
    get() = when (this) {
        NotificationAvailability.Allowed -> null
        NotificationAvailability.RuntimePermissionMissing,
        NotificationAvailability.AppNotificationsDisabled -> NotificationSettingsTarget.App
        NotificationAvailability.ChannelDisabled -> NotificationSettingsTarget.Channel
    }
```

`notifications/AndroidNotificationAccess.availability()` проверяет строго по порядку:

| № | Условие | Статус |
| --- | --- | --- |
| 1 | API 33+ и `checkSelfPermission(POST_NOTIFICATIONS) != GRANTED` | `RuntimePermissionMissing` |
| 2 | `NotificationManagerCompat.areNotificationsEnabled() == false` | `AppNotificationsDisabled` |
| 3 | канал `daily_reminder` создан и его важность `IMPORTANCE_NONE` | `ChannelDisabled` |
| 4 | иначе | `Allowed` |

На API 26–32 статус `RuntimePermissionMissing` невозможен по построению: runtime-разрешения нет.

**Настройки.** `SettingsViewModel` хранит в `SavedStateHandle` одно намерение `pendingEnable: Boolean` — «пользователь включил напоминание, доступа ещё нет». Оно переживает пересоздание во время системного диалога и уход в системные настройки и исчезает вместе с ViewModel.

| Ситуация (нажато «включить») | Что происходит |
| --- | --- |
| `Allowed` | команда `ReminderEnabled(true)`; синхронизатор планирует работу |
| `RuntimePermissionMissing` (только API 33+) | записи нет; `pendingEnable = true`; эффект `RequestNotificationPermission`; route-контейнер запускает `ActivityResultContracts.RequestPermission()` для `POST_NOTIFICATIONS` |
| Системный диалог вернул результат (любой) | событие `NotificationPermissionResult` — булево значение callback'а **не используется для решения**: ViewModel перечитывает `availability()`; `Allowed` → `ReminderEnabled(true)`, `pendingEnable = false`; иначе — подсказка с действием по `settingsTarget` |
| «Не разрешать», диалог закрыт, постоянный отказ (система сразу возвращает отказ) | после перечитывания статус `RuntimePermissionMissing` → подсказка и действие «Открыть настройки уведомлений» (`App`); отдельного распознавания «навсегда» и флага нет |
| `AppNotificationsDisabled` (любой API) | runtime-запрос **не** запускается; `pendingEnable = true`; эффект `OpenNotificationSettings(App)` сразу; подсказка и действие остаются под строкой |
| `ChannelDisabled` (любой API) | runtime-запрос **не** запускается; `pendingEnable = true`; эффект `OpenNotificationSettings(Channel)` сразу; подсказка и действие (`Channel`) |
| API 26–32 без доступа | это всегда `AppNotificationsDisabled` или `ChannelDisabled` → сразу системные настройки, как выше |
| Возврат из системных настроек или диалога (`ON_START` экрана) | `onScreenStarted()` перечитывает статус; при `pendingEnable && Allowed` — одна команда `ReminderEnabled(true)` и `pendingEnable = false` (сброс до команды, поэтому повторный `ON_START` второй записи не даёт); иначе подсказка обновляется по новой причине |
| Разрешение отозвано вне приложения | на следующем `ON_START` переключатель показан выключенным (`UX_FLOW.md` §11), подсказка с действием по причине; `reminderEnabled` не переписывается; worker не показывает уведомления без `Allowed` |
| Доступ снова выдан в системе | переключатель снова показан включённым, напоминания возобновляются — пользователь его не выключал |
| Выключение | `ReminderEnabled(false)`, `pendingEnable = false`; синхронизатор отменяет работу |
| Смена времени | `ReminderTime(t)` по подтверждению; синхронизатор перепланирует |
| Повторный запрос | только по новому явному нажатию переключателя; автоматических повторов нет |

`ExternalApps.openNotificationSettings(target)`: `App` — `Settings.ACTION_APP_NOTIFICATION_SETTINGS` + `EXTRA_APP_PACKAGE`; `Channel` — `Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS` + `EXTRA_APP_PACKAGE` + `EXTRA_CHANNEL_ID = "daily_reminder"` (оба действия есть с API 26). Запуск, перехват исключений и защита от повторного запуска — как у остальных методов границы (ADR-018).

### 8.3 Предложение на `DayRecap` (I6-D40)

| Вопрос | Решение |
| --- | --- |
| Условие показа | сессионный вариант (`origin` отсутствует) **и** день завершён (`day_results.is_complete`) **и** `notificationPromptShown == false` **и** `reminderEnabled == false`; читается один раз при создании `ReminderPromptViewModel` (`GetReminderPromptEligibilityUseCase`) |
| Архивный итог, незавершённый день | никогда |
| Компонент | `NotificationOptInDialog` по `COMPONENTS.md`: один текстовый узел «Напоминать о новом задании?» (`titleMedium`, `heading()`), `PrimaryButton` «Да, в 9:00», `SecondaryButton` «Не нужно», `shape.large`, `surfaceContainerHigh`, `elevation.dialog`, `scrim`/`opacity.scrim`, `dismissOnClickOutside = false` |
| Отметка «предложение показано» | **подтверждаемая** запись: `MarkReminderPromptShownUseCase` — `suspend`, вызывает существующий `UserPreferencesRepository.setNotificationPromptShown(true)` и возвращается только после завершения `edit` DataStore; ошибка пробрасывается вызывающему. Через `SettingsWriteQueue` отметка **не** идёт: `submit` не ждёт записи |
| «Да, в 9:00» | 1) диалог скрывается сразу; 2) `markPromptShown()` ожидается в `viewModelScope`; 3) только после успешной записи — `availability()`: `Allowed` → команды `ReminderTime(09:00)`, `ReminderEnabled(true)`; `RuntimePermissionMissing` → `pendingEnable` в `SavedStateHandle` и эффект системного запроса; `AppNotificationsDisabled`/`ChannelDisabled` → ничего не включается, runtime-запроса нет, системные настройки из диалога не открываются (включить можно в «Настройках», где есть подсказка и действие) |
| Результат системного запроса | статус перечитывается; включение — только при `Allowed`. Callback `true` при всё ещё заблокированных уведомлениях приложения или канале напоминание **не** включает; отказ — ничего не включается, без текста-упрёка |
| Ошибка записи отметки | диалог остаётся скрытым в этом экземпляре; разрешение не запрашивается, напоминание не включается; итог дня работает; в следующий раз предложение может появиться снова — честное следствие незаписанной отметки |
| «Не нужно», системная «назад» | диалог скрывается сразу; ожидается `markPromptShown()`; системного запроса нет; ошибка записи — как выше |
| Касание вне диалога | ничего |
| Смерть процесса после появления системного диалога | отметка уже в DataStore (запрос создаётся только после неё) → новый `ReminderPromptViewModel` получает `notificationPromptShown == true` и диалога не показывает; результат запроса, доставленный восстановленному route-контейнеру, обрабатывается по `pendingEnable` из `SavedStateHandle` |
| Уход с итога во время записи отметки | `viewModelScope` отменяется: запрос не создаётся, `CancellationException` пробрасывается; записана ли отметка — решает DataStore, и оба исхода безопасны |
| Отказ чтения условий | диалог не показывается; итог дня не затронут |
| Итог дня | `DayRecapViewModel` не меняется и настроек не инжектирует (I5-D31); диалог — слой route-контейнера поверх `DayRecapScreen` |
| Обычные переключатели | «Звук», «Вибрация», «Тема», «Напоминание», «Время» по-прежнему пишут через `SettingsWriteQueue` |

### 8.4 Уведомление и нажатие

| Элемент | Значение |
| --- | --- |
| Заголовок | «По порядку!» (`app_name`) |
| Текст | **O6-2** (предложение — таблица 8.6) |
| Малая иконка | `drawable/ic_stat_reminder` — входные данные 18.2 |
| Нажатие | открывает приложение как значок на рабочем столе: холодный старт — Home; живая задача — выводится на передний план в том состоянии, где её оставили (**I6-D35**) |
| Назад после открытия | холодный старт: Home → выход из приложения; живая задача — обычный бэкстек экрана |
| Действия в уведомлении | нет |
| Снятие | нажатием (`autoCancel`); при любом старте `MainActivity` (пользователь уже в приложении); автоматически в начале следующей локальной даты (`timeoutAfter`) |

### 8.5 Когда уведомление не показывается

| Условие | Почему |
| --- | --- |
| Напоминание выключено | настройка — единственный источник намерения |
| Нет доступа (разрешение, выключенные уведомления, заблокированный канал) | 8.2 |
| День завершён | `UX_FLOW.md`, `ARCHITECTURE.md` §6 |
| День начат и закрыт хотя бы один слот | **O6-1** (рекомендация — не показывать) |
| Наборы исчерпаны (`ContentExhausted`) | новых заданий нет |
| Новый набор не положен (`AwaitingNextDay`) | «новое задание» было бы неправдой |
| Работа устарела (другая дата, другое время, выключено) | 9.6 |
| Ошибка чтения базы или настроек | закрытый отказ: лучше пропустить напоминание, чем соврать |

### 8.6 Новые пользовательские тексты (I6-D42)

Строки с источником взяты дословно из утверждённых документов; строки «предложение» — новые формулировки и **не считаются подтверждёнными** до ответа владельца (**O6-2**).

| Ресурс | Текст | Источник | PR |
| --- | --- | --- | --- |
| `settings_group_reminder` | Напоминание | предложение | 6B |
| `settings_reminder` | Напоминание | `COMPONENTS.md` (`Settings row`) | 6B |
| `settings_reminder_time` | Время | предложение | 6B |
| `settings_reminder_time_value` | %1$d:%2$02d | предложение (формат `H:mm`) | 6B |
| `cd_settings_reminder_time` | Время напоминания, %1$s | предложение | 6B |
| `settings_reminder_permission_hint` | Разрешите уведомления в настройках системы | `UX_FLOW.md` §8, `COMPONENTS.md` | 6B |
| `settings_reminder_open_system` | Открыть настройки уведомлений | предложение (действие обязательно по **I6-D36**, текст — **O6-4**) | 6B |
| `reminder_time_confirm` / `reminder_time_cancel` | Сохранить / Отмена | предложение (**O6-3**) | 6B |
| `reminder_prompt_question` | Напоминать о новом задании? | `UX_FLOW.md` §2 | 6B |
| `reminder_prompt_accept` | Да, в 9:00 | `UX_FLOW.md` §2 | 6B |
| `reminder_prompt_decline` | Не нужно | `UX_FLOW.md` §2 | 6B |
| `reminder_channel_name` | Напоминание о заданиях | предложение | 6B |
| `reminder_channel_description` | Одно напоминание в день в выбранное время | предложение | 6B |
| `reminder_notification_text` | Новые три задания готовы | `ARCHITECTURE.md` §6; альтернатива «Три новых задания готовы» — **O6-2** | 6B |
| `drag_hint` | Перетащите за ручку или используйте стрелки | `UX_FLOW.md` §2, `COMPONENTS.md` | 6C |
| `cd_drag_hint` | Для изменения порядка используйте действия карточки или кнопки перемещения | `COMPONENTS.md` (`DragEducationHint`) | 6C |

---

## 9. Напоминания: планирование (PR 6B)

### 9.1 Границы и файлы (I6-D24, I6-D25)

```
domain/reminder/                          // чистый Kotlin + kotlinx.coroutines, без android.*, без di.*
  ReminderTrigger.kt                      // data class ReminderTrigger(targetDate: LocalDate, minuteOfDay: Int, at: Instant)
  NextReminderTrigger.kt                  // object: next(now: Instant, zone: ZoneId, time: LocalTime): ReminderTrigger
  ReminderEligibility.kt                  // object: decide(decision: Decision, today: DayResult?): ReminderVerdict
  ReminderScheduler.kt                    // interface { suspend fun schedule(t, mode: ScheduleMode); suspend fun cancel() }
  ReminderScheduleLock.kt                 // @Singleton: один Mutex на все записи планировщика в процессе
  ReminderNotifier.kt                     // interface { suspend fun show(); fun cancelShown() }
  NotificationAccess.kt                   // NotificationAvailability, NotificationSettingsTarget, interface { fun availability() }
  EvaluateReminderUseCase.kt              // решение перед показом (только чтение)
  SyncReminderScheduleUseCase.kt          // ОДНА операция синхронизации: suspend invoke(): ReminderSyncResult
  ReminderRun.kt                          // оркестрация одного срабатывания worker'а
  GetReminderPromptEligibilityUseCase.kt  // условие диалога на DayRecap
domain/usecase/
  MarkReminderPromptShownUseCase.kt       // подтверждаемая запись notificationPromptShown (8.3)
notifications/                            // Android и оркестрация процесса
  WorkManagerReminderScheduler.kt         // над узкой границей UniqueWorkOperations; каждая операция ожидается (Operation.await)
  ReminderScheduleObserver.kt             // @Singleton, @ApplicationScope: постоянный коллектор настроек → SyncReminderScheduleUseCase
  ReminderBroadcastHandler.kt             // фильтр действий, запуск синхронизации, finish() по завершении
  ReminderTimeChangeReceiver.kt           // тонкий BroadcastReceiver: goAsync() → ReminderBroadcastHandler
  ReminderWorker.kt                       // CoroutineWorker: EntryPoint → ReminderRun
  ReminderEntryPoint.kt                   // @EntryPoint @InstallIn(SingletonComponent): worker и receiver
  AndroidReminderNotifier.kt              // канал, показ, снятие
  AndroidNotificationAccess.kt            // статус по таблице 8.2
di/ReminderModule.kt                      // @Binds интерфейсов domain/reminder
```

Зависимости: `notifications → domain → core.model`; `ui` видит `domain/reminder` (use cases, `NotificationAccess`) и не видит `notifications`, кроме `MainActivity` (корень композиции: `ReminderScheduleObserver.start()`, создание канала, снятие показанного уведомления). `domain/reminder` не импортирует `androidx.work`, `android.*` и `ru.poporyadku.di`: `@ApplicationScope` и жизненный цикл процесса живут только в `notifications`.

**Зависимость.** В `app/build.gradle.kts` добавляется `implementation(libs.androidx.work.runtime.ktx)`; строка catalog уже существует. Версия 2.11.2 зафиксирована 2026-08-30 — в начале 6B она сверяется со страницей релизов WorkManager и записывается в `VERSIONS.md` с датой проверки (правило итерации 0: ни одной версии по памяти). `androidx.hilt:hilt-work` не нужен: `ReminderWorker` и `ReminderTimeChangeReceiver` получают граф через `EntryPointAccessors.fromApplication(context.applicationContext, ReminderEntryPoint::class.java)` — это API уже подключённого `hilt-android`. `work-testing` не нужен: логика — в `ReminderRun`, `SyncReminderScheduleUseCase`, `ReminderBroadcastHandler` и чистых объектах (JVM/Robolectric), адаптер WorkManager — в instrumented-тесте на настоящем WorkManager (`I6-N6`). Если в 6B выяснится, что без новой зависимости критерий не проверяем, это возвращается владельцу отдельным решением, а не добавляется молча.

### 9.2 Расчёт момента (I6-D28)

```kotlin
object NextReminderTrigger {
    fun next(now: Instant, zone: ZoneId, time: LocalTime): ReminderTrigger {
        val today = now.atZone(zone).toLocalDate()
        var date = today
        var at = ZonedDateTime.of(date, time, zone).toInstant()   // gap → вперёд; overlap → раннее смещение
        if (!at.isAfter(now)) {
            date = today.plusDays(1)
            at = ZonedDateTime.of(date, time, zone).toInstant()
        }
        return ReminderTrigger(date, time.hour * 60 + time.minute, at)
    }
}
// задержка для WorkRequest: Duration.between(now, trigger.at).coerceAtLeast(Duration.ZERO)
```

| Случай | Результат | Тест |
| --- | --- | --- |
| Сейчас 08:59, время 09:00 | сегодня 09:00 | `I6-S1` |
| Сейчас ровно 09:00 или позже | завтра 09:00 | `I6-S1` |
| Время 00:00 и 23:59; конец месяца и года | корректная дата | `I6-S1` |
| Переход на летнее время, время внутри разрыва (Europe/Berlin, 2026-03-29 02:30) | 03:30 местного времени того же дня | `I6-S2` |
| Переход на зимнее время, время в перекрытии (Europe/Berlin, 2026-10-25 02:30) | первое (более раннее) вхождение | `I6-S2` |
| Зона без перехода (Europe/Moscow) | ровно сутки между срабатываниями | `I6-S2` |
| Смена зоны при том же моменте (Moscow → Asia/Novosibirsk) | другой момент, то же местное время | `I6-S3` |

Часы и зона — `ClockProvider.now()` (один `TimeSnapshot`: момент и зона из одного `Clock`); в debug-сборке это управляемые часы, поэтому сценарии даты воспроизводимы.

### 9.3 Уникальная работа (I6-D26, I6-D27)

| Параметр | Значение |
| --- | --- |
| Имя | `daily_reminder` |
| Тип | `OneTimeWorkRequestBuilder<ReminderWorker>()` с `setInitialDelay(delay)`, без `Constraints`, тег `reminder` |
| Данные | `targetDate` — ISO `yyyy-MM-dd`; `minuteOfDay` — `Int` 0..1439. Ничего больше |
| Граница надёжности записи | каждая операция (`enqueueUniqueWork`, `cancelUniqueWork`, чтение `getWorkInfosForUniqueWork`) ожидается через `Operation.await()`/`ListenableFuture.await()` из `work-runtime-ktx`: `schedule()`/`cancel()` возвращаются только после того, как WorkManager записал операцию в свою базу, и бросают при её отказе |
| Синхронизация (`ScheduleMode.Replace`) | `enqueueUniqueWork(name, REPLACE, request)` — отменяет ожидающую и выполняющуюся работу; новые настройки важнее |
| Перепланирование из worker'а (`ScheduleMode.AfterCurrent(workId)`) | под `ReminderScheduleLock`: если в цепочке `daily_reminder` уже есть работа в `ENQUEUED`/`BLOCKED`, отличная от `workId`, — ничего (её поставила синхронизация); иначе `enqueueUniqueWork(name, APPEND_OR_REPLACE, request)` — следующая работа ставится после текущей и не отменяет её |
| Отмена | `cancelUniqueWork(name)` |
| Периодическая работа | не используется: период 24 часа не следует местному времени через смену зоны и DST |

`ReminderScheduleLock` сериализует все записи планировщика внутри процесса. Порядок «синхронизация `REPLACE` → перепланирование worker'а» поэтому не создаёт в цепочке второй ожидающей работы: worker видит уже поставленную синхронизацией работу и не добавляет свою. Обратный порядок безопасен сам: `REPLACE` заменяет всю цепочку.

### 9.4 Одна операция синхронизации и её вызывающие (I6-D29, I6-D30)

**Единственная операция** — `SyncReminderScheduleUseCase`: и постоянный коллектор, и приёмник, и worker с неразбираемыми данными вызывают её, а не собственные копии логики.

```kotlin
// domain/reminder/SyncReminderScheduleUseCase.kt
sealed interface ReminderSyncResult {
    data class Scheduled(val trigger: ReminderTrigger) : ReminderSyncResult
    data object Cancelled : ReminderSyncResult
    data class Failed(val cause: Throwable) : ReminderSyncResult      // НЕ успех
}

@Singleton
class SyncReminderScheduleUseCase @Inject constructor(
    private val preferences: UserPreferencesRepository,
    private val scheduler: ReminderScheduler,
    private val clock: ClockProvider,
    private val lock: ReminderScheduleLock,
) {
    suspend operator fun invoke(): ReminderSyncResult = lock.mutex.withLock {
        try {
            val prefs = preferences.preferences.first()               // актуальные reminderEnabled и reminderTime
            if (!prefs.reminderEnabled) {
                scheduler.cancel()                                     // ожидается запись WorkManager
                ReminderSyncResult.Cancelled
            } else {
                val now = clock.now()                                  // один TimeSnapshot: момент и зона
                val trigger = NextReminderTrigger.next(Instant.ofEpochMilli(now.epochMillis), now.zone, prefs.reminderTime)
                scheduler.schedule(trigger, ScheduleMode.Replace)      // ожидается запись WorkManager
                ReminderSyncResult.Scheduled(trigger)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ReminderSyncResult.Failed(e)
        }
    }
}
```

**Постоянный коллектор** (открытое приложение):

```kotlin
// notifications/ReminderScheduleObserver.kt
@Singleton
class ReminderScheduleObserver @Inject constructor(
    private val preferences: UserPreferencesRepository,
    private val sync: SyncReminderScheduleUseCase,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            preferences.preferences
                .map { it.reminderEnabled to it.reminderTime }
                .distinctUntilChanged()
                .collect { sync() }   // первая эмиссия — синхронизация при старте; Failed не считается успехом и не роняет сбор
        }
    }
}
```

**Приёмник** (в том числе холодный процесс):

```kotlin
// notifications/ReminderTimeChangeReceiver.kt
class ReminderTimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        EntryPointAccessors.fromApplication(context.applicationContext, ReminderEntryPoint::class.java)
            .broadcastHandler()
            .handle(intent.action, onFinished = pending::finish)
    }
}

// notifications/ReminderBroadcastHandler.kt
class ReminderBroadcastHandler @Inject constructor(
    private val sync: SyncReminderScheduleUseCase,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun handle(action: String?, onFinished: () -> Unit) {
        if (action != Intent.ACTION_TIMEZONE_CHANGED && action != Intent.ACTION_TIME_CHANGED) {
            onFinished(); return
        }
        scope.launch {
            // бюджет меньше системного предела обработки broadcast; истечение — Failed, а не успех
            withTimeoutOrNull(RECEIVER_SYNC_BUDGET) { sync() } ?: ReminderSyncResult.Failed(ReceiverTimeout)
        }.invokeOnCompletion { onFinished() }   // ровно один раз и только после завершения корутины
    }
}
```

| Требование | Как выполнено |
| --- | --- |
| Читает актуальные `reminderEnabled`, `reminderTime` | `preferences.first()` внутри операции, под блокировкой — не значение, захваченное при событии |
| Цель от одного `TimeSnapshot` | один `clock.now()` на вызов |
| Последовательные `schedule`/`cancel` | `ReminderScheduleLock`: коллектор, приёмник и worker никогда не пишут WorkManager одновременно |
| Одна операция для коллектора и приёмника | оба вызывают `SyncReminderScheduleUseCase` |
| `finish()` только после завершения | `invokeOnCompletion` запущенной корутины; операция завершается только после `Operation.await()` — к `finish()` работа уже записана в базу WorkManager либо результат `Failed` |
| Холодный процесс без `MainActivity` | система поднимает процесс для приёмника из манифеста; `Application.onCreate` создаёт граф Hilt, стартовый инициализатор WorkManager (`InitializationProvider`) выполняется до `onReceive`; ни `ReminderScheduleObserver.start()`, ни `MainActivity` не нужны |
| Событие не хранится без коллектора | `SharedFlow` не используется: приёмник сам выполняет операцию до конца |
| `CancellationException` | пробрасывается из операции; при отмене корутины `invokeOnCompletion` всё равно вызывает `finish()` |
| Обычная ошибка | `ReminderSyncResult.Failed`: процесс не падает, результат не считается успешной синхронизацией, `finish()` вызывается; следующая попытка — при следующем изменении настроек, событии времени или старте `MainActivity` |
| Неизвестное действие | `finish()` сразу, без синхронизации |

`RECEIVER_SYNC_BUDGET` — техническая константа 6B (не токен дизайна), выбирается меньше системного предела времени обработки broadcast; её значение и обоснование фиксируются в KDoc при реализации.

| Вопрос | Ответ |
| --- | --- |
| Кто вызывает планировщик при изменении настроек | только `SyncReminderScheduleUseCase` через `ReminderScheduleObserver`; `SettingsViewModel` и `ReminderPromptViewModel` пишут DataStore и о WorkManager не знают |
| Конкурентные включение/выключение/смена времени | команды записи упорядочены `SettingsWriteQueue`; коллектор получает эмиссии по порядку, каждая операция перечитывает настройки и выполняется под блокировкой — итог соответствует последней записи (`I6-Y1`) |
| Идемпотентность | повтор с теми же настройками заменяет работу той же целью; двух работ не бывает — имя уникально |
| Старт приложения | `MainActivity.onCreate` вызывает `ReminderScheduleObserver.start()`; не из `Application.onCreate`: Robolectric-тесты создают `PoPoRyadkuApp` без инициализатора WorkManager |
| Перезапуск процесса WorkManager'ом без Activity | коллектор не нужен: цепочку продолжает сам worker (`AfterCurrent`) |
| Зависит ли от доступа к уведомлениям | нет: работа планируется по намерению; без `Allowed` worker завершается без показа и перепланирует следующий день |
| Ошибка чтения DataStore | `UserPreferencesRepositoryImpl` превращает `IOException` в значения по умолчанию (`reminderEnabled = false`) → операция отменит работу; следующее успешное чтение запланирует её снова (риск в разделе 16) |
| Уже просроченное сегодня напоминание при открытии приложения | цель — следующее вхождение, сегодняшнее не показывается: пользователь уже открыл приложение |

### 9.5 Смена времени, зоны, перезагрузка, force stop (I6-D30, I6-D31)

| Событие | Реакция |
| --- | --- |
| Смена часового пояса (`ACTION_TIMEZONE_CHANGED`) | `ReminderTimeChangeReceiver` → `goAsync()` → `ReminderBroadcastHandler` → `SyncReminderScheduleUseCase` → `finish()` после завершения (9.4) |
| Ручной перевод часов (`ACTION_TIME_CHANGED`, `android.intent.action.TIME_SET`) | то же |
| Приложение не запущено в момент события | оба действия входят в список исключений из ограничения неявных broadcast — приёмник из манифеста получает их и поднимает процесс; `MainActivity` не запускается |
| Переход DST | зона не меняется — событие не приходит; следующее срабатывание уже рассчитано через `ZonedDateTime` (9.2); worker перепроверяет момент (9.6) |
| Работа сработала раньше (часы переведены вперёд, WorkManager считает по настенным часам) | worker видит `now < trigger.at` → не показывает, перепланирует ту же цель |
| Работа сработала позже (Doze, агрессивная прошивка) | показывает, если дата цели всё ещё сегодняшняя и остальные проверки прошли; после смены даты — не показывает |
| Перезагрузка устройства | WorkManager сам восстанавливает работу (его `RescheduleReceiver`, разрешение `RECEIVE_BOOT_COMPLETED` вливается из библиотеки); собственного приёмника `BOOT_COMPLETED` нет |
| Force stop | система снимает задачи и не доставляет broadcast приложению до следующего ручного запуска — **ограничение платформы, а не обещание приложения**; первый запуск `MainActivity` перепланирует |
| Home при смене даты в открытом приложении | O-2 итерации 3 закрыт без приёмника: минутный тикер и `ON_START` (I3-D15) |

`exported="false"` у приёмника: системные broadcast доставляются от системного uid и такому приёмнику; факт проверяется в 6B на эмуляторе с убитым процессом (`I6-M7`). Если доставка не подтвердится, допустимая замена — `exported="true"` с теми же двумя защищёнными системными действиями; другие изменения решения не требуются.

### 9.6 Worker и проверки перед показом (I6-D27, I6-D32, I6-D33)

```kotlin
class ReminderRun @Inject constructor(
    private val evaluate: EvaluateReminderUseCase,
    private val notifier: ReminderNotifier,
    private val scheduler: ReminderScheduler,
    private val lock: ReminderScheduleLock,
) {
    data class Report(val evaluationFailure: Exception?, val rescheduled: Boolean, val rescheduleFailure: Exception?)

    /** Бросает только CancellationException. */
    suspend operator fun invoke(scheduledDate: LocalDate, scheduledMinute: Int, workId: UUID): Report {
        var evaluationFailure: Exception? = null
        // 1. Оценка и показ. Отмена отсюда пробрасывается — ни показа, ни перепланирования.
        val next: ReminderTrigger? = try {
            val verdict = evaluate(scheduledDate, scheduledMinute)
            if (verdict is ReminderVerdict.Show) notifier.show()
            verdict.nextTrigger                               // null — напоминание выключено
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            evaluationFailure = e                             // первоначальная ошибка сохраняется
            fallbackOrNull()                                  // её собственная ошибка наружу не выходит
        }
        if (next == null) return Report(evaluationFailure, rescheduled = false, rescheduleFailure = null)

        // 2. Отмена, пришедшая после вычисления next, но до перепланирования: schedule() не вызывается.
        currentCoroutineContext().ensureActive()

        // 3. Перепланирование: ошибка фиксируется отдельно и не подменяет evaluationFailure.
        val rescheduleFailure = try {
            lock.mutex.withLock { scheduler.schedule(next, ScheduleMode.AfterCurrent(workId)) }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }
        return Report(evaluationFailure, rescheduled = rescheduleFailure == null, rescheduleFailure = rescheduleFailure)
    }

    private suspend fun fallbackOrNull(): ReminderTrigger? = try {
        evaluate.fallbackNextTrigger()                        // только настройки и часы, без базы; null — выключено
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
```

| Сценарий | Показ | Перепланирование | Наружу |
| --- | --- | --- | --- |
| Оценка успешна, `Show` | да | да, `AfterCurrent` | `Report` без ошибок |
| Оценка успешна, `Skip` с целью | нет | да | `Report` без ошибок |
| `Skip(Disabled)` (цели нет) | нет | нет | `Report` |
| Обычная ошибка оценки или показа | нет (или показ уже прошёл до ошибки) | по `fallbackNextTrigger`, если он дал цель | `Report.evaluationFailure` — первоначальная ошибка |
| Ошибка `fallbackNextTrigger` | нет | нет | `Report.evaluationFailure` — первоначальная ошибка |
| Ошибка `scheduler.schedule` | как по оценке | нет | `Report.rescheduleFailure`; `evaluationFailure` не меняется |
| `CancellationException` до вердикта, из `notifier.show()`, из fallback | нет | **нет** | `CancellationException` |
| Отмена после вычисления `next`, до вызова `schedule()` | как по оценке | **нет** (`ensureActive`) | `CancellationException` |
| Отмена во время уже начатого `schedule()` | как по оценке | операция, уже переданная WorkManager, не отзывается платформой | `CancellationException` |

Порядок «оценка → показ → проверка отмены → перепланирование» — единственный; блок, выполняющийся при отмене, в `ReminderRun` отсутствует.

`EvaluateReminderUseCase` — только чтение, порядок проверок фиксирован:

| № | Проверка | Не прошла → | Следующая цель |
| --- | --- | --- | --- |
| 1 | `reminderEnabled == true` | `Skip(Disabled)` | нет (работа не продолжается) |
| 2 | `scheduledMinute == reminderTime` в настройках | `Skip(StaleTime)` | `next(now)` по текущему времени |
| 3 | `scheduledDate == today` (`ClockProvider`) | `Skip(StaleDate)` | `next(now)` |
| 4 | `now >= ZonedDateTime.of(scheduledDate, time, zone)` | `Skip(Early)` | та же цель |
| 5 | `NotificationAccess.availability() == Allowed`; `RuntimePermissionMissing`, `AppNotificationsDisabled`, `ChannelDisabled` — одинаково без показа | `Skip(NoAccess(availability))` | `next(now)` (завтра) |
| 6 | `DayAssignmentRepository.peek()` + `ProgressRepository.getDayResult(today)` → `ReminderEligibility.decide` | `Skip(DayCompleted / InProgress / ContentExhausted / AwaitingNextDay)` | `next(now)` |
| — | все прошли | `Show` | `next(now)` (завтра) |

`ReminderEligibility.decide(decision, todayResult)`:

| Решение политики | Результат сегодня | Вердикт |
| --- | --- | --- |
| `NewSet`, `CarryOver` | — | показать (набор для продолжения существует) |
| `Assigned` | нет строки или `completedCount == 0` | показать |
| `Assigned` | `completedCount` 1–2 | **O6-1** (рекомендация: не показывать) |
| `Assigned` | `completedCount == 3` | не показывать — день завершён |
| `AwaitingNextDay` | — | не показывать |
| `ContentExhausted` | — | не показывать — контент исчерпан |

Что worker **не делает**, и чем это доказано: не назначает набор (`startSession` не вызывается), не импортирует контент (`ensureInstalled` не вызывается), не пишет прогресс и настройки, не обращается к UI и ViewModel — фейковые репозитории в `I6-W2` бросают на любой записывающий метод, `rg`-проверка `I6-K3` не находит этих имён в `notifications/` и `domain/reminder/`.

`ReminderWorker.doWork()`: разбирает `inputData` (неразбираемые данные → без показа один вызов `SyncReminderScheduleUseCase`, затем `Result.success()`); вызывает `ReminderRun(date, minute, id)`; возвращает `Result.success()` при любом `Report` — повтор с backoff не нужен; `CancellationException` пробрасывается.

После обновления приложения с новой `contentVersion`, пока пользователь не открыл его, база может содержать старый пакет: `peek()` ответит по нему. Это даёт в худшем случае пропуск напоминания (`ContentExhausted` на старом числе наборов), но не показ ложного — импорт в фоне не выполняется сознательно.

### 9.7 Повторный запуск и дубли

| Сценарий | Итог |
| --- | --- |
| WorkManager перезапустил worker после смерти процесса до `Result` | проверки повторяются; показ идёт с тем же `REMINDER_NOTIFICATION_ID` и заменяет, а не добавляет уведомление; перепланирование `AfterCurrent` идемпотентно |
| Пользователь смахнул уведомление, затем worker перезапустился | возможен повторный показ в тот же день — редкое окно смерти процесса между показом и `Result`; ключ «последний показ» ради него не заводится (**I6-D2**, риск в разделе 16) |
| Две работы одновременно | невозможно: одно уникальное имя |
| Смена настроек во время выполнения worker'а | `REPLACE` отменяет выполняющийся worker; если показ уже прошёл — он остаётся, новая работа учитывает новые настройки |

### 9.8 Ошибки и отмена (I6-D49)

- `SyncReminderScheduleUseCase` превращает обычные ошибки в `ReminderSyncResult.Failed` — это не успех; коллектор продолжает сбор, приёмник вызывает `finish()`; `CancellationException` пробрасывается.
- `ReminderRun` бросает только `CancellationException`; ошибки оценки, fallback и планировщика возвращаются в `Report` раздельно (9.6); пользователь ошибок планирования не видит, игровой поток от них не зависит.
- Отказ `enqueue`: работа не запланирована до следующего изменения настроек, события времени, срабатывания цепочки или старта `MainActivity`.
- Отказ показа (`SecurityException` при отозванном разрешении между проверкой и `notify`) перехватывается в `AndroidReminderNotifier`.
- Отмена worker'а системой или `REPLACE`: `CancellationException` пробрасывается, показа и перепланирования после отмены нет — работу отменил тот, кто поставил новую.

---

## 10. Android-контракты уведомлений (PR 6B)

### 10.1 Канал и уведомление (I6-D34)

| Параметр | Значение |
| --- | --- |
| Channel ID | `daily_reminder` — стабилен навсегда (переименование создаёт новый канал и теряет выбор пользователя) |
| Имя, описание | ресурсы `reminder_channel_name`, `reminder_channel_description` (**O6-2**) |
| Важность | `IMPORTANCE_DEFAULT` (в статус-баре, без всплывающего баннера) |
| Звук, вибрация | `setSound(null, null)`, `enableVibration(false)` — спокойный тон продукта (`ARCHITECTURE.md` §6) |
| Значок на иконке | `setShowBadge(false)` — счётчик на иконке был бы давлением, которого продукт избегает |
| Создание | `AndroidReminderNotifier.ensureChannel()` — идемпотентно, в `MainActivity.onCreate` и перед каждым `show()`; `minSdk 26` — каналы без ветвления |
| Notification ID | `REMINDER_NOTIFICATION_ID = 1001`, без тега |
| Builder | `NotificationCompat.Builder(context, "daily_reminder")`, `setSmallIcon(R.drawable.ic_stat_reminder)`, заголовок `app_name`, текст — ресурс, `setCategory(CATEGORY_REMINDER)`, `setAutoCancel(true)`, `setOnlyAlertOnce(true)`, `setVisibility(VISIBILITY_PUBLIC)` (в тексте нет личных данных), `setTimeoutAfter(до начала следующей локальной даты)` |
| Показ | `NotificationManagerCompat.notify(1001, …)` после повторной проверки доступа; `SecurityException` перехватывается |
| Снятие | `cancel(1001)`: в `MainActivity.onStart` и при выключении напоминания синхронизатором |

### 10.2 Нажатие, `PendingIntent`, бэкстек (I6-D35)

```kotlin
val intent = Intent(context, MainActivity::class.java)
    .setAction(Intent.ACTION_MAIN)
    .addCategory(Intent.CATEGORY_LAUNCHER)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
val pending = PendingIntent.getActivity(
    context, REMINDER_REQUEST_CODE, intent,
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)
```

| Вопрос | Ответ |
| --- | --- |
| Deep link / навигационный контракт | не вводится: `Destinations` и `AppNavHost` не меняются; стартовый маршрут — `home`, где `ON_START` пересчитывает день |
| Почему не deep link в задание | задание зависит от даты и назначения; ссылка, построенная вчера, открыла бы чужой день, а Home уже решает, что показать |
| Задача жива, пользователь был в задании | задача выводится на передний план как есть (интент совпадает с интентом значка приложения) — порядок карточек и бэкстек сохранены |
| Холодный старт | `MainActivity` → Home; «назад» — выход |
| Повторное нажатие | уведомление снято `autoCancel`; если осталось видимым — повторный вывод той же задачи, идемпотентно |
| Флаги | `FLAG_IMMUTABLE` обязателен с API 31; `FLAG_UPDATE_CURRENT` и постоянный `requestCode` не создают разных `PendingIntent` |
| Несколько одинаковых уведомлений | невозможны: один ID, одна работа |

### 10.3 Манифест (итоговые правки 6B)

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<application …>
    <receiver
        android:name=".notifications.ReminderTimeChangeReceiver"
        android:exported="false">
        <intent-filter>
            <action android:name="android.intent.action.TIMEZONE_CHANGED" />
            <action android:name="android.intent.action.TIME_SET" />
        </intent-filter>
    </receiver>
</application>
```

### 10.4 Сеть и итоговый манифест (I6-D41)

WorkManager вливает в итоговый манифест собственные компоненты (`InitializationProvider`, `SystemJobService`, `RescheduleReceiver` и др.) и разрешения. Точный состав сверяется в 6B по `app/build/intermediates/merged_manifests/…` (`I6-M13`) и фиксируется тестом `I6-P5` на `PackageManager.getPackageInfo(GET_PERMISSIONS).requestedPermissions`:

| Разрешение | Происхождение | Решение |
| --- | --- | --- |
| `POST_NOTIFICATIONS` | приложение | утверждено продуктом |
| `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE` (ожидаемо) | WorkManager | остаются: не сетевые, нужны перезапуску работы; точный список — по факту сборки |
| `ACCESS_NETWORK_STATE` (ожидаемо) | WorkManager (отслеживание сетевых ограничений работ) | **остаётся**: normal-разрешение только на чтение состояния сети, не даёт ни доступа в сеть, ни сокетов; удалять транзитивное разрешение библиотеки манифестным слиянием без официально документированной гарантии WorkManager, что без него не будет отказа, нельзя |
| `INTERNET` | — | **запрещено**; тест проверяет отсутствие |
| Локальные сетевые разрешения: `ACCESS_LOCAL_NETWORK`, `NEARBY_WIFI_DEVICES`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `CHANGE_NETWORK_STATE` | — | **запрещены**; тест проверяет отсутствие |

Критерий итерации 7 уточняется синхронно (`IMPLEMENTATION_PLAN.md`): «нет разрешения `INTERNET`, локальных сетевых разрешений и сетевых вызовов»; `ACCESS_NETWORK_STATE`, объявленный WorkManager, документируется как normal-разрешение без сетевого доступа.

Сетевых вызовов нет: `I6-K2` проверяет отсутствие `HttpURLConnection`, `URL(`, `Socket`, `okhttp`, `ktor`, `retrofit` в `app/src/main`.

---

## 11. Визуальная полировка, анимации, иконки (PR 6D; отдельные пункты — 6A–6C)

### 11.1 Аудит расхождений (I6-D47)

Методика: чтение всех экранов и компонентов `ui/**`, поиск `rg` по литералам (`Color(0x`, числовые `.dp`/`.sp`, `tween(`, `spring(`, `RoundedCornerShape`, `fontSize`, `letterSpacing`) вне `ui/theme/**`, сверка с `COMPONENTS.md`, `DESIGN_TOKENS.md`, `UI_REVIEW_CHECKLIST.md`. **Положительный результат:** захардкоженных цветов, размеров, радиусов, типографики и длительностей вне `ui/theme/**` не найдено; оба `tween` (`SourcesBlock`, `PuzzleScreen`) берут `MotionTokens`. Найденные расхождения:

| № | Файл / компонент | Расхождение | Токен или правило | PR | Проверка |
| --- | --- | --- | --- | --- | --- |
| V1 | `AndroidManifest.xml`, `res/` | нет иконки приложения: у `<application>` нет `android:icon`/`roundIcon`, нет `mipmap*` | `ARCHITECTURE.md` ADR-009; план итерации 6 | 6D (входные данные 18.2) | `I6-U6`, `I6-M15` |
| V2 | `ui/navigation/AppNavHost.kt` | `NavHost` без переходов — библиотечные `fadeIn/fadeOut(tween(700))`, не учитывают reduced motion | `DESIGN_PRINCIPLES.md` §9; `motion.duration.long`/`exit`; §6.8 reduced motion | 6D | `I6-U1`, `I6-M12` |
| V3 | `ui/puzzleresult/PuzzleResultScreen.kt` | постепенное появление результата не реализовано, `staggerResultReveal` не используется | `UX_FLOW.md` §5; `motion.stagger.resultReveal`, `motion.duration.short` | 6D | `I6-U2` |
| V4 | `ui/theme/Motion.kt` | `rememberMotionTokens` не реагирует на смену «Убрать анимацию» | `DESIGN_TOKENS.md` §6.8 | 6C | `I6-A10` |
| V5 | `ui/puzzle/PuzzleScreen.kt` | `liveRegion = Polite` на списке дублирует объявление | `UI_REVIEW_CHECKLIST.md`, TalkBack; **I6-D17** | 6C | `I6-A5` |
| V6 | `ui/navigation/AppNavHost.kt`, `ui/settings/SettingsRows.kt` | `announceForAccessibility` устарел с API 36 | **I6-D16** | 6C | `I6-A4`, `I6-N5`, `I6-K5` |
| V7 | `ui/components/OrderableCard.kt` | нет `DragHandle` и состояния `dragging` | `COMPONENTS.md` (`OrderableCard`, `DragHandle`); `elevation.dragged`, `opacity.dragStateLayer` | 6A | `I6-C1`, `I6-M1` |
| V8 | `ui/puzzle/PuzzleScreen.kt` | нет `DragEducationHint` | `COMPONENTS.md`; `dragHint.*` | 6C | `I6-V13`, `I6-C8` |
| V9 | `ui/recap/*` | нет `NotificationOptInDialog` | `COMPONENTS.md`; `shape.large`, `elevation.dialog`, `opacity.scrim` | 6B | `I6-C10` |
| V10 | `ui/settings/*` | нет строк напоминания | `COMPONENTS.md` (`Settings row`) | 6B | `I6-C9` |
| V11 | тесты `HomeScreenTest`, `PuzzleScreenTest`, `PuzzleResultScreenTest`, `DayRecapScreenTest` | нет проверки ландшафта (у Archive, Sources, Settings есть) | `UI_REVIEW_CHECKLIST.md`, «Landscape»; `Sizing.contentMaxWidth` | 6D | `I6-U3` |
| V12 | все экраны | `safeDrawing` применяется только сверху и снизу; боковые вставки (вырез, кнопочная навигация в ландшафте) не проверены | `UI_REVIEW_CHECKLIST.md`, «Системные inset» | 6D: проверить; исправлять только при фактическом перекрытии | `I6-M11` |
| V13 | `ui/home/HomeScreen.kt`, `RecoveryConfirmationDialog` | M3 `AlertDialog` со стандартной формой и тональным подъёмом, вне токенов | `DESIGN_PRINCIPLES.md` §6 | 6D: только документирование как debug-исключения — в release набор действий пуст (I3-D47) | существующий `ReleaseRecoveryContributionsTest` |
| V14 | `docs/design/COMPONENTS.md`, `InvertedPairRow` | шаблон «перед/после» | I3-D5 | 6D (документ) | чтение |
| V15 | `COMPONENTS.md`, `UI_REVIEW_CHECKLIST.md` | «Переместить выше/ниже» | **I6-D18** | 6C (документ) | `I6-A2` |
| V16 | системный экран запуска API 31+ | без иконки система показывает значок по умолчанию на фоне окна | `PRODUCT.md`: собственного splash нет; иконка — V1 | 6D | `I6-M15` |
| V17 | `ui/components/OrderableCard.kt` | новая пара цветов ручки и поднятой карточки отсутствует в таблицах контраста | `DESIGN_TOKENS.md` §6.2 | 6A (документ + значения 5.6) | чтение |

Loading/Empty/Error, верхние панели, CTA, карточки и списки на момент аудита соответствуют state sheets (`I3-*`, `I5-C*`); итерация 6 их не переделывает, а сверяет по матрице 11.5.

### 11.2 Недостающие токены — предложения для `DESIGN_TOKENS.md`

Числа в код не вносятся, пока токены не утверждены (**O6-5**, **O6-6**).

| Токен (предложение) | Значение | Обоснование |
| --- | --- | --- |
| `dragAutoScroll.edgeZone` | = `size.touchTarget.min` (48 dp) | ссылка на существующий токен, без нового числа |
| `dragAutoScroll.maxVelocity` | (`size.orderableCard.minHeight` + `spacing.listGap`) / `motion.duration.long` ≈ 413 dp/с | «одна карточка за длительность перестановки»; производное значение |
| `navTransition.enter` | fade in, `motion.duration.long`, `motion.easing.standardDecelerate` | `DESIGN_PRINCIPLES.md` §9 уже задаёт длительности и кривые, не задаёт тип |
| `navTransition.exit` | fade out, `motion.duration.exit`, `motion.easing.standardAccelerate` | «выход быстрее входа» |
| `resultReveal.groups` | фиксированный перечень групп экрана результата (11.3), без числового токена | ограничивает длительность составом экрана, а не новым числом |

### 11.3 Анимации (I6-D43, I6-D44, I6-D45)

| Событие | Анимация | Токены | Reduced motion |
| --- | --- | --- | --- |
| Переход между любыми маршрутами | вход — fade in; выход — fade out; «назад» — те же спецификации (тип — **O6-5**) | `motion.duration.long` / `standardDecelerate`; `motion.duration.exit` / `standardAccelerate` | 1 мс, linear |
| Предиктивный «назад» | библиотечное поведение `NavHost` с теми же спецификациями | — | — |
| Появление результата | альфа от 0 к 1 по группам: 1) «Правильный порядок» и карточки 1–4 — по одному шагу на карточку (4 шага); 2) объяснение; 3) счёт и подсказка о парах; 4) перепутанные пары блоком; 5) источники и «Сообщить о неточности» — итого ≤ 8 шагов; задержка шага = номер × `motion.stagger.resultReveal`, длительность шага `motion.duration.short` | 60 мс, 100 мс; максимум 7 × 60 + 100 = 520 мс | все шаги сразу (stagger 0, длительность 1 мс) |
| Перестановка карточки | 5.6 | `motion.duration.long` | 1 мс |
| Подъём/опускание карточки | 5.6 | `motion.duration.medium` | 1 мс |
| Покачивание подсказки | 7.9 | `dragHint.*` | нет |

Правила:

- семантика и узлы результата существуют с первого кадра: анимируется только `graphicsLayer { alpha }`, TalkBack читает экран сразу (`I6-U2`);
- «Дальше»/«К итогу дня» доступна и нажимаема сразу, не ждёт окончания reveal;
- reveal проигрывается только при первом показе записи бэкстека (`rememberSaveable`); после поворота и возврата — без анимации;
- ни один переход не блокирует ввод: навигация по эффекту выполняется сразу, анимация — на `AnimatedContent` `NavHost`;
- победных эффектов, отскоков, `spring` с `dampingRatio < 1` нет (`DESIGN_PRINCIPLES.md` §9).

Группировка шагов вместо «каждой строки» (`DESIGN_PRINCIPLES.md` §9) — сознательное уточнение: на экране с шестью перепутанными парами построчный reveal длился бы больше секунды. Вынесено на подтверждение (**O6-5**).

### 11.4 Иконки (I6-D46)

| Требование | Иконка приложения | Иконка уведомления |
| --- | --- | --- |
| Ресурсы | `mipmap-anydpi-v26/ic_launcher.xml` и `ic_launcher_round.xml` (`<adaptive-icon>`), `drawable/ic_launcher_foreground.xml`, `ic_launcher_background.xml`, `ic_launcher_monochrome.xml`; `android:icon`, `android:roundIcon` в `<application>` | `drawable/ic_stat_reminder.xml` |
| Формат | векторные drawable (XML); `minSdk 26` покрывает адаптивные иконки без растровых наборов плотностей; растр — только если владелец не может дать вектор, тогда WebP по плотностям | вектор 24 × 24 dp |
| Слои | foreground и background раздельно; холст 108 × 108 dp, видимая область 72 × 72 dp, значимое содержимое — в безопасном круге диаметром 66 dp; monochrome — одноцветный силуэт того же знака (тематические иконки API 33+) | один слой |
| Прозрачность | background непрозрачен; foreground — прозрачный фон | только альфа-канал: белая заливка на прозрачном, цвет задаёт система |
| Цвет и контраст | цвета — из палитры `DESIGN_TOKENS.md` §6.2 (без нового фирменного цвета); знак к фону ≥ 3 : 1 (графический объект, WCAG 1.4.11) | силуэт читается на 24 dp без мелких деталей |
| Стиль | `DESIGN_PRINCIPLES.md` §8: линейная система, без эмодзи, без декоративных «печатей»; срезанный угол `OrderableCardCutCorner` не переносится на иконку без пересмотра §6 | тот же знак, что у иконки приложения, либо утверждённый символ |
| Происхождение и лицензия | собственная работа владельца или CC0 / Apache 2.0 с записью автора, источника, лицензии, даты и SHA-256 в `docs/VERSIONS.md`, раздел «Графические ресурсы» (по образцу «Звуковые ресурсы») | то же |
| Когда нужны | до мержа 6D | до мержа 6B |

### 11.5 Итоговая матрица UI (I6-D50)

Строка — экран; ячейка — тест или ручная проверка, покрывающая условие после итерации 6. `M` — ручная проверка раздела 15.

| Экран | Светлая | Тёмная | 320 dp | 200 % | Ландшафт | Семантика/TalkBack (авто) | Reduced motion |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Home | существующие `HomeScreenTest` | существующий | существующий | существующий | `I6-U3` | `I6-A7` | `I6-U1` |
| Puzzle (с ручкой, подсказкой, поднятой картой) | `I6-C1` | `I6-U5` | `I6-A9` | `I6-A9`, `I6-U4` | `I6-U3` | `I6-A1`, `I6-A2`, `I6-A6` | `I6-A11` |
| PuzzleResult (сессия и архив) | существующие | существующий | существующий | существующий | `I6-U3` | `I6-A7`, `I6-A8` | `I6-U2` |
| DayRecap (сессия, архив, диалог) | `I6-C10` | `I6-U5` | существующий | `I6-C10` | `I6-U3` | `I6-A7`, `I6-C10` | `I6-U1` |
| Archive | существующие `I5-C*` | `I5-C7` | `I5-C6` | `I5-C6` | `I5-C7` | `I6-A7` | `I6-U1` |
| Settings (с напоминанием) | `I6-C9` | `I6-U5` | `I6-C9` | `I6-C9` | `I5-C12` | `I6-A7`, `I6-C9` | `I6-U1` |
| Sources | существующие | `I5-C16` | существующий | существующий | существующий | `I6-A7` | `I6-U1` |
| Все экраны вручную | `I6-M14` | `I6-M14` | `I6-M11` | `I6-M11` | `I6-M11` | `I6-M10` (итерация 7) | `I6-M12` |

---

## 12. Архитектурные границы и конкурентность

### 12.1 Правила зависимостей

| Правило | Как проверяется |
| --- | --- |
| `domain` не импортирует Android, Compose, WorkManager | `I6-K1` |
| Жест и Compose-анимация — только в `ui` | `CardOrder`, `DragTargetResolver`, `DragAutoScroll` — чистый Kotlin (`I6-R*` на JVM); `pointerInput`/`detectDragGestures` — только `ui/components/DragHandle.kt` и `ui/puzzle` (ревью 6A) |
| Изменение порядка — только через ViewModel | `I6-K6` |
| Android-уведомления — за интерфейсами | `I6-K3` (первая команда) |
| Worker не обращается к UI и не мутирует прогресс | `I6-K3` (вторая команда), `I6-W2` |
| Настройки — единственный источник намерения | синхронизатор и worker читают `UserPreferencesRepository`; своих копий не хранят |
| Room-схема не меняется | `git diff --stat main -- app/schemas` пусто |
| Повторный коллектор/перекомпозиция не создают побочных эффектов | эффекты — `Channel`, один коллектор на route; `DragHandle` и таймеры подсказки не пишут ни в ViewModel-состояние, ни в DataStore, кроме событий пользователя и одной команды флага |
| Ошибки планирования не ломают игровой поток | **I6-D49**, `I6-Y1`, `I6-Y2`, `I6-W3` |
| `domain/reminder` не зависит от жизненного цикла процесса | `rg -n 'ru\.poporyadku\.di' app/src/main/java/ru/poporyadku/domain` пусто; `@ApplicationScope` только в `notifications` и `data/prefs` |
| `CancellationException` не становится пользовательской ошибкой | явный `catch (e: CancellationException) { throw e }` перед общим `catch` в `ReminderRun`, синхронизаторе, `ReminderPromptViewModel`; `I6-W3` |
| `DayRecapViewModel` не пишет DataStore (I5-D31) | существующая `rg`-проверка 5B остаётся зелёной; диалог — отдельная ViewModel |
| `PuzzleViewModel` инжектирует только use cases и `SavedStateHandle` | +2 use case в 6C; `rg` итерации 3 (§22.4) остаётся зелёной |

### 12.2 Process death, пересоздание, повторная навигация, конкурентные события

| Ситуация | Поведение | Тест |
| --- | --- | --- |
| Смерть процесса во время перетаскивания | восстанавливается последний подтверждённый порядок, без отдачи и объявлений | `I6-V9`, `I6-M3` |
| Пересоздание Activity во время перетаскивания | карточка не остаётся поднятой; висящая сессия заменяется первым новым жестом с новым `DragGestureId`; запоздалые события старого жеста игнорируются | `I6-V9`, `I6-N2` |
| Нажатие кнопки ↑ другим пальцем во время жеста | подтверждается как кнопочная перестановка, сессия закрывается; следующий `DragMovedTo` той же карточки игнорируется, пока нет нового `DragStarted` | `I6-V11` |
| «Проверить» во время жеста | отправляется подтверждённый порядок; жест отменяется переходом в `Submitting` | `I6-V8` |
| Два жеста одновременно | второй игнорируется в UI | `I6-C2` |
| Смерть процесса во время системного диалога разрешения | цель запроса в `SavedStateHandle`, результат доставляется `ActivityResultRegistry` после восстановления; включение выполняется | `I6-V14` |
| Пересоздание во время диалога на `DayRecap` | флаг ещё не записан, диалог показывается снова; после ответа — никогда | `I6-V15` |
| Повторный вход в `DayRecap` того же дня после ответа | диалога нет | `I6-V15` |
| Быстрые включение → выключение → смена времени | последовательные команды очереди; итоговая работа соответствует последнему состоянию | `I6-Y1` |
| Изменение настроек, пока worker выполняется | `REPLACE` отменяет выполняющийся worker; перепланирование не дублируется | `I6-N6` |
| Смена зоны или времени при незапущенном приложении | система поднимает процесс для приёмника; `goAsync()`, одна операция синхронизации, `finish()` после записи WorkManager; `MainActivity` не нужна | `I6-P4`, `I6-N6`, `I6-M7` |
| Приёмник и открытое приложение синхронизируют одновременно | `ReminderScheduleLock`: операции последовательны, каждая перечитывает настройки | `I6-Y2` |
| Смерть процесса после появления системного диалога из предложения на `DayRecap` | отметка уже подтверждена записью; новый ViewModel диалога не показывает; результат запроса обрабатывается по `pendingEnable` | `I6-V15` |
| Отмена worker'а после вычисления следующей цели | `ensureActive()` перед `schedule`; перепланирования нет | `I6-W3` |
| Уведомление пришло, пользователь уже в приложении | снимается при следующем `onStart` `MainActivity` | `I6-P2`, `I6-M9` |

---

## 13. Разбиение на PR 6A–6D (I6-D1)

Разрез из задания сохранён с одним уточнением: **6C идёт после 6A**, потому что объявление по завершении жеста и семантика ручки проверяются только поверх работающего жеста, а **6B от 6A не зависит** и может разрабатываться параллельно (общие файлы — `strings.xml`, `AppNavHost.kt`: конфликты текстовые). Ни один PR не совмещает подключение WorkManager, переписывание `OrderableCard` и полировку экранов.

```
6A (drag + единый путь) ──▶ 6C (доступность, reduced motion, подсказка) ──┐
6B (напоминания) ─────────────────────────────────────────────────────────┴─▶ 6D (полировка, иконки, матрица)
```

Общие критерии каждого PR — 13.5.

### 13.1 PR 6A — перетаскивание и единый путь перестановки

**Цель.** Жест за ручку поверх кнопок; один алгоритм перестановки для кнопок, custom actions и жеста; отдача захвата и перемещения.

**Зависит от:** `main` на `f96a561`; утверждения этого документа; ответа **O6-6** (токены auto-scroll) — до мержа.

| Создаётся | Назначение |
| --- | --- |
| `ui/puzzle/CardOrder.kt` | `MoveTarget`, `CardOrder.move` |
| `ui/puzzle/DragTargetResolver.kt` | чистый расчёт цели по единственному порогу «центр пересёк центр соседа» |
| `ui/puzzle/DragGestureIds.kt` | `DragGestureId` и процессный счётчик |
| `ui/puzzle/DragAutoScroll.kt` | чистая функция скорости |
| `ui/puzzle/ReorderDragState.kt` | Compose-состояние жеста, компенсация смещения, цикл auto-scroll |
| `ui/components/DragHandle.kt` | глиф, 48 dp цель, `pointerInput` |
| `test/…/ui/puzzle/CardOrderTest.kt`, `DragTargetResolverTest.kt`, `DragAutoScrollTest.kt` | `I6-R1`…`I6-R4` |
| `testDebug/…/ui/puzzle/PuzzleDragTest.kt` | `I6-C1`…`I6-C7` |
| `androidTest/…/PuzzleDragFlowTest.kt` | `I6-N1`, `I6-N2`, `I6-N3` |

| Изменяется | Что именно |
| --- | --- |
| `ui/puzzle/PuzzleEvent.kt`, `PuzzleUiState.kt` | 4.3 (события с `DragGestureId`); удаление `draggedCardId` |
| `ui/puzzle/PuzzleViewModel.kt` | `reorder()`, `DragSession(cardId, gesture, startIndex)`, обработчики жеста |
| `ui/puzzle/PuzzleScreen.kt` | `ReorderDragState`, передача в карточки, `animateItem` без placement у поднятой |
| `ui/components/OrderableCard.kt` | ручка в индексной зоне, состояние `dragging` |
| `ui/feedback/FeedbackCue.kt`, `FeedbackPolicy.kt`, `AndroidFeedbackPlayer.kt`, `SoundCues.kt` | 6.2 |
| `ui/theme/Sizing.kt`/`Motion.kt` | только токены auto-scroll после **O6-6** |
| `test/…/ui/puzzle/PuzzleViewModelTest.kt` | `I6-V1`…`I6-V12` |
| `test/…/ui/feedback/FeedbackPolicyTest.kt` | `I6-F1` |
| `testDebug/…/ui/feedback/AndroidFeedbackPlayerTest.kt` | `I6-F2` |
| `testDebug/…/ui/puzzle/PuzzleScreenTest.kt` | `I3-C6` заменяется на `I6-C5` |
| `androidTest/…/DayFlowDriver.kt` | помощник жеста |
| документы | раздел 17, строки 6A |

**Не входит:** снятие `liveRegion`, `AccessibilityAnnouncer`, `DragEducationHint`, reduced motion реактивность (6C); напоминания (6B); переходы и иконки (6D).

**Тесты:** `I6-R1`…`I6-R4`, `I6-V1`…`I6-V12`, `I6-F1`, `I6-F2`, `I6-C1`…`I6-C7`, `I6-N1`, `I6-N2`, `I6-N3`; `I6-K4`, `I6-K6`. **Ручные:** `I6-M1`, `I6-M3` (эмулятор).

**Готов, когда:** все перечисленные тесты зелёные; `I5-V29`…`I5-V34`, `I3-C3`, `I3-C4`, `I3-C16`, `I5-N8` проходят без изменения утверждений; `I6-N*` скомпилированы и пройдены вручную на эмуляторе; бюджет высоты `Puzzle` при 100 % не вырос; токены auto-scroll и пары контраста внесены в `DESIGN_TOKENS.md` до кода.

**Rollback:** `git revert` PR; схема и ключи не меняются, прогресс не теряется; кнопки ↑/↓ и действия продолжают работать по коду итерации 5.

**Запрещено в diff:** `app/schemas`, `assets`, Gradle, CI, манифест, `notifications/`, `ui/settings`, `ui/recap`, `ui/navigation`, новые зависимости, вызовы `performHapticFeedback` вне `ui/feedback`.

### 13.2 PR 6B — напоминания, разрешение, настройки

**Цель.** Полный путь напоминания: настройки, предложение на `DayRecap`, разрешение, планирование, проверки, уведомление.

**Зависит от:** `main`; ответов **O6-1**, **O6-2**, **O6-3**, **O6-4**; входных данных «иконка уведомления» (18.2) — до мержа. Разработка до ответов возможна: код не зависит от текстов и формы времени.

| Создаётся | Назначение |
| --- | --- |
| `domain/reminder/*` (9.1), `domain/usecase/MarkReminderPromptShownUseCase.kt` | расчёты, статус доступа, интерфейсы, `SyncReminderScheduleUseCase`, `ReminderScheduleLock`, `ReminderRun`, подтверждаемая отметка предложения |
| `notifications/*` (9.1) | WorkManager с ожидаемыми операциями, `ReminderScheduleObserver`, `ReminderBroadcastHandler`, приёмник, worker, уведомление, статус доступа |
| `di/ReminderModule.kt` | привязки |
| `ui/settings/ReminderRows.kt` | группа напоминания, выбор времени по **O6-3** |
| `ui/recap/ReminderPromptViewModel.kt` | предложение (8.3) |
| `ui/components/NotificationOptInDialog.kt` | компонент по `COMPONENTS.md` |
| `ui/platform/NotificationPermissionRequest.kt` | `rememberNotificationPermissionRequest(onResult)` |
| `res/drawable/ic_stat_reminder.xml` | входные данные владельца |
| тесты JVM: `NextReminderTriggerTest`, `ReminderEligibilityTest`, `EvaluateReminderUseCaseTest`, `ReminderRunTest`, `SyncReminderScheduleUseCaseTest`, `test/…/notifications/ReminderScheduleObserverTest`, `ReminderPromptViewModelTest` | `I6-S*`, `I6-W*`, `I6-Y1`, `I6-Y2`, `I6-V15` |
| тесты Robolectric: `testDebug/…/notifications/WorkManagerReminderSchedulerTest.kt`, `AndroidReminderNotifierTest.kt`, `AndroidNotificationAccessTest.kt`, `ReminderBroadcastHandlerTest.kt`, `ManifestPermissionsTest.kt`; `testDebug/…/ui/components/NotificationOptInDialogTest.kt` | `I6-P1`…`I6-P5`, `I6-C10` |
| `androidTest/…/notifications/ReminderWorkSchedulingTest.kt` | `I6-N6` |

| Изменяется | Что именно |
| --- | --- |
| `app/build.gradle.kts` | `implementation(libs.androidx.work.runtime.ktx)` |
| `gradle/libs.versions.toml` | только комментарии «не подключён» → «подключён в 6B»; версия — после сверки |
| `AndroidManifest.xml` | 10.3 (`POST_NOTIFICATIONS`, приёмник); влитые разрешения WorkManager, включая `ACCESS_NETWORK_STATE`, не удаляются |
| `MainActivity.kt` | `ReminderScheduleObserver.start()`, `ensureChannel()`, снятие показанного в `onStart` |
| `domain/model/SettingMutation.kt`, `data/prefs/SettingsWriteQueue.kt` | новые команды и ключи (**I6-D39**) |
| `ui/settings/SettingsState.kt`, `SettingsEvent.kt`, `SettingsEffect.kt`, `SettingsViewModel.kt`, `SettingsScreen.kt` | 8.1, 8.2 |
| `ui/platform/ExternalApps.kt`, `AndroidExternalApps.kt` | `openNotificationSettings(target: App/Channel)` |
| `ui/navigation/AppNavHost.kt` | `SettingsRoute`: запрос разрешения; `DayRecapRoute`: вторая ViewModel и диалог |
| `res/values/strings.xml` | таблица 8.6 (строки 6B) |
| `test/…/ui/settings/SettingsViewModelTest.kt`, `testDebug/…/ui/settings/SettingsScreenTest.kt`, `testDebug/…/ui/platform/AndroidExternalAppsTest.kt` | `I6-V14`, `I6-C9`, `I6-C11`, `I6-P6` |
| документы | раздел 17, строки 6B |

**Не входит:** drag, доступность игрового экрана, переходы, иконка приложения; deep link; точные будильники.

**Тесты:** `I6-S1`…`I6-S4`, `I6-W1`…`I6-W4`, `I6-Y1`, `I6-Y2`, `I6-V14`…`I6-V16`, `I6-C9`…`I6-C11`, `I6-P1`…`I6-P6`, `I6-N6`; `I6-K1`…`I6-K3`, `I6-K8`. **Ручные:** `I6-M5` (эмулятор), `I6-M6`, `I6-M7`, `I6-M8`, `I6-M9`, `I6-M13`.

**Готов, когда:** тесты зелёные; `I6-N6` (включая холодную синхронизацию без `MainActivity`) и ручные проверки пройдены на эмуляторе API 35 и эмуляторе API ниже 33 с записью API и результата в описании PR; итоговый манифест совпадает с набором, зафиксированным `I6-P5` (есть `ACCESS_NETWORK_STATE` от WorkManager, нет `INTERNET` и локальных сетевых разрешений); в `ReminderRun`, `SyncReminderScheduleUseCase` и `ReminderBroadcastHandler` нет перепланирования или записи в блоке, выполняемом при отмене; системный запрос в `ReminderPromptViewModel` создаётся только после успешной `MarkReminderPromptShownUseCase`; `VERSIONS.md` содержит сверенную версию WorkManager с датой; `DayRecapViewModel` не изменён; тексты и иконка уведомления получены от владельца.

**Rollback:** `git revert` PR. Ключи DataStore существовали до 6B, их значения остаются и ни на что не влияют. Уже запланированная у пользователя работа ссылается на удалённый класс worker'а: WorkManager завершит её ошибкой создания worker'а без показа уведомления и без повтора, а уже показанное уведомление снимет пользователь или система в начале следующей даты (`timeoutAfter`, заданный при показе). Если откатываемая сборка уже ушла альфа-пользователям, следующая сборка на одну версию содержит однострочную очистку `cancelUniqueWork("daily_reminder")` при старте `MainActivity`.

**Запрещено в diff:** удаление влитых разрешений библиотек манифестным слиянием, `app/schemas`, `assets`, CI, `ui/puzzle`, `ui/components/OrderableCard.kt`, `DayRecapViewModel.kt`, `INTERNET`, сетевые библиотеки, `hilt-work`, `work-testing`, `SCHEDULE_EXACT_ALARM`, `AlarmManager`.

### 13.3 PR 6C — доступность и reduced motion

**Цель.** Единое объявление, снятие дублей, заголовки и порядок фокуса, реактивный reduced motion, `DragEducationHint`, автоматические проверки доступности всего дня.

**Зависит от:** 6A; подтверждения текстов подсказки (уже утверждены `COMPONENTS.md`).

| Создаётся | Назначение |
| --- | --- |
| `ui/platform/AccessibilityAnnouncer.kt` | граница объявлений |
| `ui/theme/ReducedMotion.kt` | `rememberReducedMotion()` |
| `ui/components/DragEducationHint.kt` | подсказка |
| `domain/usecase/ObserveDragHintUseCase.kt`, `MarkDragHintSeenUseCase.kt` | флаг подсказки |
| `testDebug/…/ui/puzzle/PuzzleAccessibilityTest.kt` | `I6-A1`…`I6-A4`, `I6-A6`, `I6-A9`, `I6-A11`…`I6-A13`, `I6-C8` |
| `testDebug/…/ui/AccessibilityHeadingsTest.kt` | `I6-A7` |
| `testDebug/…/ui/theme/ReducedMotionTest.kt` | `I6-A10` |
| `androidTest/…/AccessibilityDayFlowTest.kt` | `I6-N4`, `I6-N5` |

| Изменяется | Что именно |
| --- | --- |
| `ui/puzzle/PuzzleScreen.kt` | без `liveRegion`; `heading()` формулировки; подсказка; блокировка «Проверить» |
| `ui/puzzle/PuzzleViewModel.kt`, `PuzzleUiState.kt` | `showDragHint`, `DragHintDismissed` |
| `ui/navigation/AppNavHost.kt`, `ui/settings/SettingsRows.kt` | объявления через границу |
| `ui/theme/Motion.kt` | `rememberMotionTokens` через `rememberReducedMotion` |
| `domain/model/SettingMutation.kt`, `data/prefs/SettingsWriteQueue.kt` | `DragHintSeen` (если 6B ещё не влит — добавляется здесь же тем же образцом) |
| `ui/puzzleresult/PuzzleResultScreenTest` (testDebug) | `I6-A8` |
| `test/…/ui/puzzle/PuzzleViewModelTest.kt` | `I6-V13` |
| `testDebug/…/ui/puzzle/PuzzleScreenTest.kt` | «card list is a polite live region» → `I6-A5` |
| `res/values/strings.xml` | `drag_hint`, `cd_drag_hint` |
| документы | раздел 17, строки 6C |

**Не входит:** переходы экранов и reveal (6D); напоминания; живой TalkBack (итерация 7).

**Тесты:** `I6-A1`…`I6-A13`, `I6-V13`, `I6-C8`, `I6-N4`, `I6-N5`; `I6-K5`. **Ручные:** `I6-M12` (эмулятор), `I6-M3` повтор с подсказкой.

**Готов, когда:** тесты зелёные; `I6-N4`, `I6-N5` пройдены на эмуляторе; `rg -n announceForAccessibility app/src/main` — один файл; `rg -n liveRegion app/src/main/java/ru/poporyadku/ui/puzzle` пусто; `COMPONENTS.md`/`UI_REVIEW_CHECKLIST.md`/`UX_FLOW.md` §10 синхронизированы; `I6-M10` явно записан как не выполненный пункт итерации 7.

**Rollback:** `git revert`; флаг `hasSeenDragHint`, если уже записан, лишь не даст подсказке появиться при повторном мерже — это совпадает с её контрактом.

**Запрещено в diff:** схемы, Gradle, манифест, `notifications/`, новые зависимости, изменение текстов действий карточки.

### 13.4 PR 6D — визуальная полировка, переходы, иконки, итоговая матрица UI

**Цель.** Закрыть таблицу 11.1, ввести переходы и reveal по токенам, добавить иконку приложения, проверить матрицу 11.5 и `UI_REVIEW_CHECKLIST.md` на всех экранах, отметить итерацию 6 технически выполненной.

**Зависит от:** 6A, 6B, 6C; ответ **O6-5**; входные данные «иконка приложения».

| Создаётся | Назначение |
| --- | --- |
| `ui/navigation/NavTransitions.kt` | спецификации переходов из `MotionTokens` |
| `ui/puzzleresult/ResultReveal.kt` | группы reveal |
| `res/mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml`, `res/drawable/ic_launcher_foreground.xml`, `ic_launcher_background.xml`, `ic_launcher_monochrome.xml` | входные данные владельца |
| `testDebug/…/ui/navigation/NavTransitionsTest.kt` | `I6-U1` |
| `testDebug/…/ui/puzzleresult/ResultRevealTest.kt` | `I6-U2` |
| `testDebug/…/LauncherIconTest.kt` | `I6-U6` |

| Изменяется | Что именно |
| --- | --- |
| `ui/navigation/AppNavHost.kt` | переходы `NavHost` |
| `ui/puzzleresult/PuzzleResultScreen.kt` | reveal |
| `AndroidManifest.xml` | `android:icon`, `android:roundIcon` |
| `testDebug/…/HomeScreenTest.kt`, `PuzzleScreenTest.kt`, `PuzzleResultScreenTest.kt`, `DayRecapScreenTest.kt`, `SettingsScreenTest.kt`, `NotificationOptInDialogTest.kt` | `I6-U3`…`I6-U5` |
| точечные исправления V12, если `I6-M11` найдёт перекрытие | только перечисленные файлы экрана |
| документы | раздел 17, строки 6D; `IMPLEMENTATION_PLAN.md` — статус итерации 6 |

**Не входит:** новые компоненты, изменение палитры, типографики, шкалы отступов; напоминания и drag по существу.

**Тесты:** `I6-U1`…`I6-U6`; `I6-K7`. **Ручные:** `I6-M11`, `I6-M12`, `I6-M14`, `I6-M15` (эмулятор).

**Готов, когда:** тесты зелёные; `UI_REVIEW_CHECKLIST.md` отмечен для всех шести экранов и новых компонентов на эмуляторе с записью в описании PR; литералов вне токенов нет (`I6-K7`); иконка и её происхождение записаны в `VERSIONS.md`; `IMPLEMENTATION_PLAN.md` отмечает итерацию 6 технически выполненной и перечисляет незакрытые физические проверки итерации 7.

**Rollback:** `git revert`; переходы возвращаются к библиотечным, иконка — к системной; данные не затрагиваются.

**Запрещено в diff:** схемы, Gradle, CI, `notifications/`, `domain/`, `data/`, изменение контрактов событий и состояний.

### 13.5 Общие критерии всех PR

```bash
./gradlew assembleDebug assembleDebugAndroidTest lint testDebugUnitTest testReleaseUnitTest :app:assembleRelease
python3 -m pytest tools/validate-content/tests -q
python3 tools/validate-content/validate.py app/src/main/assets/content --expect-sets 35 --expect-puzzles 105
```

Контрольные команды `I6-K*` (14.9) — по списку PR. Для каждого PR: `git diff --stat main -- app/schemas app/src/main/assets .github` пуст; `I4-C6`, `I5-M5`, `I5-M6`, `I5-M10` не упоминаются как выполненные; ручные проверки записаны с устройством/эмулятором, API и результатом.

---

## 14. Тестовая матрица `I6-*` (I6-D48)

Группы: `R` — чистые функции перестановки и жеста (JVM); `V` — ViewModel (JVM); `F` — отдача; `C` — Compose-экраны (Robolectric, `testDebug`); `A` — доступность (Robolectric, `testDebug`); `S` — расчёт момента и решения показа (JVM); `W` — срабатывание worker'а (JVM); `Y` — синхронизатор (JVM); `P` — Android-границы уведомлений (Robolectric); `N` — instrumented (`androidTest`: компилируются в CI, выполняются вручную на эмуляторе); `U` — визуальные условия и ресурсы; `K` — контрольные команды; `M` — ручные проверки (раздел 15). Свойства, проверяемые дешевле на JVM или Compose, в instrumented не дублируются.

### 14.1 Перестановка и перетаскивание

| ID | Сценарий | Класс | PR | Решение |
| --- | --- | --- | --- | --- |
| I6-R1 | `CardOrder.move` для всех позиций 0..3 и целей `Up`, `Down`, `First`, `Last`, `Index(0..3)`: применимые дают новую перестановку с карточкой на цели; неприменимые (край, цель = индекс, индекс вне диапазона, неизвестный `cardId`) — `null` | `test/…/ui/puzzle/CardOrderTest` | 6A | I6-D4 |
| I6-R2 | Свойство на всех 24 перестановках × 4 карточки × все цели: результат — перестановка входа (без потерь и дублей), относительный порядок остальных сохранён | `CardOrderTest` | 6A | I6-D4 |
| I6-R3 | `DragTargetResolver.targetIndex` по правилу 5.3: центр ниже центра соседа снизу на 1 px → цель +1; ровно на центре → цель прежняя; проход двух центров за сэмпл → +2; то же вверх; карточки высотой 112 и 200 px — порог по центру каждой; последний индекс и `0` не превышаются; невидимый сосед останавливает цикл; после перестановки вниз смещение вверх меньше `hD + g` не даёт обратной цели (последовательность сэмплов без чередования) | `test/…/ui/puzzle/DragTargetResolverTest` | 6A | I6-D7 |
| I6-R4 | `DragAutoScroll.velocity`: вне краевых зон 0; в верхней — отрицательная, пропорциональна заходу, не больше максимума; в нижней — положительная; при невозможности прокрутки 0 | `test/…/ui/puzzle/DragAutoScrollTest` | 6A | I6-D12 |
| I6-V1 | `DragStarted` + `DragMovedTo` на одну позицию вниз: состояние, `position`/`canMove*`, оба ключа `SavedStateHandle` обновлены; ровно один `Feedback(CardMoved)`; `AnnounceCardMoved` не отправлен | `test/…/ui/puzzle/PuzzleViewModelTest` | 6A | I6-D6, I6-D15 |
| I6-V2 | `DragMovedTo` сразу на три позиции: одна перестановка, одна отдача | `PuzzleViewModelTest` | 6A | I6-D7 |
| I6-V3 | Повтор `DragMovedTo` с той же целью: второй перестановки, записи и отдачи нет | `PuzzleViewModelTest` | 6A | I6-D3 |
| I6-V4 | `DragFinished` после чистого смещения: ровно одно `AnnounceCardMoved` с финальной позицией; жест вернул карточку на место — объявлений нет | `PuzzleViewModelTest` | 6A | I6-D16 |
| I6-V5 | `DragStarted(g1)`: `Feedback(CardGrabbed)` с `performHaptic = true`, `playSound = false`; вибрация выключена или `Unknown` — эффекта нет; повтор `DragStarted` с тем же `g1` — без второй отдачи; `DragStarted` той же карточки с новым `g2` — ровно одна новая отдача и новый `startIndex` | `PuzzleViewModelTest` | 6A | I6-D9, I6-D14 |
| I6-V6 | События жеста в `Loading`, `Submitting.Answer`, `Error`: состояние, `SavedStateHandle` и канал эффектов не меняются | `PuzzleViewModelTest` | 6A | I6-D11 |
| I6-V7 | Эквивалентные намерения `MoveUp`, custom action «Переместить вверх» и `DragMovedTo(index − 1)` из одинакового исходного состояния дают одинаковые порядок, строку `puzzle.currentOrder` и по одному `CardMoved` | `PuzzleViewModelTest` | 6A | I6-D4 |
| I6-V8 | `Submit` при открытой сессии после двух подтверждений: отправлен порядок после второго; последующий `DragFinished` ничего не делает | `PuzzleViewModelTest` | 6A | I6-D8 |
| I6-V9 | Жизненный цикл сессии: (а) тот же ViewModel — `DragStarted(c, g1)`, `DragMovedTo(c, 2, g1)`; UI уничтожен без `DragFinished`; новый UI — `DragStarted(c, g2)` → ровно один `CardGrabbed`; `DragMovedTo(c, 0, g2)`, `DragFinished(c, g2)` → одно объявление, сравнение с позицией на начало `g2`; затем запоздалый `DragFinished(c, g1)` — ни объявления, ни изменений; (б) новый ViewModel на том же `SavedStateHandle` (восстановление процесса) — порядок восстановлен, отдачи и объявлений нет, `DragMovedTo(c, 1, g2)` без нового `DragStarted` игнорируется | `PuzzleViewModelTest` | 6A | I6-D9 |
| I6-V10 | Неизвестный `cardId`; `DragFinished` без сессии; `DragMovedTo`/`DragFinished` с верным `cardId`, но чужим `DragGestureId`; с верным `DragGestureId`, но чужим `cardId` — ничего не происходит | `PuzzleViewModelTest` | 6A | I6-D5 |
| I6-V11 | `MoveDown` другой карточки при открытой сессии: одна кнопочная перестановка с объявлением; сессия закрыта; следующий `DragMovedTo` прежней карточки игнорируется | `PuzzleViewModelTest` | 6A | I6-D9 |
| I6-V12 | Кнопочный контракт итерации 5 не изменился: `MoveUp` даёт ровно `Feedback(CardMoved)`, затем `AnnounceCardMoved`; на краю — ничего (`I5-V29`, `I5-V30` проходят без изменения утверждений) | `PuzzleViewModelTest` | 6A | I6-D15 |
| I6-F1 | `FeedbackPolicy` для `CardGrabbed`: вибрация включена → только haptic при любом звуке; вибрация выключена → `null`; `Unknown` → `null`; для `CardMoved`/`AnswerAccepted` таблица `I5-F1` не меняется | `test/…/ui/feedback/FeedbackPolicyTest` | 6A | I6-D14 |
| I6-F2 | `AndroidFeedbackPlayer`: `CardGrabbed` → `DRAG_START` на API 34, `GESTURE_START` на API 30, `VIRTUAL_KEY` на API 29; звук не вызывается; `SoundCueBank.play(CardGrabbed)` без звукового ресурса не бросает и не играет | `testDebug/…/ui/feedback/AndroidFeedbackPlayerTest` | 6A | I6-D14 |
| I6-C1 | `Playing`: у каждой карточки есть ручка с тестовым тегом по `cardId`, область касания ≥ 48 × 48 dp; ручка не создаёт отдельного узла семантики; высота карточки при 100 % = 112 dp | `testDebug/…/ui/puzzle/PuzzleDragTest` | 6A | I6-D10, I6-D19 |
| I6-C2 | `performTouchInput` на ручке: `down`, смещение больше touch slop и за центр соседа, `up` → события `DragStarted`, `DragMovedTo(цель)`, `DragFinished` в этом порядке; позиция прокрутки не изменилась; второй одновременный указатель на другой ручке событий не шлёт | `PuzzleDragTest` | 6A | I6-D10 |
| I6-C3 | На `w320dp-h844dp` при 200 %: свайп по центральной зоне карточки прокручивает список и не шлёт событий жеста | `PuzzleDragTest` | 6A | I6-D10 |
| I6-C4 | `cancel()` жеста → `DragFinished`, дальнейших событий нет, смещение сброшено | `PuzzleDragTest` | 6A | I6-D8 |
| I6-C5 | `Submitting.Answer`: ручки есть, жест событий не шлёт; read-only `PuzzleResult`: ручек в дереве нет (заменяет `I3-C6`) | `PuzzleDragTest`, `PuzzleScreenTest` | 6A | I6-D11 |
| I6-C6 | Прокручиваемый список: удержание карточки в нижней краевой зоне при продвижении `mainClock` прокручивает список и шлёт `DragMovedTo` для пройденных карточек; после `up` позиция прокрутки больше не меняется | `PuzzleDragTest` | 6A | I6-D12 |
| I6-C7 | Переход состояния в `Submitting.Answer` во время удержания отменяет жест: `DragFinished` отправлен, auto-scroll остановлен | `PuzzleDragTest` | 6A | I6-D11 |
| I6-N1 | Эмулятор: реальный жест на одну и на три позиции; итоговый порядок на экране; `RecordingFeedbackPlayer`: `CardGrabbed` = число захватов, `CardMoved` = число подтверждений | `androidTest/…/PuzzleDragFlowTest` | 6A | I6-D6 |
| I6-N2 | Эмулятор: частичный жест, затем `ActivityScenario.recreate()` без отпускания: карточка не поднята, порядок = последнее подтверждение, после пересоздания отдач нет; новый жест той же карточки даёт один `CardGrabbed` (запись `RecordingFeedbackPlayer`) и объявление относительно своего начала | `PuzzleDragFlowTest` | 6A | I6-D9 |
| I6-N3 | Эмулятор: полный день только жестами (`DayFlowDriver`), итог 18 из 18 | `PuzzleDragFlowTest` | 6A | I6-D4 |

### 14.2 Доступность и reduced motion

| ID | Сценарий | Класс | PR | Решение |
| --- | --- | --- | --- | --- |
| I6-A1 | У каждой карточки один узел с описанием «Позиция N из 4. {Название}»; после кнопочной перестановки и после жеста описания всех затронутых карточек обновлены; ручка узлов не добавляет; кнопки — «Переместить вверх/вниз» | `testDebug/…/ui/puzzle/PuzzleAccessibilityTest` | 6C | I6-D19 |
| I6-A2 | Набор custom actions по позициям 1, 2, 3, 4 — ровно таблица 7.2; `Submitting.Answer` и read-only — пусто; подписи из ресурсов «вверх/вниз/в начало/в конец» | `PuzzleAccessibilityTest` | 6C | I6-D18 |
| I6-A3 | `performSemanticsAction(CustomActions)` для каждого действия отправляет то же событие, что кнопка или `MoveToTop/MoveToBottom` | `PuzzleAccessibilityTest` | 6C | I6-D4 |
| I6-A4 | С фейковым `AccessibilityAnnouncer` в `PuzzleRoute`: успешная перестановка — один вызов с текстом `puzzle_card_moved`; край — ноль; жест — один при завершении | `PuzzleAccessibilityTest` | 6C | I6-D16 |
| I6-A5 | Ни у контейнера списка, ни у карточек нет `LiveRegion` (заменяет «card list is a polite live region») | `PuzzleScreenTest` | 6C | I6-D17 |
| I6-A6 | Порядок узлов дерева семантики `Puzzle.Playing`: «Назад» → заголовок → категория → формулировка → направление → (подсказка) → карточка/↑/↓ × 4 → «Проверить» последняя | `PuzzleAccessibilityTest` | 6C | I6-D22 |
| I6-A7 | Инвентарь `heading()` по таблице 7.4 на Home, Puzzle, PuzzleResult, DayRecap (с диалогом), Archive, Settings (с напоминанием), Sources | `testDebug/…/ui/AccessibilityHeadingsTest` | 6C | I6-D22 |
| I6-A8 | PuzzleResult: строки пар — отдельные узлы, число = `6 − score`, описание — полная форма и при сокращённой визуальной; у карточек нет цветового или семантического признака «верно/неверно»; при 6 из 6 — текст «Всё верно» | `PuzzleResultScreenTest` | 6C | 7.5 |
| I6-A9 | На `w320dp-h844dp` при 200 %: все узлы с действием нажатия на Puzzle (включая ручки), PuzzleResult, DayRecap с диалогом — не меньше 48 × 48 dp | `PuzzleAccessibilityTest` | 6C | 7.6 |
| I6-A10 | `rememberReducedMotion`: смена `Settings.Global.ANIMATOR_DURATION_SCALE` 1 → 0 и событие `ON_RESUME` переключают `rememberMotionTokens` на `Reduced`, обратно — на `Normal` | `testDebug/…/ui/theme/ReducedMotionTest` | 6C | I6-D20 |
| I6-A11 | При `Reduced`: спецификация перестановки 1 мс linear; покачивание подсказки отсутствует; таймеры 4000 и 600 мс не сокращены | `PuzzleAccessibilityTest` | 6C | I6-D20 |
| I6-A12 | Подсказка не озвучивает визуальный текст: её описание — `cd_drag_hint` | `PuzzleAccessibilityTest` | 6C | I6-D21 |
| I6-A13 | Клавиатура: фокус на `MoveButton` + `performKeyInput(Enter)` отправляет `MoveUp`; «Проверить» активируется так же | `PuzzleAccessibilityTest` | 6C | I6-D23 |
| I6-V13 | Подсказка во ViewModel: `hasSeenDragHint = false` → `showDragHint = true` и ровно одна команда `DragHintSeen`; скрывается первой перестановкой любого источника, `DragStarted`, `DragHintDismissed`; при `true` не показывается; повторная загрузка того же ViewModel не отправляет вторую команду; новый ViewModel при всё ещё `false` (запись потеряна) показывает подсказку снова — ожидаемое поведение fire-and-forget | `PuzzleViewModelTest` | 6C | I6-D21 |
| I6-C8 | Подсказка на экране: текст, место между направлением и первой карточкой, не перекрывает ручку и кнопки; «Проверить» disabled первые 600 мс; через 4000 мс отправлен `DragHintDismissed` | `PuzzleAccessibilityTest` | 6C | I6-D21 |
| I6-N4 | Эмулятор: полный день только через custom actions (без кнопок и жестов), 18 из 18 | `androidTest/…/AccessibilityDayFlowTest` | 6C | 7.2 |
| I6-N5 | Эмулятор API 35: `UiAutomation`-слушатель получает ровно один `TYPE_ANNOUNCEMENT` на успешную перестановку и ни одного на край | `AccessibilityDayFlowTest` | 6C | I6-D16 |

### 14.3 Напоминания: расчёты и решения

| ID | Сценарий | Класс | PR | Решение |
| --- | --- | --- | --- | --- |
| I6-S1 | `NextReminderTrigger`: до времени — сегодня; ровно в момент и позже — завтра; время 00:00 и 23:59; 31 декабря → 1 января | `test/…/domain/reminder/NextReminderTriggerTest` | 6B | I6-D28 |
| I6-S2 | DST: Europe/Berlin 2026-03-29 02:30 → 03:30 местного; 2026-10-25 02:30 → более раннее смещение; Europe/Moscow — задержки между последовательными целями ровно 24 ч | `NextReminderTriggerTest` | 6B | I6-D28 |
| I6-S3 | Тот же момент в Europe/Moscow и Asia/Novosibirsk даёт разные моменты цели при одном местном времени | `NextReminderTriggerTest` | 6B | I6-D28 |
| I6-S4 | `ReminderEligibility`: все строки таблицы 9.6 (`NewSet`, `CarryOver`, `Assigned` с 0/1/2/3 закрытыми, `AwaitingNextDay`, `ContentExhausted`); строка «1–2» — по ответу **O6-1** | `test/…/domain/reminder/ReminderEligibilityTest` | 6B | I6-D33 |
| I6-W1 | `ReminderRun` с фейками по таблице 9.6: выключено → нет показа и нет перепланирования; время изменилось → нет показа, цель по новым настройкам; дата устарела → нет показа, следующая цель; рано → нет показа, та же цель; каждый из `RuntimePermissionMissing`, `AppNotificationsDisabled`, `ChannelDisabled` → нет показа, завтра; не подходит день → нет показа, завтра; всё прошло → один показ, завтра; перепланирование — `AfterCurrent(workId)` под `ReminderScheduleLock`; `Report` без ошибок | `test/…/domain/reminder/ReminderRunTest` | 6B | I6-D32 |
| I6-W2 | Фейковые `DayAssignmentRepository`, `ProgressRepository`, `UserPreferencesRepository`, `ContentInstaller` бросают на любую запись: `ReminderRun` во всех ветках их не вызывает; `peek()` бросает → показа нет, перепланирование по настройкам, исключение не выходит | `ReminderRunTest`, `test/…/domain/reminder/EvaluateReminderUseCaseTest` | 6B | I6-D32 |
| I6-W3 | Отмена и ошибки `ReminderRun`: (а) `CancellationException` из оценки до вердикта; (б) отмена задания корутины после вычисления `next` (фейковая оценка возвращает вердикт и отменяет `Job`); (в) `CancellationException` из `notifier.show()`; (г) `CancellationException` из `fallbackNextTrigger` — во всех четырёх исключение пробрасывается и `schedule()` не вызван ни разу; (д) обычная ошибка оценки + исключение `fallbackNextTrigger` → наружу ничего, `schedule()` не вызван, `Report.evaluationFailure` — исходная ошибка оценки; (е) исключение `scheduler.schedule` → наружу ничего, `Report.rescheduleFailure` заполнен; (ж) ошибка оценки, fallback дал цель, `schedule` бросает → `evaluationFailure` остаётся исходной ошибкой оценки, `rescheduleFailure` — ошибка планировщика | `ReminderRunTest` | 6B | I6-D49 |
| I6-W4 | Два последовательных запуска с одинаковыми данными в один день: два вызова `show()` одного notifier'а; вместе с `I6-P2` — одно активное уведомление | `ReminderRunTest` | 6B | 9.7 |
| I6-Y1 | `ReminderScheduleObserver` (фейковый `SyncReminderScheduleUseCase`, `TestScope`): `start()` → синхронизация на первой эмиссии; вкл → выкл → вкл → смена времени — по одной синхронизации на эмиссию, по порядку; эмиссия другого ключа — без вызова; второй `start()` — без второго коллектора; результат `Failed` не останавливает сбор | `test/…/notifications/ReminderScheduleObserverTest` | 6B | I6-D29 |
| I6-Y2 | `SyncReminderScheduleUseCase`: включено → `schedule(Replace)` с целью `NextReminderTrigger` от одного `clock.now()` (фейк часов считает вызовы = 1); выключено → `cancel`; настройки, изменённые между двумя вызовами, читаются заново; результат возвращается только после завершения фейковой операции планировщика; исключение планировщика → `Failed` (не `Scheduled`/`Cancelled`); `CancellationException` пробрасывается; два одновременных вызова выполняются последовательно (второй ждёт `ReminderScheduleLock`) | `test/…/domain/reminder/SyncReminderScheduleUseCaseTest` | 6B | I6-D29 |
| I6-V14 | `SettingsViewModel` с фейковым `NotificationAccess`: переключатель = `enabled && Allowed`; включение при `Allowed` → `ReminderEnabled(true)`; при `RuntimePermissionMissing` → `pendingEnable`, эффект запроса, записи нет; результат запроса `true`, но перечитанный статус `ChannelDisabled` → записи нет, подсказка с целью `Channel`; перечитанный `Allowed` (в том числе при callback `false`) → одна команда; при `AppNotificationsDisabled` и `ChannelDisabled` → эффекта запроса нет, сразу `OpenNotificationSettings(App)`/`(Channel)`, подсказка; API 26–32 без доступа → никогда не запрос, сразу настройки; `onScreenStarted()` после возврата: `pendingEnable && Allowed` → ровно одна команда, повторный `onScreenStarted()` — ни одной; отзыв доступа → показано выключенным, записей нет; выключение сбрасывает `pendingEnable`; выбор времени → `ReminderTime`; `pendingEnable` переживает новый экземпляр ViewModel на том же `SavedStateHandle`; ошибки ключей `Reminder`/`ReminderTime` видны, `DragHintSeen` — нет | `test/…/ui/settings/SettingsViewModelTest` | 6B | I6-D36…I6-D39 |
| I6-V15 | `ReminderPromptViewModel` с управляемыми фейками `MarkReminderPromptShownUseCase` и `NotificationAccess`: показ только при сессии + завершённом дне + `!promptShown` + `!reminderEnabled`; архив, незавершённый день, флаг, включённое напоминание — нет; «Да» при **задержанной** записи → диалог скрыт, эффект запроса и команды не отправлены, пока запись не завершилась; после завершения при `RuntimePermissionMissing` → ровно один эффект запроса, при `Allowed` → `ReminderTime(09:00)` и `ReminderEnabled(true)`, при `AppNotificationsDisabled`/`ChannelDisabled` → ничего; «Да» при **упавшей** записи → диалог скрыт, запроса и команд нет, `CancellationException` не глотается; результат запроса `true`, но перечитан `ChannelDisabled` или `AppNotificationsDisabled` → включения нет; перечитан `Allowed` → две команды; «Не нужно»/«назад» → только ожидаемая запись, без запроса; **новый ViewModel после подтверждённой записи** (флаг `true`) — диалога нет, а результат запроса, пришедший ему по `pendingEnable` из `SavedStateHandle`, при `Allowed` включает напоминание; отказ чтения условий → диалога нет | `test/…/ui/recap/ReminderPromptViewModelTest` | 6B | I6-D40 |
| I6-V16 | I5-D31 не нарушен: `DayRecapViewModel` не изменён, `rg`-проверка PR 5B пуста, `I5-V35` зелёный | `DayRecapViewModelTest` (существующий) | 6B | I6-D40 |

### 14.4 Напоминания: Android-границы и UI

| ID | Сценарий | Класс | PR | Решение |
| --- | --- | --- | --- | --- |
| I6-P1 | `WorkManagerReminderScheduler` с фейковой обёрткой над операциями WorkManager: `Replace` → `REPLACE`, `AfterCurrent` → `APPEND_OR_REPLACE`, имя `daily_reminder`, данные ровно `targetDate` и `minuteOfDay`, задержка = `Duration.between(now, at)`, отрицательная → 0; `cancel` → `cancelUniqueWork` | `testDebug/…/notifications/WorkManagerReminderSchedulerTest` | 6B | I6-D26, I6-D27 |
| I6-P2 | `AndroidReminderNotifier` (Shadow `NotificationManager`): канал `daily_reminder`, `IMPORTANCE_DEFAULT`, без звука и вибрации, без значка, создаётся идемпотентно; уведомление с ID 1001, малой иконкой, `CATEGORY_REMINDER`, `autoCancel`, `contentIntent` на `MainActivity` с `ACTION_MAIN`/`CATEGORY_LAUNCHER` и `FLAG_IMMUTABLE`; повторный показ — одно активное уведомление; `cancelShown` снимает; `SecurityException` при показе перехвачен | `testDebug/…/notifications/AndroidReminderNotifierTest` | 6B | I6-D34, I6-D35 |
| I6-P3 | `AndroidNotificationAccess.availability()` по порядку таблицы 8.2: API 33 без разрешения → `RuntimePermissionMissing` (даже при включённых уведомлениях приложения); API 33 с разрешением и выключенными уведомлениями приложения → `AppNotificationsDisabled`; API 32 с выключенными уведомлениями → `AppNotificationsDisabled` и никогда `RuntimePermissionMissing`; канал `IMPORTANCE_NONE` → `ChannelDisabled`; всё включено → `Allowed`; канал ещё не создан → не `ChannelDisabled` | `testDebug/…/notifications/AndroidNotificationAccessTest` | 6B | I6-D36 |
| I6-P4 | `ReminderBroadcastHandler` при **незапущенном** `ReminderScheduleObserver` (реальный `SyncReminderScheduleUseCase`, фейковые настройки и управляемый планировщик): `TIMEZONE_CHANGED` при включённом напоминании → настройки прочитаны, `schedule(Replace)` с новой целью; `onFinished` не вызван, пока операция планировщика не завершена, и вызван ровно один раз после неё; `TIME_SET` — то же; выключенное напоминание → `cancel`, затем `onFinished`; планировщик бросает → результат `Failed`, `onFinished` один раз, исключение не выходит; истечение бюджета → `Failed`, `onFinished` один раз; отмена scope → `onFinished` один раз; неизвестное действие → `onFinished` сразу, синхронизации нет; итоговый манифест: приёмник с ровно двумя действиями и `exported = false` | `testDebug/…/notifications/ReminderBroadcastHandlerTest` | 6B | I6-D30 |
| I6-P5 | Итоговый манифест (`PackageManager.getPackageInfo(GET_PERMISSIONS)`): набор запрошенных разрешений равен набору, зафиксированному в 6B по фактической сборке (`POST_NOTIFICATIONS` и разрешения WorkManager, включая `ACCESS_NETWORK_STATE`); отсутствуют `INTERNET`, `ACCESS_LOCAL_NETWORK`, `NEARBY_WIFI_DEVICES`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `CHANGE_NETWORK_STATE` | `testDebug/…/ManifestPermissionsTest` | 6B | I6-D41 |
| I6-P6 | `AndroidExternalApps.openNotificationSettings(App)` → `Settings.ACTION_APP_NOTIFICATION_SETTINGS` с `EXTRA_APP_PACKAGE`; `(Channel)` → `Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS` с `EXTRA_APP_PACKAGE` и `EXTRA_CHANNEL_ID = daily_reminder`; защита от повторного запуска и перехват исключений — как `I5-P1` | `testDebug/…/ui/platform/AndroidExternalAppsTest` | 6B | I6-D36 |
| I6-C9 | Настройки: группа «Напоминание» с `heading()`; строка с ролью `Switch`; строка времени есть только при показанном включении; для каждой недоступности (`RuntimePermissionMissing`, `AppNotificationsDisabled`, `ChannelDisabled`) под строкой — подсказка и действие «Открыть настройки уведомлений», нажатие отправляет `OpenNotificationSettingsClicked`; при `Allowed` подсказки нет; ошибка записи под своей строкой; `w320dp-h844dp` при 200 % — всё видно, цели ≥ 48 dp, горизонтальной прокрутки нет; тёмная тема | `testDebug/…/ui/settings/SettingsScreenTest` | 6B | 8.1, 8.2 |
| I6-C10 | `NotificationOptInDialog`: один текстовый узел вопроса с `heading()` и первым в порядке; кнопки «Да, в 9:00» → «Не нужно»; касание вне диалога не закрывает; системная «назад» → `Declined`; при 200 % кнопки вертикально и ≥ 48 dp; светлая и тёмная темы | `testDebug/…/ui/components/NotificationOptInDialogTest` | 6B | 8.3 |
| I6-C11 | Выбор времени (форма — **O6-3**): выбор 10:30 и подтверждение → одно `ReminderTimeChosen(10:30)`; отмена → событий нет; описание строки «Время напоминания, 9:00» | `SettingsScreenTest` | 6B | I6-D38 |
| I6-N6 | Эмулятор, настоящий WorkManager: `schedule(Replace)` → одна работа `ENQUEUED` с данными; повтор → по-прежнему одна; `cancel` → `CANCELLED`; `AfterCurrent` при уже поставленной синхронизацией работе второй работы не добавляет; **холодная синхронизация** — процесс инструментации без запуска `MainActivity` и без `ReminderScheduleObserver.start()`: `ReminderBroadcastHandler.handle(ACTION_TIMEZONE_CHANGED)` из графа Hilt, ожидание `onFinished`, сразу после него `getWorkInfosForUniqueWork` содержит `ENQUEUED` работу с новыми данными; worker без ограничений выполняется при объявленном WorkManager `ACCESS_NETWORK_STATE` | `androidTest/…/notifications/ReminderWorkSchedulingTest` | 6B | I6-D26, I6-D30, I6-D41 |

### 14.5 Визуальные условия и ресурсы

| ID | Сценарий | Класс | PR | Решение |
| --- | --- | --- | --- | --- |
| I6-U1 | Спецификации переходов из `MotionTokens.Normal`: вход 300 мс `standardDecelerate`, выход 200 мс `standardAccelerate`; из `Reduced` — 1 мс linear | `testDebug/…/ui/navigation/NavTransitionsTest` | 6D | I6-D43 |
| I6-U2 | Reveal: не больше 8 шагов, задержка шага = номер × 60 мс, длительность 100 мс; `Reduced` — всё сразу; все узлы семантики и CTA существуют и доступны на первом кадре при остановленных часах; после пересоздания повтора нет | `testDebug/…/ui/puzzleresult/ResultRevealTest` | 6D | I6-D44, I6-D45 |
| I6-U3 | `w844dp-h390dp`: Home, Puzzle (список прокручивается, «Проверить» видна), PuzzleResult, DayRecap — колонка ≤ `contentMaxWidth`, горизонтальной прокрутки нет | `HomeScreenTest`, `PuzzleScreenTest`, `PuzzleResultScreenTest`, `DayRecapScreenTest` | 6D | V11 |
| I6-U4 | 200 % и 320 dp для новых элементов: подсказка, строки напоминания, диалог — ни один текст не обрезан | `PuzzleScreenTest`, `SettingsScreenTest`, `NotificationOptInDialogTest` | 6D | 7.6 |
| I6-U5 | Тёмная тема новых элементов: ручка, поднятая карточка, подсказка, диалог, строки напоминания рендерятся ролями `ColorScheme` | те же классы | 6D | 5.6 |
| I6-U6 | Иконка приложения: `applicationInfo.icon` и `roundIcon` разрешаются в `AdaptiveIconDrawable` с foreground, background и monochrome (Robolectric `sdk=35`) | `testDebug/…/LauncherIconTest` | 6D | I6-D46 |

### 14.6 Контрольные команды

`rg` возвращает код 1 при отсутствии совпадений; ожидаемый результат — в комментарии.

```bash
# I6-K1 — domain без Android/Compose/WorkManager
rg -n -e '^import android\.' -e '^import androidx\.' app/src/main/java/ru/poporyadku/domain            # пусто
# I6-K2 — нет сети
rg -n -e 'HttpURLConnection' -e 'java\.net\.URL\(' -e 'java\.net\.Socket' -e 'okhttp' -e 'retrofit' -e 'ktor' app/src/main   # пусто
rg -n 'android.permission.INTERNET' app/src/main/AndroidManifest.xml                                     # пусто
# I6-K3 — уведомления и WorkManager только в notifications/, worker без UI и мутаций
rg -l -e 'NotificationManager' -e 'androidx\.work' app/src/main/java/ru/poporyadku | rg -v '/notifications/'   # пусто
rg -n -e 'ru\.poporyadku\.ui' -e 'startSession' -e 'recordAttempt' -e 'ensureInstalled' -e 'setReminder' -e 'setNotificationPromptShown' \
   app/src/main/java/ru/poporyadku/notifications app/src/main/java/ru/poporyadku/domain/reminder        # пусто
# I6-K4 — тактильная отдача только в ui/feedback
rg -n -e 'performHapticFeedback' -e 'LocalHapticFeedback' -e 'HapticFeedbackType' app/src/main/java/ru/poporyadku/ui | rg -v '/ui/feedback/'   # пусто
# I6-K5 — объявления только через границу; нет liveRegion на игровом экране
rg -l 'announceForAccessibility' app/src/main                                                          # ровно ui/platform/AccessibilityAnnouncer.kt
rg -n 'liveRegion' app/src/main/java/ru/poporyadku/ui/puzzle                                          # пусто
# I6-K6 — перестановка только во ViewModel
rg -l 'CardOrder\.move' app/src/main                                                                   # ровно ui/puzzle/PuzzleViewModel.kt
# I6-K7 — литералы вне токенов (после 6D)
rg -n -e 'Color\(0x' -e '[0-9]\.?[0-9]*\.(dp|sp)\b' -e 'tween\([0-9]' -e 'RoundedCornerShape\(' app/src/main/java/ru/poporyadku/ui -g '!ui/theme/**'   # пусто
# I6-K8 — иконка уведомления одноцветная
rg -n 'android:fillColor' app/src/main/res/drawable/ic_stat_reminder.xml | rg -v '#FFFFFFFF'           # пусто
```

---

## 15. Ручные проверки и release gate

Правило записи для каждой ручной проверки: устройство или AVD, версия Android/API, сборка (debug/release, коммит), дата, результат «пройдено / не пройдено» с описанием. Проверка без такой записи **не считается выполненной**. Локально доступны эмуляторы (`Pixel_9a`, API 35; `compact`); физического устройства нет.

### 15.1 Ручные проверки итерации 6

| ID | Проверка | Где возможна | Обязательна для мержа | Физическая часть |
| --- | --- | --- | --- | --- |
| I6-M1 | Перетаскивание: короткий список (390 dp, 100 %) и прокручиваемый (320 dp через `wm density`, 200 %); auto-scroll вверх и вниз; карточка не «прилипает» и не теряется; свайп вне ручки прокручивает | эмулятор | 6A | плавность на слабом устройстве — `I6-M2` |
| I6-M2 | Отсутствие заметных подтормаживаний при перетаскивании на слабом устройстве | **только физическое устройство** | — | итерация 7 |
| I6-M3 | Поворот и «Не сохранять действия» во время перетаскивания и при показанной подсказке: порядок сохранён, повторной отдачи нет | эмулятор | 6A, 6C | — |
| I6-M4 | Реальная тактильная отдача захвата и перемещения; различимость `DRAG_START`/`CLOCK_TICK` | **только физическое устройство** | — | итерация 7 |
| I6-M5 | Напоминание в реальном времени: включить, выставить время через 2–3 минуты, дождаться уведомления; текст, иконка, отсутствие звука; повторное срабатывание на следующий день по управляемым часам debug-сборки | эмулятор — для мержа; физическое устройство — итерация 7 | 6B | итерация 7 |
| I6-M6 | Доступ к уведомлениям по статусам: `RuntimePermissionMissing` — выдать, отказать, отказать повторно до постоянного отказа (API 33+, эмулятор API 35), каждый раз подсказка и действие; `AppNotificationsDisabled` — выключить уведомления приложения (эмулятор API ниже 33 и API 35): переключатель сразу открывает системные настройки, runtime-диалога нет, после возврата с включёнными уведомлениями напоминание включено; `ChannelDisabled` — выключить канал «Напоминание о заданиях»: открывается настройка канала, после возврата статус перечитан; `DayRecap` — «Да» при выключенном канале не включает напоминание; отзыв разрешения в системе и возврат в «Настройки» | эмулятор — для мержа; физическое устройство — итерация 7 | 6B | итерация 7 |
| I6-M7 | Смена часового пояса и ручной перевод часов: (а) при открытом приложении; (б) **холодный процесс** — приложение не запущено, процесс убит (`run-as … kill`), затем смена зоны (`adb shell cmd alarm set-timezone …`): приёмник с `exported="false"` получил broadcast, работа перепланирована на местное время новой зоны (диагностика WorkManager / `dumpsys jobscheduler`) без запуска `MainActivity` | эмулятор | 6B | — |
| I6-M8 | Нет уведомления при завершённом дне, при исчерпанном контенте и при выключенной настройке (debug-часы) | эмулятор | 6B | — |
| I6-M9 | Нажатие на уведомление: холодный старт → Home, «назад» → выход; живая задача в середине задания → тот же экран и порядок; уведомление снято; при открытом приложении снимается на `onStart` | эмулятор | 6B | — |
| I6-M10 | Живой TalkBack: Puzzle (действия, объявления, порядок, подсказка), PuzzleResult, DayRecap с диалогом напоминания, Settings с напоминанием; полный день только средствами TalkBack | **только физическое устройство** (TalkBack на эмуляторе не реагирует на внедрённый ввод) | — | итерация 7 |
| I6-M11 | Все экраны и новые элементы: 320 dp, шрифт 200 %, ландшафт с кнопочной навигацией и вырезом (V12) | эмулятор | 6D | итерация 7 (200 %, ландшафт на устройстве) |
| I6-M12 | «Убрать анимацию» в системе: перестановка, подъём, переходы, reveal мгновенны, подсказка без покачивания; включение/выключение при работающем приложении | эмулятор | 6C, 6D | — |
| I6-M13 | Итоговый манифест собранного APK: разрешения, провайдеры и приёмники WorkManager; сверка с 10.4 | локально (`build/intermediates`, `aapt2 dump`) | 6B | — |
| I6-M14 | `UI_REVIEW_CHECKLIST.md` для всех шести экранов и новых компонентов, светлая и тёмная темы | эмулятор | 6D | итерация 7 (светлая/тёмная на устройстве) |
| I6-M15 | Иконка приложения в лаунчере, тематическая иконка (API 33+), системный экран запуска API 31+, иконка уведомления в статус-баре | эмулятор | 6D | итерация 7 |

### 15.2 Release-readiness итерации 7 — незакрытые пункты

Этот список переносится в критерии итерации 7 `IMPLEMENTATION_PLAN.md` (раздел 17) и остаётся открытым, пока каждый пункт не выполнен с записью устройства, API и результата. Ни один автоматический тест и ни одна проверка на эмуляторе его не закрывает.

| Пункт | Происхождение | Что проверить |
| --- | --- | --- |
| `I4-C6` | итерация 4 | замер полного импорта на физическом устройстве и решение о `mapLatest` |
| `I5-M5` | итерация 5, перенесён 2026-09-12 | настоящая вибрация, беззвучный и вибро-режим, системно отключённая тактильная отдача |
| `I5-M6` | итерация 5 | живой TalkBack: Archive, архивный итог, исторический результат, Settings, Sources |
| `I5-M10` | итерация 5, перенесён 2026-09-12 | полный день с включённой и полностью выключенной отдачей |
| `I6-M10` | итерация 6 | живой TalkBack экранов итерации 6 |
| `I6-M4` | итерация 6 | реальный haptic захвата и перемещения |
| Беззвучный и вибро-режимы (часть `I5-M5`, вместе с `I6-M4`) | итерации 5–6 | звук не играет в беззвучном и вибро-режиме, вибрация захвата и перемещения соблюдает режим |
| Полностью отключённая отдача (часть `I5-M10`) | итерация 5 | звук и вибрация выключены в приложении: ни одного отклика на всём пути, включая жест |
| `I6-M5` (физ.) | итерация 6 | уведомление в реальном времени на устройстве, в том числе после перезагрузки |
| `I6-M6` (физ.) | итерация 6 | отказ в разрешении и постоянный отказ на устройстве |
| `I6-M11` (физ.) | итерация 6 | шрифт 200 % и ландшафт на устройстве |
| `I6-M14`, `I6-M15` (физ.) | итерация 6 | светлая и тёмная темы, иконки на устройстве |
| `I6-M2` | итерация 6 | плавность перетаскивания на слабом устройстве |
| Полный регрессионный путь | `IMPLEMENTATION_PLAN.md`, итерация 7 | первый запуск → три задания (кнопками, жестом, действиями) → итог → предложение напоминания → шеринг → архив → настройки → уведомление → второй день → пропуск дня → возврат |

---

## 16. Риски и откат

| Риск | Предотвращение | Тест | Безопасный откат |
| --- | --- | --- | --- |
| Жест конфликтует со скроллом | захват только на ручке; ручка потребляет указатель после slop | `I6-C2`, `I6-C3`, `I6-M1` | revert 6A: кнопки и действия продолжают работать |
| Jank при перетаскивании | смещение через `graphicsLayer` без перекомпозиции; подтверждения только на пересечениях; `animateItem` без placement у поднятой | `I6-M1`, `I6-M2` | revert 6A |
| Двойная отдача | отдача только эффектом ViewModel; `CardGrabbed` без звука; идемпотентные цели | `I6-V3`, `I6-V5`, `I6-F1`, `I6-K4` | revert 6A |
| Потеря порядка при отмене или пересоздании | подтверждение и `SavedStateHandle` на каждом пересечении; отмена не откатывает | `I6-V8`, `I6-V9`, `I6-C4`, `I6-N2` | revert 6A |
| Устаревшая сессия жеста после пересоздания UI (старый `startIndex`, чужое завершение) | `DragGestureId` процессного счётчика; замена сессии новым жестом; фильтр событий по идентификатору | `I6-V9`, `I6-V10`, `I6-N2` | revert 6A |
| Недоступность карточек для TalkBack | семантика карточки не меняется; ручка без узла; действия через тот же путь | `I6-A1`…`I6-A3`, `I6-N4` | revert 6C; семантика итерации 3 остаётся |
| Двойное или отсутствующее объявление | снят `liveRegion`; граница объявлений; запасной путь описан (7.3) | `I6-A4`, `I6-A5`, `I6-N5`, `I6-M10` | revert 6C |
| Бесконечный auto-scroll | условие `canScroll*` на каждом кадре, цикл привязан к сессии и состоянию | `I6-C6`, `I6-C7`, `I6-R4` | revert 6A |
| Дубли уведомлений | одно уникальное имя работы; постоянный ID | `I6-P1`, `I6-P2`, `I6-W4`, `I6-N6` | выключить напоминание; revert 6B |
| Уведомление после завершения дня | проверка непосредственно перед показом; снятие на `onStart`; `timeoutAfter` | `I6-W1`, `I6-S4`, `I6-M8` | revert 6B |
| Ошибки часового пояса и DST | `ZonedDateTime`; приёмник смены зоны и времени; проверка «рано» | `I6-S2`, `I6-S3`, `I6-M7` | revert 6B |
| Повторный запрос разрешения | диалог на `DayRecap` один раз (флаг до запроса); в настройках — только по нажатию | `I6-V15`, `I6-V14`, `I6-M6` | revert 6B |
| WorkManager как скрытый источник мутаций | worker только читает; `rg` и фейки, бросающие на запись | `I6-W2`, `I6-K3` | revert 6B |
| Повторный показ после смерти процесса worker'а | окно узкое; ключ «последний показ» сознательно не заводится (I6-D2); если риск подтвердится в альфе — отдельное решение | `I6-W4` | — |
| `ACCESS_NETWORK_STATE` от WorkManager читается как «сетевое разрешение» | уточнённый критерий итерации 7; объяснение в 10.4; запрет `INTERNET` и локальных сетевых разрешений проверяется тестом | `I6-P5`, `I6-M13`, `I6-K2` | — (разрешение не даёт доступа в сеть) |
| Событие смены зоны или времени в холодном процессе не перепланирует работу | приёмник сам выполняет `SyncReminderScheduleUseCase` внутри `goAsync()`, `finish()` — после ожидаемой операции WorkManager; без `SharedFlow` и без `MainActivity` | `I6-P4`, `I6-N6`, `I6-M7` | revert 6B |
| Перепланирование после отмены worker'а создаёт лишнюю работу | нет блока, выполняемого при отмене; `ensureActive()` перед `schedule`; проверка ожидающей работы под `ReminderScheduleLock` | `I6-W3`, `I6-N6` | revert 6B |
| Предложение напоминания показывается повторно после смерти процесса во время системного диалога | отметка — подтверждаемая `suspend`-запись до создания запроса | `I6-V15` | revert 6B |
| Отказ чтения DataStore превращается в `reminderEnabled = false` и отменяет работу | поведение существующего репозитория (`IOException` → значения по умолчанию); следующее успешное чтение перепланирует; повреждённый файл заменяется `ReplaceFileCorruptionHandler` | `I6-Y2` | — |
| Robolectric-тесты падают из-за WorkManager в `Application` | синхронизатор стартует из `MainActivity`, не из `Application` | весь `testDebugUnitTest` | — |
| Чрезмерная полировка и scope creep | таблица 11.1 закрыта; «запрещено в diff» по каждому PR | ревью по разделу 13 | revert отдельного исправления |
| Нет или неподходящая лицензия иконок | входные данные до мержа; запись в `VERSIONS.md` | `I6-U6`, `I6-M15` | системная иконка до получения; 6D не мержится |
| Расхождение токенов и кода | предложения токенов до кода (11.2); `I6-K7` | `I6-K7`, `I6-M14` | revert исправления |
| `announceForAccessibility` перестанет работать на новых API | граница `AccessibilityAnnouncer`, запасной путь описан | `I6-N5`, `I6-M10` | замена реализации границы |

**Откат итерации в целом.** Итерация 6 не меняет Room-схему, не добавляет ключей DataStore и не трогает контент, поэтому каждый PR откатывается `git revert` без миграции и без потери прогресса; порядок отката — обратный мержу. Особенность отката 6B — оставшаяся запланированная работа (13.2).

---

## 17. Синхронизация документов по PR

Выполняется после архитектурного утверждения, в указанных PR, и входит в их критерии готовности. В этом Design Gate перечисленные документы не меняются.

### `docs/ARCHITECTURE.md`

| Раздел | Правка | PR |
| --- | --- | --- |
| §1, дерево | `ui/puzzle/CardOrder`, `DragTargetResolver`, `DragAutoScroll`, `ReorderDragState`; `ui/components/DragHandle` | 6A |
| §1, дерево и правила зависимостей | `domain/reminder/*`; фактический `notifications/*` (включая `ReminderScheduleObserver`, `ReminderBroadcastHandler`); `notifications → domain` (реализует интерфейсы, вызывает use cases, владеет `@ApplicationScope`-оркестрацией; `ui` и `data` не импортирует) | 6B |
| §1, дерево | `ui/platform/AccessibilityAnnouncer`, `ui/theme/ReducedMotion`, `ui/components/DragEducationHint`, `ui/components/NotificationOptInDialog` | 6B, 6C |
| §4, контракт игрового экрана | `PuzzleEvent` (4.3), удаление `draggedCardId`, единый `reorder()`, сессия жеста; `FeedbackCue.CardGrabbed` в описании границы отдачи | 6A |
| §5, таблица инструментов | синхронизатор напоминания в `@ApplicationScope`; WorkManager — уникальная работа, политики `REPLACE`/`APPEND_OR_REPLACE` | 6B |
| §6 | полностью по разделам 8–10: `NotificationAvailability`, одна операция `SyncReminderScheduleUseCase` для коллектора, приёмника и worker'а, холодный приёмник с `goAsync()`, отмена без перепланирования, подтверждаемая отметка предложения, отсутствие перепланирования «при завершении дня», канал, force stop, `ACCESS_NETWORK_STATE` от WorkManager | 6B |
| §9 | instrumented-тесты жеста и WorkManager выполняются вручную | 6A, 6B |
| новый ADR-019 | «Единый путь перестановки и подтверждение жеста на пересечениях» | 6A |
| новый ADR-020 | «Напоминание: проверка при срабатывании, WorkManager без Hilt-work, приёмник смены времени» | 6B |

### `docs/UX_FLOW.md`

| Раздел | Правка | PR |
| --- | --- | --- |
| §4, состояния `Puzzle` | `Dragging` — локальное состояние экрана; отмена сохраняет последний порядок; «Проверить» во время жеста | 6A |
| §9, «Смена даты во время работы» | рантайм-приёмников нет: тикер и `ON_START` (I6-D31); смена зоны перепланирует напоминание | 6B |
| §8, «Напоминание» | группа, строки, эффективное значение; статусы недоступности, подсказка и переход в системные настройки (8.2); тексты 8.6 после **O6-2**, **O6-4**; форма времени — **O6-3** | 6B |
| §2, «Права и разрешения» | точный поток диалога (8.3) | 6B |
| §3/§11 | отзыв разрешения — переключатель показан выключенным без записи | 6B |
| §10, «Semantics для TalkBack» | снять `liveRegion = Polite`; одно объявление; объявление жеста при завершении | 6C |
| §10, «Звук и вибрация» | событие захвата — только тактильное | 6A |
| §5, «Тон» | reveal группами (**O6-5**) | 6D |
| §12 | время напоминания по умолчанию — подтверждено реализацией | 6B |

### `docs/design/COMPONENTS.md`

| Компонент | Правка | PR |
| --- | --- | --- |
| `OrderableCard` | ручка в индексной зоне; `dragging`: `zIndex`, смещение, отмена; `Submitting` — ручка без ввода; custom actions «вверх/вниз» (**I6-D18**) | 6A, 6C |
| `DragHandle` | нет отдельного `disabled`-вида; пары контраста 5.6 | 6A |
| `DragEducationHint` | место в `LazyColumn`, условия скрытия по ViewModel | 6C |
| `NotificationOptInDialog` | точные условия показа (8.3); подтверждаемая отметка до системного запроса; включение только при `Allowed` | 6B |
| `Settings row` | группа «Напоминание», строка времени, эффективное значение, подсказка и действие по статусу недоступности (8.2; тексты — **O6-4**), форма выбора времени (**O6-3**) | 6B |
| `InvertedPairRow` | единственный шаблон «после» (I3-D5) | 6D |
| Новый раздел «Иконки приложения и уведомления» | требования 11.4 | 6D |
| `Error` / debug | `RecoveryConfirmationDialog` — debug-исключение из правил формы и тени | 6D |

### `docs/design/DESIGN_TOKENS.md`

| Раздел | Правка | PR |
| --- | --- | --- |
| §6.2 | пары контраста ручки и поднятой карточки (5.6) | 6A |
| §6.5 / §6.8 | `dragAutoScroll.edgeZone`, `dragAutoScroll.maxVelocity` (**O6-6**) | 6A |
| §6.8 | `navTransition.enter/exit`, группы `resultReveal`; предел последовательности (**O6-5**) | 6D |
| §6.8, reduced motion | реактивность настройки | 6C |

### `docs/design/UI_REVIEW_CHECKLIST.md`

| Раздел | Правка | PR |
| --- | --- | --- |
| «Custom accessibility actions» | подписи «вверх/вниз»; удалить пункт `liveRegion`; одно объявление, в том числе после жеста | 6C |
| «Отдельный чек-лист: `OrderableCard`» | ручка 48 dp, отмена жеста, отсутствие ручки в read-only, auto-scroll | 6A |
| Новый «Напоминание и уведомление» | строки настроек, отказ, диалог, уведомление, нажатие | 6B |
| «Reduced motion» | переходы и reveal; смена настройки при работающем приложении | 6D |
| Новый «Иконки» | 11.4 | 6D |

### Прочие документы

| Файл | Правка | PR |
| --- | --- | --- |
| `docs/VERSIONS.md` | WorkManager: «подключено в 6B», сверенная версия и дата; исправить строки DataStore и Turbine («подключено с итерации 2») | 6B |
| `docs/VERSIONS.md` | раздел «Графические ресурсы»: иконки, происхождение, лицензия, SHA-256 | 6B (иконка уведомления), 6D (иконка приложения) |
| `docs/IMPLEMENTATION_PLAN.md` | итерация 6: статус по мере мержа PR; итерация 7: пункты 15.2 | каждый PR; 6D — итоговый |
| `docs/IMPLEMENTATION_PLAN.md` | итерация 7: критерий «нет разрешения `INTERNET`, локальных сетевых разрешений и сетевых вызовов» | **выполнено этим Design Gate** (ревизия 1.1) |
| `docs/design/COMPONENTS.md`, `docs/ARCHITECTURE.md` | формулировки «ни одного сетевого разрешения» (`SourceRow`, «Сеть»; §7) → уточнённый критерий 10.4 с пояснением про `ACCESS_NETWORK_STATE` от WorkManager | 6B |
| `docs/ITERATION_3_DESIGN.md` | O-2 закрыт (I6-D31); I3-D24 исполнен — поля заполнены/удалены (I6-D9) | 6A |
| `docs/ITERATION_5_DESIGN.md` | без правок (статус синхронизирован этим Design Gate) | — |

---

## 18. Решения владельца и входные данные

До ответов на решения ниже статус документа остаётся «ревизия 1.1, готова к архитектурному ревью». Чисто технические решения, следующие из утверждённой архитектуры, сюда не вынесены.

### 18.1 Открытые решения

| ID | Вопрос | Рекомендуемый вариант | Альтернативы | Что блокирует |
| --- | --- | --- | --- | --- |
| O6-1 | Показывать ли напоминание, если сегодняшний день начат и закрыто 1–2 задания | **Не показывать**: пользователь уже вернулся сам, а напоминание о недоигранном дне читается как давление за серию (`PRODUCT.md` §4, R-9) | показывать с отдельным нейтральным текстом | мерж 6B (`I6-S4`) |
| O6-2 | Тексты канала и уведомления (8.6) | канал «Напоминание о заданиях» / «Одно напоминание в день в выбранное время»; уведомление «По порядку!» + «Три новых задания готовы» (грамматически естественнее текста `ARCHITECTURE.md` §6) | дословно «Новые три задания готовы»; иная формулировка владельца | мерж 6B |
| O6-3 | Композиция напоминания в настройках и форма выбора времени | отдельная группа «Напоминание» после «Звук и вибрация»; выбор времени — **встроенный M3 `TimeInput`** под строкой «Время» с «Сохранить»/«Отмена», без диалога: не появляется третий компонент с тенью (`DESIGN_PRINCIPLES.md` §6) | M3 `TimePicker` в диалоге (новое исключение для тени и формы); системный `TimePickerDialog` (чужой визуальный язык) | мерж 6B; правка `COMPONENTS.md` |
| O6-4 | Тексты подсказки при недоступности уведомлений (само действие перехода в системные настройки обязательно по **I6-D36**) | один общий текст для всех причин: «Разрешите уведомления в настройках системы» + действие «Открыть настройки уведомлений» — пользователю не нужно различать разрешение, уведомления приложения и канал | отдельные тексты для `RuntimePermissionMissing`, `AppNotificationsDisabled` и `ChannelDisabled` | мерж 6B |
| O6-5 | Тип перехода между экранами и появление результата | переход — **только затухание/проявление** (fade) на токенах `DESIGN_PRINCIPLES.md` §9; reveal — **группами**, не больше 8 шагов (≤ 520 мс) | сдвиг по оси (shared axis) — больше движения, чем требует спокойный продукт; построчный reveal без предела | мерж 6D; правка `DESIGN_TOKENS.md` |
| O6-6 | Токены auto-scroll | `dragAutoScroll.edgeZone` = `size.touchTarget.min`; `dragAutoScroll.maxVelocity` = одна карточка с зазором за `motion.duration.long` — оба производные, без новых чисел | собственные значения владельца | мерж 6A |

### 18.2 Входные данные

| Материал | Требования | Срок | Что блокирует |
| --- | --- | --- | --- |
| Иконка уведомления `ic_stat_reminder` | 11.4: вектор 24 dp, одноцветный силуэт, происхождение и лицензия | до мержа 6B | мерж 6B |
| Иконка приложения: foreground, background, monochrome | 11.4: адаптивная, безопасная зона 66 dp, цвета палитры, контраст ≥ 3 : 1, вектор, происхождение и лицензия | до мержа 6D | мерж 6D |
| Подтверждение новых текстов 8.6 | ответ на **O6-2** и строки «предложение» | до мержа 6B и 6C | мерж 6B, 6C |
| Время напоминания по умолчанию | уже зафиксировано 9:00 (`UX_FLOW.md` §2, §12; `COMPONENTS.md`; код) — подтверждения не требуется; изменение — только по желанию владельца | — | — |
| Иные визуальные решения | нет, кроме **O6-3**, **O6-5**, **O6-6** | — | — |

Design Gate не генерирует и не добавляет ни одного бинарного или графического файла; ассеты приходят только входными данными конкретных PR.

---

## 19. Changelog документа

| Ревизия | Дата | Изменения |
| --- | --- | --- |
| 1.0 | 2026-09-13 | Первая редакция: аудит кода на `f96a561`; решения `I6-D1`…`I6-D50`; единый путь перестановки и контракт жеста; отдача захвата; доступность и reduced motion; пользовательский контракт, планирование и Android-контракты напоминания; аудит полировки, анимации, иконки, итоговая матрица UI; разрез PR 6A–6D; тестовая матрица `I6-*`; ручные проверки и release gate итерации 7; риски; план синхронизации документов; решения владельца `O6-1`…`O6-7` и входные данные. Статус — готова к архитектурному ревью |
| 1.1 | 2026-09-13 | Точечные исправления архитектурного ревью: холодная синхронизация напоминания — одна `suspend`-операция `SyncReminderScheduleUseCase` для коллектора, приёмника и worker'а, приёмник выполняет её внутри `goAsync()` и вызывает `finish()` после ожидаемой записи WorkManager, оркестрация процесса перенесена в `notifications` (`I6-D24`, `I6-D26`, `I6-D29`, `I6-D30`, `I6-Y2`, `I6-P4`, `I6-N6`, `I6-M7`); `ReminderRun` без перепланирования при отмене, раздельные ошибки оценки и планировщика (`I6-D49`, `I6-W3`); `NotificationAvailability` вместо Boolean, запрос `POST_NOTIFICATIONS` только при `RuntimePermissionMissing`, переход в настройки приложения или канала (`I6-D36`, `I6-D37`, `I6-V14`, `I6-V15`, `I6-P3`, `I6-P6`, `I6-C9`, `I6-M6`); подтверждаемая отметка `notificationPromptShown` до системного запроса, честный контракт `DragHintSeen` (`I6-D21`, `I6-D39`, `I6-D40`, `I6-V13`, `I6-V15`); `DragGestureId` в событиях жеста (`I6-D5`, `I6-D9`, `I6-V5`, `I6-V9`, `I6-V10`, `I6-N2`); единственный порог «центр пересёк центр соседа» с доказательством отсутствия дребезга (`I6-D7`, `I6-R3`); O6-7 закрыт технически — `ACCESS_NETWORK_STATE` от WorkManager остаётся, запрещены `INTERNET` и локальные сетевые разрешения, критерий итерации 7 уточнён (`I6-D41`, `I6-P5`); O6-4 переформулирован в вопрос о текстах. Статус — готова к архитектурному ревью |

---

**Статус документа: ревизия 1.1, готова к архитектурному ревью, 2026-09-13.** Решения `I6-D*` предложены и не утверждены; решения владельца `O6-1`…`O6-6` открыты; входные данные раздела 18.2 не получены. Реализация итерации 6 не начата. `I4-C6`, `I5-M5`, `I5-M6`, `I5-M10` не выполнены и остаются пунктами release-readiness итерации 7.
