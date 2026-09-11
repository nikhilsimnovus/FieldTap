package com.fieldtap.core.nettest

import com.fieldtap.core.time.Clock
import java.io.IOException
import java.io.InputStream

/** How reading a download body ended. */
data class BodyCount(
    /** Body bytes read, never more than the cap. */
    val bytes: Long,
    val end: End,
    /** The failure when [end] is [End.ERROR]. */
    val error: IOException?,
) {
    enum class End {
        /** The body ended. */
        COMPLETE,

        /** The byte cap was reached. */
        CAP,

        /** The time limit passed while data was still arriving. */
        DEADLINE,

        /** The caller stopped caring (the coroutine was cancelled). */
        CANCELLED,

        /** A read failed; see [error]. */
        ERROR,
    }
}

/**
 * Counts a download body: reads [InputStream] until it ends, [capBytes] have been read, the elapsed
 * clock reaches `deadlineElapsedMs`, `isActive` turns false, or a read throws. Bytes are counted, not
 * kept. A single blocked read is bounded by the connection's own read timeout, which surfaces as an
 * [IOException] and ends with [BodyCount.End.ERROR].
 *
 * Owner: workstream `service-and-tests`.
 */
object BodyCounter {
    const val BUFFER_BYTES: Int = 64 * 1024

    fun count(
        input: InputStream,
        capBytes: Long,
        clock: Clock,
        deadlineElapsedMs: Long,
        isActive: () -> Boolean = { true },
    ): BodyCount {
        if (capBytes <= 0) return BodyCount(0, BodyCount.End.CAP, null)
        val buffer = ByteArray(BUFFER_BYTES)
        var bytes = 0L
        while (true) {
            if (!isActive()) return BodyCount(bytes, BodyCount.End.CANCELLED, null)
            if (clock.elapsedRealtimeMillis() >= deadlineElapsedMs) return BodyCount(bytes, BodyCount.End.DEADLINE, null)
            val wanted = minOf(buffer.size.toLong(), capBytes - bytes).toInt()
            val read = try {
                input.read(buffer, 0, wanted)
            } catch (e: IOException) {
                return BodyCount(bytes, BodyCount.End.ERROR, e)
            }
            if (read < 0) return BodyCount(bytes, BodyCount.End.COMPLETE, null)
            bytes += read
            if (bytes >= capBytes) return BodyCount(bytes, BodyCount.End.CAP, null)
        }
    }
}
