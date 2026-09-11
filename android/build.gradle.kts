// Every plugin is declared here once, so the Android Gradle plugin and the Kotlin Gradle
// plugin share one classloader. AGP 9 only guarantees KGP 2.2.10 at runtime; declaring
// org.jetbrains.kotlin.jvm here resolves the whole build to the Kotlin version in the catalog.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
