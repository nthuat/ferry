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
 * Names a committed directory as Ferry's own, and records which repo id owns it.
 *
 * The commit step replaces whatever sits at the target path, and one repo id is free to be a
 * directory prefix of another — "owner" and "owner/model" are both ordinary ids, and both resolve
 * to strict children of the download root, so no containment check can tell them apart. Ferry
 * therefore deletes only what it wrote under this exact id. A directory with no marker, or a
 * marker naming a different id, is refused rather than removed.
 */
private const val MARKER_FILE = ".ferry"

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

        var staging: File? = null
        try {
            // repoId is used as a relative path rather than flattened into one directory name, so
            // two distinct ids (e.g. "a/b" and "a--b") can never collide onto the same directory.
            //
            // staging is validated against stagingRoot (into/.staging), not against `into` itself:
            // `into` is loose enough to contain both `.staging` and every already-committed repo, so
            // a repoId like "../<other-repo>" resolves to that other repo's own directory and would
            // still pass a check against `into`. Shrinking the boundary to the one directory a
            // repoId's staging copy is actually allowed to land in closes that.
            val stagingRoot = File(into, ".staging")
            val stagingDir = resolveInside(stagingRoot, repoId)
            staging = stagingDir
            val target = resolveInside(into, repoId)

            // A repoId of ".staging/evil" resolves `target` to a path inside stagingRoot — not an
            // escape (it never leaves `into`, so the check above misses it), but a collision with
            // the reserved staging namespace that would let one repo's commit clobber another
            // repo's in-flight staging copy, or vice versa.
            val targetPath = target.canonicalPath
            val stagingRootPath = stagingRoot.canonicalPath
            if (targetPath == stagingRootPath || targetPath.startsWith(stagingRootPath + File.separator)) {
                throw IOException("repo id collides with the staging area: $repoId")
            }

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

            stagingDir.mkdirs()

            manifest.files.forEachIndexed { index, remote ->
                // remote.path comes from the hub's manifest over the network — untrusted the same
                // way repoId is. Without this, a hostile listing could write anywhere on disk.
                val destination = resolveInside(stagingDir, remote.path)
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

            // Written into staging rather than into target after the rename, so the rename commits
            // the marker atomically with the repo: a reader never sees a committed directory that
            // has no marker, and a crash between the two cannot strand a repo that is then refused
            // forever. Written after the download loop, so a manifest entry literally named
            // ".ferry" cannot forge ownership of a directory — Ferry's own write always lands last.
            File(stagingDir, MARKER_FILE).writeText(repoId)

            if (target.exists()) {
                // An absent marker is a refusal, not an exception: something Ferry did not write is
                // sitting here, and that is precisely what must not be deleted to make room.
                val marker = File(target, MARKER_FILE)
                if (!marker.isFile || marker.readText() != repoId) {
                    throw IOException(
                        "$target was not committed by Ferry under '$repoId'; refusing to replace it",
                    )
                }
                if (!target.deleteRecursively()) {
                    return@withContext Result.failure(IOException("cannot replace $target"))
                }
            }
            target.parentFile?.mkdirs()
            if (!stagingDir.renameTo(target)) {
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
            // Captured in the outer `staging` var rather than recomputed here: a second, independent
            // resolveInside call in finally is exactly what let a too-loose boundary check silently
            // agree with itself and delete an unrelated, already-committed repo. Reusing the one
            // validated value means there is only one computation to get right, and if it was never
            // assigned — repoId was rejected before staging was known — there is nothing to clean up.
            staging?.deleteRecursively()
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
     *
     * Strictly inside: resolving to [parent] itself is rejected, not tolerated as a base case.
     * File(parent, "") and File(parent, ".") are both exactly parent, so permitting equality made
     * an empty repo id — a blank search field, a null coalesced to "" — resolve `target` onto the
     * download root, whose commit step then deleteRecursively()s every repo the user had. None of
     * the four callers wants the parent: a repo never stages as the whole staging area, a target
     * is never the download root, and a file is never the directory containing it.
     */
    private fun resolveInside(parent: File, relative: String): File {
        val candidate = File(parent, relative)
        val root = parent.canonicalPath
        val resolved = candidate.canonicalPath
        if (resolved == root || !resolved.startsWith(root + File.separator)) {
            throw IOException("path must resolve strictly inside $parent: $relative")
        }
        return candidate
    }
}
