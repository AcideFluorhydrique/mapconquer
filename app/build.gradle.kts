// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.acidefluorhydrique.mapconquer"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.acidefluorhydrique.mapconquer"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // lint 的報告本來只被打包成 artifact，等於錯誤藏在一個要下載解壓才看得到的
    // zip 裡。textOutput 指向 stdout 之後，違規內容直接印在 CI log 上，
    // 讀 log 就知道是哪一條規則，不必為了看一行訊息再跑一次建置。
    lint {
        textReport = true
        textOutput = file("stdout")
        htmlReport = true
        xmlReport = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    testImplementation("junit:junit:4.13.2")
}
