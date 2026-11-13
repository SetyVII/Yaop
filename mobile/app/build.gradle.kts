plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.yaop"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "org.yaop"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testBuildType = "stress"

    buildTypes {
        // Stress / on-device tests: a separate applicationId means separate data and Keystore,
        // and no server token, so it never touches a real vault or syncs.
        create("stress") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".stress"
            matchingFallbacks += listOf("debug")
        }
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
            // Sign release builds with the debug keystore so they install over the debug build
            // with `adb install -r` (same signature) without wiping local data.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

// Room schema exports (app/schemas): keep them, a future migration test needs them.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

// androidTest reads the exported schemas (MigrationTestHelper fixture assets).
android {
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // Room & Encrypted Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.sqlcipher.android)

    // Serialization & Coroutines
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Media & Image Loading
    implementation(libs.coil.compose)

    // Security & Biometrics
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.security.crypto)

    // Vault crypto (spec/crypto.md): XChaCha20-Poly1305, HKDF-SHA256 and X25519 come
    // from BouncyCastle's low-level API (no provider registration, no network services); zstd
    // (level 3) compresses JSON payloads before encrypting (envelope version 0x02).
    implementation(libs.bouncycastle.bcprov)
    implementation("com.github.luben:zstd-jni:${libs.versions.zstd.get()}@aar")
    testImplementation(libs.zstd.jni)
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)

    testImplementation(libs.junit)
    testImplementation("org.json:json:20231013")
    // Room migration tests on device (MigrationTestHelper).
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Private differential runs over a real vault (tools/doc-diff.sh): forward the corpus paths.
tasks.withType<Test>().configureEach {
    listOf("YAOP_SPEC_DIR", "YAOP_ROUNDTRIP_DUMP").forEach { k ->
        System.getenv(k)?.let { environment(k, it) }
    }
    outputs.upToDateWhen { System.getenv("YAOP_ROUNDTRIP_DUMP") == null }
}
