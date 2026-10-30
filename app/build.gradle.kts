plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ch.broillet.veloce"
    compileSdk = 37

    defaultConfig {
        applicationId = "ch.broillet.veloce"
        minSdk = 34
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0-dev"
    }

    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }

    signingConfigs {
        providers.gradleProperty("veloce.storeFile").orNull?.let { path ->
            create("veloceRelease") {
                storeFile = file(path)
                storePassword = providers.gradleProperty("veloce.storePassword").get()
                keyAlias = providers.gradleProperty("veloce.keyAlias").get()
                keyPassword = providers.gradleProperty("veloce.keyPassword").get()
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("veloceRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.car.app)
    implementation(libs.car.projected)
    implementation(libs.lifecycle.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.guava)
    implementation(libs.media3.session)
    debugImplementation(libs.compose.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
