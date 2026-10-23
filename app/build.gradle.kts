plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // Takes in a profile recorded by :baselineprofile, only when asked to
    // (docs/baseline-profile.md); a normal build is unchanged.
    id("androidx.baselineprofile")
}

android {
    namespace = "com.openauto.dash"
    compileSdk = 36

    // Release signing key, supplied by CI via env vars. Local builds may fall
    // back to the debug key; CI must not, because a debug-signed release can
    // never be updated in place by a later properly signed one.
    val keystorePath = providers.environmentVariable("KEYSTORE_FILE").orNull
    val releaseKeystore = keystorePath?.let { file(it) }?.takeIf { it.exists() }
    val onCi = providers.environmentVariable("CI").orNull == "true"
    // Enforced only when a release task is actually scheduled: the lint / unit
    // test workflow builds debug and never prepares a keystore.
    gradle.taskGraph.whenReady {
        val buildsRelease = allTasks.any { it.project == project && it.name.contains("Release") }
        if (onCi && releaseKeystore == null && buildsRelease) {
            throw GradleException(
                "CI release build without a keystore: set the KEYSTORE_BASE64 / " +
                    "KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD secrets."
            )
        }
    }

    defaultConfig {
        applicationId = "com.openauto.dash"
        minSdk = 27
        targetSdk = 36
        // Version is driven by CI (the Actions run number) so each build is
        // newer than the last; defaults keep local builds working.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0"

        // Where the in-app updater looks for new releases.
        buildConfigField("String", "GITHUB_OWNER", "\"deviloufr-ai\"")
        buildConfigField("String", "GITHUB_REPO", "\"Dashwheel\"")

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Two editions from one code base. "github" is the full app shipped from
    // GitHub Releases, root and firmware hooks included. "play" is the Google
    // Play edition: no root, no internal ADB, no PMPatch3, no self-update, and
    // only permissions Play accepts for a launcher. Gate code on Edition.play.
    flavorDimensions += "edition"
    productFlavors {
        create("github") {
            dimension = "edition"
            buildConfigField("boolean", "PLAY", "false")
            // Keep rules for the libraries only this edition ships (dadb).
            proguardFile("proguard-github.pro")
        }
        create("play") {
            dimension = "edition"
            // Its own package on Google Play: the GitHub edition keeps com.openauto.dash,
            // so the two never overwrite each other (their signing keys differ anyway).
            applicationId = "com.dashwheel.app"
            buildConfigField("boolean", "PLAY", "true")
        }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            // R8 + resource shrinking: the APK ships over the in-app updater to a
            // head unit, so size matters. Keep rules live in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            // Head units are ARM. MapLibre and Filament otherwise also ship their
            // x86 / x86_64 native code (emulators only), tens of MB in every OTA
            // download and in the /system copy. Debug builds keep every ABI.
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
            // Sign with the persistent release key when available, otherwise the
            // debug key so the APK is still installable.
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // The Shizuku shell service's interface (app/src/github/aidl, ShizukuShell.kt).
        aidl = true
    }

    androidResources {
        // The languages the app is translated into (see tools/check_translations.py);
        // drops the dozens of others the libraries bring, keeping the APK small.
        localeFilters += listOf("en", "fr", "de", "es", "it", "pt", "nl", "pl", "ru", "hu", "lt")
    }

    // The language can be switched inside the app, so every build carries them all.
    bundle {
        language { enableSplit = false }
    }

    lint {
        // Errors fail CI; warnings (unused resources, newer versions) do not.
        abortOnError = true
        checkReleaseBuilds = false
    }

    testOptions {
        // Pure-logic tests touch android.util.Log and org.json through the SDK
        // stubs; let those return defaults instead of throwing.
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2,LOCF}"
            excludes += "/META-INF/LGPL3.0"
        }
    }
}

// Compose compiler: stability settings (compose_stability.conf), plus compiler
// reports and metrics only when asked for, so normal builds are unchanged:
//   ./gradlew :app:compileGithubReleaseKotlin --rerun -PcomposeReports
// then read app/build/compose_compiler/ (*-classes.txt, *-composables.txt, *-module.json).
// --rerun: Gradle does not count these settings as compile inputs, so an
// up-to-date compile writes no reports. After editing the .conf, rebuild with
// --rerun too, or the compile may be skipped as up to date.
composeCompiler {
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose_stability.conf"))
    if (providers.gradleProperty("composeReports").isPresent) {
        reportsDestination = layout.buildDirectory.dir("compose_compiler")
        metricsDestination = layout.buildDirectory.dir("compose_compiler")
    }
}

// The map SDK is held at 10.x against the navigation SDK's wishes.
//
// Both navigation modules ask for android-sdk 11.8.1, and Gradle's default
// answer to two versions is the higher one — so a plain dependency on 10.3.7 is
// quietly upgraded back to 11.8.1 and the ES 2.0 units lose the map again, with
// nothing in the build log to say so. Only force() settles it.
//
// This is safe because of what the navigation SDK actually touches: of its own
// classes, only MapLibreLocationEngine reaches into the map SDK (five classes in
// org.maplibre.android.location.engine), and this app substitutes its own engine
// so that class is never loaded. Everything else it uses from the map SDK lives
// in navigation-ui, which is likewise confined to ManeuverView here.
configurations.configureEach {
    resolutionStrategy.force("org.maplibre.gl:android-sdk:10.3.7")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")

    // Material Components — provides the XML Theme.Material3 parent used by the
    // Activity theme (Compose UI itself uses androidx.compose.material3).
    implementation("com.google.android.material:material:1.12.0")

    // Jetpack Compose BOM (Compose 1.7.x; older BOMs ship lint checks that crash on Kotlin 2.1 metadata)
    val composeBom = "2024.12.01"
    implementation(platform("androidx.compose:compose-bom:$composeBom"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Activity Compose
    implementation("androidx.activity:activity-compose:1.9.2")
    // Installs the baseline profile (src/main/baseline-prof.txt) on the head
    // unit's Android 10, which does not read profiles from the APK by itself:
    // the launcher's startup and swipe paths are compiled ahead of time
    // instead of running interpreted until the runtime notices them.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Pure-Kotlin ADB client — lets the app self-install to /system/priv-app
    // over the head unit's root wireless-ADB socket (no Magisk/su needed).
    // GitHub edition only: the Play edition never opens the unit's ADB.
    "githubImplementation"("dev.mobile:dadb:1.2.10")
    // Reaches the @hide input calls that pass the tile's touches to Google Maps inside it (EmbeddedApp.kt),
    // through HiddenApi. GitHub edition only: Google Play refuses API-bypass SDKs.
    "githubImplementation"("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    // Shizuku (ShizukuShell.kt): the shell without root, through the Shizuku app the driver
    // starts over wireless debugging or root. GitHub edition only, like the other shells.
    "githubImplementation"("dev.rikka.shizuku:api:13.1.5")
    "githubImplementation"("dev.rikka.shizuku:provider:13.1.5")
    // MapLibre GL — free/open-source map (OpenFreeMap style, no token/API key).
    //
    // Pinned to the 10.x line, and it has to stay there. MapLibre 11.0.0 moved
    // the renderer to OpenGL ES 3.0, and the head units this app is for top out
    // at ES 2.0 (Mali-450 on the AC8227L boards, which report
    // ro.opengles.version = 0x20000): on those, 11.x finds no matching EGL
    // config, hands eglCreateContext a null, and takes the app down from its own
    // render thread. There is no fallback to ask for — 11.x deleted the ES 2.0
    // backend outright (MLN_LEGACY_RENDERER is a fatal error in its CMake).
    // 10.3.7 is the last release of that line, and still maintained.
    //
    // 11.0.0 is also where the packages were renamed, so 10.x is
    // com.mapbox.mapboxsdk.*, not org.maplibre.android.* (MapLibrePanel.kt).
    // Filament: the driver's own car live in 3D on the Car problems card (Car3D.kt),
    // drawn only while it moves.
    implementation("com.google.android.filament:filament-android:1.71.5")
    implementation("com.google.android.filament:gltfio-android:1.71.5")
    implementation("com.google.android.filament:filament-utils-android:1.71.5")
    implementation("org.maplibre.gl:android-sdk:10.3.7")
    // MapLibre Navigation — free turn-by-turn (routing via a free Valhalla server).
    //
    // Stays on 5.x even though the map SDK went back to 10.x. navigation-core
    // touches the map SDK in exactly one class, MapLibreLocationEngine, which
    // this app does not use: InAppNav supplies its own engine over the
    // dashboard's existing location feed (FeedLocationEngine.kt), so that class is
    // never loaded and the routing, milestones and off-route detection around it
    // run untouched.
    //
    // navigation-ui IS bound to the map SDK, deeply, so nothing of it may be used
    // but ManeuverView — a plain Canvas view that draws the turn arrow and
    // references no map class at all. The route line is drawn by our own
    // RouteLine.kt instead of its NavigationMapRoute.
    implementation("org.maplibre.navigation:navigation-core:5.0.0-pre8")
    implementation("org.maplibre.navigation:navigation-ui-android:5.0.0-pre8") {
        // This and the map SDK's own geojson both carry com.mapbox.geojson.*.
        // Keep the map SDK's: its GeoJsonSource is compiled against that one.
        exclude(group = "com.mapbox.mapboxsdk", module = "mapbox-sdk-geojson")
    }
    // HTTP + JSON for the free routing/geocoding requests.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // TLS 1.3 for an Android that has none (ModernTls.kt). The routing and
    // Overpass servers refuse TLS 1.2 outright, so without this the units on
    // Android 8.1 can draw the map but never get a route. OkHttp picks the
    // provider up by itself once it is registered.
    implementation("org.conscrypt:conscrypt-android:2.5.2")
    implementation("com.google.code.gson:gson:2.11.0")
    // Words read off a navigation app's tile (TileText): the Latin recognizer Play services ship.
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    // Phone link: the protocol shared with the companion app, and the pairing QR code.
    implementation(project(":link"))
    // Head unit firmware families: detection rules and vendor wire formats (UnitPlatform.kt).
    implementation(project(":unitproto"))
    implementation("com.google.zxing:core:3.5.3")
    // USB OBD adapters (UsbObd.kt): FTDI, CH34x, PL2303, CP210x and CDC serial chips over Android's USB host API.
    implementation("com.github.mik3y:usb-serial-for-android:3.10.0")
    // The Play edition's tips (TipJar.kt): Google Play's own billing, as Play requires.
    "playImplementation"("com.android.billingclient:billing-ktx:8.0.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    // Where a recorded baseline profile comes from (docs/baseline-profile.md).
    "baselineProfile"(project(":baselineprofile"))

    // JVM unit tests (app/src/test): grid placement, OBD decoding, directions
    // parsing, layout JSON. org.json is a real implementation on the JVM since
    // the SDK stub only returns defaults.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
