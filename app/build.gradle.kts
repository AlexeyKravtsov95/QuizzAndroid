// AGP 9 включает built-in Kotlin по умолчанию — org.jetbrains.kotlin.android не применяется.
// org.jetbrains.kotlin.kapt несовместим с built-in Kotlin, поэтому Hilt (на kapt — Dagger KSP
// пока alpha) использует официальный com.android.legacy-kapt той же версии, что AGP.
// kotlin.plugin.compose остаётся: built-in Kotlin по-прежнему требует отдельного
// Compose-компилятора; kotlin.plugin.serialization — по той же причине отдельный
// плагин компилятора (ITERATION_4_DESIGN.md, §13, PR 4B; совместимость со встроенным
// Kotlin AGP 9 проверена эмпирически, docs/VERSIONS.md).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.legacy.kapt)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Каталог общих фикстур валидатора контента (tools/validate-content/fixtures).
// Вычисляется от корня проекта, а не от `projectDir` модуля и не от cwd Gradle.
val contentFixturesDir: String =
    rootProject.layout.projectDirectory.dir("tools/validate-content/fixtures").asFile.absolutePath

android {
    namespace = "ru.poporyadku"
    compileSdk = 37

    defaultConfig {
        applicationId = "ru.poporyadku.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            // ARCHITECTURE.md, раздел 10: обфускация R8 в release.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // ITERATION_4_DESIGN.md, §8.7: единственный потребитель BuildConfig — провайдер
        // @VerifyBundleIntegrity в ContentModule. Проверка целостности пакета — свойство
        // сборки, а не тип сборки в теле класса; в тестах она задаётся обычным
        // параметром конструктора, без Robolectric.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // ITERATION_2_DESIGN.md, D-2: Robolectric на JDK 17 требует явные --add-opens.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // ITERATION_4_DESIGN.md, §7.4: JVM-тесты parity читают ТОТ ЖЕ каталог
                // фикстур, что и Python-CLI. Путь абсолютный и вычислен от rootProject,
                // поэтому не зависит от рабочего каталога Gradle; копии в
                // app/src/test/resources не заводится — две копии разошлись бы молча.
                it.systemProperty("content.fixtures.dir", contentFixturesDir)
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
}

// Room Gradle Plugin, D-14: расширение верхнего уровня, регистрируется на Project,
// а не внутри android { }. schemaDirectory обязателен для плагина и коммитится в git.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    kapt(libs.hilt.android.compiler)

    // Room, D-1/D-14/D-15: генерируется через KSP, а не kapt; room-runtime включает
    // API, слитые из KTX-артефакта с релиза 2.7 (D-15), и Room.inMemoryDatabaseBuilder —
    // отдельный testing-артефакт не подключается (D-22).
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    // DataStore, D-18/D-12: единственный потребитель — UserPreferencesRepositoryImpl
    // и PreferencesModule.
    implementation(libs.androidx.datastore.preferences)

    // kotlinx-serialization, ITERATION_4_DESIGN.md §7.5 и §9.2: два разных Json —
    // терпимый @AssetJson для пакета из assets и строгий @StorageJson для JSON-колонок
    // Room. Потребители — только data/content/** и data/db/**.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    // I3-D31: Compose-тесты экранов идут в src/test под Robolectric и выполняются в CI.
    // platform(bom) нужен здесь же — без него у ui-test-junit4 нет версии.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.navigation.testing)
}

kapt {
    correctErrorTypes = true
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
