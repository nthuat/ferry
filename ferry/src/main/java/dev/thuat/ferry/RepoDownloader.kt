package dev.thuat.ferry

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

    /**
     * [path] was already staged under its final name, matching the manifest's declared size and
     * hash where one is published — so nothing was requested for it.
     *
     * Fires once, taking the place of the [Downloading]/[Verifying] pair a real fetch would have
     * produced. Without a distinct case for this, a caller watching [fileIndex] advance across
     * [fileCount] files would see some indices simply missing from the [Downloading] stream, with
     * no event to say why — indistinguishable from a caller that just missed them. A staged file
     * whose bytes do *not* match the manifest is not this case: it is fetched like any other miss,
     * and reports [Downloading]/[Verifying] normally.
     */
    data class Skipped(
        val repoId: String,
        val path: String,
        val fileIndex: Int,
        val fileCount: Int,
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
 * Lives at `target/.ferry`, inside the repo's own directory, written into staging so the same
 * `renameTo` that publishes the repo's content publishes the marker with it, and read back from
 * `target` itself before any replace. That co-location is the entire safety property: the marker
 * lives and dies with exactly the directory it describes. A directory removed out of band — the
 * only way to delete a model, per this file's own refusal message — takes its marker with it, so
 * nothing is left to misdescribe whatever gets put at that path afterwards. An earlier version of
 * this fix moved ownership into a side tree keyed by name instead of by directory; nothing ever
 * deleted an entry there, so a directory removed out of band and replaced with foreign content
 * inherited the old commit's ownership and was deleted to make room for a new one — the exact class
 * of bug this file exists to guard against. Reverted for that reason; see
 * docs/known-limitations.md's closed entry on this and its discussion of what still moved.
 *
 * The commit step replaces whatever sits at the target path, and one repo id is free to be a
 * directory prefix of another — "owner" and "owner/model" are both ordinary ids, and both resolve
 * to strict children of the download root, so no containment check on the id alone can tell them
 * apart. Ferry therefore deletes only what it wrote under this exact id: a directory with no
 * marker, or a marker naming a different id, is refused rather than removed. Telling whether
 * something is committed *nested inside* this id is a different question, answered by a separate
 * shadow tree — see [download]'s own nested-check comment for why that one could not stay
 * co-located the same way: a hub's manifest can declare a file at any path in the real tree,
 * including one that happens to be named `.ferry`, and nothing there can tell that file apart from
 * a marker by name alone (docs/known-limitations.md's closed entry).
 */
private const val MARKER_FILE = ".ferry"

/**
 * The root of a shadow tree, sibling to every repo under `into`, that records only *which* repo ids
 * have been committed — never what they contain, and never anything resembling the ownership
 * question [MARKER_FILE] answers.
 *
 * Named the same as [MARKER_FILE] — this directory and that file share nothing but a name; Ferry
 * never conflates them. `into/.ferry/X` exists as a plain, possibly-empty directory once repo id
 * `X` is committed, mirroring `into` one-for-one — deliberately, and safely, unlike `into/.staging`
 * (see [stagingDirFor]'s own doc for the Critical that same mirroring caused there, and why this
 * tree does not share it): nothing here is ever `deleteRecursively()`'d or summed for one repo id at
 * a time, so `X/Y`'s entry sitting inside `X`'s is exactly the relationship the nested-check below
 * is built to read, never a way to reach a different id's own bookkeeping by accident. A nested id
 * like `X/Y` then creates `into/.ferry/X/Y` as `X`'s own child, without needing to tell `X`'s entry
 * apart from `Y`'s the way a leaf-file scheme would — a directory can always gain another child, so
 * no two ids ever compete for the same path here regardless of commit order.
 *
 * A shadow entry is written once and never deleted — there is no API that would tell it to be, any
 * more than there is one to clear an ordinary refusal. That makes every entry here a *candidate*
 * nested id, not proof of one still being real: the real directory it names may since have been
 * removed out of band, or may never have existed if the commit that would have written it crashed
 * first. [download]'s nested-check cross-references each candidate against the real tree before
 * trusting it, which is what lets a stale entry sit here harmlessly forever rather than block
 * anything — see that check's own comment.
 */
private const val MARKER_ROOT = MARKER_FILE

/**
 * Appended to a repo id's own last path segment when resolving where it stages — see
 * [stagingDirFor]'s own doc for what nests without it and why suffixing only the last segment is
 * what stops that.
 */
private const val STAGING_SUFFIX = ".d"

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
     * same scratch directory and write into it independently — interleaved writes to the same
     * destination file are a corruption risk on their own. Whichever commits first renames staging
     * onto `target`; if the other still holds open file descriptors into it, its writes follow the
     * inode into what is now a committed repo. If the second call reaches its own commit afterward,
     * `target`'s marker still names the same repo id, so the guard against replacing a directory
     * this method did not write does not catch this either: the second call deletes the first's
     * freshly committed repo and renames its own version over it. Serialising calls per repo id is
     * the caller's responsibility; different repo ids are independent. See also [abandon]'s own KDoc
     * for the same hazard between `abandon` and `download`.
     */
    suspend fun download(
        repoId: String,
        into: File,
        onProgress: (RepoProgress) -> Unit = {},
    ): Result<File> = withContext(dispatcher) {
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

        try {
            // repoId is used as a relative path rather than flattened into one directory name, so
            // two distinct ids (e.g. "a/b" and "a--b") can never collide onto the same directory.
            //
            // staging is validated against stagingRoot (into/.staging), not against `into` itself:
            // `into` is loose enough to contain `.staging`, `.ferry`, and every already-committed
            // repo, so a repoId like "../<other-repo>" resolves to that other repo's own directory
            // and would still pass a check against `into`. Shrinking the boundary to the one
            // directory a repoId's staging copy is actually allowed to land in closes that.
            //
            // Staging is durable scratch, not a transaction log: a failed attempt leaves it exactly
            // as far as it got, on disk, deliberately, so the next attempt can resume from those
            // bytes instead of re-fetching them. Success consumes it — stagingDir.renameTo(target)
            // below moves it out from under this path entirely, so there is nothing left afterward
            // to clean up. A failure is not cleaned up here; Task 3 adds the explicit abandon() that
            // reclaims a staging directory the caller has given up on.
            //
            // stagingDir is not a bare resolveInside(stagingRoot, repoId): "owner" and "owner/model"
            // are both ordinary repo ids (MARKER_FILE's own doc), and staging has no shadow tree the
            // way target's own nesting question does (MARKER_ROOT) — see stagingDirFor's own doc for
            // the Critical that caused and the reserved per-id suffix that closes it.
            val stagingRoot = File(into, ".staging")
            val stagingDir = stagingDirFor(stagingRoot, repoId)
            val target = resolveInside(into, repoId)

            // Which ids are committed *nested inside* repoId — see MARKER_ROOT's doc for the shape
            // and why it is a separate tree from the ownership marker. markerDir is repoId's own
            // slot: empty until something nests inside it, at which point it gains a child.
            val markerRoot = File(into, MARKER_ROOT)
            val markerDir = resolveInside(markerRoot, repoId)

            // A repoId of ".staging/evil" or ".ferry/evil" never leaves `into` (so resolveInside's
            // own "strictly inside into" check misses both), but a target there collides with a
            // namespace `into` reserves for Ferry's own bookkeeping — staging or markers — which
            // would let one repo's commit clobber another repo's in-flight staging copy, or the
            // marker namespace itself, rather than just being an ordinary sibling directory.
            val targetPath = target.canonicalPath
            if (collidesWith(targetPath, stagingRoot)) {
                throw IOException("repo id collides with the staging area: $repoId")
            }
            if (collidesWith(targetPath, markerRoot)) {
                throw IOException("repo id collides with the marker directory: $repoId")
            }

            // Already here and still correct: the cheapest possible outcome, and the one a naive
            // implementation misses by re-fetching gigabytes the device is already holding.
            //
            // Inside the try: isSatisfiedBy re-hashes existing files, which is I/O and can throw
            // the same way every other read in this method can, and must become Result.failure
            // rather than escape the public boundary.
            //
            // Checked before free space, not after: a repo already present and verified needs no
            // space at all, and must not be refused because the device that already holds it has
            // since filled up. Nothing above this line writes anything, so a hit is returned here
            // having touched the filesystem only to read it.
            if (target.isDirectory && manifest.isSatisfiedBy(target)) {
                onProgress(RepoProgress.Complete(repoId, target))
                return@withContext Result.success(target)
            }

            // Before the first byte: spending a user's data allowance to discover the disk is full
            // is the failure this library exists to prevent. Run from inside the try, same as the
            // cache-hit check above it: a probe is caller-supplied and free to throw, and that
            // failure must become Result.failure like every other I/O in this method, not escape
            // the public boundary.
            //
            // into may not exist yet — a clean install's first-ever download into a fresh directory
            // is the ordinary case, not an edge case — and passed straight through to whichever
            // FreeSpaceProbe the caller configured. Handling that is SpaceCheck's default probe's own
            // job (see DefaultFreeSpaceProbe's doc in SpaceCheck.kt), not this call site's: fixing it
            // here would only protect callers who go through RepoDownloader, and SpaceCheck is public,
            // usable directly for a preflight check without ever calling download() at all.
            //
            // manifest.creditingStaged(stagingDir, satisfiedPaths), not manifest itself: a download
            // staged 90% already needs only the remaining 10%, and reserving the full total tells a
            // device with room to finish it has no room to start — worse the more progress a resume
            // has made, which is exactly backwards. See creditingStaged's own doc for what is and is
            // not credited. SpaceCheck itself is unchanged; only the manifest it is asked about
            // differs.
            //
            // satisfiedPaths is computed once, here, rather than left for isSatisfiedIn to answer
            // twice: a bare staged file's hash is the expensive half of that check, and the loop
            // below used to ask the identical question again per file to decide whether to skip it
            // — doubling every staged byte's SHA-256 for no new information, all of it before a
            // single network request. Computed against the manifest as given, not the space-credited
            // copy below, since sizeBytes is exactly what changes between the two.
            onProgress(RepoProgress.CheckingSpace(repoId))
            val satisfiedPaths = manifest.files
                .filter { it.isSatisfiedIn(stagingDir) }
                .mapTo(HashSet()) { it.path }
            val report = spaceCheck.check(manifest.creditingStaged(stagingDir, satisfiedPaths), into)
            if (!report.sufficient) {
                return@withContext Result.failure(InsufficientSpaceException(report))
            }

            stagingDir.mkdirs()

            // Durable staging (kept since Task 1) can carry scratch from a manifest this attempt no
            // longer agrees with. Pruned before the loop below touches anything, so a stale file
            // never has a chance to be mistaken for progress worth resuming.
            pruneOrphans(stagingDir, manifest)

            manifest.files.forEachIndexed { index, remote ->
                // remote.path comes from the hub's manifest over the network — untrusted the same
                // way repoId is. Without this, a hostile listing could write anywhere on disk.
                val destination = resolveInside(stagingDir, remote.path)

                // satisfiedPaths, computed once above — not a fresh remote.isSatisfiedIn(stagingDir)
                // call here. The verdict is the same per-file predicate isSatisfiedBy (whole-repo
                // cache hit) and remainingBytes (space credit) already apply, reused rather than
                // asked a second time: isSatisfiedIn re-hashes a bare staged file to reach this
                // answer, and asking it again per file here — after the space check had already
                // asked it for every file — hashed every staged byte twice for no new information.
                // A bare destination.exists() would count a staged file as done because a file
                // happens to sit at that path, committing whatever bytes are actually there unread —
                // the drift between "counted as progress" and "skipped" that this file's own history
                // (docs/known-limitations.md) warns is how a repo ends up committed and then failing
                // its own cache check forever; satisfiedPaths only ever contains a path isSatisfiedIn
                // actually verified, so a staged file that merely exists but does not match still
                // falls through to an ordinary fetch below.
                if (remote.path in satisfiedPaths) {
                    onProgress(RepoProgress.Skipped(repoId, remote.path, index, manifest.files.size))
                    return@forEachIndexed
                }

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
                // the whole of the verification.
                //
                // Unconditional, not guarded on `remote.sizeBytes > 0` as this once was: that guard
                // treated a declared 0 as "unknown, skip the check", but isSatisfiedBy below has never
                // had a matching guard — it compares onDisk.length() == remote.sizeBytes unconditionally
                // — so the two disagreed. A hub declaring an explicit 0 could pass a non-empty body
                // here and then fail isSatisfiedBy on every later call forever: committed once, never a
                // cache hit again (docs/known-limitations.md's closed entry on this). Dropping the guard
                // here instead treats a declared 0 the same way isSatisfiedBy always has: a real
                // assertion that the file is empty, checked, not an "unknown size" sentinel skipped.
                // Neither HuggingFace nor ModelScope was ever observed omitting a real file's size this
                // way — both always publish an explicit figure for a "file"/"blob" entry — so this
                // costs neither adapter anything today; it only stops trusting a hub that starts
                // publishing a real, checkable zero.
                if (destination.length() != remote.sizeBytes) {
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
            // forever, or — the sharper failure — leave a marker whose directory is gone, standing
            // ready to misdescribe whatever gets put at that path next. Written after the download
            // loop, so a manifest entry literally named ".ferry" *at the repo root* cannot forge
            // ownership of a directory — Ferry's own write always lands last, overwriting it. A
            // manifest entry named ".ferry" *in a subdirectory* is not shadowed by this at all and
            // downloads as ordinary content: this write only ever touches stagingDir's own root.
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
                // "owner" and "owner/model" are both ordinary ids (see MARKER_FILE's doc): committing
                // "owner/model" writes its own marker at target/model/.ferry without objection,
                // because into/owner/model does not exist yet when it commits. A later
                // download("owner") would otherwise sail past the check above — its own marker
                // still matches — and deleteRecursively() would take the nested repo with it.
                //
                // Answered by the shadow tree (MARKER_ROOT's doc), not by walking target's own
                // subtree the way this used to: a hub's manifest can put a file named ".ferry" at
                // any depth in the real tree, and nothing in that tree can tell it apart from a real
                // nested marker by name alone (docs/known-limitations.md's closed entry) — the walk
                // this replaces was exactly that guess. markerDir's children are never that
                // ambiguous, because only Ferry ever writes there, but they are not proof either: a
                // shadow entry is never deleted, so it can name an id whose real directory was since
                // removed out of band, or one whose commit crashed before ever creating it. Cross-
                // referencing each candidate against `File(target, candidate.name).exists()` is what
                // tells a genuinely-nested repo apart from a stale entry — real content wins the
                // refusal, a stale entry loses it and blocks nothing, which is also what makes
                // removing the nested repo the refusal names actually clear that refusal afterwards.
                val nestedChild = markerDir.listFiles()?.firstOrNull { File(target, it.name).exists() }
                if (nestedChild != null) {
                    throw IOException(
                        "$target contains a repo committed under '$repoId/${nestedChild.name}'; " +
                            "refusing to replace it — remove that nested repo first",
                    )
                }

                if (!target.deleteRecursively()) {
                    return@withContext Result.failure(IOException("cannot replace $target"))
                }
            }

            // Records repoId itself as a candidate nested id for whichever ancestor id, if any, is
            // ever checked against it later — see MARKER_ROOT's doc. Written before the rename, not
            // after: a crash in between leaves this entry naming a directory that turns out not to
            // exist yet, which the nested check above already treats as a stale, harmless candidate.
            // The other order would leave the opposite: real, committed content with no shadow entry
            // at all, invisible to an ancestor's nested check, which would then delete it for real —
            // the one direction that actually loses something that was there. Only one mkdirs() call,
            // not a write too: nothing is ever read back out of this tree's own content, only its
            // shape, so recording repoId here needs nothing more than the directory existing.
            markerDir.mkdirs()
            if (!markerDir.isDirectory) {
                return@withContext Result.failure(IOException("cannot record $repoId as committed"))
            }

            target.parentFile?.mkdirs()
            if (!stagingDir.renameTo(target)) {
                return@withContext Result.failure(IOException("cannot commit $target"))
            }

            onProgress(RepoProgress.Complete(repoId, target))
            Result.success(target)
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    /**
     * Reclaims the staging bytes of a [download] for [repoId] under [into] that the caller has given
     * up on — a failed attempt that will not be retried, or one abandoned mid-flight. Task 1 stopped
     * deleting staging on failure so a retry can resume from it; this is what reclaims the bytes when
     * no retry is coming.
     *
     * **Not safe to call concurrently with [download] for the same [repoId] and [into].** `download`
     * recreates whatever directories it needs as it goes (`destination.parentFile?.mkdirs()`) and
     * verifies only the one file it is currently fetching — never a file a previous iteration of the
     * same attempt already verified and moved past. An `abandon` landing mid-loop deletes exactly
     * those already-verified files out from under it; the loop has no way to notice and does not
     * re-fetch them, so every later file still verifies fine on its own, the commit at the end of
     * `download` still finds everything *it* checked present and correct, and `stagingDir.renameTo`
     * still succeeds. The result is `Result.success`, publishing a repo silently missing every file
     * downloaded before the `abandon` landed — a committed partial model, not a failure either call
     * could detect or report. Serialising `abandon` against `download` for the same repo id is the
     * caller's responsibility, the same way two concurrent `download` calls are (see `download`'s own
     * KDoc and docs/known-limitations.md's concurrency entry) — nothing here enforces it.
     *
     * Deletes only [repoId]'s own staging directory under `into/.staging`, resolved through the same
     * [stagingDirFor] helper [download] uses to compute its own `stagingDir` — shared rather than
     * re-argued, so a hostile or malformed [repoId] cannot escape the staging area any more than it
     * can escape `download`'s, and so this call can never reach a *different* repo id's own staging
     * the way a bare `resolveInside(stagingRoot, repoId)` once let `deleteRecursively()` below do —
     * see [stagingDirFor]'s own doc. Touches nothing else: never [into] itself, never the staging
     * root `into/.staging` itself, and never a committed target.
     *
     * In particular — the property most worth stating plainly — **this method never resolves
     * against, looks at, or touches `into/[repoId]`.** Abandoning an in-progress download says
     * nothing about a previously committed copy of the same repo id, which may be complete, verified,
     * and in use by the host right now. A method named `abandon` that deleted that would be the worst
     * API in this library.
     *
     * No staging present for [repoId] is success, not failure: the caller asked for a state — this
     * repo's staging reclaimed — and that state already holds.
     */
    suspend fun abandon(repoId: String, into: File): Result<Unit> = withContext(dispatcher) {
        try {
            val stagingRoot = File(into, ".staging")
            val stagingDir = stagingDirFor(stagingRoot, repoId)
            if (stagingDir.exists() && !stagingDir.deleteRecursively()) {
                return@withContext Result.failure(IOException("cannot delete staging for $repoId"))
            }
            Result.success(Unit)
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    /**
     * Bytes already staged for [repoId] under [into] that a resumed [download] could reuse instead of
     * re-fetching — the number behind a "Resume, N already downloaded" row.
     *
     * **Cheap, on purpose.** This may be called once per row to render a whole list, so unlike
     * [download]'s own credit check ([remainingBytes], via [isSatisfiedIn]) it never hashes a byte and
     * never touches the network — it only sums file lengths already on disk. That is exactly why the
     * two can disagree: [remainingBytes] re-hashes a bare staged file before trusting it, because an
     * under-reserved space check risks filling the disk; this one cannot afford to and settles for an
     * estimate. Treat the result as what it is — a hint for what to show, not a promise of what
     * [download] will actually transfer next. The hub is free to invalidate a `.part`'s validator the
     * moment the next attempt asks, and a `.part` with no validator restarts from zero regardless of
     * what this number said.
     *
     * "Cheap" is an argument against hashing, not against blocking a caller's thread: an unbounded
     * [File.walkTopDown] over however many files a repo has staged is still blocking file I/O, and
     * this was the one non-suspend public method on this class, running that walk directly on
     * whatever thread called it. It now `suspend`s and runs on [dispatcher], the same one [download]
     * and [abandon] already use, rather than asking every caller to know to move it off their own
     * thread by hand — which the sample app's own `SampleViewModel` was doing with a manual
     * `withContext(Dispatchers.IO)` around this exact call, direct evidence the shape belonged here
     * instead.
     *
     * **Total, not `Result`-returning.** An absent, unreadable, or otherwise unusable staging
     * directory is zero bytes of progress, not an error: there is nothing for a caller to react to
     * beyond "no progress to resume", so a `Result` would only make every call site unwrap a failure
     * that never carries more than 0 already does. A small result type was considered in place of a
     * plain [Long] and dropped for the same reason: there is no second fact to carry alongside the
     * count — no partial flag, no error — so a wrapper would be ceremony around one number.
     *
     * **What counts as reusable, without hashing:**
     * - A `<file>.part` with a sibling `<file>.validator` counts at the `.part`'s own length — the
     *   bytes [ResumableDownloader] resumes from when a validator is present.
     * - A `<file>.part` with **no** validator counts as zero: [ResumableDownloader] refuses to resume
     *   blind (its own KDoc) and restarts that file from byte zero, so those bytes are not reusable.
     * - A bare file staged under its final name — the shape left once the *server's* declared length
     *   is satisfied, before anything is compared to the manifest — counts at its full length. It is
     *   the strongest kind of progress a resume can find (a real hit skips the file entirely), but is
     *   *not* re-verified here, so a stale or corrupt one still counts; [download] itself would still
     *   catch and re-fetch it, just not for free the way this number implies.
     * - `.validator` files contribute nothing on their own; they are metadata about a `.part`, not
     *   payload, and the marker [download] writes into staging just before committing
     *   (`target/[MARKER_FILE]`, still sitting in staging in the narrow window before the rename)
     *   contributes nothing either, for the same reason.
     */
    suspend fun stagedBytes(repoId: String, into: File): Long = withContext(dispatcher) {
        try {
            val stagingDir = stagingDirFor(File(into, ".staging"), repoId)
            val marker = File(stagingDir, MARKER_FILE)
            if (!stagingDir.isDirectory) {
                0L
            } else {
                stagingDir.walkTopDown()
                    .filter { it.isFile && it != marker }
                    .sumOf { staged ->
                        when {
                            staged.name.endsWith(".validator") -> 0L
                            staged.name.endsWith(".part") -> {
                                val validator =
                                    File(staged.parentFile, "${staged.name.removeSuffix(".part")}.validator")
                                if (validator.isFile) staged.length() else 0L
                            }
                            else -> staged.length()
                        }
                    }
            }
        } catch (e: IOException) {
            0L
        }
    }

    /**
     * Whether [dir] already holds every file of this manifest, at the right size, with the right
     * hash where one was published.
     *
     * Deliberately re-hashes rather than trusting a marker file: a marker records what was true
     * once, and the point of the check is what is true now.
     */
    private fun RepoManifest.isSatisfiedBy(dir: File): Boolean = files.all { it.isSatisfiedIn(dir) }

    /**
     * Whether [dir] already holds this one file at the right size, with the right hash where one was
     * published.
     *
     * `onDisk.length() == sizeBytes` is unconditional — including when `sizeBytes` is 0 — and always
     * has been. The post-download check in `download()` above now matches it exactly, for the same
     * reason: the two used to disagree (that check skipped entirely on a declared 0), which let a hub
     * declaring an explicit zero pass a non-empty body on download and then fail this check on every
     * later call forever. A declared 0 is treated as a real, checked assertion that the file is empty
     * in both places now, not "unknown, don't check" in one and enforced in the other.
     *
     * One predicate behind two questions now — down from three: [isSatisfiedBy] applies it to every
     * file to decide a whole-repo cache hit against the *committed* directory, and [download] itself
     * applies it once per staged file, before the transfer loop, to build `satisfiedPaths` — the set
     * both [remainingBytes] (space credit) and the loop's own skip check consult afterward, rather
     * than each calling this a second time. It used to be three call sites invoking this directly:
     * the loop asked the identical question again per file, after the space check had already asked
     * it for every file, hashing every staged byte twice for no new information. All call sites still
     * need the same answer to "is this file actually done", so the predicate stays one function —
     * only the number of times it actually runs against a given file changed.
     */
    private fun RemoteFile.isSatisfiedIn(dir: File): Boolean {
        val onDisk = resolveInside(dir, path)
        return onDisk.isFile &&
            onDisk.length() == sizeBytes &&
            (sha256 == null || Sha256.matches(onDisk, sha256))
    }

    /**
     * [manifest] as the space check should see it: each file's size reduced by whatever of it is
     * already staged and safe to reuse, so a mostly-resumed download does not reserve bytes that are
     * already on disk.
     *
     * [satisfiedPaths] is [download]'s own precomputed verdict — every path in [manifest] for which
     * [isSatisfiedIn] answered true against [stagingDir] — passed in rather than recomputed here, so
     * crediting a bare, correct staged file does not hash it a second time (the loop's own skip check
     * is the first).
     *
     * Scoped to this one call site — [SpaceCheck.check] is the only consumer. Everywhere else in
     * [download] (the transfer loop, per-file verification, [pruneOrphans]) uses the real [manifest],
     * because those need the actual declared size, not this reduced stand-in.
     */
    private fun RepoManifest.creditingStaged(stagingDir: File, satisfiedPaths: Set<String>): RepoManifest =
        copy(files = files.map { it.copy(sizeBytes = it.remainingBytes(stagingDir, satisfiedPaths)) })

    /**
     * Bytes of this file still needed from the network, after crediting whatever [stagingDir] already
     * holds for it.
     *
     * Staging can hold this file in three shapes (see the plan's "What staging actually contains").
     * Only two are credited:
     *
     * - **A bare file under its final name** is credited in full, but only when [satisfiedPaths]
     *   (this [path] having already answered true to [isSatisfiedIn]) agrees — reused rather than
     *   trusting presence alone. `ResumableDownloader` renames `.part` onto the final name as soon as
     *   the *server's* declared length is met, before anything is compared to the manifest, so a
     *   complete-looking file can still be one the manifest rejects. Crediting it anyway would
     *   under-reserve for bytes about to be re-downloaded in full — the unsafe direction of error —
     *   so an unsatisfied bare file counts as no progress, not partial progress.
     * - **A `.part` with a `.validator`** is credited for the bytes already on disk:
     *   `ResumableDownloader` resumes from exactly `haveBytes` when a validator is present, so that
     *   many bytes are genuinely not fetched again.
     * - **A `.part` alone** is not credited. No validator means `ResumableDownloader` refuses to
     *   resume blind and restarts from zero, so those bytes are not reusable either.
     *
     * Clamped at zero rather than allowed to go negative: a `.part` larger than this manifest's
     * current declared size — stale scratch from a revision that has since shrunk the file; pruning
     * only discards a path the manifest no longer names at all, not a still-named path whose
     * declaration changed — must not manufacture a negative requirement that silently subsidises some
     * other file's.
     */
    private fun RemoteFile.remainingBytes(stagingDir: File, satisfiedPaths: Set<String>): Long {
        if (path in satisfiedPaths) return 0L

        val staged = resolveInside(stagingDir, path)
        val part = File(staged.parentFile, "${staged.name}.part")
        val validator = File(staged.parentFile, "${staged.name}.validator")
        val resumableBytes = if (part.isFile && validator.isFile) part.length() else 0L
        return maxOf(0L, sizeBytes - resumableBytes)
    }

    /**
     * Deletes every staged file under [stagingDir] that [manifest] no longer vouches for.
     *
     * Durable staging (kept since Task 1) means a file here can outlive the manifest that produced
     * it — the hub removed it, renamed it, or the caller is retrying against a different revision —
     * and three different things can be sitting at a stale path, not only one: `<path>.part`
     * (interrupted mid-transfer), `<path>.validator` (the ETag that makes resuming that `.part`
     * safe), and `<path>` itself, bare, under its final name. That third shape is not an edge case:
     * `ResumableDownloader` renames a `.part` onto the final name as soon as the *server's own*
     * declared length is satisfied, before this method has compared anything to the manifest, so a
     * short body with a self-consistent length produces exactly it. All three are orphans on equal
     * footing once their path drops out of the manifest — none will ever be completed or committed,
     * and left alone a long-lived repo would accrete them forever.
     *
     * Walks staging rather than the manifest, because an orphan is exactly the file the manifest
     * cannot name. Every path the walk finds is re-resolved through [resolveInside] rather than
     * deleted directly — staging content is not attacker-controlled today, but a walk over a
     * directory is exactly where a symlink would matter, and resolving through the same helper every
     * other path in this file trusts means this one does not need its own argument for why what it
     * found is safe to delete.
     *
     * A path still named in the manifest survives here untouched, even if the bytes staged under it
     * are no longer the size or hash the manifest currently declares. That is deliberate, not an
     * oversight: recording what a file's size or hash was declared as on a previous attempt, so this
     * could be caught before transferring anything, would need its own bookkeeping that outlives the
     * scratch it describes, and would need to be invalidated correctly across manifest changes,
     * retried revisions, and interrupted writes — a second staleness problem in service of avoiding
     * the first. It is not needed for correctness: [download]'s existing per-file verification
     * already resolves this case on its own, because a stale `.part` completes against a length or
     * hash the manifest no longer agrees with, fails verification, and is never committed. The only
     * cost of leaving it alone is a wasted transfer of bytes that turn out to be unusable — correct,
     * not free, and judged not worth a second piece of state to avoid.
     */
    private fun pruneOrphans(stagingDir: File, manifest: RepoManifest) {
        if (!stagingDir.isDirectory) return
        val declaredPaths = manifest.files.mapTo(HashSet()) { it.path }
        stagingDir.walkTopDown()
            .filter { it.isFile }
            .forEach { staged ->
                val relativePath = staged.relativeTo(stagingDir).invariantSeparatorsPath
                val vouchedFor = relativePath in declaredPaths ||
                    relativePath.removeSuffix(".part") in declaredPaths ||
                    relativePath.removeSuffix(".validator") in declaredPaths
                if (!vouchedFor) {
                    resolveInside(stagingDir, relativePath).delete()
                }
            }
    }

    /**
     * Where [repoId] stages under [stagingRoot] (`into/.staging`).
     *
     * Naively this would be `resolveInside(stagingRoot, repoId)` — mirroring [repoId]'s own
     * "/"-separated structure exactly the way `target` still does. But "owner" and "owner/model" are
     * both ordinary repo ids (see [MARKER_FILE]'s doc), and unlike `target`, staging has no shadow
     * tree ([MARKER_ROOT]) to tell a genuinely nested commit apart from reaching into a *different*
     * repo's own in-flight scratch: `resolveInside(stagingRoot, "owner")` was a literal ancestor
     * directory of `resolveInside(stagingRoot, "owner/model")`, so [pruneOrphans] walking "owner"'s
     * staging walked straight into "owner/model"'s live `.part` files and deleted them as orphans of
     * a manifest that was never theirs — [abandon] and [stagedBytes] reached the same way, via
     * `deleteRecursively()` and a recursive sum respectively. The emptied nested directory then rode
     * `stagingDir.renameTo(target)` straight into the committed repo, because [pruneOrphans] only
     * ever deletes files (tracked separately; see its own doc).
     *
     * Fixed by appending [STAGING_SUFFIX] to [repoId]'s own *last* path segment, rather than treating
     * the joined string as one more ordinary path component. "owner" alone resolves to `owner.d` — a
     * single path segment, different from plain `owner`. Any id "owner" is itself a prefix of —
     * "owner/model", the shape this bug is about — continues from plain, *unsuffixed* `owner`,
     * because only a repo id's own last segment is ever touched: `into/.staging/owner` therefore
     * names no repo's staging at all, only `into/.staging/owner.d` and `into/.staging/owner/model.d`
     * do, and neither is an ancestor of the other. Two *unrelated* ids can still collide here only if
     * one id's own path segment is, character for character, another id's last segment with
     * [STAGING_SUFFIX] already appended — an id that itself contains the literal text "owner.d" as
     * one of its own segments. That requires deliberately constructing a path to collide with this
     * library's own reserved suffix; it is the same shape of residual docs/known-limitations.md
     * already accepts for a `..` that reconstructs a legitimate-looking path, and no more reachable —
     * stated here rather than left silent, not treated as a gap worth closing beyond that.
     */
    private fun stagingDirFor(stagingRoot: File, repoId: String): File {
        // Validates repoId exactly as an unsuffixed resolve always did — rejects "", "..", and any
        // escape — before this function's own suffixing gets a chance to be more permissive:
        // resolving "" + STAGING_SUFFIX lands on a proper descendant of stagingRoot, not stagingRoot
        // itself, so the "strictly inside" guard alone would not catch an empty repoId below.
        resolveInside(stagingRoot, repoId)
        // trimEnd('/'): a trailing separator must not turn the suffix into a new segment of its own
        // ("owner/" -> "owner/.d", three segments) instead of extending the last real one
        // ("owner.d", two) — repoId with or without a trailing slash names the same target directory
        // (File(into, "owner/").canonicalPath == File(into, "owner").canonicalPath), and must name
        // the same staging directory too.
        return resolveInside(stagingRoot, "${repoId.trimEnd('/')}$STAGING_SUFFIX")
    }

    /**
     * Whether canonical path [targetPath] is, or falls strictly inside, [reservedRoot].
     *
     * Separate from [resolveInside]: that function only rejects a path that escapes its parent, and
     * both `.staging` and `.ferry` are ordinary strict children of `into`, not escapes — this is the
     * narrower check that a repoId did not resolve onto one of the handful of names `into` itself
     * reserves for Ferry's own bookkeeping.
     */
    private fun collidesWith(targetPath: String, reservedRoot: File): Boolean {
        val reservedPath = reservedRoot.canonicalPath
        return targetPath == reservedPath || targetPath.startsWith(reservedPath + File.separator)
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
     * this method's callers wants the parent: a repo never stages as the whole staging area, a
     * target is never the download root, a repo's own shadow directory never sits directly at the
     * marker root, and a file is never the directory containing it.
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
