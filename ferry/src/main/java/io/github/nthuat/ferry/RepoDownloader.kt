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

/**
 * A downloaded file is not what the manifest said it would be.
 *
 * [reason] carries the specific mismatch because the two causes need different responses: a wrong
 * SHA-256 is corruption in transit, a wrong size is usually an intermediary answering with something
 * that is not the file at all — a captive portal's login page arrives with a perfectly consistent
 * Content-Length and would otherwise pass every check.
 */
class VerificationException(
    val path: String,
    val reason: String,
) : IOException("$path failed verification: $reason")

/**
 * Names a committed directory as Ferry's own, and records which repo id owns it.
 *
 * The commit step replaces whatever sits at the target path, and one repo id is free to be a
 * directory prefix of another — "owner" and "owner/model" are both ordinary ids, and both resolve
 * to strict children of the download root, so no containment check can tell them apart. Ferry
 * therefore deletes only what it wrote under this exact id. A directory with no marker, a marker
 * naming a different id, or one that contains another marker anywhere beneath it, is refused
 * rather than removed.
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

    /**
     * Downloads [repoId] into a directory under [into] and returns it.
     *
     * **Not safe to call concurrently for the same [repoId] and [into].** Both calls stage into the
     * same scratch directory, so the first to finish deletes the other's in-flight work, and a
     * rename by one while the other still holds open file descriptors into it follows the inode
     * into the committed repo — writing into a directory that has already been verified. Serialising
     * calls per repo id is the caller's responsibility; different repo ids are independent.
     */
    suspend fun download(
        repoId: String,
        into: File,
        onProgress: (RepoProgress) -> Unit = {},
    ): Result<File> = withContext(dispatcher) {
        onProgress(RepoProgress.CheckingSpace(repoId))

        val manifest = repo.manifest(repoId).getOrElse { return@withContext Result.failure(it) }

        // An empty manifest is a listing that failed without saying so — a hub answering 200 with
        // [], a revision that does not exist, a filter that matched nothing. Refused here because
        // every downstream check is written as "every file is correct", and every file of no files
        // is trivially correct: the cache check would call any directory that happened to exist a
        // hit and return it, and with nothing there the commit step would publish a repo containing
        // only its own marker. Both are permanent cache hits that no later call can repair.
        if (manifest.files.isEmpty()) {
            return@withContext Result.failure(IOException("no files listed for $repoId"))
        }

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

                onProgress(RepoProgress.Verifying(repoId, remote.path))

                // Checked here and not delegated: ResumableDownloader compares what it wrote
                // against the *server's own* declared length, so a self-consistent wrong response
                // — a captive portal's login page, a hub serving an error document with a correct
                // Content-Length — satisfies it at any size. This is the only place the manifest's
                // figure is ever consulted, and for a file the hub published no sha256 for it is
                // the whole of the verification. Skipped when the hub omits the size, which leaves
                // such a hub exactly where it was rather than failing every file.
                if (remote.sizeBytes > 0 && destination.length() != remote.sizeBytes) {
                    return@withContext Result.failure(
                        VerificationException(
                            remote.path,
                            "expected ${remote.sizeBytes} bytes, got ${destination.length()}",
                        ),
                    )
                }

                // A null sha256 means the hub published none; the size check above is then the only
                // acceptance test, which is weaker and unavoidable.
                if (remote.sha256 != null && !Sha256.matches(destination, remote.sha256)) {
                    return@withContext Result.failure(
                        VerificationException(remote.path, "sha256 mismatch"),
                    )
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
                        "$target was not committed by Ferry under '$repoId'; refusing to replace " +
                            "it — remove the directory to retry",
                    )
                }

                // A marker matching repoId at $target only says Ferry committed *this* directory
                // under this id — it says nothing about what got committed underneath it since.
                // "owner" and "owner/model" are both ordinary ids (see MARKER_FILE's doc): download
                // ("owner/model") writes its own marker at target/model/.ferry without objection,
                // because into/owner/model does not exist yet when it commits. A later
                // download("owner") would otherwise sail past the check above — its own marker
                // still matches — and deleteRecursively() would take the nested repo with it. Any
                // .ferry strictly below target, at any depth, is refused before the delete rather
                // than risk that: usually a real nested repo, but not provably so — a manifest can
                // declare an ordinary file at that same name (docs/known-limitations.md) — so the
                // message below states only what is actually known, not which case this is.
                val nested = target.walkTopDown()
                    .firstOrNull { it.isFile && it.name == MARKER_FILE && it != marker }
                if (nested != null) {
                    throw IOException(
                        "$target contains $nested; refusing to replace it — remove $nested first",
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
