plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.pirorin215.btclockmob"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pirorin215.btclockmob"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
        // Explicitly set ndkVersion if not already set, for better compatibility
        // Replace "25.2.9519653" with a version you have installed or want Gradle to download
            ndkVersion = "29.0.14206865" // Example NDK version from my environment
        }
        
        
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs += "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

        packaging {

            resources {

                excludes += "META-INF/INDEX.LIST"

                excludes += "META-INF/DEPENDENCIES"

                pickFirsts += "META-INF/LICENSE.md"

                pickFirsts += "META-INF/LICENSE.txt"

                pickFirsts += "META-INF/LICENSE"

            }

        }



    }

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)

    // Google Play Services Location
    implementation(libs.google.play.services.location)
    // Add this for Task.await() extension function
    implementation(libs.kotlinx.coroutines.play.services)

    // Google Sign-In
    implementation(libs.google.play.services.auth)

    // Google Cloud
    implementation(platform(libs.google.cloud.libraries.bom))
    implementation(libs.google.cloud.speech)
    implementation(libs.kotlinx.coroutines.guava)

    // gRPCトランスポートを追加 (Google Cloud Libraries BOMがバージョンを管理)
    implementation(libs.grpc.okhttp)

    // Gemini API for AI button feature
    implementation(libs.generativeai)

    // DataStore for persistent settings
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.composereorderable)

    // Vico for charts
    implementation(libs.vico.compose)
    implementation(libs.vico.compose.m3)
    implementation(libs.vico.core)

    // Koin for DI
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    testImplementation(libs.junit)
    testImplementation("io.mockk:mockk:1.13.5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("org.robolectric:robolectric:4.11.1")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}