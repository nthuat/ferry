package dev.thuat.ferry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import java.nio.file.Files

class SpaceCheckTest {

    private val fakeFileSystem = FakeFileSystem()
    private val oneGb = 1024L * 1024 * 1024

    private fun manifestOf(vararg sizes: Long) = RepoManifest(
        repoId = "test/repo",
        files = sizes.mapIndexed { i, size -> RemoteFile("file$i.bin", "https://example.test/$i", size, null) },
    )

    private fun checkWith(freeBytes: Long, headroom: Long = 0L) =
        SpaceCheck(probe = { _ -> freeBytes }, headroomBytes = headroom)

    @Test
    fun `sufficient when free space exceeds the total`() {
        val report = checkWith(freeBytes = 4 * oneGb).check(manifestOf(oneGb, oneGb), "/downloads".toPath())

        assertTrue(report.sufficient)
        assertEquals(0L, report.shortfallBytes)
    }

    @Test
    fun `insufficient when the repo is larger than free space`() {
        val report = checkWith(freeBytes = 2 * oneGb).check(manifestOf(3 * oneGb), "/downloads".toPath())

        assertFalse(report.sufficient)
    }

    @Test
    fun `shortfall reports exactly how many bytes are missing`() {
        val report = checkWith(freeBytes = 2 * oneGb).check(manifestOf(3 * oneGb), "/downloads".toPath())

        assertEquals(oneGb, report.shortfallBytes)
    }

    @Test
    fun `headroom is required on top of the repo size`() {
        // Exactly enough for the files, nothing spare.
        val report = SpaceCheck(probe = { _ -> oneGb }, headroomBytes = oneGb)
            .check(manifestOf(oneGb), "/downloads".toPath())

        assertFalse(report.sufficient, "a full disk must not count as sufficient")
        assertEquals(oneGb, report.shortfallBytes)
    }

    @Test
    fun `an empty repo needs only headroom`() {
        val report = SpaceCheck(probe = { _ -> 10L }, headroomBytes = 0L)
            .check(RepoManifest("test/repo", emptyList()), "/downloads".toPath())

        assertTrue(report.sufficient)
        assertEquals(0L, report.requiredBytes)
    }

    @Test
    fun `the report carries the numbers needed to explain the refusal`() {
        val report = checkWith(freeBytes = 2 * oneGb, headroom = 0L).check(manifestOf(3 * oneGb), "/downloads".toPath())

        assertEquals(3 * oneGb, report.requiredBytes)
        assertEquals(2 * oneGb, report.freeBytes)
    }

    @Test
    fun `the default probe reports a positive figure for a real directory`() {
        // jvm-only behavior of the default probe; moves to jvmTest in Task 7
        val temp = Files.createTempDirectory("ferry-test")
        val tempPath = temp.toString().toPath()
        assertTrue(DefaultFreeSpaceProbe.freeBytes(tempPath) > 0L)
    }

    @Test
    fun `the probe is asked about the target directory`() {
        var asked: Path? = null
        val targetPath = "/downloads".toPath()
        SpaceCheck(probe = { dir -> asked = dir; oneGb }, headroomBytes = 0L)
            .check(manifestOf(1L), targetPath)

        assertEquals(targetPath, asked)
    }

    /**
     * `File.usableSpace` returns 0 — not an exception — for a path that does not exist, and
     * `SpaceCheck` is public, exported on the `api` configuration: a host preflighting with
     * `SpaceCheck().check(manifest, File(filesDir, "models"))` on a clean install, before that
     * directory is ever created, would otherwise see "nothing fits" regardless of how much space is
     * actually free. `temp.newFolder` is used only for the parent, never for the directory under
     * test itself — that call creates the folder, which is exactly the condition this test must not
     * have; a `TemporaryFolder`-backed suite is why 100 pre-existing tests never saw this bug.
     */
    @Test
    fun `the default probe reports the volume's real free space for a directory that does not exist yet`() {
        // jvm-only behavior of the default probe; moves to jvmTest in Task 7
        val parentDir = Files.createTempDirectory("ferry-test-parent")
        val missing = "${parentDir}/fresh-install".toPath()

        val report = SpaceCheck().check(manifestOf(1L), missing)

        assertTrue(
            report.freeBytes > 0L,
            "must report the volume's real free space, not the phantom zero usableSpace reports " +
                "for a path that does not exist yet",
        )
    }
}
