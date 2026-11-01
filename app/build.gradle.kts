plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tj.tvsaver"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "tj.tvsaver"
        minSdk = 26
        targetSdk = 35
        versionCode = 17
        versionName = "2.6"
        // Только русский интерфейс — лишние языки из библиотек не тянем.
        resourceConfigurations += listOf("ru")
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Личное приложение: подписываем отладочным ключом, чтобы ставилось через adb.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // JPEG уже сжаты — не тратим время на повторное сжатие в APK.
    androidResources { noCompress += listOf("jpg") }

    // lint при release-сборке тянет ~100 МБ библиотек — для личного приложения не нужен.
    lint { checkReleaseBuilds = false }
}
