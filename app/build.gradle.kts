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

ksp {
    // Export the Room schema so migrations can be diffed and verified instead
    // of written from memory. Committed under app/schemas/.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // RecyclerView (for the Timeline screen)
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // EncryptedSharedPreferences for Cloud AI API keys (key held in Android Keystore)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Camera
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    
    // Room database
    implementation("androidx.room:room-runtime:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Unit tests (ExampleUnitTest.kt)
    testImplementation("junit:junit:4.13.2")

    // Instrumented tests (ExampleInstrumentedTest.kt)
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}