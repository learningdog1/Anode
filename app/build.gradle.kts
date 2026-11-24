import java.util.Properties
import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val appVersionName = "2.11.2"
val appVersionCode = 23

base {
    archivesName.set("Anode-v$appVersionName")
}

val keystoreProperties = Properties().apply {
    val propsFile = rootProject.file("key.properties")
    if (propsFile.isFile) {
        propsFile.inputStream().use(::load)
    }
}

fun signingProp(propKey: String, envKey: String): String? =
    (keystoreProperties.getProperty(propKey) ?: System.getenv(envKey))?.trim()?.takeIf { it.isNotEmpty() }

val releaseStoreFile = signingProp("storeFile", "KEYSTORE_FILE")
val releaseStorePassword = signingProp("storePassword", "KEYSTORE_PASSWORD")
val releaseKeyAlias = signingProp("keyAlias", "KEY_ALIAS")
val releaseKeyPassword = signingProp("keyPassword", "KEY_PASSWORD")
val releaseStoreFileObj = releaseStoreFile?.let { rootProject.file(it) }
val hasAllReleaseSigningProps = releaseStoreFile != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null
val hasReleaseSigningConfig = hasAllReleaseSigningProps && releaseStoreFileObj?.isFile == true

if (hasAllReleaseSigningProps && !hasReleaseSigningConfig) {
    val missingMsg =
        "Release signing properties are set, but keystore file does not exist: ${releaseStoreFileObj?.absolutePath}"
    val isReleaseTaskRequested = gradle.startParameter.taskNames.any {
        it.contains("release", ignoreCase = true) || it.contains("bundle", ignoreCase = true)
    }
    if (isReleaseTaskRequested) {
        throw GradleException(missingMsg)
    } else {
        logger.warn("WARNING: $missingMsg")
    }
}

android {
    namespace = "com.nodeseek.app"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.nodeseek.app"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "GITHUB_OWNER", "\"learningdog1\"")
        buildConfigField("String", "GITHUB_REPO", "\"Anode\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = releaseStoreFileObj!!
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("app/compose_stability.conf"))
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // AndroidX Core & Edge-to-Edge Activity
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Jetpack Compose UI & Material 3
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.windowSizeClass)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Lifecycle & Navigation Compose
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // Networking, Parsing & Coroutines
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Persistence (DataStore & Room)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Images (Coil 3 + OkHttp network fetcher + SVG & GIF decoders)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)

    // WebView Control
    implementation(libs.androidx.webkit)

    // Lightweight SSH Client
    implementation(libs.jsch)

    // Background Scheduling (WorkManager)
    implementation(libs.androidx.work.runtime.ktx)

    // Unit Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.work.testing)

    // Instrumented Testing
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
