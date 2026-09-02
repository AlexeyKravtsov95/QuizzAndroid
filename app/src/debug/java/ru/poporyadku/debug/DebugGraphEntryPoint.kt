package ru.poporyadku.debug

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.domain.content.ContentInstaller
import ru.poporyadku.domain.repository.DayAssignmentRepository
import ru.poporyadku.domain.repository.ProgressRepository

/**
 * Доступ к продуктовым синглтонам графа приложения из debug-сборки.
 *
 * Существует ради детерминированной подготовки и очистки состояния базы в
 * instrumented-тестах — навигации (`AppNavHostTest`) и сквозного дня (`FullDayFlowTest`):
 * без этого они зависели бы от того, что уже накопило устройство, а порядок выполнения
 * тестов JUnit не гарантирует.
 *
 * Живёт в `src/debug`, а не в `androidTest`, потому что `@EntryPoint` обязан быть
 * обработан вместе с графом САМОГО приложения: интерфейс, объявленный в `androidTest`,
 * попадает в отдельный компонент, и `EntryPointAccessors.fromApplication` падает с
 * `ClassCastException`. Альтернатива — `hilt-android-testing` с `HiltTestApplication`,
 * то есть новая библиотека, которую граница итерации 3 не допускает.
 *
 * Частью удалённого в PR 3D debug-экрана не является и переживает его: инструмент
 * итерации 2 снят, а детерминированная подготовка базы в тестах нужна по-прежнему.
 *
 * В release не компилируется, поэтому в продуктовую сборку не попадает. Ни одной
 * тестовой реализации не отдаёт — только те же экземпляры, которыми пользуется
 * работающее приложение.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugGraphEntryPoint {
    fun database(): AppDatabase
    fun content(): ContentInstaller
    fun assignments(): DayAssignmentRepository
    fun progress(): ProgressRepository
}
