import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Same signing scheme as LiteTube: a release key from environment variables, the debug key
// when LITETUBE_SIGN_WITH_DEBUG_KEY=1 (CI), otherwise unsigned (F-Droid signs builds itself).
val releaseKeystore: String? = System.getenv("LITETUBE_KEYSTORE")
val signWithDebugKey = System.getenv("LITETUBE_SIGN_WITH_DEBUG_KEY") == "1"

android {
    namespace = "app.litelauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.milutinbasic_lang.litelauncher"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("LITETUBE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LITETUBE_KEY_ALIAS")
                keyPassword = System.getenv("LITETUBE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
                ?: if (signWithDebugKey) signingConfigs.getByName("debug") else null
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    sourceSets["main"].java.srcDir("../shared/java")
    sourceSets["main"].res.srcDir("../shared/res")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += setOf("META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// No dependencies on purpose: only the Android framework, so the APK and memory use stay tiny.
