import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// ============================================================
//  本地密钥配置
//  ------------------------------------------------------------
//  真实密钥存放在根目录的 secrets.properties（不入库）。
//  首次使用请复制 secrets.properties.template 并按说明填写。
//  未创建该文件时仍可编译，但密钥为空，相关功能在运行时不可用。
// ============================================================
val secretsFile = rootProject.file("secrets.properties")
val secrets = Properties().apply {
    if (secretsFile.exists()) {
        secretsFile.inputStream().use { load(it) }
    } else {
        logger.warn(
            "⚠️  未找到 secrets.properties，地图/POS 搜索等功能将不可用。\n" +
            "   请执行：cp secrets.properties.template secrets.properties 并填入密钥。"
        )
    }
}

/** 读取密钥原文，缺失时返回空串，保证无配置也能编译通过。 */
fun secret(key: String): String = secrets.getProperty(key, "").trim()

/** 转义为 Java/Kotlin 字符串字面量，供 buildConfigField 使用。 */
fun quoted(key: String): String =
    "\"" + secret(key).replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.lhht.xiaozhi"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lhht.xiaozhi"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ---- 密钥注入（值来自本地 secrets.properties，不进源码）----
        // 腾讯地图 WebService API Key：SearchNaviActivity 做 POI 搜索时使用
        buildConfigField(
            "String",
            "TENCENT_MAP_WEBSERVICE_KEY",
            quoted("tencent.map.webserviceKey")
        )
        // 腾讯地图 Android SDK Key：写入 AndroidManifest 的 TencentMapSDK meta-data
        manifestPlaceholders["TENCENT_MAP_SDK_KEY"] = secret("tencent.map.sdkKey")

        // 为不同CPU架构编译native库
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
        
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++11"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }
    
    buildFeatures {
        viewBinding = true
        buildConfig = true   // 生成 BuildConfig 类，用于注入 TENCENT_MAP_WEBSERVICE_KEY
    }

    packagingOptions {
        pickFirst("lib/arm64-v8a/libc++_shared.so")
        pickFirst("lib/armeabi-v7a/libc++_shared.so")
        pickFirst("lib/x86/libc++_shared.so")
        pickFirst("lib/x86_64/libc++_shared.so")
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation("com.google.android.material:material:1.11.0")
    implementation(libs.activity)
    implementation(project(":sdk"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    
    // WebSocket
    implementation("org.java-websocket:Java-WebSocket:1.5.4")
    
    // WebRTC
    implementation("io.github.webrtc-sdk:android:104.5112.09")
    
    // ConstraintLayout
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // CameraX
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // Guava for ListenableFuture
    implementation("com.google.guava:guava:32.1.3-android")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // JSON
    implementation("org.json:json:20231013")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.0")
    // 地图库
    implementation ("com.tencent.map:tencent-map-vector-sdk:4.5.5.1-lite")
    // 导航库
    implementation ("com.tencent.map:tencent-map-nav-sdk:5.3.8.3")
    // 导航依赖库
    implementation ("com.tencent.map:tencent-map-nav-surport:1.1.0.1")
    implementation ("com.squareup.okhttp3:okhttp:4.12.0")
    implementation ("com.google.code.gson:gson:2.10.1")
    
    // LeakCanary - 内存泄漏检测（仅 debug 版本）
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.12")

    // MMKV - 高性能 key-value 存储
    implementation(libs.mmkv)
}