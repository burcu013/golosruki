plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.alexandr.golosruki"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.alexandr.golosruki"
        minSdk = 26
        targetSdk = 34
        versionCode = 94
        versionName = "6.63"
    }

    signingConfigs {
        create("stable") {
            storeFile = file("golosruki.keystore")
            storePassword = "golosruki"
            keyAlias = "golosruki"
            keyPassword = "golosruki"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("stable") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")

    // Офлайн-распознавание речи (русский)
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    // Вариант B: встроенный adb-клиент для выдачи разрешения по коду сопряжения (без Termux)
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    // выравнено с транзитивной версией libadb (bcprov-jdk15to18:1.81), иначе дубли классов
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    // ИИ на устройстве (этап 1: самая лёгкая модель Gemma-3 1B int4)
    implementation("com.google.mediapipe:tasks-genai:0.10.27")
}
