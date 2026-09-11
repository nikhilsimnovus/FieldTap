package com.fieldtap.core.radio

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Properties
import java.util.spi.ToolProvider

/**
 * Public constants of the Android SDK's android.jar, read with the JDK's own javap (module jdk.jdeps)
 * so tests can check values :core copies by hand without putting Android on its classpath.
 *
 * The SDK is found through ANDROID_HOME, ANDROID_SDK_ROOT or `sdk.dir` in android/local.properties; the
 * newest installed platform is used. Without an SDK the test fails, because it would verify nothing.
 */
internal object AndroidSdkConstants {
    private val PLATFORM = Regex("android-(\\d+)(?:\\.(\\d+))?")
    private val INT_CONSTANT = Regex("public static final int (\\w+) = (-?\\d+);")
    private val LONG_CONSTANT = Regex("public static final long (\\w+) = (-?\\d+)l;")

    val androidJar: File by lazy { findAndroidJar() }

    fun intConstants(className: String): Map<String, Int> =
        INT_CONSTANT.findAll(javap(className)).associate { it.groupValues[1] to it.groupValues[2].toInt() }

    fun longConstants(className: String): Map<String, Long> =
        LONG_CONSTANT.findAll(javap(className)).associate { it.groupValues[1] to it.groupValues[2].toLong() }

    private fun javap(className: String): String {
        val tool = ToolProvider.findFirst("javap")
            .orElseThrow { AssertionError("This JDK has no javap tool (module jdk.jdeps)") }
        val out = StringWriter()
        val err = StringWriter()
        val outWriter = PrintWriter(out)
        val errWriter = PrintWriter(err)
        val code = tool.run(outWriter, errWriter, "-constants", "-classpath", androidJar.path, className)
        outWriter.flush()
        errWriter.flush()
        if (code != 0) throw AssertionError("javap $className against $androidJar exited $code: $err")
        return out.toString()
    }

    private fun findAndroidJar(): File {
        val roots = listOfNotNull(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            sdkDirFromLocalProperties(),
        ).filter { it.isNotBlank() }.map { File(it) }
        val jars = roots
            .flatMap { root -> File(root, "platforms").listFiles().orEmpty().map { File(it, "android.jar") } }
            .filter { it.isFile }
        return jars.maxByOrNull { platformRank(it.parentFile.name) }
            ?: throw AssertionError(
                "No Android SDK platform found. Set ANDROID_HOME or sdk.dir in android/local.properties " +
                    "(searched ${roots.map { it.path }}).",
            )
    }

    private fun sdkDirFromLocalProperties(): String? {
        val file = File("../local.properties")
        if (!file.isFile) return null
        val properties = Properties()
        file.inputStream().use { properties.load(it) }
        return properties.getProperty("sdk.dir")
    }

    private fun platformRank(name: String): Int {
        val match = PLATFORM.matchEntire(name) ?: return -1
        return match.groupValues[1].toInt() * 1_000 + (match.groupValues[2].toIntOrNull() ?: 0)
    }
}
