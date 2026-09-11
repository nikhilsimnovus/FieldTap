package com.fieldtap.core.input

/**
 * Everything the platform adapters deliver, as plain values. The adapters in
 * com.fieldtap.platform turn Android callbacks into these; the session recorder and the Live
 * screen's reducer consume them. No Android type crosses this boundary.
 *
 * Every input is stamped by the adapter at the moment the callback fired, with both clocks read
 * from the injected [com.fieldtap.core.time.Clock], never when a consumer gets to it.
 *
 * The implementations live in RadioInputs.kt (workstream `radio-core`) and LocationInputs.kt
 * (workstream `location-privacy-core`). Their shapes are a frozen contract between workstreams;
 * see android/ARCHITECTURE.md, "Changing a frozen contract".
 *
 * Owner: workstream `session-core`.
 */
sealed interface MeasurementInput {
    /** Unix milliseconds when the platform callback fired. */
    val observedWallMs: Long

    /** elapsedRealtime milliseconds when the platform callback fired. */
    val observedElapsedMs: Long
}
