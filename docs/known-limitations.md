# Known limitations

Findings that were identified and understood. Most were deliberately not fixed; entries marked
**Closed** were, and are kept rather than deleted so the reasoning that made them worth writing down
in the first place — and the reasoning that later closed them — both stay on record. Each names the
condition that makes it reachable, so the next person can tell whether their change makes it worse.

This exists because the review record that produced it lives in a git-ignored scratch directory that
gets deleted. A limitation nobody wrote down is a limitation nobody knows they inherited.

## Closed: a manifest that declares a file literally named `.ferry` inside a subdirectory

Was: the nested-repo guard refused to replace a target directory when any `.ferry` file existed
anywhere in its subtree other than `target/.ferry` itself. That guard is what stops one committed
repo id from deleting another nested inside it (`owner` containing `owner/model`). It could not tell
a real marker apart from an ordinary downloaded file that merely happened to share the name —
distinguishing them was exactly the kind of cleverness this codebase has been bitten by before, so it
did not try. A hub's manifest listing a file at `<subdirectory>/.ferry` committed once and could then
never be replaced: a failed cache check, a corrupted file, a plain re-download all hit the
nested-marker check and were refused, permanently, with no API to clear it.

**Closed by splitting the question the marker was answering, not by relocating the whole marker.**
An intermediate version of this fix moved the marker entirely to a shadow tree
(`into/.ferry/X/.ferry`), keyed by repo id rather than living inside the repo's own directory —
code review, verified against an isolated copy rather than argued, found that this made ownership a
property of a *name* nothing ever deletes, instead of a property of the *directory* that `renameTo`
and `deleteRecursively` already keep in lockstep. A directory removed out of band — the only way to
delete a model, and the remedy "A refused directory needs manual removal" below names for every
refusal — left its shadow marker standing with nothing left to describe; foreign content placed at
the same path afterwards inherited the old commit's ownership and was deleted to make room for a new
one, the exact class of bug this file exists to guard against. Reverted for that reason.

The ownership marker is back at `target/.ferry`, exactly where and how it always was — written into
staging so the rename that publishes the repo's content publishes the marker with it, atomically, so
there is nothing to migrate for this half. **Only the nested-repo question moved**, to a shadow tree
under `into/.ferry` that records which repo ids are committed, never anything read back about their
content — see `RepoDownloader.kt`'s `MARKER_ROOT` doc. That is enough on its own to close this entry:
the nested check no longer walks the real tree looking for a file named `.ferry`, so a manifest
entry at `<subdirectory>/.ferry` is now ordinary content at every depth, root included, and the
original condition can no longer brick a repo. A shadow entry is never deleted either, so it is
cross-referenced against the real tree before being trusted — see the nested-check's own comment —
which is what keeps a stale entry from blocking a replace forever the way the original bug did, and
what makes removing the nested repo the refusal names actually clear that refusal afterwards (see
`RepoDownloaderTest`'s revert-checked coverage of both this and the reverted design's own Critical).

**One carve-out this entry used to celebrate removing is back, correctly.** A manifest entry named
`.ferry` at the repo *root* — not a subdirectory — is still silently overwritten by Ferry's own
marker write landing last on the same path, `target/.ferry`, because ownership is co-located again.
This is the original, pre-existing behaviour, not a new gap: neither HuggingFace nor ModelScope
publishes a file named `.ferry`, so it costs neither adapter anything in practice, and it is the same
shape of trade already accepted elsewhere in this file rather than a silent reversal — stated here
plainly instead of leaving the earlier "no longer overwritten" claim standing.

**Compatibility.** The ownership marker itself is unchanged from its original format, so a directory
committed by any version of this library reads the same way here — nothing to migrate for that half.
The nested-repo shadow tree is new bookkeeping with no retroactive knowledge of relationships
established before it existed: an ancestor and descendant both committed under a version of Ferry
that predates the shadow tree would, on first contact with this version, replace as if nothing were
nested — moot in practice, since Ferry is unpublished with no installed base old enough to have such
a pair, and stated plainly rather than left silent.

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

## Closed: resume did not survive the process

Was: `ResumableDownloader` recovered from a dropped connection within one attempt, but `RepoDownloader`
deleted its staging directory in a `finally`, so a failed attempt started the next one from byte zero.
Deliberate at the time, not an oversight: deleting staging was what guaranteed no half-written repo
survived a failure, and having both needs persisted state — noted then as a later phase, with the
README's guarantee table scoped accordingly.

**Closed by making staging durable instead of transactional, not by adding persistence.** The
`finally` is gone; a failed attempt, a cancellation, or the process dying now all leave staging exactly
as far as they got. That is safe for the same reason removing it was safe to attempt at all: staging
was never inside the target directory the no-half-written-repo guarantee is about — it sits under
`into/.staging`, a reader of `into` itself never sees it — so deleting it on failure was only ever
protecting against a disk leak, not against corruption. `abandonStaging(repoId, into)` now protects
against that leak explicitly instead, called when the caller has decided no retry is coming, rather than
automatically the moment one attempt fails and possibly ripping out bytes a retry would have reused.

A later `download` call for the same repo id resumes from whatever staging holds: a `.part` with a
validator resumes via `Range`; a bare file already staged under its final name is skipped entirely,
not re-fetched, once re-verified against the manifest — presence alone is deliberately not trusted,
since a bare file is only ever what the *server's* declared length was satisfied, before anything
compared it to the manifest. `stagedBytes(repoId, into)` lets a caller detect there is something to
resume before calling `download` at all. A manifest that no longer vouches for a staged path — the hub
renamed or removed the file — has that stale scratch pruned before it can be mistaken for progress
worth resuming.

**Residual, not closed by this:** deliberate **pause** — stopping a download mid-flight and recording
that it was intentional rather than a failure — remains unimplemented. Coroutine cancellation already
stops a transfer, but nothing records why it stopped, and resume is only ever as good as the hub's own
validator: a `.part` with no `ETag`/`Last-Modified` still restarts that one file from byte zero
regardless of how the previous attempt ended.

## Concurrent downloads of one repo id are the caller's problem

Two concurrent `download()` calls for the same repo id into the same directory share a staging path
and write into it independently — interleaved writes to the same destination file are a corruption
risk on their own, before either call reaches its commit step. Whichever commits first renames
staging onto `target`; if the other still holds open descriptors into it, its writes follow the inode
into what is now a committed repo — nothing left running in the first call's own `download` to notice,
since it already returned. If the second call reaches its own commit afterward, `target`'s marker
still names the same repo id — both calls share it — so the guard against replacing a directory this
method did not write does not catch this either: the second call deletes the first's freshly committed
repo and renames its own version over it. The pre-commit check below stops that second version from
being silently incomplete — its own staging can be left missing files the same way `abandonStaging`
leaves it missing files, and is checked the same way — but does not stop the first call's already-
committed repo from being deleted to make room for it, and does not touch the open-descriptor
corruption in the previous sentence at all: that damage lands after the corrupting call's own
`download` has already returned, with nothing left running on that side to check anything against.

Documented on `download`'s KDoc. Serialising is the caller's responsibility.

**Closed, for the specific failure this used to cause silently: `abandonStaging` racing `download` for
the same repo id — precisely, not just in spirit.** `download`'s loop recreates whatever directories it
needs as it goes and verifies only the one file it is currently fetching, never one a previous
iteration already verified and moved past. An `abandonStaging` landing mid-loop deletes exactly those
already-verified files out from under it; the loop does not know and does not re-fetch them. What that
used to cost: every file the loop checks afterward still verifies fine on its own, so the commit at the
end still found everything *it* looked at present and correct — `Result.success`, publishing a repo
silently missing every file downloaded before `abandonStaging` landed, a failure neither call could
detect. `download` now runs one final check immediately before `stagingDir.renameTo(target)`: for every
file the manifest declares, `resolveInside(stagingDir, it.path).length() != it.sizeBytes` fails the
download. One `stat` per file, no hashing — every byte was already verified once by the loop above, so
this only needs to catch a file *going missing after* that, which the loop itself cannot see. A file
`abandonStaging` deleted mid-loop is exactly that, so this race now ends in a clean `Result.failure`
with nothing committed, never the silent partial model above. The same check also catches the same
shape of gap in a second concurrent `download` call's own staging (next paragraph) — but not the
open-file-descriptor corruption that paragraph describes, and not which of two concurrent calls "wins":
serialising remains the caller's responsibility for both; documented on `abandonStaging`'s own KDoc.

**Narrowed for one caller: `:ferry-work`'s `enqueueRepoDownload`.** `:ferry` itself has no enqueue
step to serialise at — a plain method call has nothing to deduplicate against. `:ferry-work`, the
optional WorkManager module, does: `enqueueRepoDownload` wraps `WorkManager.enqueueUniqueWork` with
`ExistingWorkPolicy.KEEP`, keyed by repo id, so two enqueues for the same id while one is already
running or queued collapse into one `RepoDownloadWorker` — the second is dropped, not run.

This closes the case *within WorkManager only*, not the general one this entry names. A host that
calls `RepoDownloader.download` directly — bypassing `enqueueRepoDownload`, whether or not
`:ferry-work` is even on its classpath — is exactly as exposed as before, and so is a host that
mixes both call paths for the same repo id (one direct call, one enqueued through `:ferry-work`):
`enqueueUniqueWork`'s bookkeeping only sees work enqueued through it, so it cannot detect, let alone
serialise against, a call it was never part of. Serialising remains the caller's responsibility for
every path other than "always go through `enqueueRepoDownload` for this repo id."

**Untested, by construction — not merely un-tested.** No automated test exercises
`enqueueUniqueWork`'s actual dedup behaviour. `RepoDownloadWorkerTest` runs a single
`RepoDownloadWorker` in isolation via `TestListenableWorkerBuilder`, which never calls
`WorkManager.enqueueUniqueWork` at all — there is no enqueue step in that test for `KEEP` to act
on, only a worker instance already built directly. Proving the dedup itself needs a real, running
`WorkManager` (`WorkManagerTestInitHelper`), which needs a real or Robolectric-backed `Context`;
this project uses neither anywhere, deliberately (`:ferry`'s own `unitTests.isReturnDefaultValues
= true` exists to avoid needing exactly that). Not reached for here either, rather than adding an
instrumented suite this project does not otherwise have. The policy is exercised only by reading —
this entry, and `RepoDownloadWork.kt`'s own KDoc — not by a passing test.

## A refused directory needs manual removal

When a target exists without a matching marker, Ferry refuses rather than deleting it — the whole
point of the marker. There is no API to clear it; the error message says to remove the directory.

## The `api` configuration is not regression-detectable from inside the module

`HttpClient` and `CoroutineDispatcher` are on `api` so a consumer can pass its own client, which is
what `EmbeddabilityTest` exists to protect. That test compiles against module source and would pass
under `implementation` too, so it cannot catch a regression. Proving it needs a separate consumer
project built against the published artifact, and no such project exists yet.

**Cheap partial fix, done:** `checkEmbeddable` asserts that the `api` configuration's dependencies
include Ktor's `HttpClient` and a module exporting `CoroutineDispatcher`.

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
both build `api/v1/models` before `repoId`). The prefix is computed off `base` at every one of these
sites — `base.newBuilder().addPathSegments("api/models" | "api/v1/models").build()`, then continuing
the *same* builder to add `repoId` and the rest — not a bare literal constant. That distinction is
itself a fix, not a stylistic choice: `baseUrl` is a public constructor parameter on both adapters
(and on `Ferry.huggingFace`/`Ferry.modelScope`), a self-hosted mirror is a contemplated configuration
(see `sameOriginOrNull`'s own doc), and a literal `["api", "models"]` comparison ignores whatever path
segments `base` itself already carries. A mirror at `https://nexus.corp/repository/huggingface` builds
a perfectly correct `.../repository/huggingface/api/models/...` request; a literal-constant check
rejected it anyway, on every call, because `pathSegments.take(2)` was `["repository", "huggingface"]`,
never `["api", "models"]`. That was caught by review before ever shipping, not found live, precisely
*because* it repeats the mistake this whole entry exists to name: an implicit, hardcoded claim about
what's legal (here, "`baseUrl` has no path of its own") that goes stale the moment a real deployment
doesn't match it. Computing the prefix from `base` removes the claim rather than widening it.

**Not meaningfully exposed** on `HuggingFace`'s own download (`resolve/main`) URL via `repoId`
specifically: there, `repoId` is the *first* thing appended to `base`, with no fixed literal prefix
ahead of it for `repoId`'s own `..` to pop — confirmed directly against okhttp 4.12.0, a malicious
`repoId` alone resolves there to `{baseUrl}/etc/passwd/resolve/main/{path}`, never escaping outside a
`{repoId}/resolve/main/{path}` shape, because `resolve/main` and the file path are pushed by later,
independent `addPathSegments` calls that nothing processed earlier can reach back and pop. See Half 2
below for what *is* exposed at that same call site.

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

**Preserved across the Ktor port.** Both halves' defense above is described against OkHttp's
`HttpUrl.Builder.addPathSegments`, the client this project shipped 0.1.0 with; the Kotlin
Multiplatform port to Ktor carries the identical structural check forward on `URLBuilder` instead —
`appendPathSegmentsResolvingDots` resolves `..` by popping the preceding segment the same way
`addPathSegments` did (so `requireWithinNamespace` and the `resolve/main` prefix check still catch
it), and `appendOpaqueSegment` keeps Ollama's `tag` segment literal exactly like OkHttp's singular
`addPathSegment` did (see `KtorUrlCompat.kt`).

**Relationship to `RepoDownloader.resolveInside`: dominance, not coincidence.** `resolveInside(stagingDir,
remote.path)` already guards the *filesystem* destination built from the same `path`, before
`downloader.download(url = remote.url, ...)` is called — so today, for a `path` of plain leading `..`
segments, the network request was already never issued even before this fix, confirmed by reverting
the new check and rerunning `RepoDownloaderTest`'s end-to-end case: it still passed, on `resolveInside`
alone. `resolveInside`'s boundary is `stagingDir` itself — zero headroom, not a depth budget: a single
leading `..` already exits it, regardless of how deep `into` is. That is *stricter* than the URL check
needs to be, and the two measurably disagree, checked directly rather than assumed: for
`path = "a//../../x.bin"`, `resolveInside` refuses it (`File.canonicalPath` collapses `//` to `/`
first, so both `..` pop for real and the result lands outside `stagingDir`) while the URL check does
not (`addPathSegments` treats the empty segment between the two `/`s as pushed and then popped, so
only one `..` nets out and the built URL still starts with the intended prefix — correctly, since
nothing there actually escaped). For `path = "../main/x.bin"`, `resolveInside` again refuses it (lands
in `stagingDir`'s sibling, not itself) while the URL check does not, because `main` is the literal
final segment of `{repoId}/resolve/main` — popping it and then pushing back a segment that happens to
be named `main` again reproduces the exact intended prefix, a genuine no-op on the URL side even
though the same string is a real escape on the filesystem side. `resolveInside` is not a weaker,
coincidentally-aligned cousin of the URL check here; in both cases checked, it is the stricter of the
two, because it is checking a different, independently-varying boundary (`repoId`'s own directory
nesting under `stagingRoot`) that has no fixed literal segment an attacker can round-trip the way
`main` allows on the URL side, and its own tolerance for any escape at all is zero regardless. Not
verified as a general property across every possible `path` string — only checked against the two
shapes above — so "dominates in the cases checked" is the honest claim, not "dominates unconditionally".

None of that makes the URL check redundant. `resolveInside` runs inside `RepoDownloader`, downstream
of `manifest()` — but `manifest()` is public, and `RemoteFile.url` is a public field. A host that calls
`HuggingFace.manifest()` directly and hands `RemoteFile.url` to its own downloader — never touching
`RepoDownloader` at all — never reaches `resolveInside`, and had nothing checking this URL before this
fix. That reachability, not agreement with a filesystem check it doesn't dominate anyway, is why this
fix earns its place.

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

## `resolveInside` on Okio: lexical normalization, not canonicalization

The Kotlin Multiplatform port of `RepoDownloader` (`ferry`'s Task 4) moved `resolveInside` off
`java.io.File.canonicalPath` onto `okio.Path.normalized()`. Okio can only canonicalize a path that
already exists, and most of what `resolveInside` guards — a staging directory, a not-yet-committed
target, a repo id's own marker slot — does not exist yet at the point it is checked, so canonicalizing
was never available as a like-for-like replacement.

`.normalized()` resolves `".."` and redundant separators exactly the way `canonicalPath` did, so every
`resolveInside` test in `RepoDownloaderTest` — including the escape, collision, and reserved-namespace
cases in the `..` entry above — still passes unchanged. `resolveInside` also gained an explicit
`relative.startsWith("/")` guard as part of the same port: `parent / "/abs"` in okio drops `parent`
entirely and returns the absolute path alone, which could otherwise happen to sit lexically inside
`root` and pass the `startsWith("$root/")` check — `RepoDownloaderTest`'s
`a repo id that is an absolute path is refused rather than resolved against root` (and its
`abandonStaging` sibling) pin this closed.

What normalization does *not* do, and canonicalization did, is resolve a symlink before the
strictly-inside comparison: a symlink planted inside `parent` that points outside it is no longer
caught here.

**Accepted, not fixed, for the same reason the `..` residual above is accepted.** Reaching this gap
requires something that can already create a symlink inside a Ferry-managed tree — `into`, its
`.staging`, or its `.ferry` shadow tree — all of which are directories Ferry itself created under an
app-private location the host app already fully controls. Anything with write access there could
already write, delete, or replace real content directly; using a symlink to redirect a subsequent
`resolveInside` call buys it nothing it did not already have. No `RepoDownloaderTest` case plants a
symlink for this reason: the residual is the same shape as `..`-cancellation above, not a new one.

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

## Ollama's `config` blob is never fetched

`Ollama.manifest` deliberately excludes the manifest's `config` entry from `RepoManifest.files` —
see `Ollama.kt`'s own KDoc for the structural argument (`config` describes the layers; `layers` is
the content). That argument is about what Ollama's `config` shape is *for*, not a guarantee about
what every model's `config` blob will ever contain.

**Condition:** a future Ollama model puts something in `config` that a consumer of the downloaded
files actually needs — not observed in any manifest this adapter was built against, but nothing in
the OCI manifest format rules it out either.

**Consequence:** Ferry has no way to surface it. `config`'s own `mediaType`, `size` and `digest` are
not parsed at all (`ignoreUnknownKeys` drops the field), so today there is no field anywhere to read
it from, let alone fetch it — not a silent truncation of something this adapter tried to list, but a
category of content it never lists in the first place.

## Ollama repo ids outside `library/` are unverified

`Ollama.manifest` builds `/v2/{namespace}/{name}/manifests/{tag}` for any `repoId`, defaulting the
namespace to `library` only when `repoId` carries none of its own — see `Ollama`'s own KDoc for the
full mapping. Every fact this adapter is built against was verified live against `library/`-namespaced
models only (`llava`, `qwen2.5`, `nomic-embed-text`, `llama3.2-vision`); a user-published model under
its own namespace was never fetched live.

**Condition:** a `repoId` of the form `namespace/name[:tag]` where `namespace` is not `library`.

**Consequence, if the assumption is wrong:** unknown — the URL shape follows the same Docker-reference
convention Ollama documents for `ollama pull namespace/name`, so this is expected to work unchanged,
but "expected" is not "observed," which is the bar every other fact in this file was held to.

## A registry response shaped like a manifest list parses as an empty manifest, not a refusal

`Ollama.manifest` requests `application/vnd.docker.distribution.manifest.v2+json` specifically to get
back a single image manifest (`layers: [...]`) rather than a manifest list / OCI image index
(`manifests: [...]`, for multi-architecture images) — never observed live against `registry.ollama.ai`
today, but not something the `Accept` header can force a compliant-but-different server, or a proxy in
between, to honour. `ManifestResponse.layers` defaults to an empty list the same way `ModelScope`'s
`Data.Files` does, so a response shaped like a manifest list parses without error and yields zero
files — `Result.success` with an empty `RepoManifest`, not `Result.failure`.

**Condition:** something between this adapter and `registry.ollama.ai` answers with a manifest list
despite the `Accept` header — an API version change, or a proxy that does not forward it.

**Consequence:** `Ollama.manifest` alone returns a "successful" empty manifest. `RepoDownloader.download`
already refuses this one layer up ("no files listed for $repoId" — see its own comment), so the one
path this library ships is not exposed; a caller that calls `Ollama.manifest` directly — the way
`SpaceCheck` is also called directly by a preflighting host — would see an empty manifest rather than a
distinguishable error.

## The filter-identity guarantee holds per verbatim repo-id spelling, not per resolved directory

`resolveInside` normalizes a repo id before resolving it against `into`, so alias spellings —
`"o/m"`, `"o/m/"`, `"o//m"`, `"o/./m"` — all resolve to the same `target` directory. The
filter-identity gate (the marker check before the manifest fetch — see `download`'s own comment)
does not: it compares the marker's content against the literal `repoId` string of *this* call,
never a normalized form.

**Condition:** a repo committed once under one spelling — say `"o/m"`, with some `fileFilter` —
then requested again under a different spelling that resolves to the same directory — `"o/m/"`,
`"o//m"`, `"o/./m"` — with a different filter (or none).

**Consequence:** the gate's prefix test never matches a differently-spelled `repoId`, so the
same-spelling refusal it exists to produce does not fire, and the request falls through to the
structural cache-hit check below (`selected.isSatisfiedBy(target)`), which can say yes to content
committed under a different filter. **No data-loss path**: this is a cache-hit gate, not the
commit path. On a miss, the commit-time gate re-checks the marker against this call's own literal
`repoId` and still refuses rather than deletes.

**Why this stays open:** the comparison is verbatim by design — a marker written under an alias
spelling by an earlier version was written with that literal text, and normalizing the comparison
now would judge it against a rule that did not exist when it was written, with no way to migrate
what is already on disk to match.

**Mitigation:** none from Ferry. A caller consistent about which spelling it uses for a given
`into` never reaches this; one that might pass aliased spellings for the same logical repo should
canonicalize its own repo id before calling `download`.
