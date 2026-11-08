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
        versionCode = 4
        versionName = "0.4.0-dev"
    }

    buildFeatures { compose = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
    }

    signingConfigs {
        providers.environmentVariable("VELOCE_STORE_FILE")
            .orElse(providers.gradleProperty("veloce.storeFile"))
            .orNull?.let { path ->
                create("veloceRelease") {
                    storeFile = file(path)
                    storePassword = providers.environmentVariable("VELOCE_STORE_PASSWORD")
                        .orElse(providers.gradleProperty("veloce.storePassword")).get()
                    keyAlias = providers.environmentVariable("VELOCE_KEY_ALIAS")
                        .orElse(providers.gradleProperty("veloce.keyAlias")).get()
                    keyPassword = providers.environmentVariable("VELOCE_KEY_PASSWORD")
                        .orElse(providers.gradleProperty("veloce.keyPassword")).get()
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
        debug {
            signingConfigs.findByName("veloceRelease")?.let {
                signingConfig = it
            }
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
    implementation(libs.palette)
    implementation(libs.yaml)
    debugImplementation(libs.compose.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
