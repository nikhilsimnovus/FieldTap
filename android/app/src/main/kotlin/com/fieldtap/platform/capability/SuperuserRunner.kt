package com.fieldtap.platform.capability

import android.util.Log
import com.fieldtap.core.capability.KernelConfigProbe
import com.fieldtap.core.capability.RootProbeRaw
import com.fieldtap.core.capability.SuStatus
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * The active, read-only root probe. Runs **only** from an explicit "Check with root" tap and may raise the
 * OS superuser grant prompt. It runs one read-only command pipeline through an **already-present** `su`,
 * with a hard [timeoutMs], on [Dispatchers.IO] via [runInterruptible] so a cancelled coroutine interrupts
 * the wait and the process is destroyed in `finally` — it never blocks the UI and never hangs.
 *
 * Read-only, always: `id` (or `whoami`), `getenforce`, `ls -l /dev/diag`, and a `/proc/config.gz` diag
 * check. It never writes, remounts, or installs anything. It never gains root and never runs an exploit —
 * it uses a `su` that is already on the phone (capability spec §0).
 *
 * su handling:
 * - **absent** → `exec` throws [IOException] → [SuStatus.NOT_PRESENT].
 * - **denied** → the process exits without `uid=0` in `id` → [SuStatus.DENIED].
 * - **timed out** → the wait exceeded [timeoutMs]; the process is destroyed → [SuStatus.TIMED_OUT].
 * - **granted** → `id` shows `uid=0` → [SuStatus.GRANTED], each section captured (a missing one left null).
 * - any other failure → [SuStatus.ERROR].
 *
 * All output parsing is factored into the pure [parseSuOutput], JVM-tested; only [run] touches the process.
 *
 * Owner: workstream `capability-core`.
 */
class SuperuserRunner(private val timeoutMs: Long = 6_000) {
    /** Runs the probe. Suspends on [Dispatchers.IO]; safe to cancel. */
    suspend fun run(): RootProbeRaw = runInterruptible(Dispatchers.IO) { execute() }

    private fun execute(): RootProbeRaw {
        val startNs = System.nanoTime()
        fun elapsed(): Long = (System.nanoTime() - startNs) / 1_000_000
        val process = try {
            ProcessBuilder("su", "-c", SCRIPT).redirectErrorStream(true).start()
        } catch (e: IOException) {
            return parseSuOutput("", EXIT_UNSET, timedOut = false, threwIoException = true, elapsedMs = elapsed())
        }
        val output = StringBuilder()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> synchronized(output) { output.append(line).append('\n') } }
                }
            } catch (e: IOException) {
                // The stream was closed when the process was destroyed; whatever arrived is enough.
            }
        }.apply { isDaemon = true; name = READER_THREAD; start() }
        try {
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                reader.join(DRAIN_JOIN_MS)
                return parseSuOutput(snapshot(output), EXIT_UNSET, timedOut = true, threwIoException = false, elapsedMs = elapsed())
            }
            reader.join(DRAIN_JOIN_MS)
            return parseSuOutput(snapshot(output), process.exitValue(), timedOut = false, threwIoException = false, elapsedMs = elapsed())
        } catch (e: RuntimeException) {
            Log.w(TAG, "The root check failed unexpectedly", e)
            return RootProbeRaw(SuStatus.ERROR, null, null, null, KernelConfigProbe.CONFIG_UNAVAILABLE, elapsed())
        } finally {
            process.destroy()
        }
    }

    private fun snapshot(output: StringBuilder): String = synchronized(output) { output.toString() }

    companion object {
        private const val TAG = "FieldTapCapability"
        private const val READER_THREAD = "fieldtap-su-reader"
        private const val DRAIN_JOIN_MS = 500L

        /** Sentinel exit value for "the process never reported one" (timeout, or exec failed). */
        const val EXIT_UNSET: Int = Int.MIN_VALUE

        /**
         * The one read-only pipeline. `---`/`---END` markers split the four sections deterministically. The
         * kernel-config section prints `CFGOK` (config readable) or `CFGNONE` (absent/unreadable) before
         * any `grep -i diag` hits, so [parseSuOutput] can tell `DIAG_ABSENT` (config read, no match) from
         * `CONFIG_UNAVAILABLE`. `zcat` is tried via `toybox` first, then plainly; both are read-only.
         */
        const val SCRIPT: String =
            "id; echo ---; getenforce; echo ---; ls -l /dev/diag; echo ---; " +
                "C=\"\$( (toybox zcat /proc/config.gz 2>/dev/null || zcat /proc/config.gz 2>/dev/null) )\"; " +
                "if [ -n \"\$C\" ]; then echo CFGOK; echo \"\$C\" | grep -i diag; else echo CFGNONE; fi; echo ---END"

        private const val CFG_OK = "CFGOK"
        private const val CFG_NONE = "CFGNONE"

        /**
         * Parses the runner's raw result into a [RootProbeRaw]. Pure: no I/O, so it is JVM-tested.
         *
         * @param stdout the merged stdout/stderr the pipeline produced (may be partial on timeout).
         * @param exitValue the process exit value, or [EXIT_UNSET] when none (timeout / exec failure).
         * @param timedOut the wait exceeded the timeout.
         * @param threwIoException `exec` threw (su not present).
         */
        fun parseSuOutput(
            stdout: String,
            exitValue: Int,
            timedOut: Boolean,
            threwIoException: Boolean,
            elapsedMs: Long = 0,
        ): RootProbeRaw {
            if (threwIoException) {
                return RootProbeRaw(SuStatus.NOT_PRESENT, null, null, null, KernelConfigProbe.CONFIG_UNAVAILABLE, elapsedMs)
            }
            if (timedOut) {
                return RootProbeRaw(SuStatus.TIMED_OUT, null, null, null, KernelConfigProbe.CONFIG_UNAVAILABLE, elapsedMs)
            }
            val sections = splitSections(stdout)
            val idSection = sections.getOrNull(0)
            val getenforceSection = sections.getOrNull(1)
            val diagLsSection = sections.getOrNull(2)
            val kernel = parseKernelConfig(sections.getOrNull(3))
            val granted = idSection != null &&
                (idSection.contains("uid=0") || idSection.trim().equals("root", ignoreCase = true))
            val status = if (granted) SuStatus.GRANTED else SuStatus.DENIED
            return RootProbeRaw(
                suStatus = status,
                idOutput = idSection,
                getenforceOutput = getenforceSection,
                diagLsOutput = diagLsSection,
                kernelConfigDiag = kernel,
                elapsedMs = elapsedMs,
            )
        }

        /** Splits on lines that are exactly `---`, ending at `---END`. Each section is trimmed; blank → null. */
        private fun splitSections(stdout: String): List<String?> {
            val normalized = stdout.replace("\r\n", "\n").replace('\r', '\n')
            val sections = ArrayList<String?>()
            val current = StringBuilder()
            fun push() {
                val text = current.toString().trim()
                sections.add(text.ifEmpty { null })
                current.setLength(0)
            }
            for (line in normalized.split("\n")) {
                when (line.trim()) {
                    "---" -> push()
                    "---END" -> {
                        push()
                        return sections
                    }
                    else -> current.append(line).append('\n')
                }
            }
            if (current.isNotEmpty()) push()
            return sections
        }

        private fun parseKernelConfig(section: String?): KernelConfigProbe {
            if (section == null) return KernelConfigProbe.CONFIG_UNAVAILABLE
            val lines = section.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (lines.isEmpty()) return KernelConfigProbe.CONFIG_UNAVAILABLE
            val diagMatch = lines.any { line ->
                !line.equals(CFG_OK, ignoreCase = true) &&
                    !line.equals(CFG_NONE, ignoreCase = true) &&
                    line.contains("diag", ignoreCase = true)
            }
            return when {
                diagMatch -> KernelConfigProbe.DIAG_PRESENT
                lines.any { it.equals(CFG_OK, ignoreCase = true) } -> KernelConfigProbe.DIAG_ABSENT
                else -> KernelConfigProbe.CONFIG_UNAVAILABLE
            }
        }
    }
}
