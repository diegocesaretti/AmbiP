plugins {
    id("com.android.application")
}

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

    buildTypes {
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
