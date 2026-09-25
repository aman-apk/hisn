// NOTE: `import java.util.Properties` MUST stay at the top of this file — inside the
// android{} block the accessor `android.Properties` shadows it and the build breaks.
// This exact mistake bit the family three times; the import lives here permanently.
import com.android.build.api.artifact.SingleArtifact
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}


// Forwards the live-sync payload to the test JVM so LiveDesktopSyncTest can reach a running
// desktop server; without it that test skips itself and the suite stays hermetic.
tasks.withType<Test>().configureEach {
    System.getProperty("hisn.sync.payload")?.let { systemProperty("hisn.sync.payload", it) }
    testLogging { showStandardStreams = true }
}

// Real release keystore (hisn.p12, PKCS12, alias "hisn"). It lives in the keystore/ folder at the
// project root — outside any repository — next to its Arabic warning file; wired, NEVER regenerated.
val releaseProps = rootProject.file("keystore/keystore.properties")

android {
    // Internal only: source packages stay org.hisn.app. The identity users and stores see is the
    // applicationId below.
    namespace = "org.hisn.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // The Aman-family identity — PERMANENT once published.
        applicationId = "org.amanlabs.hisn"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 9
        versionName = "0.2.7"
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
        // Debug stays on the shared debug keystore so side-loaded debug builds keep installing
        // over each other across machines.
        getByName("debug") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseProps.exists()) {
            val props = Properties().apply { releaseProps.inputStream().use { load(it) } }
            create("release") {
                // keystore.properties stores the path relative to the project root
                // (storeFile=keystore/hisn.p12), so it resolves through rootProject.file —
                // file() would look inside app/ and miss.
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
                storeType = "pkcs12"
            }
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
            // Signed with the real family key when the keystore is present. When it is absent
            // this stays unset on purpose and the guard below android{} fails the build — a
            // release must never fall back to the debug key silently.
            if (releaseProps.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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

// No keystore, no release. Without this, a missing keystore/ folder would quietly produce an
// unsigned app-release-unsigned.apk; instead every path to a release artifact fails loudly.
// (The package tasks fail before an artifact exists; the lifecycle tasks cover the case where a
// stale packaged output is still lying around and would otherwise be reported as UP-TO-DATE.)
if (!releaseProps.exists()) {
    tasks.configureEach {
        if (name == "assembleRelease" || name == "bundleRelease" ||
            name == "packageRelease" || name == "packageReleaseBundle"
        ) {
            doFirst {
                throw GradleException(
                    "توقيع الإصدار متعذّر: الملف keystore/keystore.properties غير موجود في جذر المشروع. " +
                        "أعد مجلد keystore/ (وفيه hisn.p12 وملف الخصائص) إلى مكانه ثم أعد البناء — " +
                        "لا يُوقَّع الإصدار بمفتاح التصحيح أبدًا."
                )
            }
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

    // Inline autofill suggestions (the IME strip on Android 11+). The platform takes an
    // androidx-defined Slice for InlinePresentation, so this library is the only supported way to
    // build one; without it the service can still fall back to RemoteViews dropdowns.
    implementation(libs.androidx.autofill)

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

// ============================================================================
// Vault permission guard — the family's mandatory build gate.
// حصن carries exactly four permissions, each argued for in AndroidManifest.xml:
// INTERNET + ACCESS_NETWORK_STATE for the local-network sync socket, and
// USE_BIOMETRIC + USE_FINGERPRINT (its API 26–27 fallback) for unlocking the
// vault. Anything else a dependency merges in fails the build and must be
// stripped with tools:node="remove" — notably CAMERA stays banned: pairing is
// a typed code, not a QR scan.
// ============================================================================
abstract class VerifyVaultPermissionsTask : DefaultTask() {
    @get:org.gradle.api.tasks.InputFile
    abstract val mergedManifest: RegularFileProperty

    @org.gradle.api.tasks.TaskAction
    fun verify() {
        val allowed = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.USE_BIOMETRIC",
            "android.permission.USE_FINGERPRINT",
        )
        val text = mergedManifest.get().asFile.readText()
        val found = Regex("<uses-permission[^>]*android:name=\"([^\"]+)\"")
            .findAll(text).map { it.groupValues[1] }.toSet()
        val intruders = found.filter {
            // The auto-generated <applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION is
            // signature-level boilerplate, not a capability — matched by suffix so both the
            // release and the .debug applicationId pass.
            it !in allowed && !it.endsWith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        }
        if (intruders.isNotEmpty()) {
            throw GradleException(
                "VAULT PERMISSION GUARD: حصن may only carry $allowed, found: $intruders\n" +
                    "Strip the intruder with tools:node=\"remove\" in AndroidManifest.xml."
            )
        }
        logger.lifecycle("Vault permission guard passed: no uses-permission outside the allowlist.")
    }
}

androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val guard = project.tasks.register(
            "verify${cap}VaultPermissions", VerifyVaultPermissionsTask::class.java
        ) {
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
        project.tasks.configureEach {
            // APK, AAB and the variant check lifecycle all gate on the guard — an app
            // bundle must honour exactly the same allowlist as the APK.
            if (name == "assemble$cap" || name == "bundle$cap" || name == "check$cap") {
                dependsOn(guard)
            }
        }
    }
}
