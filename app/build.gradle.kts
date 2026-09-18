plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.trace"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.trace"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        // Generates typed ActivityMainBinding, ActivityTimelineBinding, etc.
        // Eliminates all findViewById() calls — each View ID becomes a direct property.
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // RecyclerView (for the Timeline screen)
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Camera
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // ML Kit — on-device object detection & image labeling (offline, no internet)
    implementation("com.google.mlkit:object-detection:17.0.1")
    implementation("com.google.mlkit:image-labeling:17.0.9")

//    // TensorFlow Lite — audio classifier (YAMNet) — uncomment when upgrading audio
//    implementation("org.tensorflow:tensorflow-lite:2.14.0")
//    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    configurations.all {
        resolutionStrategy {
            force("org.tensorflow:tensorflow-lite-api:2.14.0")
            force("org.tensorflow:tensorflow-lite-support-api:0.4.4")
        }
    }

    // Room database
    implementation("androidx.room:room-runtime:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}