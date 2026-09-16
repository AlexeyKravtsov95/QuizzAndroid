package ru.poporyadku

import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `I6-P5` — итоговый манифест (ITERATION_6_DESIGN.md, §10.4, I6-D41).
 *
 * Набор разрешений фиксируется **целиком**, а не списком запретов: новая зависимость,
 * втащившая своё разрешение, обязана быть замечена здесь, а не в магазине приложений.
 *
 * `ACCESS_NETWORK_STATE` приходит из WorkManager и **остаётся**: это normal-разрешение
 * на чтение состояния сети, доступа в сеть оно не даёт, а удалять транзитивное
 * разрешение библиотеки манифестным слиянием без документированной гарантии WorkManager
 * нельзя (§10.4).
 */
@RunWith(RobolectricTestRunner::class)
class ManifestPermissionsTest {

    /** `I6-P5`. Запрошенные разрешения равны зафиксированному набору — ни больше, ни меньше. */
    @Test
    fun `I6-P5 the merged manifest requests exactly the agreed permissions`() {
        assertEquals(EXPECTED_PERMISSIONS, requestedPermissions())
    }

    /** `I6-P5`. Сети нет: ни `INTERNET`, ни локальных сетевых разрешений. */
    @Test
    fun `I6-P5 no internet and no local network permissions`() {
        val requested = requestedPermissions()

        FORBIDDEN_PERMISSIONS.forEach { permission ->
            assertTrue("запрещённое разрешение в манифесте: $permission", permission !in requested)
        }
    }

    /** `I6-P5`. Разрешение показа уведомлений объявлено — без него 6B не работает на API 33+. */
    @Test
    fun `I6-P5 the notification permission is declared`() {
        assertTrue("android.permission.POST_NOTIFICATIONS" in requestedPermissions())
    }

    private fun requestedPermissions(): Set<String> {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return app.packageManager
            .getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            .toSet()
    }

    private companion object {
        /**
         * Сверено с `app/build/intermediates/merged_manifest/debug/…` 2026-09-16 (`I6-M13`).
         * Четыре разрешения WorkManager нужны перезапуску работы после перезагрузки и
         * Doze; собственное приложение объявляет только `POST_NOTIFICATIONS`.
         */
        val EXPECTED_PERMISSIONS = setOf(
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.WAKE_LOCK",
            // Сигнатурное разрешение AndroidX для незарегистрированных экспортируемых
            // приёмников; наружу ничего не открывает.
            "ru.poporyadku.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        )

        val FORBIDDEN_PERMISSIONS = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_LOCAL_NETWORK",
            "android.permission.NEARBY_WIFI_DEVICES",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_WIFI_STATE",
            "android.permission.CHANGE_WIFI_MULTICAST_STATE",
            "android.permission.CHANGE_NETWORK_STATE",
        )
    }
}
