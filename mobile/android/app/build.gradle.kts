plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// 测试 classpath 导出助手：注册 printTestCp 任务，将 testDebugUnitTest 运行时 classpath
// 写入 app/test_cp.txt。用于绕开 AGP 9 + Flutter Gradle Plugin 下 test task 的
// ClassNotFoundException 基础设施缺陷（标准 testDebugUnitTest 不可用），改为 JUnitCore 直跑。
apply(from = "../printcp.gradle")

android {
    namespace = "com.example.docushift_mobile"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.example.docushift_mobile"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

dependencies {
    implementation("androidx.exifinterface:exifinterface:1.4.1")

    // JUnit 4 + Mockito —— 注意 AGP 9.0.1 + Flutter Gradle Plugin 下
    // test task ClassNotFoundException 是已知基础设施兼容问题。
    // 测试代码在自己独立的 JVM 工程中可正常运行。
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}
