package com.fieldtap.core.capability

/**
 * The passive root assessment: it reads the inputs `com.fieldtap.platform.capability` gathered with no su
 * call and picks a confidence tier. A live `su` binary or its manager package is the strongest passive
 * tell short of running it; an unlocked/eng-signed build is a weaker one.
 *
 * The confidence table (pure, table-tested):
 * - HIGH: a su binary is present, **or** a root-manager package is present.
 * - MEDIUM: `Build.TAGS` contains `test-keys` **and** (`ro.debuggable == 1` **or** `ro.secure == 0`);
 *   **or** any writable system path.
 * - LOW: a weak signal alone (`test-keys`, `ro.debuggable == 1` or `ro.secure == 0`) with none of the
 *   above. Common on some OEM debug/engineering firmware; worth surfacing, not asserting.
 * - NONE: no signal fired.
 *
 * [assess] always attaches [CAVEAT], because root-hiding (Magisk DenyList, Zygisk) can make a rooted
 * phone look unrooted, so "no root detected" is never proof.
 *
 * Owner: workstream `capability-core`.
 */
object RootDetector {
    /** su binaries on common paths; `/debug_ram*` is glob-expanded by the adapter, so its literal is here too. */
    val SU_PATHS: List<String> = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/odm/bin/su",
        "/vendor/bin/su",
        "/debug_ram/su",
    )

    /** Root-manager packages `PackageManager` is asked about (see the manifest `<queries>` block). */
    val ROOT_MANAGER_PACKAGES: List<String> = listOf(
        "com.topjohnwu.magisk", // Magisk
        "me.weishu.kernelsu", // KernelSU
        "eu.chainfire.supersu", // SuperSU
        "com.noshufou.android.su",
        "com.noshufou.android.su.elite",
        "com.koushikdutta.superuser",
        "com.thirdparty.superuser",
        "com.yellowes.su",
        "com.kingroot.kinguser",
        "com.kingo.root",
        "com.zachspong.temprootremovejb",
        "com.ramdroid.appquarantine",
    )

    /** System-ish paths probed with `File.canWrite`; a writable one is a strong tell of a modified system. */
    val WRITABLE_PATHS: List<String> = listOf(
        "/system",
        "/system/bin",
        "/system/xbin",
        "/vendor",
        "/data/local",
    )

    /** The only `getprop` keys the assessment reads. */
    val PropKeys: List<String> = listOf(
        "ro.debuggable",
        "ro.secure",
        "ro.build.type",
        "ro.build.tags",
        "ro.build.selinux",
    )

    /** Always attached to the passive assessment; a no-root result ships it so it is never read as proof. */
    const val CAVEAT: String =
        "Root-hiding (Magisk DenyList, Zygisk) can make a rooted phone look unrooted, so no root detected is not proof."

    /** Picks the highest confidence tier whose condition holds and records the booleans it read. */
    fun assess(input: PassiveInputs): RootSignals {
        val testKeys = input.buildTags.contains("test-keys")
        val debuggable = input.props["ro.debuggable"] == "1"
        val secureOff = input.props["ro.secure"] == "0"
        val hasSuOrManager = input.suBinariesPresent.isNotEmpty() || input.rootManagerPackages.isNotEmpty()
        val hasWritable = input.writableSystemPaths.isNotEmpty()
        val weakSignal = testKeys || debuggable || secureOff

        val confidence = when {
            hasSuOrManager -> RootConfidence.HIGH
            (testKeys && (debuggable || secureOff)) || hasWritable -> RootConfidence.MEDIUM
            weakSignal -> RootConfidence.LOW
            else -> RootConfidence.NONE
        }

        return RootSignals(
            suBinariesPresent = input.suBinariesPresent,
            rootManagerPackages = input.rootManagerPackages,
            buildTagsTestKeys = testKeys,
            debuggable = debuggable,
            secureOff = secureOff,
            writableSystemPaths = input.writableSystemPaths,
            confidence = confidence,
            caveat = CAVEAT,
        )
    }
}
