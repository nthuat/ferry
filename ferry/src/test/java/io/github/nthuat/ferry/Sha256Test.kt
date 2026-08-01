package io.github.nthuat.ferry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Sha256Test {

    @get:Rule
    val temp = TemporaryFolder()

    /** Published SHA-256 of the empty input and of "abc" — fixed points, not values we computed. */
    private val emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private val abcHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    private fun fileOf(content: String): File =
        temp.newFile().apply { writeText(content) }

    @Test
    fun `hashes the empty file to the known digest`() {
        assertEquals(emptyHash, Sha256.of(fileOf("")))
    }

    @Test
    fun `hashes abc to the known digest`() {
        assertEquals(abcHash, Sha256.of(fileOf("abc")))
    }

    @Test
    fun `output is lowercase hex of the full digest`() {
        val hash = Sha256.of(fileOf("abc"))

        assertEquals(64, hash.length)
        assertEquals(hash.lowercase(), hash)
    }

    @Test
    fun `matches accepts the correct digest`() {
        assertTrue(Sha256.matches(fileOf("abc"), abcHash))
    }

    @Test
    fun `matches rejects a different digest`() {
        assertFalse(Sha256.matches(fileOf("abc"), emptyHash))
    }

    /** Hubs are inconsistent about hex case, and the resulting bug looks exactly like corruption. */
    @Test
    fun `matches ignores hex case`() {
        assertTrue(Sha256.matches(fileOf("abc"), abcHash.uppercase()))
    }

    @Test
    fun `hashes a file larger than one read buffer`() {
        val big = temp.newFile().apply { writeBytes(ByteArray(70_000) { (it % 251).toByte() }) }

        // Correctness here is that it completes and is stable, not a memorised constant.
        assertEquals(Sha256.of(big), Sha256.of(big))
        assertEquals(64, Sha256.of(big).length)
    }

    @Test
    fun `a one byte difference changes the digest`() {
        assertFalse(Sha256.of(fileOf("abc")) == Sha256.of(fileOf("abd")))
    }
}
