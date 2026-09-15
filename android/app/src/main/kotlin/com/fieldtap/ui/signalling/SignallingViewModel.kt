package com.fieldtap.ui.signalling

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fieldtap.diag.SignallingEntry
import com.fieldtap.diag.SignallingReader
import com.fieldtap.platform.diag.DiagCaptureResult
import com.fieldtap.platform.diag.HandsetDiagCapture
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the signalling screen draws. */
data class SignallingUiState(
    val capturing: Boolean = false,
    /** A capture or a decode is in flight; the buttons wait for it. */
    val busy: Boolean = false,
    val entries: List<SignallingEntry> = emptyList(),
    /** Log records read that made no call-flow line, almost all of them RRC. */
    val rrcRecords: Int = 0,
    val message: String? = null,
    val failed: Boolean = false,
    val captureFile: File? = null,
) {
    val canExport: Boolean get() = captureFile != null && !capturing
}

/**
 * Drives [HandsetDiagCapture] and reads what it wrote.
 *
 * Capture and decode both run off the main thread: a capture is a `su` round trip and a decode walks a
 * multi-megabyte file, and neither belongs on the frame clock.
 *
 * The capture file is kept after decoding rather than deleted, because it is the half the phone cannot
 * read — RRC — and the only way to get at that is to hand the file to Wireshark or `fieldtap report`.
 *
 * Owner: workstream `diag-on-handset`.
 */
class SignallingViewModel(private val exportsDir: File) : ViewModel() {

    private val capture = HandsetDiagCapture()
    private val _state = MutableStateFlow(SignallingUiState())
    val state: StateFlow<SignallingUiState> = _state.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null, failed = false)
            when (val result = capture.start()) {
                is DiagCaptureResult.Started ->
                    _state.value = _state.value.copy(
                        capturing = true,
                        busy = false,
                        entries = emptyList(),
                        rrcRecords = 0,
                        captureFile = null,
                        message = "Recording signalling. Leave this on while the phone does something worth seeing.",
                    )

                DiagCaptureResult.NoRoot -> fail(
                    "Signalling capture needs root, and this phone did not grant it. " +
                        "Everything else in the app works without it.",
                )

                DiagCaptureResult.NoLogger -> fail(
                    "This phone has no diag_mdlog, so its modem does not expose signalling this way.",
                )

                is DiagCaptureResult.Failed -> fail(result.reason)
                is DiagCaptureResult.Stopped -> fail("the logger stopped before it started")
            }
        }
    }

    fun stop() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = "Reading the capture…")
            capture.stop()
            exportsDir.mkdirs()
            val target = File(exportsDir, CAPTURE_NAME)
            val file = capture.collect(target)
            if (file == null) {
                _state.value = _state.value.copy(
                    capturing = false,
                    busy = false,
                    failed = true,
                    message = "The logger wrote nothing. Nothing was captured.",
                )
                return@launch
            }
            val summary = withContext(Dispatchers.IO) {
                try {
                    SignallingReader.read(file.readBytes())
                } catch (e: IOException) {
                    null
                }
            }
            if (summary == null) {
                _state.value = _state.value.copy(
                    capturing = false, busy = false, failed = true,
                    message = "The capture could not be read.",
                )
                return@launch
            }
            _state.value = SignallingUiState(
                capturing = false,
                busy = false,
                entries = summary.entries,
                rrcRecords = summary.records - summary.entries.size,
                captureFile = file,
                message = summaryLine(summary.entries, summary.records, summary.crcErrors),
                failed = false,
            )
        }
    }

    private fun fail(reason: String) {
        _state.value = _state.value.copy(capturing = false, busy = false, failed = true, message = reason)
    }

    private fun summaryLine(entries: List<SignallingEntry>, records: Int, crcErrors: Int): String {
        val rejects = entries.count { it.isReject }
        val parts = mutableListOf("$records records", "${entries.size} NAS messages")
        if (rejects > 0) parts += "$rejects rejected"
        if (crcErrors > 0) parts += "$crcErrors corrupt frames"
        return parts.joinToString(" · ")
    }

    companion object {
        /** Written under `cache/exports/` so the file provider will hand it out. */
        const val CAPTURE_NAME: String = "signalling.qmdl"
        const val MIME: String = "application/octet-stream"
    }
}
