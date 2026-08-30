# VERSIONS.md — «По порядку!»

Итерация 0 / точечное закрытие в ходе исправления замечаний ревью PR №1. Статус: утверждено, 2026-08-30.

Таблица фиксирует версии инструментов и библиотек на момент 2026-08-30. Версии, отмеченные «в version catalog, не подключено», зарезервированы для будущих итераций (`IMPLEMENTATION_PLAN.md`) и **не** являются зависимостями `app` в этом PR — `gradle/libs.versions.toml` хранит их значения, чтобы не искать заново, когда придёт время подключения.

Для нескольких значений ниже (AGP 9.3.2, `androidx.hilt:hilt-navigation-compose:1.4.0`) точная версия дополнительно подтверждена **эмпирически**: `./gradlew assembleDebug` в этой сессии успешно разрешил и собрал зависимости с этими версиями — Gradle не смог бы это сделать, если бы артефакт с указанной версией не существовал в репозитории Google. Это отмечено отдельно там, где страница релиза, найденная поиском, показывала более раннюю версию, чем фактически разрешившаяся (типичное отставание индексации поисковых сниппетов от состояния репозитория).

| Компонент | Зафиксированная версия | Официальный первичный источник | Дата проверки | Комментарий |
| --- | --- | --- | --- | --- |
| Android Gradle Plugin (AGP) | 9.3.2 | [developer.android.com/build/releases/agp-9-3-0-release-notes](https://developer.android.com/build/releases/agp-9-3-0-release-notes) (минорная линия 9.3); патч 9.3.2 подтверждён эмпирически — успешно разрешён и собран Gradle в этой сессии | 2026-08-30 | 9.3.0 — задокументированный минорный релиз (июль 2026); 9.3.2 — патч той же линии, не описанный отдельной страницей release notes на момент проверки, но реально существующий в репозитории Google Maven |
| Gradle | 9.7.1 | [docs.gradle.org/current/release-notes.html](https://docs.gradle.org/current/release-notes.html); минимум для AGP 9.3 — Gradle 9.5.0 согласно [about-agp](https://developer.android.com/build/releases/about-agp) | 2026-08-30 | Wrapper (`gradle-wrapper.properties`) уже использует 9.7.1, что выше минимума 9.5.0 для AGP 9.3 |
| Kotlin | 2.4.10 | [kotlinlang.org/docs/releases.html](https://kotlinlang.org/docs/releases.html) | 2026-08-30 | Стабильный bug-fix релиз для языковой версии 2.4.0 (03.06.2026), выпущен 14.07.2026 |
| Compose BOM | 2026.08.00 | [android-developers.googleblog.com — Jetpack Compose August '26 release](https://android-developers.googleblog.com/2026/08/jetpack-compose-august-2026-release.html); [developer.android.com/develop/ui/compose/bom](https://developer.android.com/develop/ui/compose/bom) | 2026-08-30 | Соответствует Compose 1.12 (ядро). Compose 1.12 требует compileSdk 37 и минимум AGP 9.1.1 — оба условия выполнены (compileSdk=37, AGP=9.3.2) |
| Material 3 | Через Compose BOM 2026.08.00 (без отдельной версии в `libs.versions.toml`) | [developer.android.com/jetpack/androidx/releases/compose](https://developer.android.com/jetpack/androidx/releases/compose) | 2026-08-30 | Версия Material 3 определяется BOM — отдельно не пинуется, согласно `ARCHITECTURE.md` |
| Core KTX | 1.19.0 | [developer.android.com/jetpack/androidx/releases/core](https://developer.android.com/jetpack/androidx/releases/core) | 2026-08-30 | Подключено, используется |
| Lifecycle (runtime-ktx, viewmodel-compose) | 2.11.0 | [developer.android.com/jetpack/androidx/releases/lifecycle](https://developer.android.com/jetpack/androidx/releases/lifecycle) | 2026-08-30 | Подключено, используется |
| Activity Compose | 1.13.0 | [developer.android.com/jetpack/androidx/releases/activity](https://developer.android.com/jetpack/androidx/releases/activity) | 2026-08-30 | Стабильный релиз 11.03.2026, подтверждён официальной страницей release notes |
| Navigation Compose | 2.10.0 | [developer.android.com/jetpack/androidx/releases/navigation](https://developer.android.com/jetpack/androidx/releases/navigation) | 2026-08-30 | Подключено, используется; `navigation-testing` — та же версия |
| Dagger/Hilt (`hilt-android`, `hilt-android-compiler`, плагин `com.google.dagger.hilt.android`) | 2.60.1 | [github.com/google/dagger/releases](https://github.com/google/dagger/releases) | 2026-08-30 | 2.59 добавил нативную поддержку AGP 9 в Hilt Gradle Plugin (устраняет зависимость от `applicationVariants`); 2.60.1 включает дальнейшие фиксы. Совместимость с `com.android.legacy-kapt` + built-in Kotlin AGP 9.3.2 подтверждена эмпирически в этой сессии (см. `gradle.properties`/раздел 6 отчёта) |
| AndroidX Hilt (`androidx.hilt:hilt-navigation-compose`) | 1.4.0 | [developer.android.com/jetpack/androidx/releases/hilt](https://developer.android.com/jetpack/androidx/releases/hilt) — на момент проверки страница релиза показывала `1.3.0-rc01`; версия 1.4.0 подтверждена эмпирически (успешно разрешена Gradle из Google Maven в этой сессии) | 2026-08-30 | Расхождение между публичной страницей release notes и фактически резолвящейся версией — известное отставание индексации; артефакт реально существует и собирается |
| Room | 2.8.4 (в version catalog, не подключено) | [developer.android.com/jetpack/androidx/releases/room](https://developer.android.com/jetpack/androidx/releases/room) | 2026-08-30 | Последний стабильный релиз классической (не-KMP) линии 2.x (19.11.2025). Room 3.0.0 (01.07.2026, [android-developers.googleblog.com](https://android-developers.googleblog.com/2026/03/room-30-modernizing-room.html)) — новая мажорная линия с упором на KMP-таргеты (JS/WasmJS); проект не использует KMP (`ARCHITECTURE.md`), поэтому 2.8.4 — более консервативный и достаточный выбор. Решение о переходе на 3.0 — на усмотрение итерации 2 |
| DataStore (Preferences) | 1.2.1 (в version catalog, не подключено) | [developer.android.com/jetpack/androidx/releases/datastore](https://developer.android.com/jetpack/androidx/releases/datastore) | 2026-08-30 | Последний стабильный релиз (11.03.2026); более новые 1.3.0-alpha* — не стабильны, не пинуются |
| WorkManager | 2.11.2 (в version catalog, не подключено) | [developer.android.com/jetpack/androidx/releases/work](https://developer.android.com/jetpack/androidx/releases/work) | 2026-08-30 | Последний стабильный релиз (25.03.2026). WorkManager 2.11+ поднял минимальный `minSdk` до API 23 — совместимо с `minSdk = 26` проекта |
| kotlinx-serialization | 1.11.0 (в version catalog, не подключено) | [github.com/Kotlin/kotlinx.serialization/releases](https://github.com/Kotlin/kotlinx.serialization/releases) | 2026-08-30 | Релиз 09.04.2026, собран на Kotlin 2.3.20; совместимость с Kotlin 2.4.10 проекта не тестировалась в этом PR (библиотека не подключена) — перепроверить при подключении в итерации 4 |
| JUnit | 4.13.2 | [Maven Central — junit:junit](https://mvnrepository.com/artifact/junit/junit) | 2026-08-30 | Подключено (`testImplementation`), актуальная стабильная версия линии JUnit 4 (JUnit 5 не используется — не требуется для текущей стратегии тестирования `ARCHITECTURE.md`) |
| AndroidX Test (core/runner/ext.junit) | core/runner 1.7.0, ext.junit 1.3.0 | [developer.android.com/jetpack/androidx/releases/test](https://developer.android.com/jetpack/androidx/releases/test) | 2026-08-30 | `androidx-test-ext-junit` (1.3.0) уже подключено и совпадает; core/runner 1.7.0 в проект напрямую не подключены отдельными записями (транзитивно достаточно для текущих тестов) |
| Espresso | 3.7.0 | [developer.android.com/jetpack/androidx/releases/test](https://developer.android.com/jetpack/androidx/releases/test) | 2026-08-30 | Подключено (`androidTestImplementation`), используется в `AppNavHostTest` через `Espresso.pressBackUnconditionally()` |
| Turbine | 1.2.1 (в version catalog, не подключено) | [github.com/cashapp/turbine/releases](https://github.com/cashapp/turbine/releases) | 2026-08-30 | Релиз 11.06.2026; понадобится для тестирования `Flow` в итерации 2+ |
| compileSdk | 37 | [developer.android.com/about/versions/17/setup-sdk](https://developer.android.com/about/versions/17/setup-sdk) | 2026-08-30 | Совпадает с требованием Compose 1.12 (минимум compileSdk 37) |
| targetSdk | 37 | Соответствует `compileSdk` — рекомендация Google target latest | 2026-08-30 | RuStore не публикует отдельного обязательного минимума `targetSdk` (см. ниже) |
| minSdk | 26 | `ARCHITECTURE.md`, ADR-009 | 2026-08-30 | Подтверждено без изменений: `java.time` без десугаринга, каналы уведомлений и адаптивные иконки нативно; RuStore-специфичная статистика распределения версий Android недоступна публично на момент проверки (см. ниже) |
| JDK | 17 | [developer.android.com/build/releases/about-agp](https://developer.android.com/build/releases/about-agp) (AGP 9.x требует JDK 17 для запуска Gradle); фактически использованный в сборках этой сессии — OpenJDK 17.0.19 (Homebrew) | 2026-08-30 | `compileOptions`/`kotlin.compilerOptions` в `app/build.gradle.kts` уже целятся в `JavaVersion.VERSION_17`/`JvmTarget.JVM_17` |

---

## Идентификаторы приложения

| Поле | Значение | Источник |
| --- | --- | --- |
| `applicationId` | `ru.poporyadku.app` | `app/build.gradle.kts`, `IMPLEMENTATION_PLAN.md` итерация 0 |
| `namespace` / корень пакета | `ru.poporyadku` | `app/build.gradle.kts` (`namespace = "ru.poporyadku"`) |
| `minSdk` | 26 (Android 8.0) | `ARCHITECTURE.md`, ADR-009 |

---

## Требования RuStore к публикации, подписи и `targetSdk`

Проверено по официальной документации RuStore ([rustore.ru/help/en/developers/publishing-and-verifying-apps/app-publication](https://www.rustore.ru/help/en/developers/publishing-and-verifying-apps/app-publication)) 2026-08-30:

- **Формат пакета.** RuStore принимает как `APK`, так и `AAB`.
- **Подпись.** Для `APK` — разработчик подписывает файл сам, подпись обязана совпадать между версиями одного приложения. Для `AAB` — подпись добавляется отдельно до загрузки файла (иной механизм, чем для `APK`).
- **Ограничение размера.** Загружаемый файл — не более 5 ГБ (не относится к бюджету APK из `IMPLEMENTATION_PLAN.md`, итерация 7, «до 15 МБ» — это ориентир проекта, а не лимит магазина).
- **`targetSdk`/`minSdk`.** Официальная документация RuStore **не публикует обязательного минимума или целевого уровня API** для приложений. Минимальная версия Android в карточке приложения RuStore подставляется автоматически из `minSdkVersion` манифеста приложения; отдельного магазинного гейта по `targetSdk`, аналогичного Google Play, в найденных официальных источниках не описано.
- **Статистика распределения версий Android среди пользователей RuStore.** Публично на момент проверки недоступна (в отличие от общей публичной статистики apilevels.com, использованной в `ARCHITECTURE.md` для обоснования `minSdk = 26`). Это честно зафиксировано как пробел, а не восполнено придуманными цифрами — соответствует поручению `IMPLEMENTATION_PLAN.md`, итерация 0: «Проверить свежую статистику распределения версий Android... если она доступна».

**Вывод.** Ни один из уже принятых параметров (`APK`/`AAB` через стандартный процесс подписи, `compileSdk`/`targetSdk` = 37, `minSdk` = 26) не входит в противоречие с публично известными требованиями RuStore. Финальная сверка перед реальной публикацией (итерация 7) должна повторить эту проверку по документации RuStore на тот момент — правила магазина могут измениться.
