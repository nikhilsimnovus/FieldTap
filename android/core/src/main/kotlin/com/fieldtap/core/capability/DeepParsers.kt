package com.fieldtap.core.capability

/**
 * Pure parsers for the deep read-only diagnostics (deep-root-spec §3). No I/O, no `android.*` type, so every
 * one is JVM-tested. Each turns a raw command string the `:app` adapter gathered into a fact type from
 * [CapabilityModel]. None of them ever reads or returns a byte of node content, a raw log line, a packet, or
 * a subscriber identifier — only metadata, counts, versions and names (§0.3, §7).
 *
 * Owner: workstream `deep-root-core`.
 */

/**
 * `uname`/`/proc/version` → [KernelInfo]. [redactProcVersion] guarantees the exported version string carries
 * no `user@host`, no date and no build path.
 */
object KernelParser {
    /**
     * @param uname `uname -r` output (the kernel release).
     * @param machine `uname -m` output (the architecture).
     * @param procVersion `cat /proc/version` output — read only to detect SMP/PREEMPT and to redact; the
     *   raw value is never stored or exported.
     */
    fun parse(uname: String?, machine: String?, procVersion: String?): KernelInfo = KernelInfo(
        release = uname?.trim()?.ifEmpty { null },
        architecture = machine?.trim()?.ifEmpty { null },
        smp = procVersion?.contains("SMP", ignoreCase = true) == true,
        preempt = procVersion?.contains("PREEMPT", ignoreCase = true) == true,
        redactedVersion = redactProcVersion(procVersion),
    )

    private val HEADER = Regex("""^Linux version\s+(\S+)""")
    private val SMP = Regex("""\bSMP\b""")
    private val PREEMPT = Regex("""\bPREEMPT(?:_RT|_DYNAMIC)?\b""")

    /**
     * Reduces `/proc/version` to a safe summary: the leading `Linux version <release>` plus the
     * `SMP`/`PREEMPT[_RT]` flags, and **nothing else**. It is rebuilt from those safe fragments rather than
     * edited in place, so no `\S+@\S+` build-host stamp, no parenthesised compiler/builder group, no date
     * tail and no absolute build path can survive. Returns `null` when there is no usable input.
     */
    fun redactProcVersion(procVersion: String?): String? {
        val text = procVersion?.trim().orEmpty()
        if (text.isEmpty()) return null
        // The release token is `uname -r`; strip anything past an `@` defensively (a release never has one).
        val releaseToken = HEADER.find(text)?.groupValues?.get(1)?.substringBefore('@')
        val flags = buildList {
            if (SMP.containsMatchIn(text)) add("SMP")
            PREEMPT.find(text)?.value?.let { add(it) }
        }
        val head = releaseToken?.let { "Linux version $it" } ?: "Linux version"
        val result = (listOf(head) + flags).joinToString(" ").trim()
        return result.ifEmpty { null }
    }
}

/**
 * SELinux mode + node readability → a [SelinuxAssessment] with a plain-language consequence. Never changes
 * anything, and never by itself flips a proven-readable ([DiagDevice.PRESENT]) node to not-viable — matching
 * the [CapabilityVerdict.layer3] rule.
 */
object SelinuxAssessor {
    fun assess(mode: SelinuxMode, diag: DiagDevice): SelinuxAssessment {
        // ENFORCING blocks an app-reachable diag path unless the node was already listed as root (PRESENT).
        val blocks = mode == SelinuxMode.ENFORCING && diag != DiagDevice.PRESENT
        return SelinuxAssessment(
            mode = mode,
            blocksAppDiagPath = blocks,
            consequence = CapabilityMessages.selinuxConsequence(mode, blocks),
        )
    }
}

/**
 * `stat -c '%a %U %G %F' /dev/diag` → the primary [DiagNodeStat]. Reads only mode/owner/type; a
 * "No such file"/"cannot access"/"cannot stat" line → `exists = false`; a "Permission denied" line →
 * `exists = true, octalMode = null` (a denial hides the mode). **No byte of node content is ever read.**
 */
object DiagStatParser {
    const val PRIMARY_PATH: String = "/dev/diag"
    private val OCTAL = Regex("""^[0-7]{3,4}$""")

    fun parse(statOutput: String?): DiagNodeStat {
        val text = statOutput?.trim().orEmpty()
        val lower = text.lowercase()
        val absent = DiagNodeStat(PRIMARY_PATH, exists = false, charDevice = false, octalMode = null, ownerUser = null, ownerGroup = null)
        if (text.isEmpty()) return absent
        if (lower.contains("no such file") || lower.contains("cannot access") || lower.contains("cannot stat")) {
            return absent
        }
        if (lower.contains("permission denied") || lower.contains("access denied")) {
            return DiagNodeStat(PRIMARY_PATH, exists = true, charDevice = false, octalMode = null, ownerUser = null, ownerGroup = null)
        }
        // A valid `%a %U %G %F` line, e.g. "660 radio radio character special file".
        val firstLine = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val tokens = firstLine.split(Regex("""\s+"""))
        val mode = tokens.getOrNull(0)?.takeIf { OCTAL.matches(it) }
        return if (mode != null) {
            DiagNodeStat(
                path = PRIMARY_PATH,
                exists = true,
                charDevice = firstLine.contains("character special", ignoreCase = true),
                octalMode = mode,
                ownerUser = tokens.getOrNull(1),
                ownerGroup = tokens.getOrNull(2),
            )
        } else {
            absent
        }
    }
}

/**
 * `ls -l /dev/diag* /dev/ttyGS* /dev/qcqmi*` → the list of **other** diag-ish nodes (the primary `/dev/diag`
 * is reported separately and filtered out here). Metadata only — type, symbolic-mode-as-octal, owner/group
 * and path. `ls:`/"No such file" error lines (a glob that matched nothing) are skipped.
 */
object DiagNodesParser {
    fun parse(lsOutput: String?): List<DiagNodeStat> {
        val text = lsOutput?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { parseLine(it) }
            .filter { it.path != DiagStatParser.PRIMARY_PATH }
            .toList()
    }

    private fun parseLine(line: String): DiagNodeStat? {
        val lower = line.lowercase()
        if (lower.startsWith("ls:") || lower.contains("no such file") || lower.contains("cannot access") || lower.contains("permission denied")) {
            return null
        }
        val tokens = line.split(Regex("""\s+"""))
        val modeToken = tokens.getOrNull(0) ?: return null
        // A long-listing line begins with a 10-char mode string (type + 9 permission bits).
        if (modeToken.length < 10) return null
        val type = modeToken[0]
        if (type !in "cbdlsp-") return null
        val path = tokens.lastOrNull()?.takeIf { it.startsWith("/") } ?: return null
        return DiagNodeStat(
            path = path,
            exists = true,
            charDevice = type == 'c',
            octalMode = symbolicToOctal(modeToken),
            ownerUser = tokens.getOrNull(2),
            ownerGroup = tokens.getOrNull(3),
        )
    }

    /** Converts a 10-char `ls -l` mode string (e.g. `crw-rw----`) to its octal form (e.g. "660"). */
    fun symbolicToOctal(mode: String): String? {
        if (mode.length < 10) return null
        val p = mode.substring(1, 10)
        fun triad(r: Char, w: Char, x: Char): Int {
            var v = 0
            if (r == 'r') v += 4
            if (w == 'w') v += 2
            if (x == 'x' || x == 's' || x == 't') v += 1
            return v
        }
        var special = 0
        if (p[2] == 's' || p[2] == 'S') special += 4
        if (p[5] == 's' || p[5] == 'S') special += 2
        if (p[8] == 't' || p[8] == 'T') special += 1
        val digits = "${triad(p[0], p[1], p[2])}${triad(p[3], p[4], p[5])}${triad(p[6], p[7], p[8])}"
        return if (special != 0) "$special$digits" else digits
    }
}

/**
 * `ls /sys/class/net` → the modem [ModemInterfaces], **names and count only**. Keeps only interfaces matching
 * rmnet/QMI on Qualcomm and ccmni on MediaTek; never an address, MAC or route.
 */
object ModemInterfaceParser {
    private val MODEM = Regex("""^(rmnet|rmnet_data|qmux|qmi|ccmni)\w*$""")

    fun parse(netList: String?): ModemInterfaces {
        val names = netList?.split(Regex("""\s+"""))
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && MODEM.matches(it) }
            ?.distinct()
            .orEmpty()
        return ModemInterfaces(count = names.size, names = names)
    }

    /** Whether the raw `/sys/class/net` listing has any pcap-capable interface (a modem or a `wlan*`). */
    fun anyCaptureInterface(netList: String?): Boolean {
        val names = netList?.split(Regex("""\s+"""))?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return names.any { MODEM.matches(it) || it.startsWith("wlan") }
    }
}

/**
 * `which tcpdump` + known-path hits + interface presence → [CaptureTooling]. **Availability only; the app
 * never captures.** [tcpdumpPresent] is true when `which` returned a path or a known path exists;
 * [tcpdumpPaths] is the de-duplicated union.
 */
object TcpdumpParser {
    fun parse(whichOutput: String?, knownPathHits: List<String>, anyCaptureInterface: Boolean): CaptureTooling {
        val whichPaths = whichOutput?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.startsWith("/") && !it.contains("not found", ignoreCase = true) }
            ?.toList()
            .orEmpty()
        val paths = (whichPaths + knownPathHits).distinct()
        return CaptureTooling(
            tcpdumpPresent = paths.isNotEmpty(),
            tcpdumpPaths = paths,
            pcapCapableInterfacePresent = anyCaptureInterface,
        )
    }
}

/**
 * `RLOGOK`/`RLOGNO` + a shell-side `wc -l` count → [RadioLogReadout]. The type holds **only** a `Boolean` and
 * an `Int?` — there is no field a log line could go into, and the count is produced in the device shell so no
 * line ever crosses into the process (§0.3, §7).
 */
object RadioLogParser {
    const val READABLE_MARK: String = "RLOGOK"

    fun parse(readableFlag: String?, countText: String?): RadioLogReadout {
        val readable = readableFlag?.trim() == READABLE_MARK
        val count = if (readable) countText?.trim()?.toIntOrNull()?.takeIf { it >= 0 } else null
        return RadioLogReadout(readable = readable, lineCount = count)
    }
}
