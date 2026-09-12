package com.fieldtap.platform.capability

import com.fieldtap.core.capability.CapabilitySnapshot
import com.fieldtap.core.capability.RootProbeResult

/**
 * The one facade the Capability screen's ViewModel reaches capability detection through. Added to
 * `com.fieldtap.app.AppGraph` and wired to [AndroidCapabilityInspector] in `DefaultAppGraph` (both
 * frozen-contract changes the orchestrator applies, like the existing `CapabilityProbe`).
 *
 * It references only `:core` types, so a ViewModel can be unit-tested against a fake. Both calls are
 * `suspend` and do their work off the main thread.
 *
 * Owner: workstream `capability-core`.
 */
interface CapabilityInspector {
    /** Read-only, no su call, safe to run by default when the screen opens. */
    suspend fun passive(): CapabilitySnapshot

    /**
     * Runs read-only commands through an existing su on an explicit "Check with root" tap; may raise the
     * superuser grant prompt. Never called automatically.
     */
    suspend fun checkWithRoot(): RootProbeResult
}
