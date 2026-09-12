package com.fieldtap.platform.capability

import android.content.pm.PackageManager
import android.os.Build
import com.fieldtap.core.capability.RootDetector
import com.fieldtap.core.capability.RootManagerInfo

/**
 * Which known root-manager packages `PackageManager` can find, and their versions. Needs the `<queries>`
 * block in the manifest that names [RootDetector.ROOT_MANAGER_PACKAGES] (API 30+ package visibility, no
 * `QUERY_ALL_PACKAGES`).
 *
 * The mappings — a `NameNotFoundException` means the package is absent — are factored into the pure [scan]
 * and [scanVersions], JVM-tested with a fake lookup; [scanInstalled]/[scanManagerVersions] supply the real
 * `getPackageInfo` probe. The read is passive: no su, and only a package name + `versionName` (not an
 * identifier) is ever read (deep-root-spec §2).
 *
 * Owner: workstream `deep-root-core`.
 */
class PackageScanner(private val packageManager: PackageManager) {
    /** The subset of [RootDetector.ROOT_MANAGER_PACKAGES] installed on this phone. */
    fun scanInstalled(): List<String> = scan(RootDetector.ROOT_MANAGER_PACKAGES) { pkg -> isInstalled(pkg) }

    /** The installed root managers with their `versionName` (null when the platform reports none). */
    fun scanManagerVersions(): List<RootManagerInfo> = scanVersions(RootDetector.ROOT_MANAGER_PACKAGES) { pkg -> versionInfoOf(pkg) }

    private fun isInstalled(pkg: String): Boolean = versionInfoOf(pkg) != null

    /** [RootManagerInfo] when [pkg] is installed (carrying its `versionName`), or null when it is absent. */
    private fun versionInfoOf(pkg: String): RootManagerInfo? = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(pkg, 0)
        }
        RootManagerInfo(pkg, info.versionName)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    companion object {
        /** The packages in [candidates] for which [present] is true, in [candidates] order. */
        fun scan(candidates: List<String>, present: (String) -> Boolean): List<String> =
            candidates.filter(present)

        /** The [RootManagerInfo]s [versionOf] resolves (nulls dropped — absent packages), in [candidates] order. */
        fun scanVersions(candidates: List<String>, versionOf: (String) -> RootManagerInfo?): List<RootManagerInfo> =
            candidates.mapNotNull(versionOf)
    }
}
