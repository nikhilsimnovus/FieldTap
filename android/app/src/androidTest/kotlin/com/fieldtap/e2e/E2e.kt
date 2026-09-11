package com.fieldtap.e2e

import android.app.Instrumentation
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToLog
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.fieldtap.R
import com.fieldtap.app.AppGraph
import com.fieldtap.app.appGraph
import com.fieldtap.format.JsonText
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Shared pieces of the instrumented end-to-end tests. They run in the app's own process on the CI emulator, driven by
 * android/e2e/run_e2e.sh, which sets the theme and font scale before each run, feeds a GPS walk and signal changes,
 * kills the app for the recovery test, and checks every session with fieldtap afterwards.
 *
 * What a test leaves for the host (screenshots, result JSON, the exported zip) goes to
 * `<getExternalFilesDir(null)>/e2e/`, which the host pulls after every run.
 */
object E2e {
    /** Logcat tag of the tests' own diagnostics, such as the semantics tree after a timeout. */
    const val TAG: String = "FieldTapE2e"

    private const val OUTPUT_DIR = "e2e"

    /** Lets a ripple or a snackbar finish appearing before a screenshot; animations are off on the emulator. */
    private const val SETTLE_MS = 700L
    private const val PNG_QUALITY = 100
    private const val BUFFER_BYTES = 64 * 1024

    val instrumentation: Instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** The app's context: the tests run in the app's process. */
    val context: Context get() = instrumentation.targetContext

    val device: UiDevice get() = UiDevice.getInstance(instrumentation)

    /** The app's own graph, read to confirm what the screens show; every step itself goes through the UI. */
    val graph: AppGraph get() = context.appGraph

    /** An `am instrument -e NAME VALUE` argument, or null when it is absent or blank. */
    fun argument(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)?.trim()?.takeIf { it.isNotEmpty() }

    /** An argument the test cannot run without. */
    fun requireArgument(name: String): String = checkNotNull(argument(name)) { "Pass -e $name VALUE to am instrument" }

    /** One of the app's strings, so the tests follow its wording instead of copying it. */
    fun string(@StringRes id: Int, vararg formatArgs: Any): String = context.getString(id, *formatArgs)

    fun granted(permission: String): Boolean = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** `<externalFilesDir>/e2e/[child]`, created when missing. */
    fun outputDir(child: String = ""): File {
        val root = checkNotNull(context.getExternalFilesDir(null)) { "App-specific external storage is unavailable" }
        val dir = if (child.isEmpty()) File(root, OUTPUT_DIR) else File(File(root, OUTPUT_DIR), child)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Could not create $dir")
        return dir
    }

    /** Saves the whole screen, system bars and system dialogs included, as `e2e/screenshots/[group]/[name].png`. */
    fun screenshot(group: String, name: String) {
        screenshotTo(outputDir("screenshots/$group"), name)
    }

    /** Saves the whole screen as `[dir]/[name].png`. */
    fun screenshotTo(dir: File, name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(SETTLE_MS)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Android returned no screenshot for $name" }
        try {
            File(dir, "$name.png").outputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)) { "Could not encode $name.png" }
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Runs [command] as the shell user and returns what it printed. */
    fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /**
     * Writes [values] as a flat JSON object to `e2e/[fileName]`, replacing the previous file atomically, so the host
     * finds a whole file even when a test fails half way. Booleans, Ints and Longs are JSON literals; anything else is
     * a string.
     */
    fun writeResult(fileName: String, values: Map<String, Any?>) {
        val json = values.entries.joinToString(separator = ",\n", prefix = "{\n", postfix = "\n}\n") { (key, value) ->
            val encoded = when (value) {
                null -> "null"
                is Boolean, is Int, is Long -> value.toString()
                else -> JsonText.quote(value.toString())
            }
            "  ${JsonText.quote(key)}: $encoded"
        }
        val dir = outputDir()
        val temporary = File(dir, "$fileName.tmp")
        temporary.writeText(json, Charsets.UTF_8)
        if (!temporary.renameTo(File(dir, fileName))) throw IOException("Could not replace $fileName")
    }

    /** SHA-256 of [file] as 64 lower-case hex digits. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> String.format(Locale.ROOT, "%02x", byte) }
    }

    /** Taps "Wait" on an "isn't responding" dialog, which a slow CI emulator can show over any app. */
    fun dismissNotRespondingDialog() {
        device.findObject(By.res("android", "aerr_wait"))?.click()
    }
}

/** The look a run is checked in. android/e2e/run_e2e.sh applies it with `cmd uimode night` and `font_scale`. */
enum class Variant(val group: String, private val night: Boolean, private val fontScale: Float) {
    LIGHT("light", night = false, fontScale = 1.0f),
    DARK("dark", night = true, fontScale = 1.0f),
    FONT_130("font130", night = false, fontScale = 1.3f),
    ;

    /** Fails unless the app's process started in this variant's night mode and font scale. */
    fun assertApplied() {
        val configuration = E2e.context.resources.configuration
        val nightNow = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        assertEquals("night mode of variant $group", night, nightNow)
        assertEquals("font scale of variant $group", fontScale, configuration.fontScale, FONT_SCALE_TOLERANCE)
    }

    companion object {
        private const val FONT_SCALE_TOLERANCE = 0.01f

        /** The variant named by `-e variant light|dark|font130`. */
        fun fromArguments(): Variant {
            val name = E2e.requireArgument("variant")
            return entries.firstOrNull { it.group == name } ?: error("Unknown variant $name")
        }
    }
}

/** Android's runtime permission dialog. It belongs to the permission controller, so UiAutomator drives it. */
object PermissionDialogs {
    private val CONTROLLER: Pattern = Pattern.compile("com\\.(google\\.)?android\\.permissioncontroller")
    private const val APPEAR_MS = 15_000L
    private const val BUTTON_MS = 3_000L
    private const val GONE_MS = 5_000L
    private const val TAP_ATTEMPTS = 3

    /**
     * How long the dialog is left to finish opening before a tap. Android drops touches on an activity whose open
     * transition is still running ("Not sending touch gesture ... NO_INPUT_CHANNEL"), as it did on the API 36 emulator.
     */
    private const val SETTLE_MS = 1_500L

    /** "While using the app", offered for location. */
    const val LOCATION_WHILE_IN_USE: String = "permission_allow_foreground_only_button"

    /** "Allow", offered for notifications, and for location on some versions. */
    const val ALLOW: String = "permission_allow_button"

    fun showing(): Boolean = E2e.device.hasObject(By.pkg(CONTROLLER))

    /**
     * Waits for the dialog, which must appear, lets it finish opening, and taps the first of [buttonIds] it offers until
     * the dialog closes, at most [TAP_ATTEMPTS] times. Returns true once the dialog has closed; false when it offers none
     * of those buttons or stays open after every tap.
     */
    fun answer(vararg buttonIds: String): Boolean {
        val device = E2e.device
        check(device.wait(Until.hasObject(By.pkg(CONTROLLER)), APPEAR_MS) == true) { "Android's permission dialog did not appear" }
        repeat(TAP_ATTEMPTS) { attempt ->
            device.waitForIdle()
            SystemClock.sleep(SETTLE_MS)
            val button = buttonIds.firstNotNullOfOrNull { id ->
                device.wait(Until.findObject(By.pkg(CONTROLLER).res(Pattern.compile(".*:id/" + Pattern.quote(id)))), BUTTON_MS)
            } ?: return false
            button.click()
            if (device.wait(Until.gone(By.pkg(CONTROLLER)), GONE_MS) == true) return true
            Log.w(E2e.TAG, "The permission dialog stayed open after tap ${attempt + 1} of $TAP_ATTEMPTS")
        }
        return false
    }

    /** Waits until no permission dialog shows; false when one is still there after [timeoutMs]. */
    fun awaitGone(timeoutMs: Long = APPEAR_MS): Boolean = E2e.device.wait(Until.gone(By.pkg(CONTROLLER)), timeoutMs) == true
}

/** A node whose text or editable text contains a match of [regex]. */
fun hasTextMatching(regex: Regex): SemanticsMatcher = SemanticsMatcher("has text matching $regex") { node ->
    val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
        listOfNotNull(node.config.getOrNull(SemanticsProperties.EditableText)?.text)
    texts.any { regex.containsMatchIn(it) }
}

/** A node whose content description contains a match of [regex]. */
fun hasDescriptionMatching(regex: Regex): SemanticsMatcher = SemanticsMatcher("has content description matching $regex") { node ->
    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { regex.containsMatchIn(it) }
}

/**
 * Steps on the app's screens through Compose semantics. Each waits for what it needs, and a timeout names what was
 * awaited and prints the semantics tree to logcat.
 */
class Screens(private val compose: ComposeTestRule, private val group: String) {
    /**
     * Whether a node matches. False, rather than an error, while no Compose hierarchy of the app can be reached: that is
     * the case while an activity of another app, such as a permission dialog or the share sheet, covers the app, and a
     * wait should then go on waiting.
     */
    fun exists(matcher: SemanticsMatcher, unmerged: Boolean = false): Boolean = try {
        compose.onAllNodes(matcher, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty()
    } catch (e: IllegalStateException) {
        if (e.message?.startsWith(NO_HIERARCHY) != true) throw e
        false
    }

    /** Waits until [condition] holds; [what] names it in the failure. */
    fun waitFor(what: String, timeoutMs: Long = WAIT_MS, condition: () -> Boolean) {
        try {
            compose.waitUntil(conditionDescription = what, timeoutMillis = timeoutMs, condition = condition)
        } catch (e: ComposeTimeoutException) {
            logTree()
            throw AssertionError("Timed out after $timeoutMs ms waiting for $what", e)
        }
    }

    /** The first node matching [matcher], once one exists. */
    fun await(matcher: SemanticsMatcher, timeoutMs: Long = WAIT_MS, unmerged: Boolean = false): SemanticsNodeInteraction {
        waitFor(matcher.description, timeoutMs) { exists(matcher, unmerged) }
        return compose.onAllNodes(matcher, useUnmergedTree = unmerged).onFirst()
    }

    fun awaitText(@StringRes id: Int, timeoutMs: Long = WAIT_MS): SemanticsNodeInteraction = await(hasText(E2e.string(id)), timeoutMs)

    fun click(matcher: SemanticsMatcher, timeoutMs: Long = WAIT_MS) {
        await(matcher, timeoutMs).performClick()
        compose.waitForIdle()
    }

    /** Scrolls the screen's lazy list until [matcher] is composed, then until it is wholly on screen. */
    fun scrollTo(matcher: SemanticsMatcher) {
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(matcher)
        compose.onAllNodes(matcher).onFirst().performScrollTo()
    }

    /** Replaces the text of the field labelled [label] on a scrolling screen. */
    fun replaceText(@StringRes label: Int, value: String) {
        val field = hasSetTextAction() and hasText(E2e.string(label))
        scrollTo(field)
        replaceTextIn(field, value)
    }

    /** Replaces the text of the field [field], which is already on screen (for example in a dialog). */
    fun replaceTextIn(field: SemanticsMatcher, value: String) {
        await(field).performTextReplacement(value)
        compose.waitForIdle()
    }

    /** The texts of the first node matching [matcher]; a merged row holds its key and its value. */
    fun texts(matcher: SemanticsMatcher): List<String> =
        await(matcher).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    fun isOn(matcher: SemanticsMatcher): Boolean =
        await(matcher).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On

    /** A screenshot in this test's group, once the screen is idle. */
    fun shot(name: String) {
        compose.waitForIdle()
        E2e.screenshot(group, name)
    }

    /** The top bar's navigate-up button. */
    fun back() {
        click(hasContentDescription(E2e.string(R.string.action_back)) and hasClickAction())
    }

    /** Opens [label] from the Live screen's overflow menu. */
    fun openMenuItem(@StringRes label: Int) {
        click(hasContentDescription(E2e.string(R.string.live_action_more)) and hasClickAction())
        click(hasText(E2e.string(label)) and hasClickAction())
    }

    /**
     * Waits until Live shows a serving cell and its hero tile says how old the sample is. On a timeout the Live feed's
     * state (cells, listeners, conditions) is written to logcat and to `e2e/failures/[group]/live-state.txt`, so the
     * artifact says why no cell was chosen.
     */
    fun awaitServingCell(timeoutMs: Long = SERVING_CELL_WAIT_MS) {
        try {
            awaitText(R.string.live_section_serving, timeoutMs)
            await(hasDescriptionMatching(ageBadge()), timeoutMs)
        } catch (e: AssertionError) {
            val state = E2e.graph.live.state.value.toString()
            Log.w(E2e.TAG, "No serving cell on Live; the feed's state: $state")
            try {
                File(E2e.outputDir("failures/$group"), "live-state.txt").writeText(state + "\n", Charsets.UTF_8)
            } catch (io: IOException) {
                Log.w(E2e.TAG, "Could not save the Live state", io)
            }
            throw e
        }
    }

    /** Prints every window's semantics tree to logcat under [E2e.TAG]. */
    fun logTree() {
        try {
            compose.onAllNodes(isRoot(), useUnmergedTree = true).printToLog(E2e.TAG)
        } catch (e: RuntimeException) {
            Log.w(E2e.TAG, "Could not print the semantics tree", e)
        } catch (e: AssertionError) {
            Log.w(E2e.TAG, "Could not print the semantics tree", e)
        }
    }

    companion object {
        const val WAIT_MS: Long = 20_000

        /** The first screen after a cold start on a busy emulator. */
        const val LAUNCH_WAIT_MS: Long = 60_000

        /** The emulator's modem reports cell info every 10 s, and its first answer can take longer after boot. */
        const val SERVING_CELL_WAIT_MS: Long = 120_000

        /** The start of Compose testing's message when no hierarchy of the app is reachable. */
        private const val NO_HIERARCHY = "No compose hierarchies found"

        private const val PLACEHOLDER = "\u0000"

        /** "2.1 s old" as `R.string.age_old` words it, with any number of seconds. */
        fun ageBadge(): Regex {
            val parts = E2e.string(R.string.age_old, PLACEHOLDER).split(PLACEHOLDER, limit = 2)
            val before = parts[0]
            val after = parts.getOrElse(1) { "" }
            return Regex(Regex.escape(before) + "\\d+([.,]\\d)?" + Regex.escape(after))
        }
    }
}

/** On a failed test, saves a screenshot and Android's window hierarchy in `e2e/failures/[group]/`. */
class FailureCapture(private val group: String) : TestWatcher() {
    override fun failed(e: Throwable, description: Description) {
        val name = "${description.testClass.simpleName}-${description.methodName}"
        try {
            val dir = E2e.outputDir("failures/$group")
            E2e.screenshotTo(dir, name)
            E2e.device.dumpWindowHierarchy(File(dir, "$name.xml"))
        } catch (capture: IOException) {
            Log.w(E2e.TAG, "Could not capture the failure of $name", capture)
        } catch (capture: RuntimeException) {
            Log.w(E2e.TAG, "Could not capture the failure of $name", capture)
        }
    }
}
