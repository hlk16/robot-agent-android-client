plugins {
    alias(libs.plugins.android.application)
}

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
    
    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // JSON
    implementation("org.json:json:20231013")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.0")
    implementation (files("libs/SparkChain.aar"))
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
}