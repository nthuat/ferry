# Known limitations

Findings that were identified, understood, and deliberately not fixed. Each names the condition that
makes it reachable, so the next person can tell whether their change makes it worse.

This exists because the review record that produced it lives in a git-ignored scratch directory that
gets deleted. A limitation nobody wrote down is a limitation nobody knows they inherited.

## A manifest that declares a file literally named `.ferry` inside a subdirectory

The nested-repo guard refuses to replace a target directory when any `.ferry` file exists anywhere
in its subtree other than `target/.ferry` itself. That guard is what stops one committed repo id
from deleting another nested inside it (`owner` containing `owner/model`). It cannot tell a real
marker apart from an ordinary downloaded file that merely happens to share the name — distinguishing
them is exactly the kind of cleverness this codebase has been bitten by before, so it does not try.

**Condition:** a hub's manifest lists a file whose path is `<subdirectory>/.ferry` (not at the repo
root — a root-level `.ferry` entry is overwritten by Ferry's own marker write, which always lands
last). Once such a repo commits, any later attempt to replace it — a failed cache check, a
corrupted file, a re-download — hits the nested-marker check and is refused.

**Consequence:** the same terminal state as the existing no-marker refusal. There is no API to clear
it; the error message names the offending `.ferry` file and says to remove it first. Neither
HuggingFace nor ModelScope publishes a file named `.ferry`.

## Closed: a file declared with size 0 was verified by nothing

Was: `RepoDownloader`'s post-download check guarded on `remote.sizeBytes > 0`, so a hub that omits
sizes would not be broken by it. `isSatisfiedBy` (the cache-hit check) had no such guard — it compared
`onDisk.length() == remote.sizeBytes` unconditionally. Two consequences for a hub publishing an
explicit zero: a non-empty body was accepted at download time and then cache-missed forever, since
`isSatisfiedBy` would never agree it matched; and a genuinely empty file with no published `sha256`
was committed having been checked by neither path.

**Closed by making the two agree, in the restrictive direction.** The post-download check in
`download()` dropped the `remote.sizeBytes > 0` guard, so it now compares
`destination.length() != remote.sizeBytes` unconditionally — exactly matching `isSatisfiedBy`, which
never had the guard to begin with. A declared 0 is now a real, checked assertion that the file is
empty in both places, not "unknown, skip the check" in one and enforced in the other.

The other direction considered — adding the `> 0` guard to `isSatisfiedBy` too, so a declared 0 means
"unknown" everywhere — was not taken. It is not restrictive: it would make `isSatisfiedBy` accept a
case it currently rejects (any on-disk length, once `remote.sizeBytes` is 0), which is the same shape
of change (broadening what an existing check accepts) that produced two of the defects this file
documents. Dropping the download-time guard instead only makes that check reject a case it used to
silently accept — a non-empty body against a declared 0 — which is the opposite direction and costs
nothing observable: neither `HuggingFace` nor `ModelScope` was ever seen omitting a real file's size
this way (both always publish an explicit figure for a `"file"`/`"blob"` entry; the `= 0` Kotlin
default in each adapter's parsed entry type only lands on the `"directory"`/`"tree"` entries that are
filtered out before becoming a `RemoteFile`), so this closes a real gap for a hub that starts
publishing a genuine zero without changing behavior for either hub in production today.

## Resume does not survive the process

`ResumableDownloader` recovers from a dropped connection within one attempt. `RepoDownloader` deletes
its staging directory in a `finally`, so a failed attempt starts the next one from byte zero.

This is a deliberate trade, not an oversight: deleting staging is what guarantees no half-written repo
survives a failure. Having both needs persisted state and is a later phase. The README's guarantee
table is scoped accordingly.

## Concurrent downloads of one repo id are the caller's problem

Two concurrent `download()` calls for the same repo id into the same directory share a staging path.
The benign outcome is that one call's `finally` deletes the other's in-flight work. The bad one is a
rename landing while the other still holds open descriptors, so writes follow the inode into an
already-committed repo.

Documented on `download`'s KDoc. Serialising is the caller's responsibility.

## A refused directory needs manual removal

When a target exists without a matching marker, Ferry refuses rather than deleting it — the whole
point of the marker. There is no API to clear it; the error message says to remove the directory.

## The `api` configuration is not regression-detectable from inside the module

`OkHttpClient` and `CoroutineDispatcher` are on `api` so a consumer can pass its own client, which is
what `EmbeddabilityTest` exists to protect. That test compiles against module source and would pass
under `implementation` too, so it cannot catch a regression. Proving it needs a consumer project
built against the published artifact, and nothing is published yet.

**Cheap partial fix, done:** `checkEmbeddable` asserts that the `api` configuration's dependencies
include okhttp and a module exporting `CoroutineDispatcher`.

## ModelScope's file listing may paginate above what has been tested

`ModelScope.manifest` makes exactly one request and never follows a cursor or page parameter. Tried
live against `PageSize`, `PageNumber`, `Limit` and `limit` query parameters on the listing endpoint —
all four were silently ignored and the full listing came back regardless — and no `Link`-style header
appeared on repos of 15 and 39 files. The official Python client's own model-file-listing call
(`list_repo_files` in `modelscope/modelscope_hub`) takes no paging parameter either; only its separate
*dataset* listing method pages, and even that is a plain page-number loop rather than a cursor or a
total-count field. No field resembling either was seen anywhere in the model listing envelope
(`Code`/`Success`/`Message`/`RequestId`/`Data.Files`/`Data.IsVisual`/`Data.LatestCommitter`).

**This is evidence, not proof of an absent cap.** HuggingFace's own 1000-entry `Link`-header cap was
just as invisible against small test repos, until a 1724-file repo surfaced it — see the README's
"worth knowing" section. Nobody has tested a ModelScope repo of hundreds or thousands of files, and
this adapter deliberately does not build a loop against a mechanism nobody has observed.

**Condition:** a ModelScope repo whose recursive file count exceeds whatever cap, if any, the listing
endpoint enforces above 39 entries.

**Consequence, if a cap exists:** the exact failure mode this feature exists to prevent — a manifest
silently missing the entries past the cap, downloaded, verified and committed as a complete model,
with no error at any layer. A repo of that size would settle the question either way: a truncated
`files` list confirms a cap, and a complete one across a genuinely large repo would be the first real
evidence against one.

## Closed: `..` retargeting a request to an arbitrary path — both the caller-supplied repo id and the hub-supplied file path

This has two halves, closed by the same mechanism at different call sites: a `repoId` the *calling
app* supplies, and a per-file `path` the *hub's own manifest* supplies. The second is the more serious
of the two — a hostile or compromised hub reaches it, not just a careless caller — and was found while
closing the first, not named up front.

### Half 1: `repoId`

Was: both `HuggingFace` and `ModelScope` build their listing and download URLs from a caller-supplied
`repoId` via `HttpUrl.Builder.addPathSegments`, which resolves a `.` or `..` path segment by popping
the segment before it rather than rejecting it or encoding it as literal text. A `repoId` of
`"../../etc/passwd"` popped `api/models` (or, partially, `api/v1/models`) off the path, retargeting
the request to `{baseUrl}/etc/passwd/...` instead of staying inside the models namespace — bounded to
the hub's own origin, but able to aim a `GET` at any path under it, not only ones inside that
namespace.

**Closed by a structural check on the output, not the input.** Each adapter asserts, on the built
`HttpUrl` and before the request is issued, that `pathSegments` still starts with the intended prefix
— `HuggingFace.requireWithinNamespace` / `ModelScope.requireWithinNamespace`. If it does not, the
request is never sent and `manifest` returns `Result.failure`.

Applied to `HuggingFace`'s tree-listing URL and both of `ModelScope`'s URLs (listing and download —
both build `api/v1/models` before `repoId`) against a literal constant prefix (`api/models` /
`api/v1/models`). **Not meaningfully exposed** on `HuggingFace`'s own download (`resolve/main`) URL via
`repoId` specifically: there, `repoId` is the *first* thing appended to `baseUrl`, with no fixed
literal prefix ahead of it for `repoId`'s own `..` to pop — confirmed directly against okhttp 4.12.0,
a malicious `repoId` alone resolves there to `{baseUrl}/etc/passwd/resolve/main/{path}`, never
escaping outside a `{repoId}/resolve/main/{path}` shape, because `resolve/main` and the file path are
pushed by later, independent `addPathSegments` calls that nothing processed earlier can reach back and
pop. See Half 2 below for what *is* exposed at that same call site.

### Half 2: the hub's own per-file `path`

Found while closing Half 1, not by a separate report: `HuggingFace.downloadUrl` appends the per-file
`path` — from the hub's manifest response, over the network, the same untrusted source
`RepoDownloader`'s own comments already call out for `remote.path` on the filesystem side — *after*
`{repoId}/resolve/main` is already fixed. A `path` of
`"../../../../other/repo/resolve/main/secret.bin"` pops `main`, `resolve`, and both of `repoId`'s own
segments away, retargeting the *download* at a different repo's file on the same origin entirely —
confirmed empirically, not just argued. Anyone can publish a repo on HuggingFace, so this is reachable
by a hostile repo, not only a careless caller: the fetch is issued with the host app's own
`OkHttpClient`, carrying whatever auth that client attaches.

**Closed the same way as Half 1, at the same call site, against a computed rather than literal
prefix.** `HuggingFace.downloadUrl` now builds `{repoId}/resolve/main` first, asserts the full URL
(with `path` appended) still starts with exactly that, then appends `path`. The prefix is computed
per call instead of a constant because `repoId`'s own contribution varies — but it is still known at
build time, before the request is issued, which is what keeps this an output assertion rather than an
input check. `repoId`'s own `..` cannot fail this check (Half 1's finding: nothing precedes it here to
pop), so in practice this check's entire effect is against `path`. `ModelScope` needs no equivalent:
its per-file path travels through `addQueryParameter`, an opaque, percent-encoded value with no
segment-popping semantics — confirmed empirically that the same malicious `path` round-trips there
unchanged and `pathSegments` is unaffected.

**Relationship to `RepoDownloader.resolveInside`, checked, not assumed:** `resolveInside(stagingDir,
remote.path)` already guards the *filesystem* destination built from the same `path`, before
`downloader.download(url = remote.url, ...)` is called — so today, for this exact attack shape (a
`path` of leading `..` segments), the network request was already never issued even before this fix,
confirmed by reverting the new check and rerunning `RepoDownloaderTest`'s end-to-end case: it still
passed, on `resolveInside` alone. That is incidental, not designed: `resolveInside`'s boundary
(`stagingDir`, a filesystem path whose depth is `into` plus a fixed few segments) and the URL's
boundary (`{repoId}/resolve/main`, unrelated to `into` entirely) are checked by two different
algorithms over two different notions of "prefix," which happen to both be maximally sensitive to a
leading `..` today. Nothing keeps them agreeing if either shape changes later — this fix makes the URL
correct on its own terms, independent of whatever the filesystem side does.

### The original reasoning, and the residual

**The original reasoning for not fixing this conflated two different kinds of check.** The `?`/`&`/`#`
half of that reasoning still stands, unchanged, in both adapters' KDoc: a client-side shape check on
`repoId` text is a denylist, and the hub alone is the authority on which ids are valid, so rejecting a
character up front goes stale the moment the hub widens its own rules. That argument is sound for
`?`/`&`/`#` — there genuinely is no way to check them except by inspecting the input's own text — and
it was wrong to extend to `..`, because `..` was never an input-shape question, for `repoId` or for
`path`. `HttpUrl.Builder` doesn't reject a request over `..`; it silently *resolves* it, popping real
segments the way it always does. The fix available for that isn't a better denylist, it's a check on
what the builder produced: does the URL this code built still point at the namespace this code meant
it to. That says nothing about which `repoId`s or `path`s are legal — it cannot go stale as the hub's
own rules evolve, because it was never a claim about either.

**Residual:** an input containing `..` that resolves to a *legitimate*-looking path still inside the
intended namespace (vanishingly unlikely in practice, and would need to reconstruct a real path via
exact cancellation) is not distinguished from an ordinary one — this check only catches an escape from
the namespace, not a same-namespace collision. Not treated as a gap worth closing: it is exactly as
reachable, and exactly as consequential, as a caller or hub directly supplying that reconstructed value
in the first place.

## Free space is probed at the nearest existing ancestor, not the directory asked about

`DefaultFreeSpaceProbe` (`SpaceCheck`'s default `FreeSpaceProbe`) may be asked about a directory
that does not exist yet — `RepoDownloader.download()`'s `into` before a first-ever download, or any
directory a host passes to a direct, preflighting `SpaceCheck().check(...)` call, since `SpaceCheck`
is public and exported on the `api` configuration. A first-ever download into a fresh directory is
the ordinary case, not an edge case. `File.usableSpace` reports 0 for a path that is not there yet,
so probing it directly would report "nothing fits" regardless of real free space. The fix walks up
to the nearest ancestor that already exists and probes that instead, on the premise that free space
is a property of the volume, not of one directory on it. Fixed in the probe itself, not in
`RepoDownloader`, so every caller of the default probe is covered, not only one route to it.

**Condition:** the nearest existing ancestor of the directory asked about sits on a different
filesystem or mount point than the one that directory would actually be created on — for instance, a
symlink partway up the chain pointing at a separate device. Not reachable from Android app-private
storage, which is a single volume; would need a caller to pass a directory tree that isn't.

**Consequence:** the space report reflects the ancestor's volume, not necessarily the one the
directory asked about would land on — it could read sufficient when the real target volume is not,
or the reverse. A caller supplying its own `FreeSpaceProbe` does not get this walk at all — a custom
probe is that caller's own contract, and `RepoDownloader` passes it whatever directory it was given,
unwalked.

**Not fixed here:** the JVM has no portable, dependency-free way to name "the filesystem a
not-yet-created path would be created on" short of creating it first, which is the side effect this
approach exists to avoid.
