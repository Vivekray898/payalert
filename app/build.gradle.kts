import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Base64
import java.util.Properties

plugins {
    // AGP 9 provides Kotlin support itself (built-in Kotlin); the standalone
    // org.jetbrains.kotlin.android plugin must NOT be applied alongside it.
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
}

// Public client settings only. Never load dotenv files or accept a service-role key here.
// Escape Java string literals rather than interpolating untrusted -P/environment values.
val localSecrets = Properties().apply {
    val file = rootProject.file("secrets.properties")
    if (file.isFile) {
        file.inputStream().use(::load)
    }
}

fun publicBuildConfigString(name: String): String {
    val value = providers.gradleProperty(name)
        .orElse(providers.environmentVariable(name))
        .orElse(localSecrets.getProperty(name, ""))
        .getOrElse("")
        .trim()
    if (name == "SUPABASE_PUBLISHABLE_KEY" && value.isNotEmpty()) {
        val publicKey = value.matches(Regex("sb_publishable_[A-Za-z0-9_-]{6,}")) || runCatching {
            val parts = value.split('.')
            parts.size == 3 && parts.all { it.matches(Regex("[A-Za-z0-9_-]+")) } &&
                (JsonSlurper().parseText(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8))
                    as? Map<*, *>)?.get("role") == "anon"
        }.getOrDefault(false)
        check(publicKey && value.length <= 4096) {
            "SUPABASE_PUBLISHABLE_KEY must be a public publishable key or legacy anon JWT. Never embed a secret or service-role key."
        }
    }
    return buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 32) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }
}

android {
    namespace = "com.vivekray898.payalert"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.vivekray898.payalert"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        buildConfigField("String", "SUPABASE_URL", publicBuildConfigString("SUPABASE_URL"))
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", publicBuildConfigString("SUPABASE_PUBLISHABLE_KEY"))
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Apply late so google-services can read the Google Services JSON before it is consumed.
// google-services plugin must be applied after AGP has defined the android block.
// (The plugin is declared in the `plugins {}` block above; the ordering here is a safety net
// for any future build script that moves it.)

dependencies {
    // Kotlin / coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // AndroidX core / lifecycle / activity
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Compose (BOM keeps the compose artifacts consistent)
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // JSON: parses the parser rules asset with a pure-JVM library so the loader
    // itself is unit-testable (org.json is stubbed in unit tests).
    implementation("com.google.code.gson:gson:2.11.0")

    // Raw X25519 primitives are used because Android Keystore X25519 support is
    // provider/API dependent below API 29. The private key is still wrapped by
    // an Android Keystore AES key in relay.AndroidKeystoreRelayPrivateKeyStorage.
    implementation("org.bouncycastle:bcprov-jdk18on:1.80.2")

    // QR generation for the UPI payment-code screen (pure JVM, no Android deps).
    implementation("com.google.zxing:core:3.5.3")

    // Room (offline-first local database)
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    // Local relay expiry is scheduled as a persisted background task.
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    // Firebase Cloud Messaging for push-based delivery fallback.
    implementation(platform("com.google.firebase:firebase-bom:33.1.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    // Unit tests (pure-JVM; parser/dedup/format logic has no Android dependencies)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    testImplementation("androidx.room:room-testing:2.8.5")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.02.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    constraints {
        // Android instrumentation needs 1.2.0; keep the tested APK's locked runtime aligned.
        debugImplementation("androidx.concurrent:concurrent-futures:1.2.0")
    }
}

dependencyLocking { lockAllConfigurations() }
