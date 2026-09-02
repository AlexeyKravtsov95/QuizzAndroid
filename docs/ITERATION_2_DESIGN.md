# ITERATION_2_DESIGN.md — «По порядку!»

Техническое проектирование итерации 2 «Данные и хранилище». Статус: **ревизия 3.1, готова к реализации**, 2026-08-31 (после третьего архитектурного ревью).

Документ дополняет `IMPLEMENTATION_PLAN.md` (итерация 2) и не заменяет его: план говорит **что** делает итерация, этот документ — **как** и **в каком порядке**, и закрывает семь технических вопросов, на которые остальные документы ответа не дают.

**Что изменилось в ревизии 3** (подробности — строки 12–16 таблицы раздела 1):

- **D-20** — запросы назначений разделены на глобальные (`pendingAssignments`, `byDate`, `lastAssignedDate`) и pack-scoped (`maxSetIndex`, `countSets`); решения политики несут `packId` явно;
- **D-16** переработан — `TimeSnapshot` строится только фабрикой из одного `Instant`, а подмена даты живёт в variant-specific `ClockProvider`, а не в `internal`-шве репозитория;
- **D-18** переработан — `UserPreferences` переезжает в `core/model`, кэш серии получает единственную операцию записи с проверками и согласованный сброс на чтении;
- **D-21** — debug-диагностика собирается в `src/debug`, продуктовый репозиторий debug-методов не получает;
- **D-22** — `room-testing` переносится из итерации 2 в итерацию 4.

Решения **D-13**–**D-19** ревизии 2 сохранены; `room-ktx`, `AssignmentReader` и маппер `Puzzle` в итерацию 2 не возвращаются.

**Что уточнено в ревизии 3.1** — точечные правки внутри уже принятых решений, без изменения ни одного из них:

- часы обоих вариантов читают зону на каждом обращении (`Clock.system(ZoneId.systemDefault())`), а не кэшируют её в поле синглтона; `DebugClockProvider` хранит `AtomicReference<Clock?>`, где `null` — «не зафиксировано», и `setDate` проверяет round trip даты через `require` вместо утверждения о свойствах всех часовых поясов;
- выбор обычного `class` для `TimeSnapshot` обоснован тем, что `copy` не генерируется вовсе, — без утверждений о видимости `copy()` у `data class` с приватным конструктором;
- тест A5 приведён к продуктовому сценарию (`AwaitingNextDay`, `carryOver` не вызывается), а SQL-рубеж `:today > :pendingDate` вынесен в отдельный DAO-тест A13;
- `setStoredContentVersion` валидирует `version >= 0`, `setLastSeenDate` принимает `LocalDate?`;
- проверка «`clock.now()` только до транзакции» переведена со сквозного многострочного regex на подсчёт вызовов и структурный просмотр;
- формулировка про `room-testing` сужена до «единственный необходимый проекту API — `MigrationTestHelper`».

Открытых архитектурных пунктов не осталось: раздел 13 фиксирует все ранее открытые решения подтверждёнными.

Все версии и утверждения о поведении инструментов проверены по первичным источникам 2026-08-31 (список — раздел 12). Комбинация «KSP + Room Gradle Plugin + legacy-kapt» дополнительно проверена **эмпирически**: на копии репозитория в отдельном каталоге собраны debug- и release-сборки и прогнаны Robolectric-тесты на in-memory Room. Файлы проекта и история git при этом не изменялись.

---

## 1. Найденные неоднозначности и противоречия

| № | Где | В чём проблема | Статус |
| --- | --- | --- | --- |
| 1 | `VERSIONS.md`, строка Room | Строка упоминает Room 3.0.0 от 01.07.2026 как «новую мажорную линию с упором на KMP» — это верно, но неполно: Room 3 живёт в **отдельной maven-группе `androidx.room3`** с переименованными артефактами (`room3-runtime`, `room3-compiler`, `room3-gradle-plugin`) и переименованными пакетами (`androidx.room3.RoomDatabase`), а последний стабильный релиз линии — **3.0.2 от 26.08.2026**, а не 3.0.0 | **Требует уточнения формулировки.** Выбор 2.8.4 остаётся, но обоснование переписывается — решение **D-13** |
| 2 | `ARCHITECTURE.md` ADR-002, ADR-008 | Оба ADR называют «минусом» KSP в сборке, но проект собран на `com.android.legacy-kapt`, а версия KSP не зафиксирована ни в `VERSIONS.md`, ни в каталоге | Закрыто решением **D-1** |
| 3 | `ARCHITECTURE.md` §4, сигнатура `SetAssignmentPolicy` | `SetAssignmentPolicy(assignments: AssignmentDao, setCount: Int)` тянет `data`-тип в `domain`, нарушая правило §1 «data реализует интерфейсы из domain.repository». Плюс `setCount` константой конструктора несовместим с тем, что число наборов известно только после импорта контента (итерация 4) | Закрыто решениями **D-3** (`AssignmentSnapshot`) и **D-4**. Меняет `ARCHITECTURE.md` §4 |
| 4 | `UX_FLOW.md` §12 против §9 и `IMPLEMENTATION_PLAN.md` итерация 2 | Момент фиксации назначения («Играть», а не первое «Проверить») назван «решением по умолчанию, изменяемым до реализации», но критерии приёмки итераций 2 и 3 уже на нём построены | Считаем закрытым: фиксация на переходе `Home → Puzzle(0)` |
| 5 | `ARCHITECTURE.md` §3, схема | Правило «нет FK из прогресса в контент» сформулировано явно; про FK *внутри* контента (`daily_sets.puzzle_id_N → puzzles.puzzle_id`) документы молчат | Закрыто решением **D-11**: FK не заводим нигде |
| 6 | `ARCHITECTURE.md` §3, `puzzle_attempts` | Попытка связана с набором только через `local_date`. При переносе отложенного назначения ключ строки меняется, то есть идентичность назначения непостоянна | Безопасно по инварианту «переносим только назначения без попыток»; фиксируется тестом **A6** |
| 7 | `IMPLEMENTATION_PLAN.md` итерация 2, «время напоминания» | Preferences DataStore не умеет хранить `LocalTime` — нужно выбрать представление | Закрыто решением **D-6** |
| 8 | `IMPLEMENTATION_PLAN.md` «кэш серии» (ед. ч.) против `UX_FLOW.md` §6 | UX показывает текущую и лучшую серию рядом; кэш без даты пересчёта покажет вчерашнее значение как сегодняшнее | Закрыто решением **D-7**: три ключа вместо одного. **Подтверждено владельцем проекта** (раздел 13) |
| 9 | `UX_FLOW.md` §3 `ContentExhausted` против границ итерации 2 | В итерации 2 контента нет, поэтому `setCountInActivePack = 0` и политика всегда возвращает `ContentExhausted`. Это корректно и честно, но означает, что без debug-фикстуры критерии приёмки проверить нечем | Закрыто решением **D-9** (вопрос 5 задания) |
| 10 | `ARCHITECTURE.md` §9 против `.github/workflows/ci.yml` | Выбор «Robolectric или instrumented» прямо оставлен открытым до итерации 2, при этом четыре теста переноса объявлены обязательными, а CI гоняет только `testDebugUnitTest` | Закрыто решением **D-2** (вопрос 2 задания) |
| 11 | Пути документов | Дизайн-токены лежат по пути `docs/design/DESIGN_TOKENS.md`, а не `docs/DESIGN_TOKENS.md` | Мелочь, зафиксирована для точности ссылок |
| 12 | Ревизия 2 этого документа, раздел 4 | Все пять запросов снимка были отфильтрованы по `pack_id`, хотя `day_assignments.local_date` — **глобальный** первичный ключ, а `UX_FLOW.md` §9 формулирует инварианты «не более одного отложенного» и «только вперёд» без упоминания пакетов. Фильтр по пакету делал глобальные инварианты pack-scoped: два пакета могли иметь по отложенному назначению, а переключение активного пакета обходило бы `lastAssignedDate` | Закрыто решением **D-20** |
| 13 | Ревизия 2, **D-16** | `internal`-шов `peekAt`/`startSessionAt` был описан как невидимый «за пределами `data`». Это неверно: `internal` в Kotlin ограничивает видимость **Gradle-модулем**, а модуль здесь один (`:app`, ADR-001). Шов был виден всему приложению, включая `ui`, и в release-сборке тоже — то есть не давал обещанной защиты | Закрыто переработанным **D-16**: variant-specific `ClockProvider` |
| 14 | Ревизия 2, раздел 6 против раздела 4 | Debug-экран обещал показать «`Decision` и весь `AssignmentSnapshot`», но `AssignmentSnapshot` строится приватным методом внутри транзакции и наружу не возвращается ни одним API | Закрыто решением **D-21** |
| 15 | Ревизия 2, **D-18** против `ARCHITECTURE.md` §1 | `UserPreferencesRepository` в `domain/repository` возвращал `Flow<UserPreferences>`, а сам `UserPreferences` лежал в `data/prefs` — то есть доменный интерфейс импортировал тип из `data` | Закрыто переработанным **D-18** |
| 16 | Ревизия 2, **D-12** и PR 2A | `room-testing` подключался «сразу вместе с остальной линией», хотя единственный его потребитель — `MigrationTestHelper` — появляется в итерации 4. `Room.inMemoryDatabaseBuilder` живёт в `room-runtime` и в `room-testing` не нуждается | Закрыто решением **D-22** |

---

## 2. Принятые технические решения

### D-1. Room генерируется через KSP; legacy-kapt остаётся только под Hilt

Ответ на вопрос 1 задания. Из двух предложенных вариантов выбран второй: **подключить KSP для Room, оставив legacy-kapt только для Hilt**. Плагинов добавляется два: `com.google.devtools.ksp` **2.3.11** (генерация кода) и `androidx.room` **2.8.4** (конфигурация экспорта схемы, решение **D-14**). Оба — официальные, лишних плагинов нет.

**Почему не kapt для Room тоже:**

- Официальная инструкция миграции на built-in Kotlin: «Если вы используете `kapt`, мы рекомендуем мигрировать проект на KSP… Если пока не можете мигрировать на KSP, замените плагин `kotlin-kapt` на `com.android.legacy-kapt`» ([s1]). То есть `legacy-kapt` — запасной путь ровно для тех процессоров, которые на KSP не переезжают, а не общий режим сборки.
- Страница AGP-миграции «KSP, kapt, and legacy-kapt» формулирует то же как алгоритм: проверить каждую kapt-зависимость на наличие KSP-процессора и, только если его нет, «оставить эту зависимость и применить плагин `com.android.legacy-kapt`» ([s2]). У Room KSP-процессор есть.
- Room рекомендует KSP явно: «Room now targets Kotlin language 2.0… Support for KSP2 is also added and is recommended when using Room with Kotlin 2.0 or higher» ([s6], release notes 2.7.0).
- Практическая цена kapt для Room — генерация Java-стабов для всех Kotlin-исходников модуля на каждой сборке. Для Hilt эта цена уже уплачена; для Room её платить незачем.

**Почему legacy-kapt не убирается совсем (то есть почему не KSP для Hilt):**

Официальная страница Dagger по-прежнему говорит: «*Dagger's KSP support is currently in alpha*» ([s7]). Release notes Dagger 2.59–2.60.1 добавляют поддержку AGP 9 в *Gradle-плагин* Hilt, но не объявляют KSP-процессор стабильным. Комментарий в текущем `app/build.gradle.kts` остаётся верным и менять его не нужно.

**Почему KSP и legacy-kapt уживаются в одном модуле.** Это главный риск варианта; он закрыт документально и эмпирически. Из [release notes KSP][s5]:

| Версия KSP | Что дала |
| --- | --- |
| **2.3.0** (22.10.2025) | «KSP version is no longer tied to the Kotlin compiler version (moving away from the old `<kotlinversion>-<kspversion>` format)». Следствие: KSP 2.3.x **не обязан совпадать** с Kotlin 2.4.10 проекта |
| **2.3.1** | «Added support for AGP 9.0 and built-in Kotlin (#2674)» — именно этот релиз снял ошибку «KSP is not compatible with Android Gradle Plugin's built-in Kotlin», на которую до сих пор ссылается [issue #2615](https://github.com/google/ksp/issues/2615) |
| **2.3.5** | «Fix circular dependency between KSP and KAPT in AGP 9.0 (#2743)» — прямо про нашу комбинацию |
| **2.3.10** | «Fix R-class resolution in KSP when AGP 9 built-in Kotlin is enabled (#2857)» и «Sanitize ':' in internal-name module suffix so KSP works with **Kotlin 2.4.0** default module names (#2964)». Второй пункт делает 2.3.10 нижней разумной границей именно для нас |
| **2.3.11** (03.08.2026) | Последний стабильный на 2026-08-31. Берём его |

AGP со своей стороны подтягивает KSP до версии KGP, если он ниже: «if you use a KSP version lower than 2.2.10-2.0.2, AGP will upgrade it to 2.2.10-2.0.2 to match the KGP version» ([s3]). 2.3.11 выше — понижения не произойдёт.

**Эмпирическая проверка.** Копия репозитория в отдельном каталоге, добавлены KSP, Room Gradle Plugin, Room 2.8.4, минимальные `@Entity`/`@Dao`/`@Database` и Hilt-модуль, отдающий базу:

```
$ ./gradlew :app:assembleDebug
> Task :app:kaptGenerateStubsDebugKotlin
> Task :app:kaptDebugKotlin
> Task :app:compileDebugKotlin
> Task :app:copyRoomSchemas
> Task :app:hiltAggregateDepsDebug
> Task :app:hiltJavaCompileDebug
BUILD SUCCESSFUL in 28s

$ ./gradlew :app:assembleRelease        # minifyEnabled true, R8
BUILD SUCCESSFUL in 30s

app/build/generated/ksp/debug/kotlin/…/AppDatabase_Impl.kt      ← Room через KSP
app/build/generated/ksp/debug/kotlin/…/AssignmentDao_Impl.kt
app/build/generated/source/kapt/debug/…/Hilt_MainActivity.java  ← Hilt через legacy-kapt
app/schemas/ru.poporyadku.data.db.AppDatabase/1.json            ← схема экспортирована
```

Отдельно проверено, что оба процессора живут и в unit-test-варианте: в логе `testDebugUnitTest` присутствуют `:app:kspDebugUnitTestKotlin` и `:app:kaptDebugUnitTestKotlin SKIPPED` без ошибок конфигурации.

**Флаг `ksp.useKSP2` не нужен:** KSP 2.3.x — это уже только KSP2, KSP1 объявлен устаревшим в 2.3.0.

---

### D-13. Остаёмся на Room 2.8.4, зная про существование Room 3

Room 3 существует и активно развивается. Факты по [официальной странице релизов Room 3][s11] на 2026-08-31:

| Что | Значение |
| --- | --- |
| Maven-группа | `androidx.room3` (отдельная от `androidx.room`) |
| Артефакты | `room3-runtime`, `room3-compiler`, `room3-gradle-plugin`, `room3-sqlite-wrapper`, … |
| Пакеты в коде | `androidx.room3.RoomDatabase` и т. д. |
| Плагин Gradle | `androidx.room3`, DSL-блок `room3 { schemaDirectory … }` |
| 3.0.0 | 01.07.2026 |
| Последний стабильный | **3.0.2**, 26.08.2026 (подтверждено `maven-metadata.xml` артефактов `room3-runtime`, `room3-compiler`, `room3-gradle-plugin`) |

Разделение групп сделано намеренно: «To prevent compatibility issues with existing Room 2.x implementations and for libraries with transitive dependencies to Room (for example, WorkManager), Room 3.0 resides in a new package». То есть параллельное существование 2.x и 3.x — штатная ситуация, а не переходный период с обязательной миграцией.

**Решение: проект остаётся на Room 2.8.4.** Обоснование:

1. **Главная ценность Room 3 проекту не нужна.** Room 3 — «a major version update… that focuses on Kotlin Multiplatform (KMP)»: JS/WasmJS-таргеты и связанные с ними возможности. KMP в проекте запрещён (`ARCHITECTURE.md`, ADR-002 — именно этим отклонён SQLDelight). Мы платили бы за возможность, которой не воспользуемся.
2. **Остальные новшества 3.x нам нечем применить.** Custom DAO return types, FTS5, composite relationship columns — ни одна из пяти таблиц схемы (раздел 3) их не требует. Полнотекстовый поиск по архиву в MVP не предусмотрен ни `UX_FLOW.md` §7, ни `PRODUCT.md`.
3. **Переход стоит правки каждого импорта.** Новая группа и переименованные пакеты означают механическую, но сплошную замену `androidx.room.*` → `androidx.room3.*` во всех сущностях, DAO и модулях DI. Делать это ради нулевого функционального выигрыша в первой же итерации, где появляется база, — трата бюджета итерации.
4. **Room 3 меняет API там, где нам это ничего не даёт.** «SupportSQLite APIs are no longer supported, unless you are using `androidx.room3:room3-sqlite-wrapper`» и «All database operations are now Coroutine APIs based». Мы и так пишем всё на корутинах, а SupportSQLite не используем, — то есть выигрыша нет, а поверхность изменений есть.
5. **Текущая реализация проверена именно на 2.8.4.** Эмпирическая проверка из **D-1** (сборка debug и release, экспорт схемы, три Robolectric-теста) относится к 2.8.4 с KSP 2.3.11 и Room Gradle Plugin 2.8.4. Переход на 3.x обесценил бы эту проверку и потребовал бы повторить её целиком.
6. **2.8.4 — поддерживаемая линия, а не заброшенная.** Это последний стабильный релиз группы `androidx.room`; параллельное существование групп прямо предусмотрено авторами.

**Условие пересмотра.** Возврат к вопросу оправдан, если появится второй таргет сборки (что потребовало бы снятия запрета на KMP из ADR-002) или продуктовая потребность в возможностях 3.x — например, полнотекстовый поиск по архиву. Ни того, ни другого в MVP нет.

**Что правится в `VERSIONS.md` (PR 2A):** строка Room переписывается так, чтобы содержать группу `androidx.room3`, версию 3.0.2 с датой и ссылку [s11], а обоснование выбора 2.8.4 — пункты 1–6 выше вместо нынешней формулировки.

---

### D-14. Экспорт схемы — через официальный Room Gradle Plugin

Плагин `androidx.room` версии, совпадающей с версией Room (**2.8.4**):

```kotlin
// build.gradle.kts (корень)
plugins {
    alias(libs.plugins.room) apply false
}

// app/build.gradle.kts
plugins {
    alias(libs.plugins.room)
}

android { /* … обычная конфигурация модуля … */ }

room {
    schemaDirectory("$projectDir/schemas")
}
```

Блок `room { … }` — **верхнеуровневое расширение проекта**, которое плагин `androidx.room` регистрирует на `Project`, а не вложенный блок внутри `android { … }`. Пишется рядом с `android { … }`, на том же уровне; попытка вложить его внутрь `android` не скомпилируется.

**Почему плагин, а не `ksp { arg("room.schemaLocation", …) }`:**

- **Каталог схем становится настоящим Gradle input/output.** Передача пути через `arg` — это непрозрачная для Gradle строка: каталог не объявлен как выход задачи, изменения в нём не участвуют в проверке актуальности, а появившиеся файлы формально ничьи. Плагин заводит собственную задачу (`:app:copyRoomSchemas` — видна в логе сборки) и объявляет каталог явно.
- **Воспроизводимость и кэширование.** Раз каталог объявлен, сборка корректно пересоздаёт схему при изменении сущностей и корректно пропускает работу, когда изменений нет. С `arg`-вариантом задача KSP не знает про каталог, и результат зависит от того, что осталось на диске от предыдущих сборок — при переключении веток это даёт схему, не соответствующую коду.
- **Обязательность настройки.** «Setting a `schemaDirectory` is required when using the Room Gradle Plugin» ([s6]) — то есть плагин не даёт молча собраться без экспорта схемы. Это ровно та строгость, которая нужна проекту, где схема коммитится и служит базой для `MigrationTestHelper`.
- **Корректная раскладка по вариантам.** Плагин раскладывает схемы по вариантам сборки там, где варианты различаются (в документации показан пример `schemas/flavorOneDebug/…`). У проекта product flavors нет, поэтому эмпирически debug и release пишут в один и тот же путь `app/schemas/ru.poporyadku.data.db.AppDatabase/1.json` — проверено сборкой обоих вариантов. Если flavors появятся, раскладка изменится сама, без правки конфигурации.

**Эмпирическая проверка.** Плагин применён поверх AGP 9.3.2 с built-in Kotlin и `com.android.legacy-kapt`; `assembleDebug` и `assembleRelease` (с `minifyEnabled true`) прошли, задача `:app:copyRoomSchemas` отработала, файл схемы создан.

---

### D-15. `room-ktx` не подключается

Начиная с Room 2.7, артефакт `androidx.room:room-ktx` **пуст**, а его API перенесены в `room-runtime`. Официальная формулировка ([s6], release notes 2.7.0-alpha01):

> The KTX artifact `androidx.room:room-ktx` has been merged to `androidx.room:room-runtime` along with all its APIs, the artifact is now blank. Please remove it from your dependency list.

Практическое следствие для итерации 2: `withTransaction`, `suspend`-функции DAO и `Flow`-запросы берутся из `androidx.room:room-runtime` (импорт `androidx.room.withTransaction`). В списках зависимостей PR 2A `room-ktx` отсутствует, и в каталог версий он не добавляется.

---

### D-2. Room-тесты — JVM через Robolectric, в CI

Ответ на вопрос 2 задания.

| | Robolectric, `src/test` | Instrumented, `src/androidTest` |
| --- | --- | --- |
| Запускается в текущем CI | **Да**, шагом `testDebugUnitTest`, который уже есть | **Нет** — эмулятора в CI нет, и `ARCHITECTURE.md` §9 отказывается от него сознательно |
| SQLite | Настоящий фреймворковый SQLite из `android-all`-jar, не заглушка | Настоящий SQLite устройства |
| Время прогона | 3,2 с на первый тест (загрузка песочницы), далее ~15 мс на тест — измерено | Минуты плюс загрузка эмулятора |
| Цена подключения | Привязка к уровню SDK (см. ниже) и ~190 МБ в кэше | Инфраструктура эмулятора в CI |
| Что всё равно останется здесь | — | `MigrationTestHelper` на реальных схемах (итерация 4+), сквозной Compose-тест «полный день» |

**Выбор: Robolectric 4.16.1** — последний стабильный на 2026-08-31. Ветка 4.17 существует только как `4.17-beta-4`, а `VERSIONS.md` уже отказался от нестабильных версий (так была отклонена DataStore 1.3.0-alpha) — держим то же правило.

#### Ограничение, которое нужно учесть в 2A: Robolectric не знает SDK 37

В [исходнике `DefaultSdkProvider` тега `robolectric-4.16.1`][s8] список известных SDK заканчивается так (последнее число — минимальная версия JDK):

```java
knownSdks.put(U.SDK_INT,       new DefaultSdk(34, "14", …, 17));  // требует JDK 17
knownSdks.put(V.SDK_INT,       new DefaultSdk(35, "15", …, 17));  // требует JDK 17
knownSdks.put(Baklava.SDK_INT, new DefaultSdk(36, "16", …, 21));  // требует JDK 21
```

У проекта `targetSdk = 37`, а Robolectric по умолчанию берёт SDK именно из `targetSdk` ([s10]). SDK 37 ему неизвестен, SDK 36 требует JDK 21, а проект и CI работают на JDK 17. Значит, уровень надо задать явно:

```properties
# app/src/test/resources/robolectric.properties
sdk=35
```

Это не ослабляет проверку: под тестом находятся ограничения SQLite и транзакции Room, они не зависят от уровня API, а `minSdk` проекта — 26.

**Проверено на практике.** Robolectric скачал `android-all-instrumented-15-robolectric-13954326-i7.jar` и все три пробных теста прошли:

```xml
<testsuite name="…CarryOverProbeTest" tests="3" failures="0" errors="0" time="107.996">
  <testcase name="attemptMakesAssignmentNonPending"                             time="3.24"/>
  <testcase name="duplicateSlotAttemptIsRejectedByDatabase"                     time="0.015"/>
  <testcase name="pendingAssignmentIsCarriedOverWithoutViolatingUniqueSetIndex" time="0.016"/>
</testsuite>
```

Из 108 секунд ~104 ушли на разовую загрузку 190-мегабайтного `android-all`-jar в `~/.m2/repository`. **В CI этот каталог обязан кэшироваться отдельно**: `gradle/actions/setup-gradle` кэширует `~/.gradle`, а Robolectric качает мимо него, своим резолвером.

#### Robolectric нужен не всем тестам итерации 2

Разделение принципиальное, и оно же — ответ на «как гарантировать проверку критичных инвариантов в CI»:

- **Чистый JVM, без Robolectric, без Room и без корутин** — вся логика решений `SetAssignmentPolicy`, построение `TimeSnapshot` и поведение часов при смене зоны. Тест конструирует `AssignmentSnapshot` напрямую и вызывает синхронную `decide(today, snapshot)`. Семнадцать тестов (P1–P17, из них P16 — в `src/testDebug`, P17 — в `src/testRelease`), миллисекунды. Возможно потому, что политика — чистая функция (**D-3**).
- **Robolectric + `Room.inMemoryDatabaseBuilder`** — только то, что без настоящего SQLite не проверяется: три ограничения уникальности, сборка снимка и транзакционность переноса, пересчёт `day_results`, инварианты «не более одного отложенного» и «одна дата — одно назначение» на строках двух пакетов. Около тридцати тестов (A1–A13, C1–C6, T1–T10).
- **Instrumented** — в итерации 2 **ноль новых тестов**. Существующий `AppNavHostTest` продолжает только компилироваться в CI, как сейчас.

Конфигурация Gradle, необходимая Robolectric на JDK 17 ([s9]):

```kotlin
testOptions {
    unitTests {
        isIncludeAndroidResources = true
        all {
            it.jvmArgs(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            )
        }
    }
}
```

---

### D-3. `SetAssignmentPolicy` — чистая синхронная функция от `AssignmentSnapshot`

Сигнатура из `ARCHITECTURE.md` §4 нарушает собственное правило §1 «`domain` не знает про Android SDK; `data` реализует интерфейсы из `domain.repository`». Политика не должна обращаться ни к DAO, ни к repository-интерфейсу и не должна быть `suspend`: у неё нет ни одной причины быть асинхронной — она считает по уже прочитанным значениям.

```kotlin
// domain/assignment/AssignmentSnapshot.kt — только доменные значения
data class AssignmentSnapshot(
    /** Глобально по всем пакетам. ≤ 1 по инварианту; читаем до 2, чтобы нарушение было видно. */
    val pendingAssignments: List<DayAssignment>,
    /** Глобально: на дату приходится не более одного назначения — это PK таблицы. */
    val todayAssignment: DayAssignment?,
    /** Глобально: MAX(local_date) по всем пакетам. */
    val lastAssignedDate: LocalDate?,
    /** Пакет, из которого выдаётся следующий набор. */
    val activePackId: String,
    /** Только по активному пакету: MAX(set_index) WHERE pack_id = activePackId. */
    val maxSetIndexInActivePack: Int?,
    /** Только по активному пакету: COUNT(*) FROM daily_sets WHERE pack_id = activePackId. */
    val setCountInActivePack: Int,
)

// domain/assignment/SetAssignmentPolicy.kt — ни зависимостей, ни suspend, ни ввода-вывода
object SetAssignmentPolicy {
    fun decide(today: LocalDate, snapshot: AssignmentSnapshot): Decision
}
```

Разделение полей на глобальные и pack-scoped — решение **D-20**; `DayAssignment` несёт собственный `packId`, поэтому решения политики не подменяют пакет активным (**D-20**).

**Инвариант проверяется, а не предполагается.** Первая строка `decide`:

```kotlin
require(snapshot.pendingAssignments.size <= 1) {
    "нарушен инвариант: отложенных назначений ${snapshot.pendingAssignments.size}, " +
        "даты ${snapshot.pendingAssignments.map { it.localDate }}, " +
        "пакеты ${snapshot.pendingAssignments.map { it.packId }}"
}
```

Молча взять первую строку из двух — худший из возможных вариантов: система продолжила бы работать с потерянным набором, и обнаружилось бы это через недели, по жалобе на пропавшее задание. Поэтому DAO читает с `LIMIT 2`, а не `LIMIT 1`: без второй строки нарушение невозможно было бы обнаружить в принципе. Проверка имеет смысл только потому, что запрос глобальный: с фильтром по пакету две отложенные строки разных пакетов выглядели бы как одна (**D-20**).

**Снимок собирается реализацией репозитория внутри одной Room-транзакции** — см. раздел 4. Ни один из пяти запросов не может увидеть состояние базы, отличное от остальных четырёх.

**Следствия для плана:** из состава PR удаляются `AssignmentReader`, `AssignmentReaderImpl` и `FakeAssignmentReader` — они не нужны. Чистые тесты политики строят `AssignmentSnapshot` конструктором.

---

### D-4. `setCount` читается из базы и входит в снимок

`SELECT COUNT(*) FROM daily_sets WHERE pack_id = :packId`, результат кладётся в `AssignmentSnapshot.setCountInActivePack`. Причина: до итерации 4 наборов нет вообще, а после — их число задаёт манифест контента, который в код не попадает. Константа в конструкторе означала бы, что при выпуске второго пакета правится Kotlin-код, а не только assets, — это противоречит принципу 1 `CONTENT_MODEL.md` («контент — данные, а не код»).

Запрос остаётся **pack-scoped**: «сколько наборов есть» — вопрос про конкретный пакет, а не про базу целиком (**D-20**).

Следствие для итерации 2, которое надо принять сознательно: на пустой production-базе `setCountInActivePack = 0`, значит `next = 0 >= 0` и политика возвращает `ContentExhausted`. Это честное состояние («новые задания готовятся»), а не ошибка, и оно же — причина, по которой нужна debug-фикстура (**D-9**).

---

### D-20. Глобальные и pack-scoped запросы разделены по смыслу инварианта

Схема (раздел 3) даёт `day_assignments` **глобальный** первичный ключ `local_date` — не `(pack_id, local_date)`. Это не случайность и не упрощение: `UX_FLOW.md` §9 формулирует три инварианта, ни один из которых не упоминает пакет.

| Инвариант из `UX_FLOW.md` §9 | Область действия | Чем поддержан в схеме |
| --- | --- | --- |
| «Не более одного нового набора за локальную календарную дату» | **Глобальная** | `PRIMARY KEY(local_date)` |
| «Отложенное назначение в системе не более одного» | **Глобальная** | Инвариант кода, проверяемый `require` в `decide` |
| «Новый набор только если `today > lastAssignedDate`» | **Глобальная** | `MAX(local_date)` без `WHERE` |
| «Один набор не выдаётся дважды» | **Pack-scoped** | `UNIQUE(pack_id, set_index)` |
| «Следующий индекс = `max(set_index) + 1`» | **Pack-scoped** | `MAX(set_index) WHERE pack_id` |
| «Сколько наборов есть в пакете» | **Pack-scoped** | `COUNT(*) FROM daily_sets WHERE pack_id` |

**Решение: три запроса снимка — глобальные, два — pack-scoped.**

| Запрос | Область | Почему |
| --- | --- | --- |
| `pendingAssignments()` | **глобальный** | Отложенное назначение — состояние *пользователя*, а не пакета. С фильтром по пакету два пакета имели бы по своему отложенному, и `require(size <= 1)` в `decide` не поймал бы нарушение: он видел бы одну строку из двух существующих |
| `byDate(date)` | **глобальный** | На дату приходится ровно одна строка — это PK. Фильтр по пакету создал бы иллюзию, будто на одну дату бывает по назначению на пакет, и `insert` активного пакета молча падал бы на PK вместо того, чтобы вернуть `Assigned` чужого пакета |
| `lastAssignedDate()` | **глобальный** | Защита от перевода даты назад не должна обходиться переключением пакета: с фильтром пользователь, переключивший пакет, получал бы `lastAssignedDate = null` и новый набор в ту же дату — то есть два набора за календарные сутки |
| `maxSetIndex(packId)` | **pack-scoped** | Индекс — порядковый номер внутри пакета; у каждого пакета собственная последовательность (`CONTENT_MODEL.md` §7) |
| `countSets(packId)` | **pack-scoped** | Число наборов — свойство пакета (**D-4**) |

**Решения политики несут `packId` явно.** Иначе исполнитель решения подставил бы активный пакет и переписал бы `pack_id` чужой строки:

```kotlin
// domain/assignment/Decision.kt
sealed interface Decision {
    /** Создать строку в активном пакете. */
    data class NewSet(val packId: String, val setIndex: Int) : Decision

    /** Перенести существующую строку. packId и setIndex — исходной строки, не активного пакета. */
    data class CarryOver(val packId: String, val setIndex: Int, val fromDate: LocalDate) : Decision

    /** Назначение на сегодня уже есть. packId — того пакета, которому строка принадлежит. */
    data class Assigned(val packId: String, val setIndex: Int) : Decision

    data object AwaitingNextDay : Decision
    data object ContentExhausted : Decision
}
```

**Полный текст решения:**

```kotlin
object SetAssignmentPolicy {

    fun decide(today: LocalDate, snapshot: AssignmentSnapshot): Decision {
        require(snapshot.pendingAssignments.size <= 1) {
            "нарушен инвариант: отложенных назначений ${snapshot.pendingAssignments.size}, " +
                "даты ${snapshot.pendingAssignments.map { it.localDate }}, " +
                "пакеты ${snapshot.pendingAssignments.map { it.packId }}"
        }

        // 1. Отложенное назначение — какому бы пакету оно ни принадлежало — разбирается первым.
        val pending = snapshot.pendingAssignments.firstOrNull()
        if (pending != null) {
            return when {
                pending.localDate == today -> Decision.Assigned(pending.packId, pending.setIndex)
                today < pending.localDate  -> Decision.AwaitingNextDay
                else -> Decision.CarryOver(pending.packId, pending.setIndex, pending.localDate)
            }
        }

        // 2. Назначение на сегодня (уже израсходованное) — тоже любого пакета.
        snapshot.todayAssignment?.let { return Decision.Assigned(it.packId, it.setIndex) }

        // 3. Глобальная защита от перевода даты назад.
        val last = snapshot.lastAssignedDate
        if (last != null && today <= last) return Decision.AwaitingNextDay

        // 4. Новый набор берётся из активного пакета и только из него.
        val next = (snapshot.maxSetIndexInActivePack ?: -1) + 1
        if (next >= snapshot.setCountInActivePack) return Decision.ContentExhausted
        return Decision.NewSet(snapshot.activePackId, next)
    }
}
```

**Отсюда прямо следует требуемое поведение при смене активного пакета.** Отложенное назначение предыдущего пакета разбирается в шаге 1 — раньше, чем шаг 4 вообще рассматривает активный пакет. Пока это назначение не израсходовано (по нему не сделано ни одной попытки), новый набор активного пакета не выдаётся ни при каком порядке действий: пользователь либо доигрывает перенесённый набор старого пакета, либо получает `Assigned`/`CarryOver` по нему же. Порядок «завершить или перенести старое → только потом выдать новое» не проверяется отдельной проверкой, он задан порядком ветвей.

**Активный пакет — параметр конструктора, а не константа в теле репозитория.** В итерации 2 у него ровно одно продуктовое значение `ContentPack.CORE_RU`, поставляемое DI:

```kotlin
// di/ActivePack.kt
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ActivePack

// di/RepositoryModule.kt
@Provides @ActivePack fun activePackId(): String = ContentPack.CORE_RU
```

Это не «настраиваемый пакет в `main`», от которого отказался раздел 5: значение по-прежнему одно и задано в коде. Но благодаря параметру тесты A10–A12 могут построить репозиторий с другим активным пакетом и проверить именно то поведение, которое иначе проверять нечем, а итерация с тематическими паками меняет одну строку модуля DI вместо тела репозитория.

---

### D-16. Время: `TimeSnapshot` строится фабрикой из одного `Instant`; произвольная дата отсутствует в release-варианте

Переносится в **PR 2B** (в первой ревизии стояло в 2C): `StartDailySessionUseCase` появляется в 2B и без внедрённого времени существовать не может.

#### `TimeSnapshot` невозможно собрать из независимых `date` и `millis`

```kotlin
// core/time/TimeSnapshot.kt — согласованная пара; конструктор приватный
class TimeSnapshot private constructor(
    val localDate: LocalDate,
    val epochMillis: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is TimeSnapshot && other.localDate == localDate && other.epochMillis == epochMillis

    override fun hashCode(): Int = 31 * localDate.hashCode() + epochMillis.hashCode()

    override fun toString(): String = "TimeSnapshot($localDate, $epochMillis)"

    companion object {
        /** Единственный способ построения: один момент + одна зона. */
        fun of(instant: Instant, zone: ZoneId): TimeSnapshot =
            TimeSnapshot(
                localDate = LocalDate.ofInstant(instant, zone),
                epochMillis = instant.toEpochMilli(),
            )

        fun of(clock: Clock): TimeSnapshot = of(clock.instant(), clock.zone)
    }
}
```

Это **обычный `class`, а не `data class`** — и выбор сделан по простой причине: обычный класс не генерирует `copy` вообще. Никакого `snapshot.copy(localDate = вчера)` не существует как API, поэтому единственный путь получения значения — фабрика `of`, а инвариант «обе величины выведены из одного `Instant`» держится самой формой типа, без оговорок.

С `data class` пришлось бы рассуждать о видимости сгенерированного `copy()` при приватном конструкторе: правило здесь менялось между версиями Kotlin и зависит от флага компилятора, так что «`copy()` обязательно публичен» — утверждение, которое нужно было бы проверять на конкретной версии тулчейна и перепроверять при каждом обновлении Kotlin. Обычный класс снимает этот вопрос целиком: проверять нечего, потому что генерации нет. Три строки `equals`/`hashCode`/`toString` руками — приемлемая цена за то, чтобы защита инварианта не зависела от версии компилятора.

`equals`/`hashCode` нужны, потому что тесты сравнивают значения; `toString` — потому что он попадает в сообщения `check`/`require` и на debug-экран.

```kotlin
// core/time/ClockProvider.kt — источник момента и зоны
interface ClockProvider {
    fun clock(): Clock

    /** Единственный способ получить дату и метку времени вместе. */
    fun now(): TimeSnapshot = TimeSnapshot.of(clock())
}

// core/time/DateProvider.kt — узкий интерфейс для UI итерации 3
interface DateProvider {
    fun today(): LocalDate
}
```

**Почему один `Instant`, а не два вызова.** `LocalDate.now()` и `System.currentTimeMillis()`, вызванные подряд, могут разойтись через полночь: назначение получит `local_date` вчерашнего дня и `assigned_at` сегодняшнего. Это редкий, невоспроизводимый и крайне неприятный дефект. `TimeSnapshot` делает такое расхождение невозможным по построению — обе величины выводятся из одного момента, и другого пути построения нет.

#### Продуктовый API даты не принимает

`ARCHITECTURE.md` §4 говорит, что use case исполняет решение при переходе `Home → Puzzle(0)`; какая сегодня дата — не вопрос UI:

```kotlin
// domain/usecase/StartDailySessionUseCase.kt
class StartDailySessionUseCase @Inject constructor(
    private val assignments: DayAssignmentRepository,
) {
    suspend operator fun invoke(): Decision = assignments.startSession()
}

// domain/repository/DayAssignmentRepository.kt
interface DayAssignmentRepository {
    suspend fun peek(): Decision           // Home, только чтение
    suspend fun startSession(): Decision   // Home → Puzzle(0), фиксация
}
```

#### Подстановка даты — через подменяемый `ClockProvider`, а не через шов в репозитории

**`internal` здесь не работает, и это надо сказать прямо.** В Kotlin `internal` ограничивает видимость **Gradle-модулем компиляции**, а не пакетом `data`. Модуль в проекте один — `:app` (ADR-001). Значит `internal fun startSessionAt(time: TimeSnapshot)` был бы виден всему приложению: `ui`, `domain`, `notifications` — и в release-сборке тоже. Шов из ревизии 2 не давал ни одной из обещанных гарантий; предыдущая формулировка «не виден никакому продуктовому вызывающему коду за пределами `data`» неверна и здесь отменяется.

**Решение: подменяется источник времени, а не API репозитория.** Репозиторий не получает ни одного debug-метода:

```kotlin
// data/repository/DayAssignmentRepositoryImpl.kt — весь публичный API репозитория
override suspend fun peek(): Decision
override suspend fun startSession(): Decision
```

Реализации `ClockProvider` разложены по вариантам сборки:

```kotlin
// app/src/release/java/ru/poporyadku/core/time/SystemClockProvider.kt
@Singleton
class SystemClockProvider @Inject constructor() : ClockProvider, DateProvider {

    /** Зона читается на каждом обращении, а не кэшируется в поле. */
    override fun clock(): Clock = Clock.system(ZoneId.systemDefault())

    override fun today(): LocalDate = now().localDate
}

// app/src/release/java/ru/poporyadku/di/ClockModule.kt
@Module @InstallIn(SingletonComponent::class)
abstract class ClockModule {
    @Binds abstract fun clockProvider(impl: SystemClockProvider): ClockProvider
    @Binds abstract fun dateProvider(impl: SystemClockProvider): DateProvider
}
```

**Почему `Clock` не кэшируется в поле.** `SystemClockProvider` — `@Singleton`: он создаётся один раз за жизнь процесса. Строка `private val clock = Clock.systemDefaultZone()` навсегда запомнила бы зону, действовавшую в момент создания графа Hilt. После смены часового пояса — перелёта, ручной правки настроек, обработки `ACTION_TIMEZONE_CHANGED`, которую `UX_FLOW.md` §9 предписывает слушать, — провайдер продолжал бы отдавать старую зону, и «сегодня» считалось бы по покинутому поясу до перезапуска процесса. Дефект того же класса, что расхождение `local_date` и `assigned_at`: редкий, невоспроизводимый на столе разработчика и полностью ломающий продуктовое правило «новый день наступает в полночь пользователя».

Поэтому `clock()` каждый раз строит `Clock.system(ZoneId.systemDefault())`. Цена — обращение к `ZoneId.systemDefault()` на вызов, то есть чтение закэшированного в JDK значения; вызовов за сессию единицы (`peek` на Home, `startSession` при старте дня). `Clock.system(zone)` при этом остаётся динамическими часами: он читает текущий момент при каждом `instant()`, в отличие от `Clock.fixed`.

#### Где живут тесты на это — нюанс unit-test source sets

Правило простое: **`src/test` предназначен только для тестов кода из `src/main`, доступного обоим вариантам.** Всё, что тестирует variant-specific реализацию, обязано лежать в variant-specific тестовом source set.

| Source set | Компилируется задачей | Что оттуда видно |
| --- | --- | --- |
| `src/test` | **и** `testDebugUnitTest`, **и** `testReleaseUnitTest` | Только `src/main` |
| `src/testDebug` | `testDebugUnitTest` | `src/main` + `src/debug` |
| `src/testRelease` | `testReleaseUnitTest` | `src/main` + `src/release` |

Отсюда следует то, что легко упустить: **файл из `src/test` компилируется в том числе для release unit tests, поэтому он не может импортировать `DebugClockProvider` из `src/debug`** — при сборке `testReleaseUnitTest` этого класса не существует, и компиляция упадёт с «unresolved reference». Симметрично `SystemClockProvider` из `src/release` не виден задаче `testDebugUnitTest`. Общий `src/test` — не «место для всех тестов», а место ровно для тех, кому хватает `src/main`.

Поэтому тесты часов разнесены:

| Тест | Что проверяет | Файл | Чем запускается |
| --- | --- | --- | --- |
| **P16** | `DebugClockProvider` без фиксации следует за сменой зоны, а после `setDate` — перестаёт | `app/src/testDebug/java/ru/poporyadku/core/time/DebugClockProviderTest.kt` | `testDebugUnitTest` (уже в CI) |
| **P17** | `SystemClockProvider` следует за сменой зоны | `app/src/testRelease/java/ru/poporyadku/core/time/SystemClockProviderTest.kt` | `testReleaseUnitTest` — шаг добавляется в CI тем же PR 2B |

Обе реализации содержат одно и то же выражение `Clock.system(ZoneId.systemDefault())`, но проверяются раздельно: P16 закрывает путь, по которому ходит отладчик, P17 — тот, что попадает пользователю, и именно его нельзя оставить непроверенным из-за неудобства source sets.

`FakeClockProvider` при этом остаётся в общем `src/test` законно: он реализует `ClockProvider` — интерфейс из `src/main` — и ни одного variant-specific типа не упоминает.

Форма обоих тестов одна:

```kotlin
// P17 — app/src/testRelease/java/ru/poporyadku/core/time/SystemClockProviderTest.kt
@Test fun `zone follows system default`() {
    val original = TimeZone.getDefault()
    // ОДИН экземпляр на весь тест: он создаётся до первой смены зоны и переживает обе.
    val provider = SystemClockProvider()
    try {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))
        assertEquals(ZoneId.of("Europe/Moscow"), provider.clock().zone)

        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Vladivostok"))
        assertEquals(ZoneId.of("Asia/Vladivostok"), provider.clock().zone)
    } finally {
        TimeZone.setDefault(original)          // обязательно: иначе зона утечёт
    }
}
```

**Экземпляр обязан быть один, созданный до первой `setDefault`.** Если пересоздавать провайдер перед каждой проверкой, тест пройдёт и на реализации с закэшированной в поле зоной — новый объект просто прочитает актуальное значение в конструкторе, и дефект останется невидимым. Проверяется ровно то, что провайдер, **переживший** смену зоны, отдаёт новую: это и есть моделирование `@Singleton`, живущего всю сессию. То же требование к P16 — `DebugClockProvider` создаётся один раз, до первой `setDefault`.

`finally` здесь не формальность: Gradle переиспользует JVM-процесс между тестовыми классами, и оставленная чужая зона тихо испортила бы любой последующий тест, зависящий от дат, — включая A1–A13. Восстановление обязано выполниться и при провале ассерта.

```kotlin
// app/src/debug/java/ru/poporyadku/core/time/DebugClockProvider.kt
@Singleton
class DebugClockProvider @Inject constructor() : ClockProvider, DateProvider {

    /** null — «часы не зафиксированы»: время идёт системное и динамическое. */
    private val fixed = AtomicReference<Clock?>(null)

    override fun clock(): Clock = fixed.get() ?: Clock.system(ZoneId.systemDefault())

    override fun today(): LocalDate = now().localDate

    /** Фиксирует выбранную дату в выбранной зоне. */
    fun setDate(date: LocalDate, zone: ZoneId = clock().zone) {
        val instant = date.atTime(LocalTime.NOON).atZone(zone).toInstant()
        require(LocalDate.ofInstant(instant, zone) == date) {
            "дата не пережила преобразование: выбрана $date, получена " +
                "${LocalDate.ofInstant(instant, zone)} в зоне $zone"
        }
        fixed.set(Clock.fixed(instant, zone))
    }

    /** Снимает фиксацию: часы снова системные и динамические. */
    fun reset() = fixed.set(null)
}

// app/src/debug/java/ru/poporyadku/di/ClockModule.kt — то же имя, тот же пакет, другой вариант
@Module @InstallIn(SingletonComponent::class)
abstract class ClockModule {
    @Binds abstract fun clockProvider(impl: DebugClockProvider): ClockProvider
    @Binds abstract fun dateProvider(impl: DebugClockProvider): DateProvider
}
```

**Почему полдень и почему `require` на round trip.** Полночь конкретной даты в зоне с летним переходом может не существовать: `ZonedDateTime` разрешает такой локальный момент сдвигом вперёд, и обратное преобразование `LocalDate.ofInstant(instant, zone)` вернуло бы не ту дату, которую выбрал отладчик. Полдень уводит выбранный момент от границы суток на двенадцать часов и тем самым **снижает риск обычных DST-переходов** — часовых и получасовых сдвигов, которые происходят ночью и полудня не касаются.

Это именно снижение риска, а не гарантия, и полагаться на него как на инвариант нельзя:

- **календарная дата может быть пропущена целиком.** При переносе линии перемены дат сутки исчезают из календаря зоны полностью: так, Самоа не имело 30 декабря 2011 года. Для такой даты не существует ни полудня, ни любого другого момента, и `date.atTime(NOON).atZone(zone)` вернёт время соседних суток;
- правила переходов — политические решения конкретных стран, `tzdata` обновляется несколько раз в год, и зона с крупным сдвигом в середине дня технически представима.

Поэтому утверждения «любая `LocalDate` существует в любой зоне» в документе нет, а вместо него стоит `require`, проверяющий round trip фактически. Он **обнаруживает** такой случай и не даёт дате тихо съехать: отладчик получает внятную ошибку с выбранной датой, полученной датой и зоной вместо набора, назначенного на соседние сутки. Свойство round trip для полудня фиксируется тестом P11 на реальной зоне с переходом; `require` страхует всё остальное, включая пропущенные даты.

**Почему `AtomicReference<Clock?>` с `null`, а не заранее созданные системные часы.** Записанный в поле `Clock.systemDefaultZone()` навсегда запоминает зону, действовавшую в момент создания синглтона: смена часового пояса на устройстве (перелёт, ручная правка настроек) осталась бы для отладчика невидимой — ровно тот сценарий, который `UX_FLOW.md` §9 разбирает отдельно. `null` означает «не зафиксировано», и тогда `clock()` каждый раз строит `Clock.system(ZoneId.systemDefault())`, читая актуальную зону. `reset()` возвращает провайдер именно в это состояние, а не в «зафиксированные системные часы».

Debug-контроллер меняет часы и вызывает **публичный** API репозитория:

```kotlin
// app/src/debug/java/ru/poporyadku/debug/DebugSessionController.kt
class DebugSessionController @Inject constructor(
    private val clock: DebugClockProvider,
    private val assignments: DayAssignmentRepository,
) {
    suspend fun peekAt(date: LocalDate): Decision {
        clock.setDate(date)
        return assignments.peek()
    }

    suspend fun startSessionAt(date: LocalDate): Decision {
        clock.setDate(date)
        return assignments.startSession()
    }
}
```

Пары `LocalDate + millis` в его сигнатурах нет: отладчик выбирает **дату**, а метку времени выводит `TimeSnapshot.of` из зафиксированного `Instant`. Задать несогласованную пару неоткуда.

**Unit-тесты обходятся без Hilt.** `FakeClockProvider` лежит в `src/test` и подставляется конструктором:

```kotlin
// app/src/test/java/ru/poporyadku/core/time/FakeClockProvider.kt
class FakeClockProvider(private var value: Clock) : ClockProvider, DateProvider {
    override fun clock(): Clock = value
    override fun today(): LocalDate = now().localDate
    fun setDate(date: LocalDate, zone: ZoneId = value.zone) {
        value = Clock.fixed(date.atTime(LocalTime.NOON).atZone(zone).toInstant(), zone)
    }
}
```

Тесты A1–A12 создают `DayAssignmentRepositoryImpl(db, dao, sets, FakeClockProvider(…), activePackId)` напрямую — ни `@HiltAndroidTest`, ни тестового графа не требуется.

**Почему произвольная дата физически отсутствует в release.** `DebugClockProvider`, `DebugSessionController` и debug-версия `ClockModule` лежат в `app/src/debug/`. Этот source set не входит в вариант `release`: `assembleRelease` его не компилирует и о нём не знает. В release-варианте `ClockProvider` реализован единственным `SystemClockProvider`, у которого нет ни одного метода записи и ни одного изменяемого поля: `clock()` каждый раз возвращает свежий `Clock.system(ZoneId.systemDefault())`, подменить который неоткуда. Публичный API репозитория и use case даты не принимает. То есть в релизной сборке **нет ни одного вызова, которым можно задать дату** — не «не рекомендуется», а не существует в скомпилированном коде.

**Отвергнутая альтернатива — `internal`-шов `peekAt`/`startSessionAt`.** Отвергнут дважды: он не даёт заявленной изоляции (см. выше про `internal` и один модуль) и оставляет в релизной сборке метод, принимающий произвольную дату. Variant-specific `ClockProvider` стоит одного лишнего файла в каждом из двух вариантов и убирает саму возможность.

---

### D-5. `day_results` пересчитывается из `puzzle_attempts`, а не инкрементируется

Тот же довод, что в ADR-005 про серию: счётчик рассинхронизируется на любом краевом случае, а пересчёт — два агрегата по трём строкам. Оба действия — в одной транзакции с записью попытки.

### D-17. `ProgressRepository` проверяет доменные диапазоны до записи

`CHECK`-ограничений Room не поддерживает (раздел 3), поэтому границы значений обязан держать домен — иначе их не держит никто:

```kotlin
// data/progress/ProgressRepositoryImpl.kt
override suspend fun recordAttempt(attempt: PuzzleAttempt) {
    require(attempt.slotIndex in 0..2) { "slotIndex вне 0..2: ${attempt.slotIndex}" }
    require(attempt.score in 0..PairwiseScore.MAX_PER_PUZZLE) { "score вне 0..6: ${attempt.score}" }
    db.withTransaction { … }
}
```

Проверка стоит **до** открытия транзакции: некорректный вызов — это дефект кода, а не состояние базы, и откатывать тут нечего. Верхняя граница `score` берётся из константы, а не из литерала `6`, чтобы связь с `MAX_PER_PUZZLE = C(4,2)` из `ARCHITECTURE.md` была видна в коде. Значения `total_score ∈ 0..18` и `completed_count ∈ 0..3` отдельной проверки не требуют: они вычисляются из уже проверенных попыток, и их корректность закрыта тестом C4.

### D-6. Время напоминания — `Int`, минуты от полуночи

Preferences DataStore хранит только `Boolean/Int/Long/Float/Double/String/Set<String>`. Из трёх вариантов — строка `"09:00"`, два ключа `hour`/`minute`, одно число — выбрано одно число `540`: оно сравнимо и сортируемо без разбора, не зависит от локали и переживает любые изменения формата отображения. В домене поле остаётся `LocalTime`, преобразование живёт в реализации репозитория, диапазон валидируется (**D-18**).

### D-18. `UserPreferences` — доменная модель в `core/model`; DataStore не выходит за `data/prefs`

#### Граница пакетов

В ревизии 2 доменный интерфейс возвращал `Flow<UserPreferences>`, а сам `UserPreferences` лежал в `data/prefs` — то есть `domain` импортировал тип из `data`, нарушая правило `ARCHITECTURE.md` §1 «`data` реализует интерфейсы из `domain.repository`», а не наоборот. Правится переносом:

| Тип | Где живёт | Почему |
| --- | --- | --- |
| `UserPreferences` (`data class`) | **`core/model/UserPreferences.kt`** | Это доменное значение: набор пользовательских настроек. Его читают `domain` и `ui`; по правилам зависимостей оба видят `core.model` и не видят `data` |
| `ThemeMode` (`enum`) | `core/model/ThemeMode.kt` | Часть `UserPreferences` |
| `UserPreferencesRepository` (интерфейс) | `domain/repository/UserPreferencesRepository.kt` | Импортирует только `core.model` и `kotlinx.coroutines.flow` |
| `PreferenceKeys` (`Preferences.Key<*>`) | `data/prefs/PreferenceKeys.kt` | Внутренняя деталь хранилища |
| `UserPreferencesRepositoryImpl`, `DataStore<Preferences>`, `ReplaceFileCorruptionHandler` | `data/prefs/` | Единственное место в проекте, где встречается имя `androidx.datastore` |

```kotlin
// core/model/UserPreferences.kt — ни одного импорта из data
data class UserPreferences(
    val soundEnabled: Boolean,
    val vibrationEnabled: Boolean,
    val reminderEnabled: Boolean,
    val reminderTime: LocalTime,
    val themeMode: ThemeMode,
    val storedContentVersion: Int,
    val hasSeenDragHint: Boolean,
    val hasSeenScoringHint: Boolean,
    val hasCompletedFirstDay: Boolean,
    val notificationPromptShown: Boolean,
    val lastSeenDate: LocalDate?,
    val streakCache: StreakCache,
)

/** Тройка кэша серии — одно значение, а не три независимых поля. */
data class StreakCache(
    val current: Int,
    val best: Int,
    val date: LocalDate?,
) {
    companion object { val EMPTY = StreakCache(current = 0, best = 0, date = null) }
}

// domain/repository/UserPreferencesRepository.kt
interface UserPreferencesRepository {
    val preferences: Flow<UserPreferences>

    suspend fun setSoundEnabled(enabled: Boolean)
    suspend fun setVibrationEnabled(enabled: Boolean)
    suspend fun setReminderEnabled(enabled: Boolean)
    suspend fun setReminderTime(time: LocalTime)
    suspend fun setThemeMode(mode: ThemeMode)

    /** version >= 0; contentVersion не бывает отрицательной (CONTENT_MODEL.md §7). */
    suspend fun setStoredContentVersion(version: Int)

    suspend fun setHasSeenDragHint(seen: Boolean)
    suspend fun setHasSeenScoringHint(seen: Boolean)
    suspend fun setHasCompletedFirstDay(completed: Boolean)
    suspend fun setNotificationPromptShown(shown: Boolean)

    /** null удаляет ключ — «даты нет», а не «дата пустая строка». */
    suspend fun setLastSeenDate(date: LocalDate?)

    /** Единственная операция записи кэша серии. Отдельных сеттеров трёх ключей нет. */
    suspend fun updateStreakCache(current: Int, best: Int, date: LocalDate)
}
```

Сеттеров `setCachedCurrentStreak`, `setCachedBestStreak` и `setCachedStreakDate` в интерфейсе **нет и не появляется**. Три ключа не являются тремя настройками — это одно значение, разложенное по трём примитивам, потому что Preferences DataStore не хранит структуры. Публичные раздельные сеттеры позволили бы вызывающему коду записать дату сегодняшнюю, а серию вчерашнюю, — то есть ровно ту ошибку, ради предотвращения которой дата и заводилась (**D-7**). Отсутствие такого API — не соглашение, а свойство типа: невалидное состояние нечем записать.

**`setLastSeenDate` принимает `LocalDate?`.** В домене поле нулевое (`UserPreferences.lastSeenDate: LocalDate?`) — «приложение ещё не открывали». Сеттер, принимающий только не-null, делал бы обратный переход невыразимым: сбросить дату можно было бы, лишь записав в неё что-нибудь ложное. `null` удаляет ключ целиком (`prefs.remove(LAST_SEEN_DATE)`), после чего чтение честно даёт `null` — то же состояние, что на пустом хранилище. Симметрия «что читается — то и пишется» стоит одного знака вопроса.

#### Валидация на записи: три места, один принцип

```kotlin
// data/prefs/UserPreferencesRepositoryImpl.kt

override suspend fun updateStreakCache(current: Int, best: Int, date: LocalDate) {
    require(current >= 0) { "текущая серия отрицательна: $current" }
    require(best >= current) { "лучшая серия $best меньше текущей $current" }
    dataStore.edit { prefs ->
        prefs[PreferenceKeys.CACHED_CURRENT_STREAK] = current
        prefs[PreferenceKeys.CACHED_BEST_STREAK] = best
        prefs[PreferenceKeys.CACHED_STREAK_DATE] = date.toString()
    }
}

override suspend fun setStoredContentVersion(version: Int) {
    require(version >= 0) { "версия контента отрицательна: $version" }
    dataStore.edit { it[PreferenceKeys.STORED_CONTENT_VERSION] = version }
}

override suspend fun setReminderTime(time: LocalTime) {
    val minutes = time.hour * 60 + time.minute
    require(minutes in 0..1439) { "время напоминания вне 0..1439: $minutes" }
    dataStore.edit { it[PreferenceKeys.REMINDER_MINUTE_OF_DAY] = minutes }
}

override suspend fun setLastSeenDate(date: LocalDate?) {
    dataStore.edit { prefs ->
        if (date == null) prefs.remove(PreferenceKeys.LAST_SEEN_DATE)
        else prefs[PreferenceKeys.LAST_SEEN_DATE] = date.toString()
    }
}
```

`require` везде стоит **до** `edit`, как и в **D-17**: нарушение — дефект вызывающего кода, а не состояние хранилища, и откатывать нечего.

Обоснование каждой проверки:

- `current >= 0` и `best >= current` — определения из `ARCHITECTURE.md` §4: серия не бывает отрицательной, а лучшая по определению не меньше текущей (текущая — одна из тех, среди которых берётся максимум);
- `version >= 0` — `contentVersion` растёт от 1, а `0` зарезервирован под «ничего не импортировано» (`CONTENT_MODEL.md` §7). Отрицательная версия сделала бы сравнение `stored > manifest` в ветке отката приложения бессмысленным. Проверка на записи — пара к правилу чтения «`stored_content_version < 0` читается как `0`»: чтение чинит уже испорченное хранилище, запись не даёт испортить его из кода;
- `minutes in 0..1439` — та же граница, что и на чтении (**D-6**), проверяемая с обеих сторон.

Все три фиксируются тестами T1, T2 и T9.

#### Чтение: невалидная тройка сбрасывается целиком

```kotlin
private fun Preferences.readStreakCache(): StreakCache {
    val current = this[PreferenceKeys.CACHED_CURRENT_STREAK] ?: 0
    val best = this[PreferenceKeys.CACHED_BEST_STREAK] ?: 0
    val rawDate = this[PreferenceKeys.CACHED_STREAK_DATE]
    val date = rawDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    val consistent = current >= 0 &&
        best >= current &&
        (rawDate == null || date != null) &&          // дата есть, но не разбирается — тройка битая
        (date != null || (current == 0 && best == 0)) // серия без даты пересчёта бесполезна (D-7)

    return if (consistent) StreakCache(current, best, date) else StreakCache.EMPTY
}
```

**Сбрасывается вся тройка, а не отдельное поле.** Прочитать `current` из битой тройки и обнулить только дату означало бы показать на Home число, происхождение которого неизвестно, — а кэш существует ровно для того, чтобы показать правильное число мгновенно. Пустой кэш честен: Home ведёт себя как при первом запуске и ждёт базу, которая и есть источник истины (ADR-005).

**Сброс происходит только на чтении и ничего не пишет.** Возвращается `StreakCache.EMPTY`, в хранилище правка не отправляется: путь чтения — холодный `Flow`, который могут собирать несколько экранов одновременно, и запись из него превратила бы отображение в конкурирующего писателя. Битая тройка перезапишется при следующем штатном `updateStreakCache` из итерации 3.

#### Устойчивость чтения — по правилу на каждое поле

| Ситуация | Поведение |
| --- | --- |
| `theme_mode` содержит неизвестное имя | Читается как `ThemeMode.SYSTEM` |
| `last_seen_date` содержит невалидную ISO-дату | Читается как `null` (`runCatching { LocalDate.parse(it) }.getOrNull()`) |
| Тройка кэша серии невалидна (`current < 0`, `best < current`, неразбираемая или отсутствующая при ненулевой серии дата) | Вся тройка читается как `current = 0, best = 0, date = null` |
| `reminder_minute_of_day` вне `0..1439` | Читается как значение по умолчанию `540`; валидация и на чтении, и на записи |
| `stored_content_version` меньше нуля | Читается как `0`: `(this[STORED_CONTENT_VERSION] ?: 0).coerceAtLeast(0)`. Ноль означает «ничего не импортировано», и первый же запуск попадает в ветку импорта (`CONTENT_MODEL.md` §7) — отрицательное значение вело бы себя так же, но сравнение `stored > manifest` в ветке отката работало бы на мусоре |
| `IOException` при чтении потока | `emit(emptyPreferences())` — весь набор значений по умолчанию вместо падения экрана |
| **Любое другое исключение** при чтении потока | **Пробрасывается наверх**, не подменяется значениями по умолчанию |
| Файл физически повреждён | `ReplaceFileCorruptionHandler { emptyPreferences() }` — остаётся, он закрывает другой случай: не ошибку чтения, а нечитаемый файл на диске |

```kotlin
override val preferences: Flow<UserPreferences> = dataStore.data
    .catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }
    .map { it.toUserPreferences() }
```

**Почему `catch` сужен до `IOException`.** Официальный пример DataStore ловит именно `IOException`, и это не стилистика: `IOException` означает «файл не прочитался» — восстановимую ситуацию, где значения по умолчанию корректны. Всё остальное, что может прилететь из этого потока, — `IllegalStateException` из-за двух `DataStore` на один файл, `OutOfMemoryError`, `ClassCastException` при чтении ключа не того типа, `CancellationException` при отмене скоупа — это дефекты кода, а не состояние диска. Широкий `catch { emit(emptyPreferences()) }` превратил бы каждый из них в тихий откат всех настроек пользователя к умолчаниям: тема сбросилась, напоминание выключилось, флаги обучения показались заново — и ни одной записи в логе. `CancellationException` вдобавок нельзя проглатывать вовсе: это сломало бы отмену корутин.

Обоснование общего принципа: настройки — не источник истины ни для чего, потеря любого значения означает возврат к умолчанию, а не потерю прогресса. Падение приложения на старте из-за одного испорченного ключа несоразмерно последствиям — но только для тех ошибок, которые действительно про испорченный ключ.

### D-8. Все пять таблиц создаются в версии схемы 1

`puzzles` и `daily_sets` остаются пустыми до итерации 4, но их сущности пишутся сейчас и целиком, включая `retired_in` и `content_version`. Альтернатива — добавить их в итерации 4 — потребовала бы миграции 1 → 2, которая ничего не мигрирует. Первая настоящая миграция должна быть настоящей.

### D-19. Мапперы контента — не в итерации 2

В PR 2A создаются: сущности `PuzzleEntity` и `DailySetEntity`, их DAO, схема, а также доменные модели `Puzzle`, `Card`, `DailySet`. **Маппер `PuzzleEntity ↔ Puzzle` не реализуется.**

Причина: он упирается в `cards_json` и `sources_json`, разбор которых требует `kotlinx-serialization`, а её подключение относится к итерации 4 вместе с `ContentAssetSource` и `ContentImporter` (`IMPLEMENTATION_PLAN.md`, итерация 4). Писать разбор JSON руками, чтобы обойти отсутствие библиотеки, **запрещено**: ручной парсер пришлось бы выбросить через одну итерацию, а до тех пор он был бы вторым, несогласованным представлением формата контента, описанного в `CONTENT_MODEL.md` §4.

Мапперы, которые в итерации 2 реализуются, потому что JSON не требуют: `DayAssignment`, `PuzzleAttempt`, `DayResult` (в 2A вместе с сущностями и в 2B по мере использования) и `DailySet` (плоские поля `pack_id`, `set_index`, три `puzzle_id`). Их достаточно и для политики выдачи, и для `ProgressRepository`, и для debug-фикстуры.

### D-9. Debug-фикстура — кнопка, а не автозапуск

Полный разбор — раздел 5. Коротко: фикстура физически лежит в `src/debug`, пишет только в `daily_sets` и вызывается исключительно нажатием на debug-экране. Ни `Application`, ни `RoomDatabase.Callback`, ни `createFromAsset` в этом не участвуют.

### D-10. Debug-экран — отдельная Activity в `src/debug`, а не маршрут в `AppNavHost`

Полный разбор — раздел 6. Ноль строк в `src/main`. В `src/release` появляется ровно два файла, и оба продуктовые: `SystemClockProvider` и release-версия `ClockModule` (**D-16**) — не заглушки debug-экрана, а штатная реализация часов.

### D-21. Debug-диагностика — отдельный read-only снимок в `src/debug`, а не расширение репозитория

В ревизии 2 раздел 6 обещал показать на экране «`Decision` и весь `AssignmentSnapshot`», хотя `AssignmentSnapshot` строится приватным методом внутри транзакции и наружу не возвращается ни одним API. Обещание было невыполнимо без добавления в продуктовый репозиторий метода, возвращающего внутреннее состояние.

**Решение: диагностический снимок собирает сам debug-слой, своим кодом, из тех же публичных DAO.** Продуктовый `DayAssignmentRepository` остаётся с двумя методами `peek()` и `startSession()` — ни одного debug-метода в нём не появляется.

```kotlin
// app/src/debug/java/ru/poporyadku/debug/DebugDiagnostics.kt
data class DebugAssignmentView(
    val today: LocalDate,
    val activePackId: String,
    val pending: List<DayAssignmentEntity>,   // глобально, LIMIT 2
    val todayAssignment: DayAssignmentEntity?,
    val lastAssignedDate: String?,
    val maxSetIndexInActivePack: Int?,
    val setCountInActivePack: Int,
    val nextSetIndex: Int,                    // (maxSetIndexInActivePack ?: -1) + 1
)

class DebugDiagnostics @Inject constructor(
    private val db: AppDatabase,
    private val dao: AssignmentDao,
    private val sets: DailySetDao,
    private val clock: DebugClockProvider,
    @ActivePack private val activePackId: String,
) {
    suspend fun read(): DebugAssignmentView = db.withTransaction {
        val today = clock.today()
        val max = dao.maxSetIndex(activePackId)
        DebugAssignmentView(
            today = today,
            activePackId = activePackId,
            pending = dao.pendingAssignments(),
            todayAssignment = dao.byDate(today.toString()),
            lastAssignedDate = dao.lastAssignedDate(),
            maxSetIndexInActivePack = max,
            setCountInActivePack = sets.countSets(activePackId),
            nextSetIndex = (max ?: -1) + 1,
        )
    }
}
```

**Что именно показывает экран и чего он не утверждает.** Экран показывает три вещи, честно разделённые заголовками:

1. **`Decision`** — то, что вернул публичный вызов `peek()` или `startSession()`.
2. **Диагностический снимок** `DebugAssignmentView` — **отдельное чтение, выполненное после решения**, а не тот объект, который видела политика. Читается в собственной транзакции, поэтому внутренне согласован, но между решением и диагностикой состояние базы формально могло измениться. На экране это подписано строкой «снимок прочитан отдельно, после решения». На отладочном устройстве с единственным пользователем расхождение недостижимо, и притворяться, что это тот же объект, незачем.
3. **Дампы таблиц** `day_assignments`, `puzzle_attempts`, `day_results`, `daily_sets` через `Flow` — сырые строки, без интерпретации.

**Отвергнутая альтернатива — `suspend fun debugSnapshot(): AssignmentSnapshot` в `DayAssignmentRepository`.** Метод пришлось бы объявить в доменном интерфейсе, то есть он оказался бы в `src/main`, попал бы в release и стал бы частью продуктового API ради отладочного экрана, который удаляется в итерации 3. `DebugDiagnostics` живёт и умирает вместе с этим экраном.

### D-22. `room-testing` подключается в итерации 4, а не сейчас

`Room.inMemoryDatabaseBuilder` — часть `androidx.room:room-runtime` (`androidx.room.Room`), а не `room-testing`. Все тесты итерации 2 на настоящей базе (A1–A13, C1–C6) строят базу именно им и в `room-testing` не нуждаются.

Единственный необходимый проекту API из `room-testing` в обозримом плане — `MigrationTestHelper`. Он появляется вместе с **первой настоящей миграцией**, то есть в итерации 4 и позже (`ARCHITECTURE.md` §9, уровень 3; **D-8**: в итерации 2 миграций нет вовсе). Что ещё лежит в артефакте, для этого решения неважно: ни один другой его класс планом итераций 2–7 не востребован, и подключать библиотеку ради неиспользуемого содержимого незачем.

**Решение: `androidx.room:room-testing` из PR 2A, из **D-12** и из списков зависимостей удаляется.** Добавляется в итерации 4 тем же PR, который заводит `MigrationTestHelper`. Зависимость, подключённая «сразу вместе с остальной линией», — это неиспользуемая строка в каталоге версий, которую через две итерации никто не сможет обосновать и никто не решится удалить.

Остаются в 2A: `robolectric`, `androidx-test-core`, `kotlinx-coroutines-test` — все три реально используются тестами итерации 2.

### D-11. Внешних ключей нет нигде, включая связь внутри контента

Правило «нет FK из прогресса в контент» — из `ARCHITECTURE.md` §2. Про `daily_sets → puzzles` документы молчат; предлагается FK не заводить и там. Причины:

- механизм `retiredIn` прямо требует, чтобы отозванная головоломка **оставалась** в таблице и при этом переставала использоваться, — это правило контента, а не базы;
- ссылочная целостность проверяется валидатором в CI (правило 18 из `CONTENT_MODEL.md` §8), то есть *до* сборки, а не в рантайме на устройстве пользователя;
- порядок импорта в итерации 4 перестаёт иметь значение.

### D-7. Кэш серии — три ключа, а не один

**Подтверждено владельцем проекта** (раздел 13). Расширяет перечень из `IMPLEMENTATION_PLAN.md`, и это расширение принято.

Предлагаются `cached_current_streak`, `cached_best_streak` и `cached_streak_date`. Обоснование: Home по `UX_FLOW.md` §6 показывает текущую и лучшую серию рядом, поэтому кэш нужен обеим — иначе одна цифра появится мгновенно, а вторая мигнёт. Без даты пересчёта кэш после полуночи покажет вчерашнюю серию как сегодняшнюю; правило простое — если `cached_streak_date != today`, кэш игнорируется и Home ждёт базу, то есть ведёт себя как при первом запуске. Атомарность обновления тройки — **D-18**.

### D-12. Версии, добавляемые в каталог

| Библиотека / плагин | Версия | Источник и дата |
| --- | --- | --- |
| `com.google.devtools.ksp` (плагин) | **2.3.11** | Последний стабильный, 03.08.2026 — [s5] |
| `androidx.room` (плагин, `room-gradle-plugin`) | **2.8.4** | Версия совпадает с Room; подтверждено `maven-metadata.xml` артефакта `androidx.room:room-gradle-plugin` |
| `androidx.room:room-runtime` | 2.8.4 | Уже в каталоге. Включает бывшие API `room-ktx` (**D-15**) |
| `androidx.room:room-compiler` | 2.8.4 | Уже в каталоге |
| `androidx.datastore:datastore-preferences` | 1.2.1 | Уже в каталоге; подтверждено (1.3.0 существует только как alpha10) |
| `org.robolectric:robolectric` | **4.16.1** | Последний стабильный, 21.01.2026; 4.17 — только beta-4 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | **1.11.0** | Последний стабильный по `maven-metadata.xml` Maven Central |
| `androidx.test:core` | **1.7.0** | Стабильный; нужен для `ApplicationProvider` в Robolectric-тестах |
| `app.cash.turbine:turbine` | 1.2.1 | Уже в каталоге, подключается в 2C для тестов `Flow` |

**Не подключается в итерации 2:** `androidx.room:room-ktx` (пуст с 2.7 — **D-15**), группа `androidx.room3` (**D-13**), `androidx.room:room-testing` (переносится в итерацию 4 — **D-22**).

---

## 3. Схема базы: сверка с `ARCHITECTURE.md`

SQL ниже взят не из головы: это `createSql` из схемы, реально экспортированной Room Gradle Plugin в пробной сборке.

| Таблица | Ключи и ограничения в Room | Проверка |
| --- | --- | --- |
| `puzzles` | `@PrimaryKey val puzzleId: String`; поля `pack_id, category, prompt, sort_key, sort_direction, direction_label, cards_json, correct_order, explanation, sources_json, difficulty, retired_in (Int?), content_version` | **Совпадает.** `cards_json`/`sources_json` — `TEXT`, без `TypeConverter`; их разбор — итерация 4 (**D-19**) |
| `daily_sets` | `@Entity(primaryKeys = ["pack_id", "set_index"])` | **Составной PK** — как требует `ARCHITECTURE.md` и `CONTENT_MODEL.md` §7 («upsert наборов по `(packId, setIndex)`») |
| `day_assignments` | `@PrimaryKey val localDate: String` + `@Index(value = ["pack_id","set_index"], unique = true)` | **Оба ограничения на месте**, см. экспортированный SQL |
| `puzzle_attempts` | `@PrimaryKey(autoGenerate = true) val id: Long` + `@Index(value = ["local_date","slot_index"], unique = true)` | **Совпадает** |
| `day_results` | `@PrimaryKey val localDate: String`; `total_score`, `completed_count`, `is_complete: Boolean → INTEGER`, `completed_at: Long?` | **Совпадает** |

### Экспортированный SQL (фрагмент `app/schemas/ru.poporyadku.data.db.AppDatabase/1.json` пробной сборки)

```sql
CREATE TABLE IF NOT EXISTS `day_assignments` (
  `local_date`  TEXT    NOT NULL,
  `pack_id`     TEXT    NOT NULL,
  `set_index`   INTEGER NOT NULL,
  `assigned_at` INTEGER NOT NULL,
  PRIMARY KEY(`local_date`)
);
CREATE UNIQUE INDEX IF NOT EXISTS `index_day_assignments_pack_id_set_index`
  ON `day_assignments` (`pack_id`, `set_index`);

CREATE TABLE IF NOT EXISTS `puzzle_attempts` (
  `id`               INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  `local_date`       TEXT    NOT NULL,
  `slot_index`       INTEGER NOT NULL,
  `puzzle_id`        TEXT    NOT NULL,
  `submitted_order`  TEXT    NOT NULL,
  `score`            INTEGER NOT NULL,
  `submitted_at`     INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS `index_puzzle_attempts_local_date_slot_index`
  ON `puzzle_attempts` (`local_date`, `slot_index`);
```

### Три ограничения Room, которые нужно знать сразу

1. **`UNIQUE(…)` у Room — это уникальный индекс, а не табличное ограничение.** Room не умеет объявлять `UNIQUE(a, b)` внутри `CREATE TABLE`; `@Index(unique = true)` порождает `CREATE UNIQUE INDEX`. В SQLite это семантически то же самое: нарушение даёт `SQLiteConstraintException`, и продуктовое правило защищено базой ровно так, как требует `ARCHITECTURE.md`. Отличие только в тексте DDL — фиксируется здесь, чтобы при сверке схемы с документом это не выглядело расхождением.
2. **`CHECK`-ограничений Room не поддерживает вовсе.** Диапазоны `score ∈ 0..6`, `slot_index ∈ 0..2`, `total_score ∈ 0..18`, `completed_count ∈ 0..3` остаются на домене (**D-17**) и на тестах.
3. **В базе есть служебные таблицы помимо продуктовых.** Room заводит `room_master_table` (хранит `identityHash` схемы), SQLite при `AUTOINCREMENT` — `sqlite_sequence`, Android — `android_metadata`. Это учтено в тесте `DatabaseSchemaTest` (раздел 8, PR 2A).

### Пункты, которые задание просило проверить особо

| Требование | Как выполнено |
| --- | --- |
| `local_date` — PK `day_assignments` | `PRIMARY KEY(``local_date``)` — подтверждено экспортом. Ключ **глобальный**, а не `(pack_id, local_date)`: на календарную дату приходится не более одного назначения во всей системе, к какому бы пакету оно ни относилось. Из этого следует разделение запросов на глобальные и pack-scoped (**D-20**) |
| `UNIQUE(pack_id, set_index)` | `CREATE UNIQUE INDEX index_day_assignments_pack_id_set_index` |
| `UNIQUE(local_date, slot_index)` | `CREATE UNIQUE INDEX index_puzzle_attempts_local_date_slot_index` |
| Составной PK `daily_sets` | `@Entity(primaryKeys = ["pack_id", "set_index"])` |
| Нет FK из таблиц прогресса в контент | Ни одной `@ForeignKey` во всей схеме — см. **D-11** |
| ISO `yyyy-MM-dd` для дат | `TEXT`; в коде — `LocalDate.toString()` / `LocalDate.parse()` в мапперах. Побочный выигрыш: строковое сравнение ISO-дат совпадает с хронологическим, поэтому `MAX(local_date)` и условие «только вперёд» пишутся прямо в SQL |
| Epoch millis для временных меток | `INTEGER`: `assigned_at`, `submitted_at`, `completed_at` — все `Long` из `TimeSnapshot.epochMillis` (**D-16**) |
| Экспорт схемы в `app/schemas` | `@Database(exportSchema = true)` + Room Gradle Plugin с `room { schemaDirectory("$projectDir/schemas") }` (**D-14**). Каталог коммитится целиком — он и есть база для `MigrationTestHelper`. Под `.gitignore` не попадает: там игнорируются только `build/` и `app/build/` |

---

## 4. Перенос отложенного назначения: алгоритм и границы транзакции

Реализация не пишется. Ниже — запросы, границы транзакции и таблица «требование → механизм → тест».

### Запросы DAO

Разделение на глобальные и pack-scoped запросы — решение **D-20**; здесь оно записано в SQL.

```kotlin
@Dao
interface AssignmentDao {

    // ---------- ГЛОБАЛЬНЫЕ: инварианты пользователя, а не пакета ----------

    /** Отложенное назначение — то, по которому нет ни одной попытки.
     *  БЕЗ фильтра по pack_id: «не более одного отложенного» — глобальный инвариант
     *  (UX_FLOW.md §9). С фильтром две отложенные строки разных пакетов выглядели бы
     *  как одна, и require() в decide() не поймал бы нарушение.
     *  LIMIT 2, а не 1: без второй строки нарушение невозможно обнаружить. */
    @Query("""
        SELECT a.* FROM day_assignments a
        WHERE NOT EXISTS (
            SELECT 1 FROM puzzle_attempts t WHERE t.local_date = a.local_date
        )
        ORDER BY a.local_date
        LIMIT 2
    """)
    suspend fun pendingAssignments(): List<DayAssignmentEntity>

    /** БЕЗ фильтра по pack_id: local_date — первичный ключ таблицы,
     *  на дату приходится ровно одна строка, какому бы пакету она ни принадлежала. */
    @Query("SELECT * FROM day_assignments WHERE local_date = :date")
    suspend fun byDate(date: String): DayAssignmentEntity?

    /** БЕЗ фильтра по pack_id: защита «только вперёд» не должна обходиться
     *  переключением активного пакета. */
    @Query("SELECT MAX(local_date) FROM day_assignments")
    suspend fun lastAssignedDate(): String?

    // ---------- PACK-SCOPED: последовательность внутри пакета ----------

    /** С фильтром: у каждого пакета собственная последовательность set_index
     *  (CONTENT_MODEL.md §7). */
    @Query("SELECT MAX(set_index) FROM day_assignments WHERE pack_id = :packId")
    suspend fun maxSetIndex(packId: String): Int?

    // ---------- запись ----------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(assignment: DayAssignmentEntity)

    /** Перенос найденного отложенного назначения. Ни одной вставки; set_index и pack_id
     *  не в SET, поэтому переносимая строка сохраняет и пакет, и индекс.
     *  :packId приходит из Decision.CarryOver, то есть из самой строки, а НЕ из активного
     *  пакета: иначе перенос переписал бы pack_id чужой строки.
     *  Условие :today > :pendingDate делает перенос назад невозможным
     *  даже при ошибке в вызывающем коде. */
    @Query("""
        UPDATE day_assignments
           SET local_date = :today, assigned_at = :now
         WHERE local_date = :pendingDate
           AND pack_id = :packId
           AND :today > :pendingDate
    """)
    suspend fun carryOver(packId: String, pendingDate: String, today: String, now: Long): Int

    @Query("SELECT * FROM day_assignments ORDER BY local_date DESC")
    fun observeAll(): Flow<List<DayAssignmentEntity>>
}
```

`pack_id` в `WHERE` у `carryOver` — не фильтрация выборки, а **проверка**: строка на дату `:pendingDate` уже единственна по первичному ключу, а совпадение `pack_id` подтверждает, что переносится именно та строка, которую увидела политика. Если пакет не совпал, `UPDATE` вернёт 0 и `check(rows == 1)` откатит транзакцию вместо тихой правки чужой строки.

Сравнение `:today > :pendingDate` корректно именно потому, что даты хранятся как ISO-строки: лексикографический порядок `yyyy-MM-dd` совпадает с хронологическим. Это второй, независимый от Kotlin-кода рубеж защиты правила «только вперёд».

### Сборка снимка и границы транзакции

```kotlin
class DayAssignmentRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val dao: AssignmentDao,
    private val sets: DailySetDao,
    private val clock: ClockProvider,
    @ActivePack private val activePackId: String,          // D-20; в проде — ContentPack.CORE_RU
) : DayAssignmentRepository {

    // --- весь публичный API. Ни даты в параметрах, ни debug-методов (D-16, D-21) ---

    override suspend fun peek(): Decision {
        val time = clock.now()                             // один Instant, до транзакции
        return db.withTransaction {
            SetAssignmentPolicy.decide(time.localDate, snapshot(time.localDate))
        }
    }

    override suspend fun startSession(): Decision {
        val time = clock.now()                             // один Instant, до транзакции
        return db.withTransaction {
            val decision = SetAssignmentPolicy.decide(time.localDate, snapshot(time.localDate))
            when (decision) {
                is Decision.NewSet -> dao.insert(
                    DayAssignmentEntity(
                        localDate = time.localDate.toString(),
                        packId = decision.packId,          // из решения, не из поля класса
                        setIndex = decision.setIndex,
                        assignedAt = time.epochMillis,
                    )
                )
                is Decision.CarryOver -> {
                    val rows = dao.carryOver(
                        packId = decision.packId,          // пакет ПЕРЕНОСИМОЙ строки (D-20)
                        pendingDate = decision.fromDate.toString(),
                        today = time.localDate.toString(),
                        now = time.epochMillis,
                    )
                    check(rows == 1) { "перенос затронул $rows строк" }   // откат транзакции
                }
                is Decision.Assigned,
                Decision.AwaitingNextDay,
                Decision.ContentExhausted -> Unit                          // ни одной записи
            }
            decision
        }
    }

    /**
     * Пять чтений одного согласованного состояния. Вызывается только внутри транзакции.
     * Дата приходит параметром: clock.now() внутри транзакции не вызывается ни разу,
     * иначе todayAssignment читался бы по дате, отличной от переданной в decide (D-16).
     */
    private suspend fun snapshot(today: LocalDate): AssignmentSnapshot = AssignmentSnapshot(
        pendingAssignments = dao.pendingAssignments().map { it.toDomain() },   // глобально
        todayAssignment = dao.byDate(today.toString())?.toDomain(),            // глобально
        lastAssignedDate = dao.lastAssignedDate()?.let(LocalDate::parse),      // глобально
        activePackId = activePackId,
        maxSetIndexInActivePack = dao.maxSetIndex(activePackId),               // pack-scoped
        setCountInActivePack = sets.countSets(activePackId),                   // pack-scoped
    )
}
```

**Почему `clock.now()` вызывается ровно один раз и до транзакции.** Дата участвует в решении трижды: в `decide(today, …)`, в чтении `byDate(today)` и в записи `local_date`. Второй вызов `clock.now()` внутри транзакции означал бы, что политика решает про одну дату, а снимок прочитан про другую — редкий, невоспроизводимый дефект ровно того класса, ради которого заведён `TimeSnapshot`. Поэтому `snapshot()` принимает `today` параметром, а не обращается к часам: ни одного `clock.now()` внутри `withTransaction` во всём файле.

**Почему снимок собирается внутри транзакции.** Пять запросов, выполненных вне транзакции, могут увидеть пять разных состояний базы: между чтением `pendingAssignments` и `maxSetIndex` другая корутина успеет записать попытку, и политика примет решение по несуществующему состоянию. Внутри `withTransaction` все пять читают одно состояние.

**Почему решение и запись — в одной транзакции.** Между ними не должно быть окна: иначе два вызова `startSession` (двойное нажатие «Играть», восстановление процесса) могут оба увидеть `pending` и оба выполнить перенос. `withTransaction` вдобавок удерживает все вложенные вызовы DAO на одном соединении.

**Почему время берётся один раз.** `TimeSnapshot` получен из одного `Instant` до открытия транзакции и дальше используется целиком: `local_date` и `assigned_at` не могут разойтись через полночь (**D-16**).

**Почему `check(rows == 1)`, а не игнорирование результата.** `UPDATE`, не затронувший ни одной строки, — это тихий отказ: пользователь получил бы экран без задания и без ошибки. Исключение внутри `withTransaction` откатывает транзакцию и поднимается наверх как `Home.Error` с «Повторить», что предусмотрено `UX_FLOW.md` §11.

Важно, чего этот `check` **не** делает. В штатном сценарии перевода даты назад он не срабатывает никогда: политика возвращает `AwaitingNextDay`, ветка `CarryOver` не выбирается, и `dao.carryOver` не вызывается вовсе (тест A5). `check` — реакция на состояние, которого при исправном коде не бывает: пакет решения не совпал с пакетом строки, строка исчезла между чтением снимка и записью, либо в вызывающий код внесена ошибка, обошедшая ветку политики. Условие `AND :today > :pendingDate` в SQL проверяется отдельным DAO-тестом A13 — как самостоятельный рубеж, а не как путь, по которому ходит продукт.

### Требование → механизм → тест

| Требование из задания | Чем гарантировано | Тест |
| --- | --- | --- |
| Назначение без попыток переносится `UPDATE`-ом существующей строки | `carryOver` — единственный способ изменить `local_date`; `insert` вызывается только в ветке `NewSet` | A2 |
| `set_index` не изменяется | `set_index` отсутствует в `SET` | A2, A11 |
| `pack_id` переносимой строки не изменяется | `pack_id` отсутствует в `SET`; в `WHERE` подставляется `Decision.CarryOver.packId`, а не активный пакет | A11 |
| Перенос разрешён только вперёд | Два независимых рубежа: ветка `AwaitingNextDay` в политике (штатный путь — до записи дело не доходит) **и** `AND :today > :pendingDate` в SQL (страховка на случай ошибки в вызывающем коде) | A5 — первый рубеж, A13 — второй |
| Вторая строка не создаётся | `UPDATE` не вставляет; ветка `NewSet` недостижима, пока `pendingAssignments` непуст; `UNIQUE(pack_id, set_index)` — страховка на случай ошибки | A2 |
| При наличии хотя бы одной попытки прошлый день не переносится | `NOT EXISTS (… puzzle_attempts …)` в самом определении «отложенного» | A4 |
| Следующий день получает N + 1 | `next = (maxSetIndexInActivePack ?: -1) + 1` | A4 |
| Одновременно не более одного назначения без попыток — **глобально** | `pendingAssignments()` без фильтра по пакету; `NewSet` только при пустом списке; `CarryOver` строк не добавляет; `require(size <= 1)` в начале `decide` фиксирует нарушение явно | A6, A9, P9 |
| На одну дату не бывает двух назначений, даже из разных пакетов | `PRIMARY KEY(local_date)` в схеме; `byDate(date)` глобальный, поэтому политика возвращает `Assigned` чужого пакета вместо попытки вставить вторую строку | A10, P14 |
| Перевод даты назад возвращает `AwaitingNextDay` | `today < pending.localDate → AwaitingNextDay`; при отсутствии отложенного — `today <= lastAssignedDate → AwaitingNextDay`, где `lastAssignedDate` глобальный | A5, A7 |
| Переключение активного пакета не обходит защиту «только вперёд» | `lastAssignedDate()` без фильтра по пакету: смена пакета не обнуляет её | A12, P13 |
| Отложенное назначение прежнего пакета разбирается до выдачи набора активного | Ветка `pending` в `decide` стоит первой; ветка `NewSet` — последней | A11, P12 |
| Повторный запуск в тот же день идемпотентен | `pending.localDate == today → Assigned`, либо `todayAssignment != null → Assigned`; обе ветки не пишут ничего, включая `assigned_at` | A3 |

### Матрица тестов

| Код | Сценарий | Где |
| --- | --- | --- |
| P1 | Пустой снимок, `setCountInActivePack > 0` → `NewSet(activePackId, 0)` | Чистый JVM: `AssignmentSnapshot` конструируется напрямую, `decide` синхронна |
| P2 | Назначение на сегодня → `Assigned`, тот же индекс | " |
| P3 | Пропуск недели → `NewSet(N + 1)`, не `N + 7` | " |
| P4 | `today < lastAssignedDate` → `AwaitingNextDay` | " |
| P5 | «Туда-обратно» за одни реальные сутки → второго набора нет | " |
| P6 | `next >= setCountInActivePack` → `ContentExhausted` | " |
| P7 | Отложенное в будущем → `AwaitingNextDay` | " |
| P8 | `setCountInActivePack == 0` (пустая база контента) → `ContentExhausted` | " |
| **P9** | Два отложенных назначения в снимке → `decide` бросает `IllegalArgumentException`, а не берёт первое | " |
| P10 | `TimeSnapshot`, построенный из одного `Instant`, даёт согласованные `localDate` и `epochMillis` для граничного момента (23:59:59.999 и 00:00:00.000 в заданной зоне) | " |
| **P11** | `TimeSnapshot.of(date.atTime(NOON).atZone(zone).toInstant(), zone).localDate == date` для зоны с летним переходом в день перехода — полдень однозначен там, где полночь может не существовать (**D-16**) | " |
| **P12** | Отложенное назначение пакета `A` при `activePackId = "B"` → `CarryOver(packId = "A", setIndex = A.setIndex)`, **не** `NewSet("B", 0)`: пакет из решения, не из активного | " |
| **P13** | Отложенного нет, `lastAssignedDate == today`, `activePackId = "B"`, `maxSetIndexInActivePack = null`, `setCountInActivePack = 5` → `AwaitingNextDay`. Переключение пакета не даёт второго набора за календарные сутки | " |
| **P14** | `todayAssignment` принадлежит пакету `A` при `activePackId = "B"` → `Assigned(packId = "A", …)`, ни одной попытки выдать набор `B` на ту же дату | " |
| **P15** | Отложенного нет, `lastAssignedDate` в прошлом, `activePackId = "B"` без назначений → `NewSet("B", 0)`: `maxSetIndex` пакета `A` на индекс пакета `B` не влияет | " |
| A1 | День 1, старт сессии, попыток нет → в базе одна строка `(день 1, N)` | Robolectric, `inMemoryDatabaseBuilder`, репозиторий с `FakeClockProvider` |
| A2 | День 2, старт переносит **ту же** строку: `set_index == N`, строк по-прежнему одна, исключения по `UNIQUE(pack_id, set_index)` нет | " |
| A3 | Повторный старт в день 2 → тот же `N`, новой строки нет, `assigned_at` не изменился | " |
| A4 | В день 1 сделана одна попытка → в день 2 выдаётся `N + 1`, день 1 остаётся в архиве незавершённым | " |
| A5 | **Продуктовый сценарий перевода даты назад.** Отложенное `(D, N)`, часы переведены на `D − 1`. `startSession()` возвращает `AwaitingNextDay`; `dao.carryOver` **не вызывается вовсе** (проверяется DAO-шпионом или тем, что строка не изменилась ни в одном поле, включая `assigned_at`); исключения нет. Ветка `today < pending.localDate` в политике срабатывает раньше, чем дело доходит до записи | " |
| A6 | Инвариант `pendingAssignments().size <= 1` (глобальный запрос) после каждого шага A1–A4 | " |
| A7 | «Туда-обратно» на настоящей базе: назад, вперёд, назад — ни одного лишнего набора | " |
| A8 | Снимок собран внутри транзакции: пять прочитанных из базы значений согласованы между собой на подготовленном состоянии базы (шестое поле, `activePackId`, не читается — оно приходит из DI) | " |
| **A9** | Строки двух пакетов: вручную вставлены `(2026-09-01, pack-a, 0)` и `(2026-09-02, pack-b, 0)`, попыток нет ни по одной. `pendingAssignments()` возвращает **две** строки, и `startSession()` падает с `IllegalArgumentException` из `require`. Подтверждает, что pending — глобальный: с фильтром по пакету запрос вернул бы одну строку и нарушение осталось бы невидимым | " |
| **A10** | На дату `D` есть назначение `(D, pack-a, 3)`. Прямой `insert((D, pack-b, 0))` отбивается `SQLiteConstraintException` по `PRIMARY KEY(local_date)`. Далее: `startSession()` репозитория с `activePackId = "pack-b"` в дату `D` возвращает `Assigned(packId = "pack-a", setIndex = 3)` и не создаёт второй строки | " |
| **A11** | Отложенное `(D−1, pack-a, 3)`, репозиторий с `activePackId = "pack-b"`, `startSession()` в дату `D` → строк по-прежнему одна: `local_date = D`, **`pack_id = "pack-a"`, `set_index = 3`**; строк пакета `pack-b` в таблице ноль. Перенос сохранил и пакет, и индекс исходного назначения | " |
| **A12** | Все назначения `pack-a` израсходованы, `MAX(local_date) == D`, репозиторий пересоздан с `activePackId = "pack-b"`, в `daily_sets` пять наборов `pack-b`. `startSession()` в дату `D` → `AwaitingNextDay`, ни одной новой строки. Переключение пакета не обошло `lastAssignedDate` | " |
| **A13** | **SQL-рубеж «только вперёд», отдельно от политики.** Тест уровня DAO, без репозитория: строка `(D, pack-a, N)`, прямой вызов `dao.carryOver(packId = "pack-a", pendingDate = D, today = D − 1, now = …)` возвращает **0**, строка остаётся на месте со старыми `local_date` и `assigned_at`. Проверяет условие `AND :today > :pendingDate` само по себе — второй рубеж, который держит правило, даже если ошибка появится в вызывающем коде | " |
| C1 | Повтор `(local_date, slot_index)` → `SQLiteConstraintException` | Robolectric, ограничения и агрегаты |
| C2 | Повторный `insert` с тем же `local_date` в `day_assignments` отбит | " |
| C3 | Повторный `insert` с тем же `(pack_id, set_index)` отбит | " |
| C4 | `day_results` после 1, 2 и 3 попыток: `total_score` до 18, `completed_count`, `is_complete`, `completed_at` только при трёх | " |
| C5 | Отбитая по ограничению попытка не портит `day_results` — транзакция откатилась целиком | " |
| **C6** | `recordAttempt` с `slotIndex = 3` и с `score = 7` бросает `IllegalArgumentException` и ничего не пишет в базу (**D-17**) | " |
| **T1** | `updateStreakCache(current = -1, best = 0, date)` → `IllegalArgumentException`, хранилище не изменилось (**D-18**) | Robolectric, `TemporaryFolder`, Turbine |
| **T2** | `updateStreakCache(current = 5, best = 3, date)` → `IllegalArgumentException`, хранилище не изменилось | " |
| **T3** | В хранилище записана несогласованная тройка `current = 5, best = 3` → читается `StreakCache.EMPTY` целиком, а не «`current = 5`, дата `null`» | " |
| **T4** | `cached_streak_date` содержит `"не-дата"` при `current = 4` → читается `StreakCache.EMPTY`; после этого чтения хранилище **не изменилось** (сброс только на чтении) | " |
| **T5** | `stored_content_version = -3` → читается `0` | " |
| **T6** | `IOException` из потока `dataStore.data` → эмитятся значения по умолчанию | " |
| **T7** | Не-`IOException` из потока (подставленный `RuntimeException`) **пробрасывается** наружу, а не подменяется значениями по умолчанию | " |
| **T8** | Атомарность `updateStreakCache`: Turbine видит переход одним элементом — состояния «дата новая, серия старая» нет ни в одной эмиссии | " |
| **T9** | `setStoredContentVersion(-1)` → `IllegalArgumentException`, хранилище не изменилось: прежнее значение ключа на месте, `edit` не выполнялся (**D-18**) | " |
| **T10** | `setLastSeenDate(null)` удаляет ключ: чтение даёт `lastSeenDate == null`, то есть ровно то же состояние, что на пустом хранилище | " |
| **P16** | `DebugClockProvider` **без фиксации** (после `reset()`): `clock().zone` меняется вслед за `TimeZone.setDefault(…)`, а после `setDate(d, zone)` — перестаёт, потому что часы зафиксированы. Исходная зона восстанавливается в `finally` (**D-16**) | Чистый JVM, **`src/testDebug`** — `DebugClockProvider` виден только debug-варианту; `testDebugUnitTest` |
| **P17** | `SystemClockProvider().clock().zone` меняется вслед за `TimeZone.setDefault(…)`: зона не закэширована в поле синглтона (**D-16**). Исходная зона восстанавливается в `finally` | Чистый JVM, **`src/testRelease`** — `SystemClockProvider` виден только релизному варианту; `testReleaseUnitTest`. Один экземпляр провайдера на весь тест, созданный до первой смены зоны |

---

## 5. Пустая база контента и debug-фикстура

Задача: дать debug-экрану чем проверять выдачу, не заводя ни одного пути, по которому тестовые данные могли бы попасть в релиз или в production-базу.

```kotlin
// app/src/debug/java/ru/poporyadku/debug/DebugContentFixture.kt
class DebugContentFixture @Inject constructor(private val sets: DailySetDao) {

    suspend fun install(setCount: Int = 5) = sets.upsertAll(
        (0 until setCount).map { i ->
            DailySetEntity(
                packId    = ContentPack.CORE_RU,
                setIndex  = i,
                puzzleId1 = "debug-fixture-%03d".format(i * 3 + 1),
                puzzleId2 = "debug-fixture-%03d".format(i * 3 + 2),
                puzzleId3 = "debug-fixture-%03d".format(i * 3 + 3),
            )
        }
    )
}
```

| Требование задания | Чем обеспечено |
| --- | --- |
| Фикстура не попадает в release | Файл физически лежит в `app/src/debug/`. Этот source set не входит в вариант `release` — `assembleRelease` его не компилирует и не видит. Проверяется механически: `assembleRelease` в CI (добавляется в 2C) плюс `grep -r DebugContentFixture app/src/main`, который обязан быть пустым |
| Production-база остаётся пустой | Единственная точка вызова — кнопка «Залить фикстуру» на debug-экране. Ни `Application.onCreate`, ни `RoomDatabase.Callback.onCreate`, ни `createFromAsset`/`prepopulate` не участвуют. Это сознательный отказ от самого удобного механизма Room: `addCallback` сработал бы автоматически и на устройстве пользователя, если бы код когда-нибудь просочился в `main` |
| Будущий `ContentImporter` не зависит от debug-данных | Фикстура пишет через тот же публичный `DailySetDao.upsertAll` и теми же ключами `(pack_id, set_index)`, что и импортёр итерации 4. Для остальной системы это просто строки в таблице — источник неотличим и не важен. Обратной зависимости нет по построению: `data/content/**` живёт в `main` и о `src/debug` знать не может физически |
| Фикстура не оставляет мусора к итерации 4 | Пишет только в `daily_sets`: политике выдачи нужны наборы, а не головоломки, и FK между `daily_sets` и `puzzles` мы не заводим (**D-11**). Маппер `DailySet` не требует JSON и в 2A уже есть (**D-19**). При импорте настоящего контента строки `(core-ru, 0…4)` перезаписываются upsert-ом. Дополнительно на debug-экране есть кнопка «Очистить базу» (`db.clearAllTables()`) |

**Отвергнутая альтернатива:** отдельный `packId = "debug"`. Он потребовал бы менять значение активного пакета на устройстве в рантайме — то есть добавить настройку ради отладки. Использование настоящего `core-ru` честнее: фикстура имитирует ровно то, что произведёт импортёр, и потому проверяет тот же код, а не соседний. Дополнительный довод от **D-20**: отладочный пакет рядом с рабочим создал бы на устройстве ровно то состояние «по отложенному назначению на пакет», которое глобальные инварианты запрещают, и отладчик наблюдал бы поведение, невозможное у пользователя.

Активный пакет при этом остаётся параметром конструктора репозитория (**D-20**) с единственным значением из DI. Это не противоречит сказанному выше: параметр не настраивается на устройстве и не имеет UI — он существует, чтобы решения политики несли `packId` явно и чтобы тесты A10–A12 могли построить состояние двух пакетов.

---

## 6. Debug-экран без следа в release

### Размещение — отдельная Activity с собственным launcher-значком

```
app/src/debug/AndroidManifest.xml            ← объявляет DebugActivity
app/src/debug/java/ru/poporyadku/core/time/
    DebugClockProvider.kt     управляемые часы, реализуют ClockProvider и DateProvider (D-16)
app/src/debug/java/ru/poporyadku/di/
    ClockModule.kt            @Binds ClockProvider → DebugClockProvider (D-16)
app/src/debug/java/ru/poporyadku/debug/
    DebugActivity.kt          @AndroidEntryPoint, setContent { DebugScreen() }
    DebugScreen.kt            Compose, без дизайн-токенов — это инструмент, не экран продукта
    DebugViewModel.kt         @HiltViewModel
    DebugSessionController.kt ставит дату в DebugClockProvider и зовёт публичный API (D-16)
    DebugDiagnostics.kt       read-only снимок из DAO, DebugAssignmentView (D-21)
    DebugContentFixture.kt
app/src/debug/res/values/strings.xml
```

Парный ему release-вариант — два файла, и оба продуктовые:

```
app/src/release/java/ru/poporyadku/core/time/SystemClockProvider.kt
app/src/release/java/ru/poporyadku/di/ClockModule.kt      @Binds ClockProvider → SystemClockProvider
```

```xml
<!-- app/src/debug/AndroidManifest.xml -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <activity android:name="ru.poporyadku.debug.DebugActivity"
                  android:exported="true"
                  android:label="@string/debug_activity_label">
            <intent-filter>
                <action   android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

Manifest merger добавляет Activity только в debug-вариант. На отладочном устройстве появляется второй значок, ведущий прямо в инструмент. В `src/main` при этом **ноль строк**: ни маршрута в `AppNavHost`, ни константы в `Destinations`, ни заглушки. В `src/release` — ни одной строки отладочного кода; два файла там (`SystemClockProvider`, `ClockModule`) продуктовые и остаются после удаления debug-экрана в итерации 3.

### Отвергнутые варианты

- **Маршрут `debug` в `AppNavHost`, скрытый за `BuildConfig.DEBUG`.** Требует, чтобы composable-функция экрана существовала в обоих вариантах, то есть заглушки в `src/release`. Это ровно то дублирование, которого просили избежать, и оно останется в истории кода после удаления экрана в итерации 3.
- **Параметр `today` в продуктовом `StartDailySessionUseCase`.** Отвергнут решением **D-16**: подстановку даты обеспечивает debug-реализация `ClockProvider`, а публичный API остаётся без параметра.
- **`internal`-шов `peekAt`/`startSessionAt` в `DayAssignmentRepositoryImpl`** (вариант ревизии 2). Отвергнут решением **D-16**: `internal` в Kotlin ограничивает видимость Gradle-модулем, а модуль здесь один, поэтому шов был бы виден всему приложению и присутствовал бы в релизной сборке.
- **Debug-метод в продуктовом `DayAssignmentRepository`, возвращающий `AssignmentSnapshot`.** Отвергнут решением **D-21**: доменный интерфейс лежит в `src/main` и попал бы в release. Диагностику собирает `DebugDiagnostics` в `src/debug`.

### Что умеет экран — по критериям приёмки итерации 2

| Элемент | Какой критерий закрывает |
| --- | --- |
| Поле даты + кнопки «−1 день» / «+1 день»; выбранная дата уходит в `DebugSessionController.setDate` → `DebugClockProvider` | «Подмена даты на завтра выдаёт следующий по порядку набор»; «пропуск недели выдаёт следующий, а не восьмой»; «перевод даты назад даёт `AwaitingNextDay`» |
| «Показать решение» → `DebugSessionController.peekAt(date)`, выводит **`Decision`** | Отличить `ContentExhausted` из-за пустой базы от ошибки |
| Блок «Диагностика» → `DebugDiagnostics.read()`, выводит поля `DebugAssignmentView` под заголовком «снимок прочитан отдельно, после решения» (**D-21**) | Увидеть `pendingAssignments` (глобально, до двух строк), `lastAssignedDate`, `maxSetIndexInActivePack`, `setCountInActivePack` и вычисленный `nextSetIndex` глазами |
| «Начать сессию» → `DebugSessionController.startSessionAt(date)` | «Назначение без попыток переносится на новую дату с тем же `set_index` и без ошибки» |
| «Записать попытку»: слот 0–2, балл 0–6 | «Запись попытки обновляет `day_results` в той же транзакции»; «повторная запись в тот же `(local_date, slot_index)` отбивается ограничением базы» |
| Дампы `day_assignments` (с колонками `pack_id` и `set_index`), `puzzle_attempts`, `day_results`, `daily_sets` через `Flow` | «В базе в любой момент не более одного назначения без попыток» — видно глазами, а не только в тесте; видно и то, что дата не повторяется между пакетами |
| Дамп `UserPreferences` + переключатели каждого ключа; тройка кэша серии — одним полем ввода «current / best / date» и одной кнопкой, вызывающей `updateStreakCache` | «Настройки переживают перезапуск, изменения приходят через `Flow`». Раздельных кнопок для трёх ключей нет — их нет и в API (**D-18**) |
| «Залить фикстуру (5 наборов)» / «Очистить базу» | Предпосылка для всего перечисленного выше |

Ни один элемент экрана не обращается к `DayAssignmentRepositoryImpl` напрямую: `DebugSessionController` работает через интерфейс `DayAssignmentRepository`, `DebugDiagnostics` — через публичные DAO. Продуктовый репозиторий debug-методов не получает (**D-21**).

---

## 7. DataStore: полный перечень ключей

Один файл `poporyadku_prefs`, один `Flow<UserPreferences>` наружу. Модель `UserPreferences` и `ThemeMode` — в **`core/model`**, интерфейс `UserPreferencesRepository` — в `domain/repository`, `Preferences.Key` и сам `DataStore` — только в `data/prefs` (**D-18**).

| Ключ | Тип в домене | Тип в DataStore | По умолчанию | Откуда значение |
| --- | --- | --- | --- | --- |
| `sound_enabled` | `Boolean` | `Boolean` | `true` | `UX_FLOW.md` §8: звук — обычная часть отклика, выключается сознательно |
| `vibration_enabled` | `Boolean` | `Boolean` | `true` | `UX_FLOW.md` §10: тактильный отклик на перемещение — часть доступности |
| `reminder_enabled` | `Boolean` | `Boolean` | **`false`** | `UX_FLOW.md` §2: `POST_NOTIFICATIONS` не запрашивается при первом запуске; включение — осознанное действие после первого завершённого дня |
| `reminder_minute_of_day` | `LocalTime` | `Int` | `540` (09:00) | `UX_FLOW.md` §8; представление — **D-6**; диапазон `0..1439` валидируется на чтении и на записи — **D-18** |
| `theme_mode` | `ThemeMode` | `String` | `"SYSTEM"` | `UX_FLOW.md` §8. Неизвестное имя читается как `SYSTEM` — **D-18** |
| `stored_content_version` | `Int` | `Int` | `0` | `CONTENT_MODEL.md` §7. Ноль означает «ничего не импортировано»: любой настоящий `contentVersion` ≥ 1, поэтому первый запуск всегда попадает в ветку импорта. Отрицательное значение читается как `0` — **D-18** |
| `has_seen_drag_hint` | `Boolean` | `Boolean` | `false` | `UX_FLOW.md` §2 |
| `has_seen_scoring_hint` | `Boolean` | `Boolean` | `false` | `UX_FLOW.md` §5 |
| `has_completed_first_day` | `Boolean` | `Boolean` | `false` | `UX_FLOW.md` §2 |
| `notification_prompt_shown` | `Boolean` | `Boolean` | `false` | `UX_FLOW.md` §2: «Отказ не повторяем» — флаг однократный, не счётчик |
| `last_seen_date` | `LocalDate?` | `String` ISO | ключа нет или дата невалидна → `null` | `UX_FLOW.md` §9: строка «Вчера день остался незавершённым» и перепланирование напоминания |
| `cached_current_streak` | `StreakCache.current` | `Int` | `0` | ADR-005: только кэш для мгновенного показа, источник истины — `day_results` |
| `cached_best_streak` | `StreakCache.best` | `Int` | `0` | `UX_FLOW.md` §6 показывает лучшую рядом с текущей — **D-7** |
| `cached_streak_date` | `StreakCache.date` | `String` ISO | ключа нет → `null` | **D-7**: при `cached_streak_date != today` кэш игнорируется |

Три последних ключа **не являются тремя настройками**. В домене это одно значение `StreakCache`; в интерфейсе репозитория — одна операция записи `updateStreakCache(current, best, date)` с `require(current >= 0)` и `require(best >= current)`, а на чтении несогласованная тройка сбрасывается целиком в `current = 0, best = 0, date = null`. Отдельных публичных сеттеров у них нет (**D-18**).

Отдельно: `lastInterstitialDate` из `AdFrequencyPolicy` (`ARCHITECTURE.md` §8) в этот перечень **не входит** — он появляется в итерации 7 вместе с контуром монетизации.

---

## 8. План PR

| PR | Цель | Оценка |
| --- | --- | --- |
| **2A** | База создаётся и открывается, схема экспортируется и коммитится, KSP и Room Gradle Plugin работают рядом с legacy-kapt, DI отдаёт DAO. Ни строки логики выдачи | 0,5–1 день |
| **2B** | Последовательная выдача работает и покрыта тестами: снимок, чистая политика, перенос, внедряемое время, `ProgressRepository`. UI по-прежнему нет | 1,5 дня |
| **2C** | Настройки на DataStore, debug-экран и фикстура. Критерии приёмки итерации 2 проверяются руками на устройстве | 1 день |

### PR 2A — domain-модели, Room, DAO, `AppDatabase`, DI, экспорт схемы

**Цель.** Работающая база с полной схемой версии 1 и подтверждённая работоспособность связки «KSP + Room Gradle Plugin для Room, legacy-kapt для Hilt». Никакой бизнес-логики.

**Создаваемые файлы**

```
app/src/main/java/ru/poporyadku/core/model/
    Category.kt  SortDirection.kt  Card.kt  Puzzle.kt  DailySet.kt
    DayAssignment.kt  PuzzleAttempt.kt  DayResult.kt  ContentPack.kt
app/src/main/java/ru/poporyadku/data/db/
    AppDatabase.kt
    entity/PuzzleEntity.kt  DailySetEntity.kt  DayAssignmentEntity.kt
           PuzzleAttemptEntity.kt  DayResultEntity.kt
    dao/PuzzleDao.kt  DailySetDao.kt  AssignmentDao.kt  AttemptDao.kt  DayResultDao.kt
    mapper/ProgressMappers.kt      DayAssignment / PuzzleAttempt / DayResult
    mapper/DailySetMapper.kt       плоские поля, без JSON
app/src/main/java/ru/poporyadku/di/DatabaseModule.kt
app/schemas/ru.poporyadku.data.db.AppDatabase/1.json        (генерируется, коммитится)
app/src/test/resources/robolectric.properties
app/src/test/java/ru/poporyadku/data/db/DatabaseSchemaTest.kt      (C1–C3 + состав таблиц)
```

**Мапперы, которых в 2A нет:** `PuzzleEntity ↔ Puzzle` — переносится в итерацию 4 вместе с `kotlinx-serialization` (**D-19**). Сущность и доменная модель существуют, преобразования между ними — нет.

**Изменяемые файлы**

```
gradle/libs.versions.toml   версии: ksp, robolectric, coroutinesTest, androidxTestCore;
                            библиотеки: robolectric, androidx-test-core,
                            kotlinx-coroutines-test; плагины: ksp и room.
                            БЕЗ room-testing (D-22)
build.gradle.kts            alias(libs.plugins.ksp) apply false
                            alias(libs.plugins.room) apply false
app/build.gradle.kts        плагины ksp и room; зависимости Room (БЕЗ room-ktx, БЕЗ room-testing);
                            верхнеуровневый блок room { schemaDirectory("$projectDir/schemas") }
                            рядом с android { }, а не внутри него (D-14);
                            testOptions.unitTests { isIncludeAndroidResources; jvmArgs }
.github/workflows/ci.yml    кэш ~/.m2/repository/org/robolectric
docs/VERSIONS.md            новые строки; переписанная строка Room (D-13)
```

**Зависимости Gradle**

```kotlin
implementation(libs.androidx.room.runtime)      // включает бывшие API room-ktx (D-15)
                                                // и Room.inMemoryDatabaseBuilder (D-22)
ksp(libs.androidx.room.compiler)
testImplementation(libs.robolectric)
testImplementation(libs.androidx.test.core)
testImplementation(libs.kotlinx.coroutines.test)
```

`androidx.room:room-testing` в этот список **не входит**: `Room.inMemoryDatabaseBuilder` живёт в `room-runtime`, а единственный потребитель `room-testing` — `MigrationTestHelper` — появляется в итерации 4 (**D-22**).

**Основные классы и ответственность**

- `AppDatabase` — `@Database(version = 1, exportSchema = true)`, пять сущностей, пять DAO. Ни одного `TypeConverter`.
- `*Entity` — форма хранения, только примитивы и `String`. `LocalDate`, `Category`, `SortDirection` в Room не проникают.
- `mapper/ProgressMappers.kt`, `mapper/DailySetMapper.kt` — преобразования entity ↔ домен для всего, что не требует JSON, включая `LocalDate.parse`/`toString`.
- `DatabaseModule` — `@Singleton AppDatabase` плюс провайдеры пяти DAO.

**Тесты**

- C1–C3 — три ограничения уникальности на in-memory Room.
- `DatabaseSchemaTest` — база открывается, и **пять продуктовых таблиц входят в множество таблиц базы**:

  ```kotlin
  val tables = db.query("SELECT name FROM sqlite_master WHERE type='table'", null)
      .use { buildSet { while (it.moveToNext()) add(it.getString(0)) } }
  assertTrue(
      tables.containsAll(
          setOf("puzzles", "daily_sets", "day_assignments", "puzzle_attempts", "day_results")
      )
  )
  ```

  Утверждать «таблиц ровно пять» нельзя: Room заводит `room_master_table`, SQLite при `AUTOINCREMENT` — `sqlite_sequence`, Android — `android_metadata`. Проверка на точное равенство сломалась бы на первом же обновлении Room, ничего при этом не поймав.

**Критерии готовности**

- `assembleDebug`, `assembleRelease`, `lint`, `testDebugUnitTest` — зелёные;
- в логе сборки есть задача `:app:copyRoomSchemas` (Room Gradle Plugin применён и отработал);
- в `app/build/generated/ksp/**` есть `AppDatabase_Impl.kt`, в `app/build/generated/source/kapt/**` — `Hilt_MainActivity.java` (оба процессора работают);
- `app/schemas/ru.poporyadku.data.db.AppDatabase/1.json` существует, закоммичен, и его `createSql` совпадает с разделом 3;
- `rg -n "room-ktx|room-testing" gradle app` — пусто (**D-15**, **D-22**);
- второй прогон CI не качает `android-all` заново.

**Намеренно не входит.** `SetAssignmentPolicy`, `AssignmentSnapshot`, любые use case, `ClockProvider`, DataStore, debug-экран, маппер `Puzzle`, `room-testing` (**D-22**), любые изменения UI, любые миграции.

### PR 2B — снимок, политика, время, транзакции, `ProgressRepository`

**Цель.** Последовательная выдача наборов работает целиком и защищена тестами, включая четыре обязательных сценария переноса.

**Создаваемые файлы**

```
app/src/main/java/ru/poporyadku/core/time/
    TimeSnapshot.kt  ClockProvider.kt  DateProvider.kt
app/src/main/java/ru/poporyadku/domain/assignment/
    AssignmentSnapshot.kt  SetAssignmentPolicy.kt  Decision.kt
app/src/main/java/ru/poporyadku/domain/repository/
    DayAssignmentRepository.kt  ProgressRepository.kt  DailySetRepository.kt
app/src/main/java/ru/poporyadku/domain/usecase/StartDailySessionUseCase.kt
app/src/main/java/ru/poporyadku/data/repository/
    DayAssignmentRepositoryImpl.kt  DailySetRepositoryImpl.kt
app/src/main/java/ru/poporyadku/data/progress/ProgressRepositoryImpl.kt
app/src/main/java/ru/poporyadku/di/RepositoryModule.kt  ActivePack.kt

    ── variant-specific: реализации ClockProvider и их привязка (D-16) ──
app/src/release/java/ru/poporyadku/core/time/SystemClockProvider.kt
app/src/release/java/ru/poporyadku/di/ClockModule.kt
app/src/debug/java/ru/poporyadku/core/time/DebugClockProvider.kt
app/src/debug/java/ru/poporyadku/di/ClockModule.kt

app/src/test/java/ru/poporyadku/core/time/FakeClockProvider.kt                 (без Hilt)
app/src/test/java/ru/poporyadku/domain/assignment/SetAssignmentPolicyTest.kt   (P1–P9, P12–P15)
app/src/test/java/ru/poporyadku/core/time/TimeSnapshotTest.kt                  (P10, P11)
app/src/testDebug/java/ru/poporyadku/core/time/DebugClockProviderTest.kt      (P16)
app/src/testRelease/java/ru/poporyadku/core/time/SystemClockProviderTest.kt   (P17)
app/src/test/java/ru/poporyadku/data/repository/CarryOverTest.kt               (A1–A8)
app/src/test/java/ru/poporyadku/data/repository/PackScopeTest.kt               (A9–A12)
app/src/test/java/ru/poporyadku/data/db/dao/AssignmentDaoTest.kt               (A13)
app/src/test/java/ru/poporyadku/data/progress/ProgressRepositoryTest.kt        (C4–C6)
```

Два теста часов лежат в variant-specific тестовых source sets, и это обязательно, а не стилистика: **`src/test` предназначен только для тестов кода из `src/main`, доступного обоим вариантам**. Файл из `src/test` компилируется в том числе задачей `testReleaseUnitTest`, поэтому он не может импортировать `DebugClockProvider` из `src/debug` — при релизной сборке тестов этого класса не существует. Симметрично `SystemClockProvider` из `src/release` невидим для `testDebugUnitTest`.

| Тест | Файл | Задача |
| --- | --- | --- |
| P16 | `app/src/testDebug/.../DebugClockProviderTest.kt` | `testDebugUnitTest` |
| P17 | `app/src/testRelease/.../SystemClockProviderTest.kt` | `testReleaseUnitTest` |

`FakeClockProvider` остаётся в общем `src/test` законно: он реализует `ClockProvider` из `src/main` и variant-specific типов не упоминает. Соответственно PR 2B добавляет в `.github/workflows/ci.yml` шаг `./gradlew :app:testReleaseUnitTest` — иначе P17 не запускался бы никогда.

Файла `app/src/main/.../SystemClockProvider.kt` в списке **нет** намеренно: обе реализации `ClockProvider` — variant-specific, в `main` живёт только интерфейс. Файла `TimeModule.kt` в `main` тоже нет — его роль выполняют два одноимённых `ClockModule` в `src/release` и `src/debug` (**D-16**).

Файлов `AssignmentReader.kt`, `AssignmentReaderImpl.kt` и `FakeAssignmentReader.kt` в плане **нет**: политика работает с `AssignmentSnapshot`, который тесты строят конструктором (**D-3**).

Отладочные файлы `DebugSessionController.kt`, `DebugDiagnostics.kt` и debug-экран в 2B не создаются — они в 2C. `DebugClockProvider` появляется здесь, потому что без него debug-вариант не соберётся: граф Hilt требует привязки `ClockProvider` в каждом варианте.

**Изменяемые файлы.** `docs/ARCHITECTURE.md` — сигнатура `SetAssignmentPolicy` и описание `StartDailySessionUseCase` (решения **D-3**, **D-4**, **D-16**). `.github/workflows/ci.yml` — шаг `./gradlew :app:testReleaseUnitTest` для P17.

**Зависимости Gradle.** Новых нет — всё подключено в 2A.

**Основные классы и ответственность**

- `SetAssignmentPolicy` — **чистая синхронная функция** `decide(today, snapshot)`. Ни зависимостей, ни `suspend`, ни ввода-вывода. Ничего не знает о головоломках (`ARCHITECTURE.md` §2, «вторая граница»). Первым делом проверяет инвариант «не более одного отложенного» через `require`. Решения несут `packId` (**D-20**).
- `AssignmentSnapshot` — шесть доменных значений: три глобальных (`pendingAssignments`, `todayAssignment`, `lastAssignedDate`) и три про активный пакет (`activePackId`, `maxSetIndexInActivePack`, `setCountInActivePack`) — **D-20**.
- `ClockProvider` / `TimeSnapshot` — единственный источник согласованной пары «дата + метка времени» из одного `Instant`; `TimeSnapshot` строится только фабрикой `of(instant, zone)` (**D-16**). `DateProvider` — узкий интерфейс `today()` для UI итерации 3.
- `SystemClockProvider` (`src/release`) / `DebugClockProvider` (`src/debug`) — две реализации, связываемые одноимёнными `ClockModule` своего варианта. В `main` их нет (**D-16**).
- `DayAssignmentRepositoryImpl` — сборка снимка и исполнение решения в одной транзакции. Публичный API — ровно `peek()` и `startSession()`; ни debug-методов, ни `internal`-шва.
- `StartDailySessionUseCase` — `suspend operator fun invoke(): Decision`, без параметров. Время берёт репозиторий.
- `ProgressRepositoryImpl` — валидация диапазонов (**D-17**), запись попытки и пересчёт `day_results` в одной транзакции; чтение дня и диапазона дат.

**Тесты**

P1–P16 (чистый JVM, без Room и без корутинных диспетчеров), P17 (чистый JVM, релизный вариант), A1–A13 и C4–C6 (Robolectric). Матрица целиком — раздел 4.

**Критерии готовности**

- Все четыре обязательных теста переноса из `IMPLEMENTATION_PLAN.md` и инвариант «не более одного отложенного» — зелёные;
- P9 подтверждает, что два отложенных назначения приводят к явной ошибке, а не к тихому выбору первого;
- A9–A12 подтверждают глобальную семантику на строках двух пакетов: один pending глобально, одна дата — одно назначение, `lastAssignedDate` не обходится сменой пакета, перенос сохраняет `pack_id` и `set_index`;
- наивная реализация не проходит: временно заменить `carryOver` на `insert` и убедиться, что A2 падает с `SQLiteConstraintException` — разовая ручная проверка, что тест ловит именно ту ошибку, ради которой написан;
- вторая такая же проверка для **D-20**: временно вернуть `pendingAssignments(packId)` с фильтром и убедиться, что A9 краснеет;
- `rg -n "fun decide" app/src/main` показывает сигнатуру без `suspend`;
- в публичном API `StartDailySessionUseCase` и `DayAssignmentRepository` нет параметра даты;
- в `app/src/main` и `app/src/release` нет ни одного способа задать дату: `rg -n "TimeSnapshot\.of|Clock\.fixed|setDate" app/src/main app/src/release` даёт только определение фабрики в `TimeSnapshot.kt` и `Clock.system(ZoneId.systemDefault())` в `SystemClockProvider.kt`;
- `TimeSnapshot` не `data class` и не имеет публичного конструктора: `rg -n "data class TimeSnapshot" app/src` — пусто;
- `lint`, `testDebugUnitTest`, **`testReleaseUnitTest`** (без него P17 не выполняется вовсе) и `assembleRelease` зелёные.

**Намеренно не входит.** DataStore, debug-экран, `GetTodayStateUseCase`, `StreakCalculator`, `PairwiseScoreCalculator` (последние два — итерация 3), маппер `Puzzle`.

### PR 2C — `UserPreferencesRepository`, debug-экран, приёмочные тесты

**Цель.** Закрыть все критерии приёмки итерации 2 и дать инструмент для их ручной проверки.

**Создаваемые файлы**

```
    ── домен и модель: DataStore сюда не проникает (D-18) ──
app/src/main/java/ru/poporyadku/core/model/
    UserPreferences.kt  StreakCache.kt  ThemeMode.kt
app/src/main/java/ru/poporyadku/domain/repository/UserPreferencesRepository.kt   (интерфейс)

    ── данные: единственное место, где встречается androidx.datastore ──
app/src/main/java/ru/poporyadku/data/prefs/
    PreferenceKeys.kt  UserPreferencesRepositoryImpl.kt
app/src/main/java/ru/poporyadku/di/PreferencesModule.kt

    ── debug ──
app/src/debug/AndroidManifest.xml
app/src/debug/java/ru/poporyadku/debug/
    DebugActivity.kt  DebugScreen.kt  DebugViewModel.kt
    DebugSessionController.kt  DebugDiagnostics.kt  DebugContentFixture.kt
app/src/debug/res/values/strings.xml

app/src/test/java/ru/poporyadku/data/prefs/UserPreferencesRepositoryTest.kt   (T1–T8)
app/src/test/java/ru/poporyadku/AcceptanceScenarioTest.kt
```

Файла `data/prefs/UserPreferences.kt` в списке **нет**: модель переехала в `core/model` (**D-18**). `DebugClockProvider` и debug-версия `ClockModule` созданы в 2B и здесь только используются.

**Изменяемые файлы**

```
app/build.gradle.kts        implementation(libs.androidx.datastore.preferences)
                            testImplementation(libs.turbine)
.github/workflows/ci.yml    шаг ./gradlew assembleRelease
```

**Зависимости Gradle**

```kotlin
implementation(libs.androidx.datastore.preferences)   // 1.2.1
testImplementation(libs.turbine)                      // 1.2.1
```

**Основные классы и ответственность**

- `UserPreferences`, `StreakCache`, `ThemeMode` (`core/model`) — доменные значения, видимые `domain` и `ui`.
- `UserPreferencesRepository` (домен) — один `Flow<UserPreferences>`, по одному `suspend`-сеттеру на обычный ключ и **единственная** операция записи кэша серии `updateStreakCache(current, best, date)`. Раздельных сеттеров трёх ключей кэша нет (**D-18**).
- `UserPreferencesRepositoryImpl` (данные) — `Preferences.Key`, значения по умолчанию, `require` на записи, согласованный сброс тройки на чтении, `catch` только по `IOException` (**D-18**).
- `DebugViewModel` — состояние экрана: выбранная дата, последний `Decision`, отдельно прочитанный `DebugAssignmentView`, четыре потока дампов, снимок настроек.
- `DebugSessionController` — ставит дату в `DebugClockProvider` и вызывает публичный `DayAssignmentRepository` (**D-16**).
- `DebugDiagnostics` — read-only снимок из DAO для блока «Диагностика» (**D-21**).
- `DebugContentFixture` — раздел 5.

**Тесты**

- `UserPreferencesRepositoryTest` — запись и чтение каждого из 14 ключей; значения по умолчанию на пустом хранилище; доставка изменений через `Flow` (Turbine); **и отдельно каждое правило из D-18**: неизвестная тема → `SYSTEM`; невалидная ISO-дата в `last_seen_date` → `null`; `reminder_minute_of_day = -1` и `= 1440` → `540`; плюс тесты T1–T8 матрицы раздела 4 (валидация `updateStreakCache`, согласованный сброс тройки, `stored_content_version < 0`, `IOException` против прочих исключений, атомарность). На временном каталоге `TemporaryFolder`, под Robolectric.
- `AcceptanceScenarioTest` — сквозной сценарий одним тестом: день 1 старт без попыток → день 2 перенос той же строки → попытка → день 3 выдаёт `N + 1` → перевод даты назад даёт `AwaitingNextDay`. Дату двигает `FakeClockProvider`. Автоматизированная версия того, что на debug-экране проверяется руками.

**Критерии готовности**

- Все критерии приёмки итерации 2 из `IMPLEMENTATION_PLAN.md` отмечены — часть тестами, часть прогоном на устройстве по списку раздела 6;
- `assembleRelease` проходит в CI, и в релизном APK нет `DebugActivity` (проверяется `aapt dump badging` или отсутствием второго значка);
- `rg -n "ru\.poporyadku\.debug|DebugClockProvider" app/src/main app/src/release` — пусто;
- `rg -n "androidx\.datastore" app/src/main --files-with-matches` — только файлы из `data/prefs` и `di/PreferencesModule.kt`;
- `rg -n "ru\.poporyadku\.data" app/src/main/java/ru/poporyadku/domain` — пусто (доменный слой не импортирует `data`, **D-18**);
- `rg -n "setCachedCurrentStreak|setCachedBestStreak|setCachedStreakDate" app/src` — пусто;
- настройки переживают перезапуск процесса: изменить, убить приложение, открыть заново.

**Намеренно не входит.** Любой продуктовый UI, `GetTodayStateUseCase`, уведомления, `StreakCalculator` (кэш серии в 2C только хранится, но пока никем не заполняется — заполнит итерация 3).

---

## 9. Рекомендуемый первый PR

**2A.** Это единственный PR из трёх, где есть риск системы сборки; 2B и 2C — обычный Kotlin. Если KSP и Room Gradle Plugin рядом с legacy-kapt где-то себя поведут иначе, чем в пробной сборке, это выяснится на трёхстах строках схемы, а не поверх готовой политики выдачи. Плюс имя текущей ветки — `feature/iteration-2a-room-foundation` — уже соответствует именно этому объёму.

---

## 10. Команды проверок

Проверки текста используют `rg` (ripgrep): он рекурсивен по умолчанию, не требует `-r`, и его код возврата однозначен — **0 = найдено, 1 = не найдено, 2 = ошибка запуска**. Везде, где ожидается «ничего не найдено», ожидаемый код — `1`. Строку `echo "exit=$?"` надо ставить сразу после команды: `$?` хранит статус **последней** выполненной команды.

### PR 2A

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew lint
./gradlew :app:assembleRelease

# Room Gradle Plugin применён и отработал
./gradlew :app:assembleDebug --console=plain | rg copyRoomSchemas

# оба процессора отработали
find app/build/generated/ksp -name '*_Impl.kt'
find app/build/generated/source/kapt -name 'Hilt_*.java'

# схема экспортирована и попадёт в коммит
git status --porcelain app/schemas

# room { } — верхнеуровневый блок, не внутри android { } (D-14)
rg -n --multiline 'android\s*\{[\s\S]*?\n\s+room\s*\{' app/build.gradle.kts ; echo "exit=$?"  # ожидаем 1
rg -n '^room \{' app/build.gradle.kts                                                          # ожидаем 0

# пустой room-ktx и преждевременный room-testing нигде не подключены (D-15, D-22)
rg -n "room-ktx|room-testing|room\.testing" gradle app ; echo "exit=$?"   # ожидаем 1
```

### PR 2B

```bash
./gradlew :app:testDebugUnitTest --tests '*SetAssignmentPolicyTest*'
./gradlew :app:testDebugUnitTest --tests '*TimeSnapshotTest*'
./gradlew :app:testDebugUnitTest  --tests '*DebugClockProviderTest*'   # P16, src/testDebug
./gradlew :app:testDebugUnitTest --tests '*CarryOverTest*'
./gradlew :app:testDebugUnitTest --tests '*PackScopeTest*'
./gradlew :app:testDebugUnitTest --tests '*AssignmentDaoTest*'
./gradlew :app:testDebugUnitTest --tests '*ProgressRepositoryTest*'
./gradlew :app:testReleaseUnitTest --tests '*SystemClockProviderTest*'  # P17, src/testRelease
./gradlew lint test

# обе задачи unit-тестов, а не только debug: P17 живёт в релизном варианте
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest

# variant-specific тесты лежат в variant-specific source sets (D-16)
rg -n "DebugClockProvider" app/src/test ; echo "exit=$?"    # ожидаем 1: src/test не видит src/debug
rg -n "SystemClockProvider" app/src/test ; echo "exit=$?"   # ожидаем 1: src/test не видит src/release
ls app/src/testDebug/java/ru/poporyadku/core/time/DebugClockProviderTest.kt
ls app/src/testRelease/java/ru/poporyadku/core/time/SystemClockProviderTest.kt

# политика чиста: ни suspend, ни зависимостей от data
rg -n "suspend fun decide" app/src/main ; echo "exit=$?"                                  # ожидаем 1
rg -n "AssignmentDao|Repository|androidx\." \
   app/src/main/java/ru/poporyadku/domain/assignment ; echo "exit=$?"                     # ожидаем 1

# продуктовый API не принимает дату
rg -n "LocalDate|today" \
   app/src/main/java/ru/poporyadku/domain/usecase/StartDailySessionUseCase.kt ; echo "exit=$?"   # ожидаем 1
# ITERATION_3_DESIGN.md, I3-D16 (PR 3B): инвариант D-16 относится к ЗАПИСИ. peek() и
# startSession() даты не принимают; getAssignment(localDate) её принимает, но ничего не
# создаёт — подделать им назначение нельзя. Прежняя проверка «ни одной строки с LocalDate»
# стала неверной и заменена двумя.
rg -n "fun peek\(|fun startSession\(" \
   app/src/main/java/ru/poporyadku/domain/repository/DayAssignmentRepository.kt
#   ожидаем сигнатуры без параметров
rg -n "suspend fun .*\(.*LocalDate" \
   app/src/main/java/ru/poporyadku/domain/repository/DayAssignmentRepository.kt
#   ожидаем ровно одну строку — getAssignment(localDate), метод только для чтения

# TimeSnapshot нельзя собрать из независимых date и millis (D-16)
rg -n "data class TimeSnapshot" app/src ; echo "exit=$?"                                  # ожидаем 1
rg -n "private constructor" app/src/main/java/ru/poporyadku/core/time/TimeSnapshot.kt     # ожидаем 0

# управляемых часов нет в продуктовых source set (D-16)
rg -n "DebugClockProvider|Clock\.fixed|fun setDate" app/src/main app/src/release ; echo "exit=$?"  # ожидаем 1
# в release ровно одна реализация ClockProvider, и она системная
rg -n "ClockProvider" app/src/release

# зона не кэшируется в поле провайдера (D-16)
rg -n "Clock\.systemDefaultZone\(\)" app/src ; echo "exit=$?"                             # ожидаем 1
rg -n "Clock\.system\(ZoneId\.systemDefault\(\)\)" app/src/release app/src/debug          # ожидаем по одной строке в каждом

# время берётся ровно дважды и только до транзакции (D-16)
rg -c 'clock\.now\(\)' app/src/main/java/ru/poporyadku/data/repository/DayAssignmentRepositoryImpl.kt
#   ожидаем 2 — по одному на peek() и startSession()
rg -n 'clock\.now\(\)|withTransaction|override suspend fun' \
   app/src/main/java/ru/poporyadku/data/repository/DayAssignmentRepositoryImpl.kt
#   ожидаемый порядок строк, по паре на метод:
#     override suspend fun peek()          → clock.now() → withTransaction
#     override suspend fun startSession()  → clock.now() → withTransaction
#   то есть в выводе clock.now() всегда идёт ПОСЛЕ строки override и ДО строки withTransaction
#   того же метода. Проверка структурная и делается глазами по десяти строкам вывода:
#   многострочный regex здесь не годится — он молча перешагнул бы границу метода
#   и либо пропустил нарушение, либо нашёл несуществующее.

# запросы снимка разделены на глобальные и pack-scoped (D-20)
rg -n "fun pendingAssignments|fun byDate|fun lastAssignedDate|fun maxSetIndex" \
   app/src/main/java/ru/poporyadku/data/db/dao/AssignmentDao.kt
#   ожидаем: у первых трёх нет параметра packId, у maxSetIndex — есть
```

### PR 2C

```bash
./gradlew lint test
./gradlew :app:assembleRelease
./gradlew :app:installDebug        # затем прогон по списку раздела 6

# отладочного кода нет в продуктовых source set
rg -n "ru\.poporyadku\.debug|DebugSessionController|DebugDiagnostics" \
   app/src/main app/src/release ; echo "exit=$?"                                          # ожидаем 1

# DataStore не выходит за data/prefs (D-18)
rg -l "androidx\.datastore" app/src/main
#   ожидаем только data/prefs/*.kt и di/PreferencesModule.kt

# домен не импортирует data (D-18)
rg -n "ru\.poporyadku\.data" app/src/main/java/ru/poporyadku/domain ; echo "exit=$?"      # ожидаем 1

# раздельных сеттеров кэша серии нет (D-18)
rg -n "setCachedCurrentStreak|setCachedBestStreak|setCachedStreakDate" app/src ; echo "exit=$?"  # ожидаем 1

# catch в потоке настроек сужен до IOException (D-18)
rg -n -A2 "\.catch \{" app/src/main/java/ru/poporyadku/data/prefs/UserPreferencesRepositoryImpl.kt
#   ожидаем ветку `if (e is IOException) emit(emptyPreferences()) else throw e`
```

---

## 11. Риски и способы их закрытия

| PR | Риск | Признак | Закрытие |
| --- | --- | --- | --- |
| 2A | KSP, Room Gradle Plugin и legacy-kapt в одном модуле ломаются на будущем обновлении AGP, KSP или Room | Падение `kspDebugKotlin`, `kaptDebugKotlin` или `copyRoomSchemas` при апгрейде | Все три версии запиннены в каталоге. В `VERSIONS.md` фиксируется, что комбинация проверена эмпирически, с датой и выводом сборки. `assembleDebug` и `assembleRelease` в CI ловят регрессию на первом же push |
| 2A | Схема расходится с кодом при переключении веток | `MigrationTestHelper` в итерации 4 сверяется с устаревшим `1.json` | Room Gradle Plugin объявляет каталог схем как Gradle input/output и заводит `copyRoomSchemas` (**D-14**) — именно от этого класса ошибок `ksp { arg(...) }` не защищает |
| 2A | Robolectric качает ~190 МБ `android-all` в `~/.m2` мимо кэша Gradle | Каждый прогон CI дольше на две минуты | Отдельный шаг `actions/cache` на `~/.m2/repository/org/robolectric`. Запасной вариант: `robolectric.offline=true` + явная зависимость `org.robolectric:android-all-instrumented:15-robolectric-13954326-i7`, которую резолвит уже сам Gradle |
| 2A | Robolectric не знает SDK 37 → тесты не стартуют | «Unknown SDK 37» при первом же тесте | `robolectric.properties` с `sdk=35` добавляется тем же PR. `DatabaseSchemaTest` падает первым и с понятным текстом. При переходе CI на JDK 21 можно поднять до 36 — но выигрыша в этом нет |
| 2A | Соблазн написать разбор `cards_json` руками, чтобы «домапить `Puzzle` заодно» | Ручной парсер JSON в `data/db/mapper` | Явный запрет **D-19** и отсутствие маппера `Puzzle` в списке файлов 2A. `kotlinx-serialization` подключается в итерации 4 вместе с `ContentImporter` |
| 2B | Наивная реализация переноса: вставка вместо `UPDATE` | `SQLiteConstraintException` у пользователя вместо задания | Четыре обязательных теста плюс инвариант, плюс `check(rows == 1)` внутри транзакции, плюс разовая проверка «сломай и убедись, что тест краснеет» в критериях готовности 2B |
| 2B | Снимок собран вне транзакции — пять запросов видят разные состояния | Плавающие, невоспроизводимые решения политики | Сборка снимка — приватный метод, вызываемый только внутри `withTransaction`; тест A8 проверяет согласованность |
| 2B | Два отложенных назначения обрабатываются молча | Потерянный набор, обнаруживаемый через недели | `require(pendingAssignments.size <= 1)` в начале `decide` и `LIMIT 2` в запросе; тест P9 |
| 2B | `local_date` и `assigned_at` расходятся через полночь | Назначение с датой вчера и меткой времени сегодня | `TimeSnapshot` из одного `Instant` (**D-16**); тест P10 на граничные моменты суток |
| 2B | Дата из UI попадает в продуктовый use case | Назначение на произвольную дату в релизе | Публичный API без параметра даты; управляемые часы существуют только в `src/debug`, в release собран `SystemClockProvider` без методов записи (**D-16**); `TimeSnapshot` без публичного конструктора и без `copy()`; `rg`-проверки в командах 2B |
| 2B | Запросы снимка отфильтрованы по пакету, и глобальные инварианты становятся pack-scoped | Два отложенных назначения у пользователя; два набора за календарные сутки после смены пакета | Три глобальных запроса без `packId` в сигнатуре (**D-20**); тесты A9–A12 на строках двух пакетов; ручная проверка «вернуть фильтр — A9 краснеет» в критериях готовности 2B |
| 2B | Исполнитель решения подставляет активный пакет вместо пакета переносимой строки | `pack_id` чужой строки переписан; `UNIQUE(pack_id, set_index)` нарушен на следующем наборе | `packId` внутри `Decision.CarryOver`/`Assigned`/`NewSet`; `pack_id` отсутствует в `SET` у `carryOver` и присутствует в `WHERE` как проверка; `check(rows == 1)` откатывает транзакцию; тест A11 |
| 2B | `clock.now()` вызван повторно внутри транзакции | Политика решает про одну дату, снимок прочитан про другую | `snapshot(today)` принимает дату параметром; `rg --multiline` на `withTransaction { … clock.now() }` в командах 2B |
| 2B | Пустая база даёт `ContentExhausted`, и это принимают за баг | «Почему выдача не работает» | Debug-экран показывает `Decision`, а рядом — отдельно прочитанный `DebugAssignmentView` с `setCountInActivePack` и `nextSetIndex` (**D-21**). Тест P8 фиксирует поведение как ожидаемое, а не случайное |
| 2C | Debug-фикстура, `DebugSessionController`, `DebugDiagnostics`, `DebugClockProvider` или debug-экран утекают в release | Второй значок на релизной сборке; тестовые наборы или подменяемые часы у пользователя | Физическое размещение всего перечисленного в `src/debug`; продуктовый репозиторий без debug-методов (**D-21**); `assembleRelease` в CI; `rg` по `src/main` и `src/release` в критериях готовности 2B и 2C |
| 2C | Доменный слой начинает импортировать `data` через `UserPreferences` | Правило `ARCHITECTURE.md` §1 нарушено молча, и дальше это тянется в `ui` | `UserPreferences`, `StreakCache` и `ThemeMode` — в `core/model` (**D-18**); проверка `rg -n "ru\.poporyadku\.data" app/src/main/java/ru/poporyadku/domain` в критериях 2C |
| 2C | Широкий `catch` в потоке настроек проглатывает не только `IOException` | Тихий сброс всех настроек к умолчаниям; проглоченная `CancellationException` ломает отмену корутин | `catch { if (e is IOException) emit(emptyPreferences()) else throw e }` (**D-18**); тесты T6 и T7 — на восстановимую и невосстановимую ошибку отдельно |
| 2C | Тройка кэша серии читается частично: серия из хранилища, дата — `null` | На Home число неизвестного происхождения, выданное за сегодняшнее | Согласованный сброс всей тройки на чтении и `require` на записи (**D-18**); тесты T1–T4 |
| 2C | Кэш серии обновляется тремя вызовами и застревает в промежуточном состоянии | Дата сегодняшняя, серия вчерашняя — то есть ровно та ошибка, ради которой дата заводилась | Один `edit` на три значения (**D-18**), отдельный тест атомарности |
| 2C | Испорченное значение в настройках роняет старт | Приложение не открывается после обновления | Устойчивое чтение по таблице **D-18** плюс `ReplaceFileCorruptionHandler`; по тесту на каждое правило |
| 2C | Debug-экран забудут удалить в итерации 3 | Инструмент доживает до релиза | Удаляется **пофайлово**, а не каталогом (уточнено в `ITERATION_3_DESIGN.md`, раздел 16, и выполнено в PR 3D): `app/src/debug/AndroidManifest.xml` (второй launcher) и шесть файлов инструмента — `DebugActivity.kt`, `DebugScreen.kt`, `DebugViewModel.kt`, `DebugSessionController.kt`, `DebugDiagnostics.kt`, `DebugContentFixture.kt`, — плюс строки самого экрана и его диалога из `app/src/debug/res/values/strings.xml`. Каталог `app/src/debug/java/ru/poporyadku/debug/` продолжает существовать: в нём остаются `TemporaryContentReset.kt`, `TemporaryContentResetAction.kt` (живут до итерации 4, **I3-D47**, **I3-D48**) и `DebugGraphEntryPoint.kt` (доступ к продуктовым синглтонам для instrumented-тестов, частью инструмента не является). Остаются также `DebugClockProvider.kt`, debug-версия `di/ClockModule.kt`, `di/DebugHomeRecoveryModule.kt` и сам файл строк со строками восстановления. Ничего снимать в `src/main` не нужно: продуктовый код debug-швов не содержит (**D-16**, **D-21**). В критериях приёмки итерации 3 пункт «удалить debug-экран» уже есть |
| Все | Строка про Room в `VERSIONS.md` не отражает существование группы `androidx.room3` | При следующей сверке версий решение «остаться на 2.8.4» выглядит непроверенным | Правится в 2A по **D-13**: группа, артефакты, 3.0.2 от 26.08.2026 и шесть пунктов обоснования выбора 2.8.4 |

---

## 12. Источники

Все проверены 2026-08-31.

- [s1] [Migrate to built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin) — kapt несовместим с built-in Kotlin; рекомендация мигрировать на KSP, `legacy-kapt` как замена для немигрируемого
- [s2] [KSP, kapt, and legacy-kapt](https://developer.android.com/agents/skills/build-system/agp/agp-9-upgrade/references/ksp-kapt) — алгоритм выбора между KSP и legacy-kapt по каждой зависимости
- [s3] [AGP 9.0 release notes](https://developer.android.com/build/releases/agp-9-0-0-release-notes) — built-in Kotlin, автоподъём версии KSP до версии KGP
- [s4] [AGP 9.3.0 release notes](https://developer.android.com/build/releases/agp-9-3-0-release-notes) — Gradle 9.5.0 минимум, JDK 17, API 37
- [s5] [KSP releases](https://github.com/google/ksp/releases) — 2.3.0 (развязка версий с Kotlin), 2.3.1 (AGP 9 built-in Kotlin), 2.3.5 (KSP↔KAPT в AGP 9), 2.3.10 (R-class и Kotlin 2.4), 2.3.11 (последний стабильный)
- [s6] [Room release notes (2.x)](https://developer.android.com/jetpack/androidx/releases/room) — 2.8.4 последний стабильный; KSP2 поддержан и рекомендован с 2.7.0; `room-ktx` слит в `room-runtime` и пуст с 2.7.0-alpha01; Room Gradle Plugin и обязательность `schemaDirectory`
- [s11] [Room 3 release notes](https://developer.android.com/jetpack/androidx/releases/room3) — группа `androidx.room3`, 3.0.0 от 01.07.2026, 3.0.2 от 26.08.2026, фокус на KMP, переименование пакетов, отказ от SupportSQLite, плагин `androidx.room3`
- [s7] [Dagger KSP](https://dagger.dev/dev-guide/ksp) — «Dagger's KSP support is currently in alpha»
- [s8] [Robolectric 4.16.1, `DefaultSdkProvider`](https://github.com/robolectric/robolectric/blob/robolectric-4.16.1/robolectric/src/main/java/org/robolectric/plugins/DefaultSdkProvider.java) — список известных SDK и требуемых версий JDK
- [s9] [Robolectric getting started](https://robolectric.org/getting-started/) — `includeAndroidResources` и `--add-opens` для JDK 17+
- [s10] [Robolectric configuring](https://robolectric.org/configuring/) — `robolectric.properties` и поведение по умолчанию «SDK берётся из `targetSdk`»

[s1]: https://developer.android.com/build/migrate-to-built-in-kotlin
[s2]: https://developer.android.com/agents/skills/build-system/agp/agp-9-upgrade/references/ksp-kapt
[s3]: https://developer.android.com/build/releases/agp-9-0-0-release-notes
[s4]: https://developer.android.com/build/releases/agp-9-3-0-release-notes
[s5]: https://github.com/google/ksp/releases
[s6]: https://developer.android.com/jetpack/androidx/releases/room
[s7]: https://dagger.dev/dev-guide/ksp
[s8]: https://github.com/robolectric/robolectric/blob/robolectric-4.16.1/robolectric/src/main/java/org/robolectric/plugins/DefaultSdkProvider.java
[s9]: https://robolectric.org/getting-started/
[s10]: https://robolectric.org/configuring/
[s11]: https://developer.android.com/jetpack/androidx/releases/room3

---

## 13. Что требует подтверждения перед началом реализации

### Подтверждено владельцем проекта (ревизия 3)

| Пункт | Что подтверждено | Где реализуется |
| --- | --- | --- |
| **D-7** | Три ключа кэша серии вместо одного — `cached_current_streak`, `cached_best_streak`, `cached_streak_date`. Расширение перечня в `IMPLEMENTATION_PLAN.md` принято. В API это одна операция `updateStreakCache` и одно доменное значение `StreakCache` (**D-18**) | PR 2C |
| **D-3 / D-4 / D-16** | Правки `ARCHITECTURE.md` §4: `SetAssignmentPolicy.decide(today, snapshot)` — чистая синхронная функция без `AssignmentDao` и без `setCount` в конструкторе; `StartDailySessionUseCase` без параметра даты; время внедряется через `ClockProvider` | PR 2B (текст `ARCHITECTURE.md` правится тем же PR) |
| **`VERSIONS.md`, строка Room** | Переписывается по **D-13**: группа `androidx.room3`, версия 3.0.2 от 26.08.2026 со ссылкой [s11], шесть пунктов обоснования выбора 2.8.4 | PR 2A |

Эти три пункта в ревизии 2 стояли открытыми и больше блокирующими не являются.

### Открытых архитектурных пунктов нет

Все решения ревизий 2 и 3 (**D-1**–**D-22**) приняты и подтверждены; ни одно не помечено «требует подтверждения». Решения **D-20**, **D-21**, **D-22** и переработанные **D-16**, **D-18** не расширяют объём итерации и не меняют критериев приёмки из `IMPLEMENTATION_PLAN.md`. Ревизия 3.1 не вводит новых решений — она уточняет реализацию внутри уже принятых.

### Побочные правки, которые несут PR итерации 2 помимо кода

Это не открытые вопросы, а перечень файлов вне `app/src`, которые правятся вместе с соответствующим PR:

| Файл | Правка | PR |
| --- | --- | --- |
| `ARCHITECTURE.md` §4 | Сигнатуры `SetAssignmentPolicy.decide(today, snapshot)` и `StartDailySessionUseCase` без даты (**D-3**, **D-4**, **D-16**) | 2B |
| `ARCHITECTURE.md` §1 | В структуре пакетов `UserPreferences` показан в `data/prefs` — переезжает в `core/model` (**D-18**) | 2C |
| `ARCHITECTURE.md` §9 | Снимается формулировка «Robolectric или instrumented — решается на итерации 2»: решено Robolectric (**D-2**) | 2A |
| `VERSIONS.md` | Строка Room по **D-13**; новые строки версий из **D-12**, без `room-testing` (**D-22**) | 2A |
| `.github/workflows/ci.yml` | Кэш `~/.m2/repository/org/robolectric` (2A); шаг `testReleaseUnitTest` для P17 (2B); шаг `assembleRelease` (2C) | 2A, 2B, 2C |
| `IMPLEMENTATION_PLAN.md`, итерация 4 | В список работ добавляется подключение `androidx.room:room-testing` вместе с `MigrationTestHelper` (**D-22**) | 2A |

**Статус документа: ревизия 3.1, готова к реализации.** До первого коммита PR 2A ни одного Kotlin-файла, Gradle-скрипта и CI-конфигурации итерация 2 не меняет.
