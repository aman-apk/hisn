import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.hisn.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.hisn.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    // Sources live in src/main/kotlin, not src/main/java.
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
        getByName("androidTest") {
            java.srcDirs("src/androidTest/kotlin")
        }
    }

    signingConfigs {
        // The deliverable is meant to be side-loaded and debugged, so release is signed with the
        // debug key too. Swap this for a real keystore before any public distribution.
        getByName("debug") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            isShrinkResources = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            // Minification stays off on purpose: the deliverable has to be readable in a stack
            // trace, and R8 buys nothing on a single-module app of this size.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    testOptions {
        unitTests {
            // Stubs out the handful of android.* methods the JVM has no implementation for
            // (android.util.Log and friends) instead of throwing. The KDBX code deliberately uses
            // java.util.Base64 rather than android.util.Base64, so the round-trip tests run on real
            // implementations throughout and this only covers logging.
            isReturnDefaultValues = true
        }
    }

    lint {
        // Lint runs as its own task; a lint warning must not block producing the APK.
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                // Bouncy Castle ships a signed, multi-release jar; the signature files and the
                // JDK9+ module descriptors are meaningless inside an APK and collide on merge.
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // One BOM pins every Compose artifact: Compose 1.10.6 and Material 3 1.4.0.
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    // Not used from Kotlin — it supplies the Theme.Material3 parent that res/values/themes.xml
    // inherits from, which is what paints the window before Compose takes over.
    implementation(libs.google.material)
    implementation(libs.androidx.activity.compose)
    // MainActivity is a FragmentActivity because androidx.biometric hosts its prompt in a fragment.
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    // No material-icons-extended on purpose: ui/components/HisnIcons.kt draws the app's glyphs, and
    // the extended set would add tens of megabytes to an unminified APK for a handful of icons.

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.biometric)

    // Argon2, ChaCha20, Salsa20 and Twofish — none of which the platform provider offers on API 26.
    implementation(libs.bouncycastle.prov)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
