plugins {
    id("com.android.application")
}

android {
    namespace = "com.bilisten.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.bilisten.app"
        minSdk = 24
        targetSdk = 37
        versionCode = 13
        versionName = "1.11"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("bilisten-release.jks")
            storePassword = "bilisten2026"
            keyAlias = "bilisten"
            keyPassword = "bilisten2026"
        }
    }

    buildTypes {
        // debug 也使用正式密钥签名：否则 Studio 的调试包无法覆盖安装正式版，
        // 会报 "install-create" 失败 / INSTALL_FAILED_UPDATE_INCOMPATIBLE
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil:2.7.0")

    // 二维码生成（纯 Java，无 Android 依赖），用于扫码快捷登录
    implementation("com.google.zxing:core:3.5.3")
}
