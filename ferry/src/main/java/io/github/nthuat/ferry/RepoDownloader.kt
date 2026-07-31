package io.github.nthuat.ferry

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** What the download is doing, at a granularity a progress UI can render without guessing. */
sealed interface RepoProgress {

    data class CheckingSpace(val repoId: String) : RepoProgress

    data class Downloading(
        val repoId: String,
        val path: String,
        val fileIndex: Int,
        val fileCount: Int,
        val bytesWritten: Long,
        val fileBytes: Long,
    ) : RepoProgress

    data class Verifying(val repoId: String, val path: String) : RepoProgress

    data class Complete(val repoId: String, val dir: File) : RepoProgress
}

/** Carries the report so a caller can say how much space is missing, not merely that some is. */
class InsufficientSpaceException(val report: SpaceReport) : IOException(
    "needs ${report.requiredBytes} bytes, ${report.freeBytes} free, " +
        "short by ${report.shortfallBytes}",
)

class VerificationException(val path: String) : IOException("sha256 mismatch for $path")

/**
 * Downloads a whole model repository, or none of it.
 *
 * Files land in a staging directory and are moved into place only once every one of them has
 * verified, so a model loader pointed at the target directory can never observe a repo that is
 * half-written or half-correct.
 */
class RepoDownloader(
    private val repo: ModelRepo,
    private val downloader: ResumableDownloader,
    private val spaceCheck: SpaceCheck = SpaceCheck(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun download(
        repoId: String,
        into: File,
        onProgress: (RepoProgress) -> Unit = {},
    ): Result<File> = withContext(dispatcher) {
        onProgress(RepoProgress.CheckingSpace(repoId))

        val manifest = repo.manifest(repoId).getOrElse { return@withContext Result.failure(it) }

        // Before the first byte: spending a user's data allowance to discover the disk is full is
        // the failure this library exists to prevent.
        val report = spaceCheck.check(manifest, into)
        if (!report.sufficient) {
            return@withContext Result.failure(InsufficientSpaceException(report))
        }

        try {
            // repoId is used as a relative path rather than flattened into one directory name, so
            // two distinct ids (e.g. "a/b" and "a--b") can never collide onto the same directory.
            // Resolved through resolveInside because repoId comes from the calling app and cannot
            // be trusted to stay under `into` — it could just as easily be "../../evil". That call
            // does I/O and can throw, which is why staging/target moved inside this try rather than
            // sitting above it.
            val staging = resolveInside(into, ".staging/$repoId")
            val target = resolveInside(into, repoId)

            // Already here and still correct: the cheapest possible outcome, and the one a naive
            // implementation misses by re-fetching gigabytes the device is already holding.
            //
            // Inside the try: isSatisfiedBy re-hashes existing files, which is I/O and can throw
            // the same way every other read in this method can, and must become Result.failure
            // rather than escape the public boundary.
            if (target.isDirectory && manifest.isSatisfiedBy(target)) {
                onProgress(RepoProgress.Complete(repoId, target))
                return@withContext Result.success(target)
            }

            staging.mkdirs()

            manifest.files.forEachIndexed { index, remote ->
                // remote.path comes from the hub's manifest over the network — untrusted the same
                // way repoId is. Without this, a hostile listing could write anywhere on disk.
                val destination = resolveInside(staging, remote.path)
                destination.parentFile?.mkdirs()

                downloader.download(
                    url = remote.url,
                    target = destination,
                ) { written, _ ->
                    onProgress(
                        RepoProgress.Downloading(
                            repoId = repoId,
                            path = remote.path,
                            fileIndex = index,
                            fileCount = manifest.files.size,
                            bytesWritten = written,
                            fileBytes = remote.sizeBytes,
                        ),
                    )
                }.getOrElse { return@withContext Result.failure(it) }

                // A null sha256 means the hub published none. Size is still enforced upstream by
                // ResumableDownloader, which fails a body shorter than the declared total.
                if (remote.sha256 != null) {
                    onProgress(RepoProgress.Verifying(repoId, remote.path))
                    if (!Sha256.matches(destination, remote.sha256)) {
                        return@withContext Result.failure(VerificationException(remote.path))
                    }
                }
            }

            if (target.exists() && !target.deleteRecursively()) {
                return@withContext Result.failure(IOException("cannot replace $target"))
            }
            target.parentFile?.mkdirs()
            if (!staging.renameTo(target)) {
                return@withContext Result.failure(IOException("cannot commit $target"))
            }

            onProgress(RepoProgress.Complete(repoId, target))
            Result.success(target)
        } catch (e: IOException) {
            Result.failure(e)
        } finally {
            // Staging survives only as long as the attempt. ResumableDownloader keeps its own
            // .part files inside it, so removing it here forfeits resume; that is the trade for
            // never leaving a half-repo on disk, and is revisited when resume-across-launch lands.
            //
            // Recomputed rather than reusing the `staging` above: that one lives inside the try
            // and is out of scope here. resolveInside can throw a second time on the same bad
            // repoId — swallowed with runCatching, since a rejected repoId never got as far as
            // creating anything, and a throw from finally would replace the Result the try/catch
            // above already produced instead of just failing to tidy up.
            runCatching { resolveInside(into, ".staging/$repoId") }.getOrNull()?.deleteRecursively()
        }
    }

    /**
     * Whether [dir] already holds every file of this manifest, at the right size, with the right
     * hash where one was published.
     *
     * Deliberately re-hashes rather than trusting a marker file: a marker records what was true
     * once, and the point of the check is what is true now.
     */
    private fun RepoManifest.isSatisfiedBy(dir: File): Boolean = files.all { remote ->
        val onDisk = resolveInside(dir, remote.path)
        onDisk.isFile &&
            onDisk.length() == remote.sizeBytes &&
            (remote.sha256 == null || Sha256.matches(onDisk, remote.sha256))
    }

    /**
     * Resolves [relative] inside [parent] and fails if it escapes.
     *
     * Repo ids come from the calling app and file paths come from the hub's manifest over the
     * network, so neither can be trusted to stay inside the directory it is joined to. Canonical
     * paths are compared rather than the raw strings so that "..", symlinks, and redundant
     * separators are all resolved before the comparison rather than pattern-matched.
     */
    private fun resolveInside(parent: File, relative: String): File {
        val candidate = File(parent, relative)
        val root = parent.canonicalPath
        val resolved = candidate.canonicalPath
        if (resolved != root && !resolved.startsWith(root + File.separator)) {
            throw IOException("path escapes $parent: $relative")
        }
        return candidate
    }
}
