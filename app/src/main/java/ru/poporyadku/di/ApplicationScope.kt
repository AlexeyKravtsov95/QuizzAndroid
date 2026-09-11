package ru.poporyadku.di

import javax.inject.Qualifier

/**
 * `CoroutineScope` процесса (ITERATION_5_DESIGN.md, §5.5, I5-D15).
 *
 * Живёт до смерти процесса и не зависит ни от Activity, ни от ViewModel: в нём работает
 * единственный worker очереди записи настроек, поэтому уход с экрана принятую команду
 * не отменяет. `SupervisorJob` — отказ одной корутины не гасит остальные.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
