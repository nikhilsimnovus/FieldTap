package com.fieldtap.platform.capability

import android.util.Log
import com.fieldtap.core.capability.KernelConfigProbe
import com.fieldtap.core.capability.RadioLogParser
import com.fieldtap.core.capability.RadioLogReadout
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
 * Read-only, always. The `/1` sections come first — `id` (or `whoami`), `getenforce`, `ls -l /dev/diag`,
 * and a `/proc/config.gz` diag check — followed by the deep read-only sections (deep-root-spec §4): kernel
 * release/arch/version, a diag-node `stat`, the diag-ish node list, the `/sys/class/net` interface list,
 * `tcpdump` availability, and a radio-log **readability** probe. It never writes, remounts, or installs
 * anything. It never gains root and never runs an exploit — it uses a `su` that is already on the phone
 * (capability spec §0).
 *
 * Privacy (deep-root-spec §0.3, §7): the radio-log content is consumed by `wc -l` **inside the device
 * shell**; only `RLOGOK`/`RLOGNO` and an integer count ever cross into this process — no `logcat -b radio`
 * line is piped into the [StringBuilder], parsed, or stored. `/proc/version` is captured only to be redacted
 * immediately in `:core`; it is never exported.
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
class SuperuserRunner(private val timeoutMs: Long = 8_000) {
    /** Runs the probe. Suspends on [Dispatchers.IO]; safe to cancel. */
    suspend fun run(): RootProbeRaw = runInterruptible(Dispatchers.IO) { execute() }

    private fun execute(): RootProbeRaw {
        val startNs = System.nanoTime()
        fun elapsed(): Long = (System.nanoTime() - startNs) / 1_000_000
        val process = try {
            ProcessBuilder("su", "-c", DEEP_SCRIPT).redirectErrorStream(true).start()
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
         * The one read-only pipeline. `---`/`---END` markers split the sections deterministically. The four
         * `/1` sections come first (so the `/1` parse and its tests are unchanged), then the deep sections
         * (deep-root-spec §4). The kernel-config section prints `CFGOK` (config readable) or `CFGNONE`
         * (absent/unreadable) before any `grep -i diag` hits, so [parseSuOutput] can tell `DIAG_ABSENT`
         * (config read, no match) from `CONFIG_UNAVAILABLE`. `zcat`/`stat` are tried via `toybox` first,
         * then plainly; every command is read-only.
         *
         * The final section is the radio-log **readability** probe: `RLOGOK`/`RLOGNO` on one line and a
         * shell-side `| wc -l` integer on the next — **no `logcat -b radio` line ever leaves the device
         * shell**, only the flag and the count (deep-root-spec §0.3, §7).
         */
        const val DEEP_SCRIPT: String =
            "id; echo ---; " +
                "getenforce; echo ---; " +
                "ls -l /dev/diag; echo ---; " +
                "C=\"\$( (toybox zcat /proc/config.gz 2>/dev/null || zcat /proc/config.gz 2>/dev/null) )\"; " +
                "if [ -n \"\$C\" ]; then echo CFGOK; echo \"\$C\" | grep -i diag; else echo CFGNONE; fi; echo ---; " +
                "uname -r; echo ---; " +
                "uname -m; echo ---; " +
                "cat /proc/version; echo ---; " +
                "(toybox stat -c '%a %U %G %F' /dev/diag 2>/dev/null || stat -c '%a %U %G %F' /dev/diag 2>/dev/null); echo ---; " +
                "ls -l /dev/diag* /dev/ttyGS* /dev/qcqmi* 2>/dev/null; echo ---; " +
                "ls /sys/class/net 2>/dev/null; echo ---; " +
                "(which tcpdump 2>/dev/null; for p in /system/bin/tcpdump /system/xbin/tcpdump /vendor/bin/tcpdump /data/local/tmp/tcpdump; do [ -e \"\$p\" ] && echo \"\$p\"; done); echo ---; " +
                "if logcat -b radio -d -t 5 >/dev/null 2>&1; then echo RLOGOK; else echo RLOGNO; fi; " +
                "logcat -b radio -d -t 5 2>/dev/null | wc -l; " +
                "echo ---END"

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
            // ---- The four /1 sections (unchanged parse) ----
            val idSection = sections.getOrNull(0)
            val getenforceSection = sections.getOrNull(1)
            val diagLsSection = sections.getOrNull(2)
            val kernel = parseKernelConfig(sections.getOrNull(3))
            val granted = idSection != null &&
                (idSection.contains("uid=0") || idSection.trim().equals("root", ignoreCase = true))
            val status = if (granted) SuStatus.GRANTED else SuStatus.DENIED
            // ---- The deep sections (absent → null/default when the /1-only output is shorter) ----
            val radio = parseRadioLog(sections.getOrNull(11))
            return RootProbeRaw(
                suStatus = status,
                idOutput = idSection,
                getenforceOutput = getenforceSection,
                diagLsOutput = diagLsSection,
                kernelConfigDiag = kernel,
                elapsedMs = elapsedMs,
                unameOutput = sections.getOrNull(4),
                unameMachineOutput = sections.getOrNull(5),
                procVersionOutput = sections.getOrNull(6),
                diagStatOutput = sections.getOrNull(7),
                diagNodesLsOutput = sections.getOrNull(8),
                netListOutput = sections.getOrNull(9),
                tcpdumpWhichOutput = sections.getOrNull(10),
                tcpdumpPathHits = tcpdumpPathHits(sections.getOrNull(10)),
                radioLogReadable = radio.readable,
                radioLogLineCount = radio.lineCount,
            )
        }

        /** The su-side `which`/known-path hits: the path-shaped lines from the tcpdump section. */
        private fun tcpdumpPathHits(section: String?): List<String> =
            section?.lineSequence()
                ?.map { it.trim() }
                ?.filter { it.startsWith("/") }
                ?.distinct()
                ?.toList()
                .orEmpty()

        /**
         * The radio-log section holds two lines: the `RLOGOK`/`RLOGNO` flag and the shell-side `wc -l`
         * integer. Only these two cross into the process; [RadioLogParser] reduces them to a `Boolean` + an
         * `Int?`, so no log line is ever stored (deep-root-spec §7).
         */
        private fun parseRadioLog(section: String?): RadioLogReadout {
            val lines = section?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList().orEmpty()
            return RadioLogParser.parse(lines.getOrNull(0), lines.getOrNull(1))
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
