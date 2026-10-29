import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is read from environment variables so no secrets live in the repo.
// INSTACHAT_SIGN_WITH_DEBUG_KEY=1 (used by CI when no key is configured) signs with the debug key
// so the APK can be installed directly.
val releaseKeystore: String? = System.getenv("INSTACHAT_KEYSTORE")
val signWithDebugKey = System.getenv("INSTACHAT_SIGN_WITH_DEBUG_KEY") == "1"

android {
    namespace = "io.github.milutinbasic_lang.instachat"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.milutinbasic_lang.instachat"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("INSTACHAT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("INSTACHAT_KEY_ALIAS")
                keyPassword = System.getenv("INSTACHAT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
                ?: if (signWithDebugKey) signingConfigs.getByName("debug") else null
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
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

dependencies {
    testImplementation("junit:junit:4.13.2")
}
