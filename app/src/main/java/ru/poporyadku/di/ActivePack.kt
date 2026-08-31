package ru.poporyadku.di

import javax.inject.Qualifier

// ITERATION_2_DESIGN.md, D-20: активный пакет — параметр конструктора, а не константа
// в теле репозитория, чтобы тесты могли построить репозиторий с другим значением.
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ActivePack
