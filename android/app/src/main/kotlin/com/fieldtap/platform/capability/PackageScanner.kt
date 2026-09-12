package com.fieldtap.platform.capability

import android.content.pm.PackageManager
import android.os.Build
import com.fieldtap.core.capability.RootDetector

/**
 * Which known root-manager packages `PackageManager` can find. Needs the `<queries>` block in the manifest
 * that names [RootDetector.ROOT_MANAGER_PACKAGES] (API 30+ package visibility, no `QUERY_ALL_PACKAGES`).
 *
 * The mapping — a `NameNotFoundException` means the package is absent — is factored into the pure [scan],
 * JVM-tested with a fake lookup; [scanInstalled] supplies the real `getPackageInfo` probe.
 *
 * Owner: workstream `capability-core`.
 */
class PackageScanner(private val packageManager: PackageManager) {
    /** The subset of [RootDetector.ROOT_MANAGER_PACKAGES] installed on this phone. */
    fun scanInstalled(): List<String> = scan(RootDetector.ROOT_MANAGER_PACKAGES) { pkg -> isInstalled(pkg) }

    private fun isInstalled(pkg: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(pkg, 0)
        }
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        /** The packages in [candidates] for which [present] is true, in [candidates] order. */
        fun scan(candidates: List<String>, present: (String) -> Boolean): List<String> =
            candidates.filter(present)
    }
}
