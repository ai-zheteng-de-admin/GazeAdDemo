import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// 正式签名配置：从项目根目录的 keystore.properties 读取（文件不存在时自动跳过，不影响 debug）
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.experiment.gazeforcedad"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.experiment.gazeforcedad"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            // 正式包只带 arm64-v8a：x86 / x86_64 / armeabi-v7a 的 .so 一律不打进来
            ndk {
                abiFilters += "arm64-v8a"
            }
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    androidResources {
        // .task 模型本身是 zip，若被二次压缩部分机型无法用 AssetFileDescriptor 直接打开
        noCompress += "task"
        // 内置广告视频不压缩：既保证 openFd 能读到长度做缓存校验，也避免无谓的解压开销
        noCompress += "mp4"
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // CameraX：前置摄像头取流（Preview + ImageAnalysis）
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // MediaPipe Tasks Vision：本地人脸/虹膜/头部姿态推理（含 native so）
    implementation(libs.mediapipe.tasks.vision)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
