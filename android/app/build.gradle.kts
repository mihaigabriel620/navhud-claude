import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Keep the API key out of git: put MAPBOX_TOKEN=pk.eyJ... in local.properties
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val mapboxToken: String = (localProps.getProperty("MAPBOX_TOKEN")
    ?: System.getenv("MAPBOX_TOKEN") ?: "")

android {
    namespace = "com.mihai.navhud"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mihai.navhud"
        minSdk = 24          // Android 7.0 -- covers old head units too
        targetSdk = 34
        versionCode = 35
        versionName = "1.29"

        // MapLibre ships native libraries for four CPU architectures, which
        // triples the APK for no benefit: every Android head unit and phone in
        // the last decade is ARM. x86 builds are for emulators only.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        buildConfigField("String", "MAPBOX_TOKEN", "\"$mapboxToken\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Self-signed release key. A personal sideloaded app needs a stable
    // signature so updates install over the top of each other -- keep this
    // keystore, or Android will refuse the next version.
    signingConfigs {
        create("release") {
            val ks = rootProject.file("navhud-release.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = localProps.getProperty("KEYSTORE_PASSWORD") ?: "navhud2026"
                keyAlias = localProps.getProperty("KEY_ALIAS") ?: "navhud"
                keyPassword = localProps.getProperty("KEY_PASSWORD") ?: "navhud2026"
                // v2 alone is valid from API 24, but some head-unit file managers
                // and sideloading tools still look for the old JAR signature.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Left off deliberately: osmdroid and usb-serial-for-android both do
            // enough reflection that shrinking is a good way to get a crash that
            // only happens in release.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
                          "proguard-rules.pro")
            if (rootProject.file("navhud-release.jks").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    // The USB serial driver. Handles CH340, CP210x, FTDI, PL2303 and CDC-ACM.
    implementation("com.github.mik3y:usb-serial-for-android:3.10.0")
    // Vector map. MapLibre rather than a raster view because pitch and smooth
    // rotation -- the two things that make a nav map feel like one -- are not
    // possible with raster tiles. Tiles come from OpenFreeMap, which needs no key.
    implementation("org.maplibre.gl:android-sdk:11.8.0")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub that throws in unit tests; the real one
    // has to come first on the test classpath for the JSON parsing to be testable.
    testImplementation("org.json:json:20240303")
}
