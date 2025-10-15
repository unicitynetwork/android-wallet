import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24"
    id("kotlin-kapt")
}

// P2P messaging configuration

// Load local.properties
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

android {
    namespace = "org.unicitylabs.wallet"
    compileSdk = 34
    
    testOptions {
        unitTests {
            // This makes Android SDK methods available in unit tests
            // by returning default values when Android classes are used
            isReturnDefaultValues = true
        }
    }

    defaultConfig {
        applicationId = "org.unicitylabs.wallet"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Add Maps API key from local.properties
        manifestPlaceholders["MAPS_API_KEY"] = localProperties.getProperty("MAPS_API_KEY", "")
        
        // Room schema export
        javaCompileOptions {
            annotationProcessorOptions {
                arguments["room.schemaLocation"] = "$projectDir/schemas"
            }
        }
    }

    signingConfigs {
        create("release") {
            // Load from local.properties if available
            val keystoreFile = localProperties.getProperty("RELEASE_STORE_FILE")
            val keystorePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
            val keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
            val keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")

            if (keystoreFile != null && file(keystoreFile).exists()) {
                storeFile = file(keystoreFile)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Custom APK name for debug builds
            applicationIdSuffix = ".debug"
        }
        release {
            // Disable minification to avoid R8 breaking reflection-based libraries
            // This makes APK larger but ensures compatibility
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")

            // Use release signing if configured
            if (localProperties.getProperty("RELEASE_STORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Rename output APK files
    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val versionName = defaultConfig.versionName
            output.outputFileName = "unicity-wallet-${versionName}-${buildType.name}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    lint {
        abortOnError = false
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    // Unicity Nostr SDK (includes Nostr client, crypto, nametag binding, token transfer)
    implementation(project(":unicity-nostr-sdk"))

    // Unicity Java SDK: https://jitpack.io/#org.unicitylabs/java-state-transition-sdk
    implementation("org.unicitylabs:java-state-transition-sdk:1.2.0")

    // Apache Commons Codec for hex encoding (used by Nostr SDK and wallet)
    implementation("commons-codec:commons-codec:1.16.0")

    // Required dependencies for Unicity SDK (also used app-wide for JSON serialization)
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-cbor:2.17.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.17.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.81")
    implementation("com.google.guava:guava:33.0.0-android")
    
    // SLF4J dependencies for Android (required by Unicity SDK)
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("com.github.tony19:logback-android:3.0.0")
    
    // OkHttp already included below
    
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.8")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // ViewModel and LiveData
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    
    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.5")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.5")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // JSON handling - using Jackson (also required by Unicity SDK)
    // Note: Gson removed in favor of Jackson for consistency

    // Kotlin Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    // SwipeRefreshLayout
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-jackson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Glide for image loading and caching
    implementation("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.16.0")
    kapt("com.github.bumptech.glide:compiler:4.16.0")
    
    // QR Code generation
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.2")
    
    // BIP-39 for seed phrase generation
    implementation("cash.z.ecc.android:kotlin-bip39:1.0.7")
    
    // Google Drive API for backup
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("com.google.api-client:google-api-client-android:2.2.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20240730-2.0.0")
    
    // Google Maps and Location Services
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation("com.google.android.gms:play-services-location:21.2.0")
    
    // Room Database for chat storage
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    annotationProcessor("androidx.room:room-compiler:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    
    // WebSocket for P2P
    implementation("org.java-websocket:Java-WebSocket:1.5.6")
    
    // P2P messaging using native WebSocket and NSD (Network Service Discovery)
    
    // Network Service Discovery (NSD) is built into Android
    
    // Nostr P2P dependencies - using secp256k1 and spongycastle for crypto
    implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.10.1")
    implementation("com.madgag.spongycastle:core:1.58.0.0")
    implementation("com.madgag.spongycastle:bcpkix-jdk15on:1.58.0.0")

    // Phone number normalization for deterministic contact discovery
    implementation("com.googlecode.libphonenumber:libphonenumber:8.13.51")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:4.11.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:4.1.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("org.testcontainers:testcontainers:1.19.3")
    testImplementation("org.testcontainers:junit-jupiter:1.19.3")
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.81")
    testImplementation("com.madgag.spongycastle:core:1.58.0.0")
    testImplementation("fr.acinq.secp256k1:secp256k1-kmp-jvm:0.10.1")
    
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.6.8")
    
    // Mockito for testing (Java 8 compatible versions)
    androidTestImplementation("org.mockito:mockito-android:4.11.0")
    androidTestImplementation("org.mockito.kotlin:mockito-kotlin:4.1.0")
    
    // Coroutines test
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    
    debugImplementation("androidx.compose.ui:ui-tooling:1.6.8")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.6.8")
}

// JS SDK bundle no longer needed - using Java SDK now

// Configure kapt for Java 17 compatibility
kapt {
    correctErrorTypes = true
    javacOptions {
        option("-source", "11")
        option("-target", "11")
    }
}

// Add compiler arguments for Java 17 compatibility
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-Xjvm-default=all",
            "-opt-in=kotlin.RequiresOptIn"
        )
    }
}
// Custom tasks for debug and release builds
tasks.register("debug") {
    group = "build"
    description = "Build debug APK"
    dependsOn(":app:assembleDebug")
}

tasks.register("release") {
    group = "build"
    description = "Build release APK"
    dependsOn(":app:assembleRelease")
}
