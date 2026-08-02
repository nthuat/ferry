package io.github.nthuat.ferry.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `repoDownloadWorkName` has no Android dependency — unlike `enqueueRepoDownload`'s own dedup
 * behaviour (see `docs/known-limitations.md`, untested by construction), there is no reason for
 * this pure function to go untested too.
 */
class RepoDownloadWorkTest {

    @Test
    fun `the work name is stable for the same repo id`() {
        assertEquals(repoDownloadWorkName("owner/model"), repoDownloadWorkName("owner/model"))
    }

    @Test
    fun `the work name differs for different repo ids`() {
        assertNotEquals(repoDownloadWorkName("owner/model-a"), repoDownloadWorkName("owner/model-b"))
    }

    @Test
    fun `the work name carries the repo id, not just a hash of it`() {
        assertTrue(repoDownloadWorkName("owner/model").contains("owner/model"))
    }
}
