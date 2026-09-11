package com.fieldtap.core.export

import java.io.File
import java.io.IOException
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Sha256Test {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun knownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(ByteArray(0)))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hex("abc".toByteArray(Charsets.US_ASCII)),
        )
    }

    @Test
    fun aFileDigestEqualsTheDigestOfItsBytesAcrossBufferBoundaries() {
        val bytes = Random(42).nextBytes(200_003)
        val file = temp.newFile("blob.bin")
        file.writeBytes(bytes)
        assertEquals(Sha256.hex(bytes), Sha256.hex(file))
    }

    @Test
    fun hexIsSixtyFourLowerCaseDigits() {
        assertTrue(Regex("[0-9a-f]{64}").matches(Sha256.hex(byteArrayOf(-1, 0, 127, -128))))
    }

    @Test
    fun aMissingFileThrows() {
        assertThrows(IOException::class.java) { Sha256.hex(File(temp.root, "missing.bin")) }
    }
}
