package dev.thuat.ferry

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class Sha256Test {

    private val fs = FakeFileSystem()
    private val dir = "/work".toPath()

    @AfterTest
    fun tearDown() = fs.checkNoOpenFiles()

    /** Published SHA-256 of the empty input and of "abc" — fixed points, not values we computed. */
    private val emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private val abcHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun `hashes the empty file to the known digest`() {
        fs.createDirectories(dir)
        val file = dir / "empty.txt"
        fs.write(file) { writeUtf8("") }
        assertEquals(emptyHash, Sha256.of(fs, file))
    }

    @Test
    fun `hashes abc to the known digest`() {
        fs.createDirectories(dir)
        val file = dir / "abc.txt"
        fs.write(file) { writeUtf8("abc") }
        assertEquals(abcHash, Sha256.of(fs, file))
    }

    @Test
    fun `output is lowercase hex of the full digest`() {
        fs.createDirectories(dir)
        val file = dir / "abc.txt"
        fs.write(file) { writeUtf8("abc") }
        val hash = Sha256.of(fs, file)

        assertEquals(64, hash.length)
        assertEquals(hash.lowercase(), hash)
    }

    @Test
    fun `matches accepts the correct digest`() {
        fs.createDirectories(dir)
        val file = dir / "abc.txt"
        fs.write(file) { writeUtf8("abc") }
        assertTrue(Sha256.matches(fs, file, abcHash))
    }

    @Test
    fun `matches rejects a different digest`() {
        fs.createDirectories(dir)
        val file = dir / "abc.txt"
        fs.write(file) { writeUtf8("abc") }
        assertFalse(Sha256.matches(fs, file, emptyHash))
    }

    /** Hubs are inconsistent about hex case, and the resulting bug looks exactly like corruption. */
    @Test
    fun `matches ignores hex case`() {
        fs.createDirectories(dir)
        val file = dir / "abc.txt"
        fs.write(file) { writeUtf8("abc") }
        assertTrue(Sha256.matches(fs, file, abcHash.uppercase()))
    }

    @Test
    fun `hashes a file larger than one read buffer`() {
        fs.createDirectories(dir)
        val file = dir / "large.bin"
        fs.write(file) { write(ByteArray(70_000) { (it % 251).toByte() }) }

        // Correctness here is that it completes and is stable, not a memorised constant.
        assertEquals(Sha256.of(fs, file), Sha256.of(fs, file))
        assertEquals(64, Sha256.of(fs, file).length)
    }

    @Test
    fun `a one byte difference changes the digest`() {
        fs.createDirectories(dir)
        val file1 = dir / "abc.txt"
        val file2 = dir / "abd.txt"
        fs.write(file1) { writeUtf8("abc") }
        fs.write(file2) { writeUtf8("abd") }
        assertFalse(Sha256.of(fs, file1) == Sha256.of(fs, file2))
    }
}
