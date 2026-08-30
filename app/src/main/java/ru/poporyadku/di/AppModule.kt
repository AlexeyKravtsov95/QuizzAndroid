package ru.poporyadku.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Базовый модуль Hilt (ARCHITECTURE.md, раздел 1). Провайдеры данных, репозиториев и
 * use case'ов появляются вместе со слоем данных в итерации 2 — здесь пока не на чем
 * их строить.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule
