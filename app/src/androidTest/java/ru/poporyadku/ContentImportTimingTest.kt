package ru.poporyadku

import android.app.Application
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import ru.poporyadku.core.model.ContentPack
import ru.poporyadku.debug.DebugGraphEntryPoint

/**
 * Устройство-половина диагностического замера `I4-C6` (ITERATION_4_DESIGN.md, §16, §17).
 *
 * Меряет полный импорт настоящего пакета в чистую базу на ТОМ устройстве, где запущен
 * набор, и печатает модель, API и время в logcat (тег [TAG]). Порога нет и не будет:
 * решение о выносе установки из `mapLatest` принимает человек по замеру на реальном
 * устройстве; число с эмулятора такого решения не заменяет.
 *
 * Меряется продуктовый экземпляр `ContentInstaller` из графа приложения. Полный путь
 * гарантирован очисткой базы перед каждым прогоном: совпавшая отметка в DataStore
 * раннего выхода не даёт, пока его не подтвердит база. Первый прогон в процессе
 * включает чтение заголовка пакета; дальше заголовок берётся из кэша ассетов процесса.
 */
@RunWith(AndroidJUnit4::class)
class ContentImportTimingTest {

    private val deps: DebugGraphEntryPoint by lazy {
        EntryPointAccessors.fromApplication(
            ApplicationProvider.getApplicationContext<Application>(),
            DebugGraphEntryPoint::class.java,
        )
    }

    @After
    fun cleanUp() = clearDatabase()

    @Test
    fun i4C6FullImportDurationIsMeasuredOnTheDeviceWithoutAThreshold() {
        val millis = (1..RUNS).map {
            clearDatabase()
            val started = SystemClock.elapsedRealtimeNanos()
            runBlocking { deps.content().ensureInstalled() }
            val elapsed = (SystemClock.elapsedRealtimeNanos() - started) / NANOS_PER_MILLI
            assertNotNull(
                "полный путь: пакет установлен",
                runBlocking { deps.sets().getSet(ContentPack.CORE_RU, 0) },
            )
            elapsed
        }

        Log.i(
            TAG,
            "I4-C6 device: ${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}, " +
                "debug-сборка (целостность включена): первый в процессе " +
                "${"%.1f".format(millis.first())} мс; повторные " +
                millis.drop(1).joinToString { "%.1f".format(it) } + " мс",
        )
    }

    private fun clearDatabase() = runBlocking {
        withContext(Dispatchers.IO) { deps.database().clearAllTables() }
    }

    private companion object {
        const val TAG = "I4-C6"
        const val RUNS = 3
        const val NANOS_PER_MILLI = 1_000_000.0
    }
}
