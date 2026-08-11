package dev.thuat.ferry

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.IOException
import okio.Path

/** java.io.File.length() semantics: 0 for a missing path — call sites compare against it. */
private fun FileSystem.sizeOf(path: Path): Long = metadataOrNull(path)?.size ?: 0L

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

    data class Complete(val repoId: String, val dir: Path) : RepoProgress
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
    private val repo: ModelHub,
    private val downloader: ResumableDownloader,
    private val spaceCheck: SpaceCheck = SpaceCheck(),
    private val fileSystem: FileSystem = defaultFileSystem(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Downloads the whole of [repoId] — every manifest file — into a directory under [into].
     *
     * Exists as a distinct overload, not a default on the filtered form, **solely for binary
     * compatibility**: the published dev.thuat:ferry-work:0.2.0 was compiled against this exact
     * JVM descriptor (`download(String, Path, Function1, Continuation)`) and its synthetic
     * default-argument bridge. Folding it into the 4-parameter function as `fileFilter: Regex? =
     * null` keeps every caller *compiling* but breaks every already-published caller at runtime
     * with NoSuchMethodError. See BinaryCompatTest in jvmTest, which pins this descriptor.
     */
    suspend fun download(
        repoId: String,
        into: Path,
        onProgress: (RepoProgress) -> Unit = {},
    ): Result<Path> = download(repoId, into, fileFilter = null, onProgress = onProgress)

    /**
     * Downloads [repoId] into a directory under [into] and returns it.
     *
     * **Not safe to call concurrently for the same [repoId] and [into].** Two such calls sharing the
     * same [fileFilter] stage into the same scratch directory and write into it independently —
     * interleaved writes to the same destination file are a corruption risk on their own. Whichever
     * commits first renames staging onto `target`; if the other still holds open file descriptors
     * into it, its writes follow the inode into what is now a committed repo. If the second call
     * reaches its own commit afterward, `target`'s marker still names the same repo id, so the guard
     * against replacing a directory this method did not write does not catch this either: the second
     * call deletes the first's freshly committed repo and renames its own version over it. A
     * *different* [fileFilter] stages into its own sibling scratch directory instead of the same one
     * — but both calls still race each other at the shared `target`, where the loser is refused by
     * the commit-time filter-identity check (its marker no longer matches), not deleted. Serialising
     * calls per repo id is the caller's responsibility; different repo ids are independent. See also
     * [abandonStaging]'s own KDoc for the same hazard between `abandonStaging` and `download`.
     *
     * [fileFilter] selects the subset of the manifest to download: a file is selected when
     * `fileFilter.containsMatchIn(remoteFile.path)` — substring semantics against the
     * manifest-declared path, so `Regex("Q4_K_M")` is enough for the common case; a pattern
     * wanting a whole-path match anchors itself (`^...$`). `null` means every file, on exactly
     * the code path the 3-argument overload has always taken. `fileFilter` has no default —
     * that absence is what makes overload resolution against the 3-argument form unambiguous
     * for every existing call shape. A filter matching nothing fails rather than committing an
     * empty repo. The filter's identity (pattern and options together) keys both the staging
     * directory and the committed directory's marker — see stagingDirFor and markerContent.
     */
    suspend fun download(
        repoId: String,
        into: Path,
        fileFilter: Regex?,
        onProgress: (RepoProgress) -> Unit = {},
    ): Result<Path> = withContext(dispatcher) {
        try {
            val key = filterKey(fileFilter)

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
            // bytes instead of re-fetching them. Success consumes it — fileSystem.atomicMove(stagingDir,
            // target) below moves it out from under this path entirely, so there is nothing left
            // afterward to clean up. A failure is not cleaned up here; Task 3 adds the explicit
            // abandonStaging() that reclaims a staging directory the caller has given up on.
            //
            // stagingDir is not a bare resolveInside(stagingRoot, repoId): "owner" and "owner/model"
            // are both ordinary repo ids (MARKER_FILE's own doc), and staging has no shadow tree the
            // way target's own nesting question does (MARKER_ROOT) — see stagingDirFor's own doc for
            // the Critical that caused and the reserved per-id suffix that closes it.
            val stagingRoot = into / ".staging"
            val stagingDir = stagingDirFor(stagingRoot, repoId, key)
            val target = resolveInside(into, repoId)

            // Which ids are committed *nested inside* repoId — see MARKER_ROOT's doc for the shape
            // and why it is a separate tree from the ownership marker. markerDir is repoId's own
            // slot: empty until something nests inside it, at which point it gains a child.
            val markerRoot = into / MARKER_ROOT
            val markerDir = resolveInside(markerRoot, repoId)

            // A repoId of ".staging/evil" or ".ferry/evil" never leaves `into` (so resolveInside's
            // own "strictly inside into" check misses both), but a target there collides with a
            // namespace `into` reserves for Ferry's own bookkeeping — staging or markers — which
            // would let one repo's commit clobber another repo's in-flight staging copy, or the
            // marker namespace itself, rather than just being an ordinary sibling directory.
            if (collidesWith(target, stagingRoot)) {
                throw IOException("repo id collides with the staging area: $repoId")
            }
            if (collidesWith(target, markerRoot)) {
                throw IOException("repo id collides with the marker directory: $repoId")
            }

            // Filter-identity gate, before the manifest fetch: it needs only repoId, into and the
            // filter's identity, so a mismatch knowable from one marker read is refused before any
            // network request and before the cache-hit check below can call a directory committed
            // under a broader selection a hit for a narrower one. Fires only on a marker that is
            // recognisably this repo id with a different filter identity: an absent marker or a
            // foreign id falls straight through to today's behavior — the cache-hit check may still
            // hit, and the commit-time gate below still produces today's foreign-directory refusal.
            // The prefix test chooses the error *message*, never the verdict: acceptance anywhere
            // in this file is whole-string equality against markerContent, which a pathological
            // repo id cannot forge.
            val marker = target / MARKER_FILE
            if (fileSystem.metadataOrNull(marker)?.isRegularFile == true) {
                val content = fileSystem.read(marker) { readUtf8() }
                if (content != markerContent(repoId, key) &&
                    (content == repoId || content.startsWith("$repoId\n"))
                ) {
                    throw IOException(
                        "$target was committed by Ferry under '$repoId' with a different file " +
                            "filter; refusing to replace it — remove the directory to retry",
                    )
                }
            }

            // Inside the try, not before it: `repo` is a third-party ModelHub (its own KDoc), and
            // nothing stops an implementation from throwing instead of returning Result.failure — a
            // call site before this try let that throw escape download()'s own Result<Path> contract
            // entirely. A throw here is now caught below like any other failure in this method.
            // asDownloadFailure also normalises the well-behaved-looking case: a hub can return
            // Result.failure(anything), an untyped Throwable that need not be an IOException, and
            // every other failure this method hands back already is one.
            val manifest = repo.manifest(repoId).getOrElse { failure ->
                return@withContext Result.failure(failure.asDownloadFailure())
            }

            // An empty manifest is a listing that failed without saying so — a hub answering 200 with
            // [], a revision that does not exist. Refused here because
            // every downstream check is written as "every file is correct", and every file of no files
            // is trivially correct: the cache check would call any directory that happened to exist a
            // hit and return it, and with nothing there the commit step would publish a repo containing
            // only its own marker. Both are permanent cache hits that no later call can repair.
            if (manifest.files.isEmpty()) {
                return@withContext Result.failure(IOException("no files listed for $repoId"))
            }

            // Selection happens once, here, and every downstream decision — cache hit, space check,
            // satisfiedPaths, the transfer loop, pruneOrphans, the pre-commit re-verification — uses
            // `selected` in place of `manifest`. The manifest itself is only the hub's full listing.
            val selected = manifest.copy(
                files = manifest.files.filter {
                    fileFilter == null || fileFilter.containsMatchIn(it.path)
                },
            )

            // Same shape and reason as the empty-manifest guard above: every downstream check is
            // "every file is correct", and zero files is trivially correct — exactly what must not
            // commit. The guard above catches an empty upstream listing; this one catches a filter
            // that matched nothing, which selected.files can be even when manifest.files is not.
            if (selected.files.isEmpty()) {
                return@withContext Result.failure(IOException("no files matched the filter for $repoId"))
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
            if (fileSystem.metadataOrNull(target)?.isDirectory == true && selected.isSatisfiedBy(target)) {
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
            // selected.creditingStaged(stagingDir, satisfiedPaths), not selected itself: a download
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
            val satisfiedPaths = selected.files
                .filter { it.isSatisfiedIn(stagingDir) }
                .mapTo(HashSet()) { it.path }
            val report = spaceCheck.check(selected.creditingStaged(stagingDir, satisfiedPaths), into)
            if (!report.sufficient) {
                return@withContext Result.failure(InsufficientSpaceException(report))
            }

            fileSystem.createDirectories(stagingDir)

            // Durable staging (kept since Task 1) can carry scratch from a manifest this attempt no
            // longer agrees with. Pruned before the loop below touches anything, so a stale file
            // never has a chance to be mistaken for progress worth resuming.
            pruneOrphans(stagingDir, selected)

            selected.files.forEachIndexed { index, remote ->
                // remote.path comes from the hub's manifest over the network — untrusted the same
                // way repoId is. Without this, a hostile listing could write anywhere on disk.
                val destination = resolveInside(stagingDir, remote.path)

                // satisfiedPaths, computed once above — not a fresh remote.isSatisfiedIn(stagingDir)
                // call here. The verdict is the same per-file predicate isSatisfiedBy (whole-repo
                // cache hit) and remainingBytes (space credit) already apply, reused rather than
                // asked a second time: isSatisfiedIn re-hashes a bare staged file to reach this
                // answer, and asking it again per file here — after the space check had already
                // asked it for every file — hashed every staged byte twice for no new information.
                // A bare fileSystem.exists(destination) would count a staged file as done because a
                // file happens to sit at that path, committing whatever bytes are actually there
                // unread — the drift between "counted as progress" and "skipped" that this file's own
                // history (docs/known-limitations.md) warns is how a repo ends up committed and then
                // failing its own cache check forever; satisfiedPaths only ever contains a path
                // isSatisfiedIn actually verified, so a staged file that merely exists but does not
                // match still falls through to an ordinary fetch below.
                if (remote.path in satisfiedPaths) {
                    onProgress(RepoProgress.Skipped(repoId, remote.path, index, selected.files.size))
                    return@forEachIndexed
                }

                destination.parent?.let { fileSystem.createDirectories(it) }

                downloader.download(
                    url = remote.url,
                    target = destination,
                ) { written, _ ->
                    onProgress(
                        RepoProgress.Downloading(
                            repoId = repoId,
                            path = remote.path,
                            fileIndex = index,
                            fileCount = selected.files.size,
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
                // had a matching guard — it compares fileSystem.sizeOf(onDisk) == remote.sizeBytes
                // unconditionally — so the two disagreed. A hub declaring an explicit 0 could pass a
                // non-empty body here and then fail isSatisfiedBy on every later call forever:
                // committed once, never a cache hit again (docs/known-limitations.md's closed entry on
                // this). Dropping the guard here instead treats a declared 0 the same way isSatisfiedBy
                // always has: a real assertion that the file is empty, checked, not an "unknown size"
                // sentinel skipped. Neither HuggingFace nor ModelScope was ever observed omitting a
                // real file's size this way — both always publish an explicit figure for a
                // "file"/"blob" entry — so this costs neither adapter anything today; it only stops
                // trusting a hub that starts publishing a real, checkable zero.
                if (fileSystem.sizeOf(destination) != remote.sizeBytes) {
                    return@withContext Result.failure(
                        VerificationException(
                            remote.path,
                            "expected ${remote.sizeBytes} bytes, got ${fileSystem.sizeOf(destination)}",
                        ),
                    )
                }

                // A null sha256 means the hub published none; the size check above is then the only
                // acceptance test, which is weaker and unavoidable.
                if (remote.sha256 != null && !Sha256.matches(fileSystem, destination, remote.sha256)) {
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
            fileSystem.write(stagingDir / MARKER_FILE) { writeUtf8(markerContent(repoId, key)) }

            if (fileSystem.exists(target)) {
                // An absent marker is a refusal, not an exception: something Ferry did not write is
                // sitting here, and that is precisely what must not be deleted to make room.
                val markerIsFile = fileSystem.metadataOrNull(marker)?.isRegularFile == true
                if (!markerIsFile || fileSystem.read(marker) { readUtf8() } != markerContent(repoId, key)) {
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
                // referencing each candidate against `fileSystem.exists(target / candidate.name)` is
                // what tells a genuinely-nested repo apart from a stale entry — real content wins the
                // refusal, a stale entry loses it and blocks nothing, which is also what makes
                // removing the nested repo the refusal names actually clear that refusal afterwards.
                val nestedChild = (fileSystem.listOrNull(markerDir) ?: emptyList())
                    .firstOrNull { fileSystem.exists(target / it.name) }
                if (nestedChild != null) {
                    throw IOException(
                        "$target contains a repo committed under '$repoId/${nestedChild.name}'; " +
                            "refusing to replace it — remove that nested repo first",
                    )
                }

                fileSystem.deleteRecursively(target)
            }

            // Records repoId itself as a candidate nested id for whichever ancestor id, if any, is
            // ever checked against it later — see MARKER_ROOT's doc. Written before the rename, not
            // after: a crash in between leaves this entry naming a directory that turns out not to
            // exist yet, which the nested check above already treats as a stale, harmless candidate.
            // The other order would leave the opposite: real, committed content with no shadow entry
            // at all, invisible to an ancestor's nested check, which would then delete it for real —
            // the one direction that actually loses something that was there. Only one createDirectories()
            // call, not a write too: nothing is ever read back out of this tree's own content, only its
            // shape, so recording repoId here needs nothing more than the directory existing.
            fileSystem.createDirectories(markerDir)

            // Final check, immediately before the rename that publishes this repo. Cheap on purpose:
            // one fileSystem.sizeOf per manifest file, no hashing — every byte was already verified
            // once by the loop above, and re-reading gigabytes here would be the wrong trade. The only
            // thing this catches is staging changing *after* the loop already verified it, which the
            // loop itself has no way to see: `abandonStaging` racing this call deletes exactly the
            // files the loop already verified and moved past (docs/known-limitations.md), and two
            // concurrent `download` calls for the same repo id can do the same to each other. Either
            // way, without this check the commit below still finds everything *it* looks at correct —
            // it looked at every file before the race landed — and publishes a repo silently missing
            // whatever vanished, as Result.success. This turns that into a clean Result.failure
            // instead, with nothing committed.
            val corrupted = selected.files.firstOrNull { file ->
                fileSystem.sizeOf(resolveInside(stagingDir, file.path)) != file.sizeBytes
            }
            if (corrupted != null) {
                return@withContext Result.failure(
                    VerificationException(corrupted.path, "missing or wrong size immediately before commit"),
                )
            }

            target.parent?.let { fileSystem.createDirectories(it) }
            fileSystem.atomicMove(stagingDir, target)

            onProgress(RepoProgress.Complete(repoId, target))
            Result.success(target)
        } catch (e: CancellationException) {
            // Structured concurrency depends on cancellation propagating; swallowing it here would
            // make this the one place in the library that breaks that.
            throw e
        } catch (e: Exception) {
            // Widened from IOException so a throw out of repo.manifest() (moved inside this try
            // above) is caught here instead of escaping this method's own Result<Path> contract.
            // Strictly wider than before, not a behaviour change for the rest of this try: every other
            // line above this catch already only ever throws IOException in practice, so this only
            // changes what happens for a throw that was never anticipated in the first place —
            // asDownloadFailure keeps the result the IOException-only type every other failure in this
            // method already is.
            Result.failure(e.asDownloadFailure())
        }
    }

    /**
     * [this] unchanged if it is already an [IOException] — or a subtype, like
     * [InsufficientSpaceException] or [VerificationException] — otherwise wrapped in one.
     *
     * `repo` is a third-party [ModelHub] (see its own KDoc on [ModelHub.manifest]), so its failure can
     * arrive as literally anything: a `Result.failure` carrying an unrelated `Throwable`, or, now that
     * the call is inside [download]'s own try, whatever type an uncaught throw happens to be. Every
     * other failure [download] produces or passes through is already an [IOException], so normalising
     * here — rather than handing back whatever the hub gave — is what keeps that true regardless of
     * how a hub misbehaves.
     */
    private fun Throwable.asDownloadFailure(): IOException =
        this as? IOException ?: IOException(message ?: toString(), this)

    /**
     * Reclaims the staging bytes of a [download] for [repoId] under [into] that the caller has given
     * up on — a failed attempt that will not be retried, or one abandoned mid-flight. Task 1 stopped
     * deleting staging on failure so a retry can resume from it; this is what reclaims the bytes when
     * no retry is coming.
     *
     * **Not safe to call concurrently with [download] for the same [repoId] and [into] — but no longer
     * unsafe in the way that used to matter most.** `download` recreates whatever directories it needs
     * as it goes (`fileSystem.createDirectories(destination.parent!!)`) and verifies only the one file
     * it is currently fetching — never a file a previous iteration of the same attempt already verified
     * and moved past. An `abandonStaging` landing mid-loop deletes exactly those already-verified files
     * out from under it, and the loop has no way to notice or re-fetch them. What that used to cost:
     * every later file still verified fine on its own, `download`'s own commit found everything *it*
     * checked present and correct, and `fileSystem.atomicMove(stagingDir, target)` still succeeded —
     * `Result.success`, publishing a repo silently missing every file downloaded before `abandonStaging`
     * landed. `download`'s own final pre-commit check, immediately before that rename, now re-confirms
     * every manifest file is still present at its declared size right before the rename — exactly what a
     * mid-loop `abandonStaging` breaks — so this race now ends in a clean `Result.failure` with
     * nothing committed, never a silent partial model. Still not something to rely on instead of
     * serialising: the loser's network transfer and disk writes are wasted rather than avoided, and
     * this says nothing about two concurrent `download` calls corrupting each other's writes to a
     * shared file, which is a different hazard this check does not touch (see `download`'s own KDoc
     * and docs/known-limitations.md's concurrency entry). Serialising `abandonStaging` against
     * `download` for the same repo id remains the caller's responsibility; nothing here enforces it,
     * only detects the damage this one specific race used to cause.
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
     * API in this library — named `abandonStaging` instead, so the one thing it touches is in its own
     * name.
     *
     * No staging present for [repoId] is success, not failure: the caller asked for a state — this
     * repo's staging reclaimed — and that state already holds.
     *
     * Sweeps every filter's staging directory for [repoId], unfiltered and keyed alike — see
     * [stagingDirsFor] — so "wipes all staging for this repo id" is now literally true.
     */
    suspend fun abandonStaging(repoId: String, into: Path): Result<Unit> = withContext(dispatcher) {
        try {
            stagingDirsFor(into / ".staging", repoId).forEach { dir ->
                fileSystem.deleteRecursively(dir)
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
     * walk over however many files a repo has staged is still blocking file I/O, and this was the
     * one non-suspend public method on this class, running that walk directly on whatever thread
     * called it. It now `suspend`s and runs on [dispatcher], the same one [download] and
     * [abandonStaging] already use, rather than asking every caller to know to move it off their
     * own thread by hand — which the sample app's own `SampleViewModel` was doing with a manual
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
     *
     * **The enumeration above answers "is this shape reusable", never "does a current manifest still
     * name this path at all" — that is not completeness, and is not claimed as any.** The second
     * question needs a manifest, and fetching one is the network call this method exists to avoid.
     * A path the hub has since renamed or removed is exactly what [pruneOrphans] discards,
     * unconditionally, the moment a real [download] call actually runs — so a `.part` with a
     * validator, or a bare file, staged under a path no manifest names any more is still counted
     * here and then discarded there, without ever being resumed. Not a gap this method could close
     * without a manifest to check against; stated here rather than left for the list above to imply
     * a completeness it cannot have.
     *
     * Sums across every filter's staging directory for [repoId], unfiltered and keyed alike — see
     * [stagingDirsFor] — so for a repo id with two filters staged this is their union, not what one
     * particular filtered [download] would actually reuse; one more way this is a hint, not a
     * promise.
     */
    suspend fun stagedBytes(repoId: String, into: Path): Long = withContext(dispatcher) {
        try {
            stagingDirsFor(into / ".staging", repoId).sumOf { stagedBytesIn(it) }
        } catch (e: IOException) {
            0L
        }
    }

    private fun stagedBytesIn(stagingDir: Path): Long {
        val marker = stagingDir / MARKER_FILE
        if (fileSystem.metadataOrNull(stagingDir)?.isDirectory != true) return 0L
        return fileSystem.listRecursively(stagingDir)
            .filter { fileSystem.metadataOrNull(it)?.isRegularFile == true && it != marker }
            .sumOf { staged ->
                when {
                    staged.name.endsWith(".validator") -> 0L
                    staged.name.endsWith(".part") -> {
                        val validator =
                            staged.parent!! / "${staged.name.removeSuffix(".part")}.validator"
                        if (fileSystem.metadataOrNull(validator)?.isRegularFile == true) {
                            fileSystem.sizeOf(staged)
                        } else {
                            0L
                        }
                    }
                    else -> fileSystem.sizeOf(staged)
                }
            }
    }

    /**
     * Whether [dir] already holds every file of this manifest, at the right size, with the right
     * hash where one was published.
     *
     * Deliberately re-hashes rather than trusting a marker file: a marker records what was true
     * once, and the point of the check is what is true now.
     */
    private fun RepoManifest.isSatisfiedBy(dir: Path): Boolean = files.all { it.isSatisfiedIn(dir) }

    /**
     * Whether [dir] already holds this one file at the right size, with the right hash where one was
     * published.
     *
     * `fileSystem.sizeOf(onDisk) == sizeBytes` is unconditional — including when `sizeBytes` is 0 —
     * and always has been. The post-download check in `download()` above now matches it exactly, for
     * the same reason: the two used to disagree (that check skipped entirely on a declared 0), which
     * let a hub declaring an explicit zero pass a non-empty body on download and then fail this check
     * on every later call forever. A declared 0 is treated as a real, checked assertion that the file
     * is empty in both places now, not "unknown, don't check" in one and enforced in the other.
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
    private fun RemoteFile.isSatisfiedIn(dir: Path): Boolean {
        val onDisk = resolveInside(dir, path)
        return fileSystem.metadataOrNull(onDisk)?.isRegularFile == true &&
            fileSystem.sizeOf(onDisk) == sizeBytes &&
            (sha256 == null || Sha256.matches(fileSystem, onDisk, sha256))
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
    private fun RepoManifest.creditingStaged(stagingDir: Path, satisfiedPaths: Set<String>): RepoManifest =
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
    private fun RemoteFile.remainingBytes(stagingDir: Path, satisfiedPaths: Set<String>): Long {
        if (path in satisfiedPaths) return 0L

        val staged = resolveInside(stagingDir, path)
        val parent = staged.parent!!
        val part = parent / "${staged.name}.part"
        val validator = parent / "${staged.name}.validator"
        val resumableBytes = if (
            fileSystem.metadataOrNull(part)?.isRegularFile == true &&
            fileSystem.metadataOrNull(validator)?.isRegularFile == true
        ) {
            fileSystem.sizeOf(part)
        } else {
            0L
        }
        return maxOf(0L, sizeBytes - resumableBytes)
    }

    /**
     * Deletes every staged file under [stagingDir] that [manifest] no longer vouches for, and any
     * directory that pruning those files leaves empty.
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
     * The directory pass runs second, after every orphan file is already gone, and bottom-up rather
     * than interleaved with the file pass: a directory is only safe to judge once nothing that might
     * still be inside it is left to judge first. Deleting a directory nothing names is not optional
     * tidiness — an orphaned subdirectory that survives this rides `fileSystem.atomicMove(stagingDir,
     * target)` straight into the committed repo on the very same rename that publishes the real files,
     * indistinguishable from real content to anything reading it back afterward. The directory pass
     * only ever deletes a directory it first confirms is empty via `listOrNull` — mirroring
     * `File.delete()`'s old no-op-on-non-empty behaviour without relying on a boolean return — so this
     * is a no-op for every directory that still holds something legitimate — including one a file for
     * *this* attempt has not been written into yet, which [download]'s own loop repopulates moments
     * later regardless.
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
    private fun pruneOrphans(stagingDir: Path, manifest: RepoManifest) {
        if (fileSystem.metadataOrNull(stagingDir)?.isDirectory != true) return
        val declaredPaths = manifest.files.mapTo(HashSet()) { it.path }
        fileSystem.listRecursively(stagingDir)
            .filter { fileSystem.metadataOrNull(it)?.isRegularFile == true }
            .forEach { staged ->
                val relativePath = staged.relativeTo(stagingDir).toString()
                val vouchedFor = relativePath in declaredPaths ||
                    relativePath.removeSuffix(".part") in declaredPaths ||
                    relativePath.removeSuffix(".validator") in declaredPaths
                if (!vouchedFor) {
                    fileSystem.delete(resolveInside(stagingDir, relativePath), mustExist = false)
                }
            }
        fileSystem.listRecursively(stagingDir).toList().asReversed()
            .filter { fileSystem.metadataOrNull(it)?.isDirectory == true }
            .forEach { directory ->
                val relativePath = directory.relativeTo(stagingDir).toString()
                val resolved = resolveInside(stagingDir, relativePath)
                if (fileSystem.listOrNull(resolved)?.isEmpty() == true) {
                    fileSystem.delete(resolved)
                }
            }
    }

    /** Injective over (pattern, options): the length prefix delimits the pattern exactly. */
    private fun canonicalIdentity(filter: Regex): String =
        "${filter.pattern.length}:${filter.pattern}" +
            filter.options.map { it.name }.sorted().joinToString(",")

    /**
     * "" for the unfiltered case; 64 lowercase hex characters otherwise.
     *
     * Hashed rather than embedded because the identity appears in a filesystem path segment,
     * where a raw pattern cannot go — it may contain '/', '\n', arbitrary length, and case a
     * case-insensitive filesystem would fold. canonicalIdentity is never parsed, only hashed,
     * so it needs injectivity and nothing else.
     */
    private fun filterKey(filter: Regex?): String =
        filter?.let { canonicalIdentity(it).encodeUtf8().sha256().hex() } ?: ""

    /**
     * What `target/[MARKER_FILE]` contains — written, compared whole-string, **never parsed**.
     *
     * The unfiltered writer emits exactly [repoId], byte for byte what every version of ferry has
     * ever written, with no trailing separator: a directory committed by a pre-filter ferry reads
     * back equal to what an unfiltered call expects, so it stays a valid cache hit and a valid
     * commit target with no migration. The filtered writer emits [repoId], one '\n', and the 64
     * lowercase hex characters of [filterKey]. Both [repoId] and a Regex pattern may legally
     * contain '\n', which is why no field is ever extracted back out of this string — the
     * accept/reject decision everywhere is whole-string equality, and the pattern's own newlines
     * never reach the file at all because only its hash does.
     */
    private fun markerContent(repoId: String, filterKey: String): String =
        if (filterKey.isEmpty()) repoId else "$repoId\n$filterKey"

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
     * a manifest that was never theirs — [abandonStaging] and [stagedBytes] reached the same way, via
     * `deleteRecursively()` and a recursive sum respectively. The emptied nested directory then rode
     * `fileSystem.atomicMove(stagingDir, target)` straight into the committed repo, because
     * [pruneOrphans] only ever deletes files (tracked separately; see its own doc).
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
     *
     * A non-empty [filterKey] — always 64 lowercase hex characters, see [filterKey]'s own doc —
     * appends as `-<key>` after [STAGING_SUFFIX], so every filter, including the absence of one,
     * gets its own sibling scratch directory: `owner/model.d` unfiltered, `owner/model.d-<64 hex>`
     * per filter. Appended to the last segment rather than nested inside `model.d`, because nesting
     * would make the unfiltered directory a literal ancestor of every filtered one — reintroducing
     * exactly the Critical this function's own history above describes.
     */
    private fun stagingDirFor(stagingRoot: Path, repoId: String, filterKey: String): Path {
        // Validates repoId exactly as an unsuffixed resolve always did — rejects "", "..", and any
        // escape — before this function's own suffixing gets a chance to be more permissive:
        // resolving "" + STAGING_SUFFIX lands on a proper descendant of stagingRoot, not stagingRoot
        // itself, so the "strictly inside" guard alone would not catch an empty repoId below.
        resolveInside(stagingRoot, repoId)
        val suffix = if (filterKey.isEmpty()) STAGING_SUFFIX else "$STAGING_SUFFIX-$filterKey"
        // trimEnd('/'): a trailing separator must not turn the suffix into a new segment of its own
        // ("owner/" -> "owner/.d", three segments) instead of extending the last real one
        // ("owner.d", two) — repoId with or without a trailing slash names the same target directory
        // ((stagingRoot / "owner/").normalized() == (stagingRoot / "owner").normalized()), and must
        // name the same staging directory too.
        return resolveInside(stagingRoot, "${repoId.trimEnd('/')}$suffix")
    }

    /**
     * Every staging directory belonging to [repoId] under [stagingRoot]: the unfiltered one and
     * one per filter — `<last>.d` and `<last>.d-<64 lowercase hex>` siblings, where `<last>` is
     * [repoId]'s trimmed final segment.
     *
     * The exactly-64-hex requirement is load-bearing, not decoration: a bare
     * `startsWith("<last>.d-")` would match a *different* repo id's staging — an id literally
     * named `m.d-x` stages at `m.d-x.d`, which starts with `m.d-`. Requiring the remainder to be
     * exactly 64 hex characters excludes that for every possible id: a real staging directory
     * name always ends in [STAGING_SUFFIX] (".d"), and '.' is not a hex character, so no other
     * id's staging directory can ever satisfy the test. Unlike [stagingDirFor]'s documented
     * deliberately-constructed-collision residual, this one has no residual at all.
     *
     * Resolves through [stagingDirFor] first so a hostile or malformed [repoId] is rejected
     * exactly as everywhere else, before any listing happens.
     */
    private fun stagingDirsFor(stagingRoot: Path, repoId: String): List<Path> {
        val unfiltered = stagingDirFor(stagingRoot, repoId, "")
        val parent = unfiltered.parent ?: return emptyList()
        val base = unfiltered.name
        return (fileSystem.listOrNull(parent) ?: emptyList()).filter { entry ->
            entry.name == base || isFilterKeyedSibling(entry.name, base)
        }
    }

    /** Whether [name] is `[base]-` followed by exactly 64 lowercase hex characters. */
    private fun isFilterKeyedSibling(name: String, base: String): Boolean {
        if (!name.startsWith("$base-")) return false
        val hex = name.substring(base.length + 1)
        return hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' }
    }

    /**
     * Whether normalized path [targetPath] is, or falls strictly inside, [reservedRoot].
     *
     * Separate from [resolveInside]: that function only rejects a path that escapes its parent, and
     * both `.staging` and `.ferry` are ordinary strict children of `into`, not escapes — this is the
     * narrower check that a repoId did not resolve onto one of the handful of names `into` itself
     * reserves for Ferry's own bookkeeping.
     */
    private fun collidesWith(targetPath: Path, reservedRoot: Path): Boolean {
        val target = targetPath.normalized().toString()
        val reserved = reservedRoot.normalized().toString()
        return target == reserved || target.startsWith("$reserved/")
    }

    /**
     * Resolves [relative] inside [parent] and fails if it escapes.
     *
     * Repo ids come from the calling app and file paths come from the hub's manifest over the
     * network, so neither can be trusted to stay inside the directory it is joined to.
     *
     * Normalized lexically rather than canonicalized: okio can only canonicalize a path that already
     * exists, and most of what this method guards does not exist yet. ".." and redundant separators
     * are resolved by normalization; a symlink inside [parent] pointing outside it is no longer
     * resolved before the comparison — recorded in docs/known-limitations.md, acceptable because
     * every tree this method guards lives under an app-private directory Ferry itself created.
     *
     * Strictly inside: resolving to [parent] itself is rejected, not tolerated as a base case.
     * `parent / ""` and `parent / "."` both normalize to exactly [parent], so permitting equality
     * made an empty repo id — a blank search field, a null coalesced to "" — resolve `target` onto
     * the download root, whose commit step then deletes every repo the user had. None of this
     * method's callers wants the parent: a repo never stages as the whole staging area, a target is
     * never the download root, a repo's own shadow directory never sits directly at the marker root,
     * and a file is never the directory containing it.
     */
    private fun resolveInside(parent: Path, relative: String): Path {
        val candidate = parent / relative
        val root = parent.normalized()
        val resolved = candidate.normalized()
        if (relative.startsWith("/") || resolved == root ||
            !resolved.toString().startsWith("$root/")
        ) {
            throw IOException("path must resolve strictly inside $parent: $relative")
        }
        return candidate
    }
}
