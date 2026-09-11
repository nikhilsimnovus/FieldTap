package com.fieldtap.core.nettest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IcmpEchoTest {

    @Test
    fun requestBytesAreTypeCodeChecksumIdentifierSequenceAndPayload() {
        val packet = IcmpEcho.request(1, byteArrayOf(1, 2, 3, 4))

        // Words 0x0800 + 0x0001 + 0x0102 + 0x0304 = 0x0C07; its one's complement is 0xF3F8.
        val expected = bytes(0x08, 0x00, 0xF3, 0xF8, 0x00, 0x00, 0x00, 0x01, 0x01, 0x02, 0x03, 0x04)
        assertArrayEquals(expected, packet)
    }

    @Test
    fun aRequestCarriesAValidChecksum() {
        val packet = IcmpEcho.request(0xBEEF, IcmpEcho.payload())

        assertEquals(IcmpEcho.HEADER_BYTES + IcmpEcho.DEFAULT_PAYLOAD_BYTES, packet.size)
        assertEquals(0, IcmpEcho.checksum(packet, packet.size))
    }

    @Test
    fun theSequenceIsBigEndianAndTheIdentifierIsLeftToTheKernel() {
        val packet = IcmpEcho.request(0x1234, ByteArray(0))

        assertEquals(0x12.toByte(), packet[6])
        assertEquals(0x34.toByte(), packet[7])
        assertEquals(0.toByte(), packet[4])
        assertEquals(0.toByte(), packet[5])
    }

    @Test(expected = IllegalArgumentException::class)
    fun aSequenceBeyondSixteenBitsIsRefused() {
        IcmpEcho.request(70_000, ByteArray(0))
    }

    @Test
    fun anEchoReplyWithTheSameSequenceMatches() {
        val reply = IcmpEcho.request(7, IcmpEcho.payload(8)).also { it[0] = 0 }

        assertTrue(IcmpEcho.isReplyTo(reply, reply.size, 7))
        assertTrue(IcmpEcho.isReplyTo(reply, IcmpEcho.HEADER_BYTES, 7))
    }

    @Test
    fun otherPacketsDoNotMatch() {
        val request = IcmpEcho.request(7, IcmpEcho.payload(8))
        val reply = request.copyOf().also { it[0] = 0 }
        val unreachable = request.copyOf().also { it[0] = 3 }

        assertFalse("our own request is not a reply", IcmpEcho.isReplyTo(request, request.size, 7))
        assertFalse("a reply to another echo", IcmpEcho.isReplyTo(reply, reply.size, 8))
        assertFalse("destination unreachable", IcmpEcho.isReplyTo(unreachable, unreachable.size, 7))
        assertFalse("shorter than a header", IcmpEcho.isReplyTo(reply, IcmpEcho.HEADER_BYTES - 1, 7))
        assertFalse("length beyond the buffer", IcmpEcho.isReplyTo(reply, reply.size + 1, 7))
    }

    @Test
    fun sequencesCompareAsSixteenBits() {
        val reply = IcmpEcho.request(0xFFFF, ByteArray(0)).also { it[0] = 0 }
        assertTrue(IcmpEcho.isReplyTo(reply, reply.size, 0x1FFFF))
    }

    @Test
    fun anOddLengthIsPaddedWithAZeroByte() {
        assertEquals(0xFEFF, IcmpEcho.checksum(byteArrayOf(0x01), 1))
        assertEquals(0xFFFF, IcmpEcho.checksum(ByteArray(4), 4))
    }

    @Test
    fun thePayloadCountsUpAndNamesNothing() {
        val payload = IcmpEcho.payload(4)
        assertArrayEquals(bytes(0, 1, 2, 3), payload)
        assertEquals(IcmpEcho.DEFAULT_PAYLOAD_BYTES, IcmpEcho.payload().size)
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
