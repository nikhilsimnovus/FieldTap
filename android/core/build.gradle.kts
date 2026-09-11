import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Plain Kotlin/JVM, no Android: platform-independent logic, unit-tested on a JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Built with the JDK 21 toolchain, emitting Java 17 bytecode against the Java 17 API.
kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

tasks.test {
    useJUnit()
}

dependencies {
    implementation(project(":format"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
