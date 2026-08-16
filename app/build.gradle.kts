import java.util.Base64

plugins {
    id("com.android.application")
}

// Public, non-production identity used only so sideload APK updates keep the same signature.
// The JKS is stored as Base64 text because the GitHub contents connector is text-only.
val stableDebugStore = layout.buildDirectory.file("ambip-sideload-debug.jks").get().asFile
stableDebugStore.parentFile.mkdirs()
stableDebugStore.writeBytes(
    Base64.getMimeDecoder().decode(
        layout.projectDirectory.file("ambip-sideload-debug.jks.b64").asFile.readText()
    )
)

android {
    namespace = "com.bwa3d.ambiprojector"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bwa3d.ambiprojector.mibox"
        minSdk = 23
        targetSdk = 28
        versionCode = 26
        versionName = "0.26.0-mibox9-gpu"
    }

    signingConfigs {
        create("sideload") {
            storeFile = stableDebugStore
            storePassword = "ambipdebug"
            keyAlias = "ambipdebug"
            keyPassword = "ambipdebug"
            storeType = "JKS"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
        release {
            isMinifyEnabled = false
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
}

dependencies {
    implementation("androidx.activity:activity:1.11.0")
}
