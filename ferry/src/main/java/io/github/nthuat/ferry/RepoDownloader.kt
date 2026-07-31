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

        val dirName = flatten(repoId)
        val staging = File(into, ".staging/$dirName")
        val target = File(into, dirName)

        // Already here and still correct: the cheapest possible outcome, and the one a naive
        // implementation misses by re-fetching gigabytes the device is already holding.
        if (target.isDirectory && manifest.isSatisfiedBy(target)) {
            onProgress(RepoProgress.Complete(repoId, target))
            return@withContext Result.success(target)
        }

        try {
            staging.mkdirs()

            manifest.files.forEachIndexed { index, remote ->
                val destination = File(staging, remote.path)
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
            staging.deleteRecursively()
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
        val onDisk = File(dir, remote.path)
        onDisk.isFile &&
            onDisk.length() == remote.sizeBytes &&
            (remote.sha256 == null || Sha256.matches(onDisk, remote.sha256))
    }

    /**
     * "Qwen/Qwen2.5-0.5B-Instruct" is one repository, not a directory called Qwen containing one
     * called Qwen2.5-0.5B-Instruct. Flattening keeps a repo id addressable as a single directory.
     */
    private fun flatten(repoId: String): String = repoId.replace("/", "--")
}
