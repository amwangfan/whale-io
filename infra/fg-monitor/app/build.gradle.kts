plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nova.appmonitor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nova.appmonitor"
        minSdk = 28
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("debugLocal") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    flavorDimensions += "capability"
    productFlavors {
        create("root") {
            dimension = "capability"
            applicationIdSuffix = ".root"
            versionNameSuffix = "-root"
            buildConfigField("boolean", "IS_ROOT", "true")
            resValue("string", "app_name", "Nova Monitor (Root)")
        }
        create("user") {
            dimension = "capability"
            applicationIdSuffix = ".user"
            versionNameSuffix = "-user"
            buildConfigField("boolean", "IS_ROOT", "false")
            resValue("string", "app_name", "Nova Monitor")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debugLocal")
        }
        debug {
            signingConfig = signingConfigs.getByName("debugLocal")
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
        buildConfig = true
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
