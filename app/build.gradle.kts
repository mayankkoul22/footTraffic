plugins {
    id("com.android.application")
    id("kotlin-android")
    id("kotlin-kapt")
    id("kotlin-parcelize")
}

android {
    namespace = "com.datafy.foottraffic"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.datafy.foottraffic"
        minSdk = 31  // Android 12
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Only include 64-bit architectures for modern devices
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
    }

    buildTypes {
        // In Kotlin DSL, you use getByName to configure existing build types
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        getByName("debug") {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    buildFeatures {
        viewBinding = true
        mlModelBinding = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packagingOptions {
        resources {
            pickFirsts.add("META-INF/INDEX.LIST")
            pickFirsts.add("META-INF/io.netty.versions.properties")
            excludes.add("META-INF/DEPENDENCIES")
        }
    }
}

// By setting the toolchain here, you ensure Gradle uses Java 17
// for all compilation tasks, resolving the inconsistency.
kotlin {
    jvmToolchain(17)
}


dependencies {
    implementation("androidx.room:room-runtime:2.6.1")

    // Room compiler (annotation processor)
    kapt("androidx.room:room-compiler:2.6.1")

    // Optional: Kotlin extensions and coroutines support for Room
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-metadata:0.4.4")



    implementation("com.google.android.material:material:1.11.0")
    implementation("org.opencv:opencv:4.12.0")

    implementation("androidx.camera:camera-core:1.3.1")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")

    implementation("com.github.bumptech.glide:glide:4.16.0")
    kapt("com.github.bumptech.glide:compiler:4.16.0")

    // Add this to your app/build.gradle dependencies
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Assuming 'libs' are defined in your settings.gradle or gradle.properties via a version catalog
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}


