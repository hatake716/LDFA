import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Play upload signing. keystore.properties is machine-local (gitignored) and
// points at a keystore OUTSIDE the repo — see keystore.properties.example and
// tools/release/README.md. When absent (CI, fresh clones), the release build
// stays unsigned rather than failing.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.hatake716.linuxdesktop"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        // Play rejects publishing under Termux's owned package name "com.termux"
        // (impersonation). Use our own namespace, which also matches `namespace`
        // above so the app's real data dir is /data/data/com.hatake716.linuxdesktop.
        // The bundled Termux bootstrap is rebuilt under this same prefix
        // (/data/data/com.hatake716.linuxdesktop/files/usr) via termux-packages with
        // TERMUX_APP__PACKAGE_NAME set to match — see docs/ and the bootstrap zips.
        applicationId = "com.hatake716.linuxdesktop"
        minSdk = 26
        targetSdk = 36
        versionCode = 21
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        buildConfigField("String", "HOST_SCRIPT_VERSION", "\"1.2.0\"")
    }

    signingConfigs {
        // Pin the debug signing key to a keystore committed in the repo so EVERY
        // build (any machine, regardless of ANDROID_USER_HOME/ANDROID_SDK_HOME)
        // signs with the SAME key. Without this, the debug key is resolved from
        // ~/.android or ~/.config/.android depending on the environment; when
        // that path changed, the new APK's signature no longer matched the one
        // already installed on devices and over-install failed with "app not
        // installed". A debug keystore holds no secret (standard android/
        // androiddebugkey credentials), so committing it is safe.
        getByName("debug") {
            storeFile = file("keystore/ldfa-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = File(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
            // Play bundles default to ARM64. The release APK can include the
            // separately built x86_64 runtime for testing the same signed APK
            // on an emulator before installing it on an ARM64 physical device.
            // Never include an ABI without both its own-prefix bootstrap and PRoot.
            ndk {
                abiFilters += providers.gradleProperty("ldfa.releaseAbis").orElse("arm64-v8a").get().split(",")
            }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "/META-INF/DEPENDENCIES"
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")

    implementation(project(":termux-runtime"))
    implementation(project(":embedded-x11"))
    // Direct dep so the app can register its proot exec-rewriter into
    // com.termux.shared.shell.ExecInterceptor (Play/targetSdk-35 W^X path).
    implementation(project(":termux-shared"))

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // Android ships org.json only as a stub for unit tests (every method throws),
    // so pull in the real implementation for JVM tests. Not shipped in the APK,
    // which uses the platform org.json at runtime.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
