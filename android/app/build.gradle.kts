// AGP 9 compiles Kotlin itself (built-in Kotlin), so org.jetbrains.kotlin.android is not applied.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The applicationId is a working ID that changes before registration. It is defined once,
// as fieldtap.applicationId in android/gradle.properties, and read only here.
val fieldtapApplicationId: String = providers.gradleProperty("fieldtap.applicationId").orNull
    ?: error("fieldtap.applicationId is not set; define it in android/gradle.properties")

android {
    namespace = "com.fieldtap"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = fieldtapApplicationId
        minSdk {
            version = release(31)
        }
        targetSdk {
            version = release(36)
        }
        versionCode = 1
        versionName = "0.1.0"
    }

    // Java 17 bytecode; with built-in Kotlin, Kotlin's jvmTarget follows targetCompatibility.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

// Compiled by the JDK 21 toolchain.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":format"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
