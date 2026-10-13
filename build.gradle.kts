plugins {
    kotlin("android")
    kotlin("android.extensions")
    compose("1.6.1")
}

repositories {
    mavenCentral()
    google()
}

android {
    namespace = "com.vivekray898.payalert"

    compileSdk = 34

    defaultConfig {
        applicationId = "com.vivekray898.payalert"
        minSdk = 24
        targetSdk = 34

        // Enable Jetpack compose compilation avoidance
        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf(
                    "supabasePublishableUrl" to findProperty("SUPABASE_PUBLISHABLE_URL"),
                    "supabasePublishableKey" to findProperty("SUPABASE_PUBLISHABLE_KEY")
                )
            }
        }
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.6.1"
    }

    defaultConfig {
        // MultiDex enabled for phones with many classes, but we keep it simple for now
        // if we exceed 65K methods
        multiDexEnabled true
    }
}

dependencies {

    // Kotlin stdlib
    kotlin("stdlib-jdk8")

    // AndroidX
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle.lifecycle-viewmodel-ktx:2.8.0")
    implementation("androidx.lifecycle.lifecycle-livedata-ktx:2.8.0")
    implementation("androidx.annotation:annotation:1.6.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.3")
    implementation("com.google.android.material:material:1.12.0")

    // Compose BOM (Bill of Materials)
    implementation(platform("androidx.compose:compose-bom:2024.11.00"))
    // Core compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-unit")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Material design 3
    implementation("androidx.compose.material3:material3")
    // Activity
    implementation("androidx.activity:activity-compose:1.8.2")
    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.0")

    // ---------------------------------------------------
    // Local data & persistence
    // ---------------------------------------------------
    // Room for offline-first database
    implementation("androidx.room:room-ktx:2.5.2")
    implementation("androidx.room:room-rxjava2:2.5.2")
    // RXJava optional — keep if needed, otherwise just Room DAOs
    // implementation("io.reactivex.rxjava2:rxjava:2.2.21")
    // implementation("io.reactivex.rxjava2:rxandroid:3.6.0")

    // ---------------------------------------------------
    // Network sync (Supabase client)
    // ---------------------------------------------------
    // Supabase Kotlin client (v2.x). Add the dependency; pin version later.
    // implementation("com.supabase:supabase-kotlin-client:2.5.6")
    // If the Supabase client isn't available via Maven, we'll use the
    // REST API via OkHttp or just HttpURLConnection in the repo layer.

    // ---------------------------------------------------
    // Barcode scanner (journeyapps)
    // ---------------------------------------------------
    //implementation("com.journeyapps:barcodeScanner:2.2.0") // optional, camera permission needed

    // ---------------------------------------------------
    // JSON handling
    // ---------------------------------------------------
    implementation("com.google.code.gson:gson:2.10.1")

    // ---------------------------------------------------
    // Supabase Realtime (if needed) — keep commented for now,
    // implement with plain HTTPS upsert + offline queue.
    // ---------------------------------------------------

    // ---------------------------------------------------
    // AndroidX Test (test-only)
    // ---------------------------------------------------
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.rules:monitoring:1.6.2")
}