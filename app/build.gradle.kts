import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.yume.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yume.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 8
        versionName = "1.3.0"
        // Shown in Settings > About: which commit and when this build was made.
        val gitSha = runCatching {
            providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }
                .standardOutput.asText.get().trim()
        }.getOrDefault("unknown")
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
        // ONNX Runtime ships native code: tablets (arm64) and the x86_64 emulator.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // Release signing comes from keystore.properties (never committed):
    //   storeFile=/path/to/yume-release.jks
    //   storePassword=...
    //   keyAlias=yume
    //   keyPassword=...
    // Without it, release builds are signed with the debug key so they can be
    // installed for testing; Play rejects debug-signed uploads.
    val keystoreFile = rootProject.file("keystore.properties")
    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                val props = Properties().apply { keystoreFile.inputStream().use { load(it) } }
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystoreFile.exists()) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
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

    lint {
        // The lifecycle library's LiveData detector crashes on this Kotlin
        // version (IncompatibleClassChangeError in UAST). Yume uses no LiveData.
        disable += "NullSafeMutableLiveData"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Qualcomm's NPU (HTP) libraries must be real files on disk: the
            // NPU loads its half of them by path.
            useLegacyPackaging = true
            // Only the NPU backend is used: not the older DSP or the GPU one,
            // and not the NPU code for 2021 chips (Snapdragon 888 / 8 Gen 1,
            // HTP v68 / v69), which run the models on the CPU instead.
            excludes += listOf(
                "**/libQnnDsp*.so", "**/libQnnGpu.so",
                "**/libQnnHtpV68*.so", "**/libQnnHtpV69*.so"
            )
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // Material Symbols (Rounded) for the interface icons; R8 keeps only those used.
    implementation("androidx.compose.material:material-icons-extended")
    // Liquid glass surfaces (Kyant0/AndroidLiquidGlass, Apache-2.0).
    implementation("io.github.kyant0:backdrop:1.0.6")
    // On-device AI tools (background removal, upscaling): ONNX Runtime (MIT),
    // built with Qualcomm's QNN so Snapdragon NPUs can run the models. It
    // brings Qualcomm's QNN runtime (Qualcomm AI Hub Model License); other
    // devices run the same models on the CPU.
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.29.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
