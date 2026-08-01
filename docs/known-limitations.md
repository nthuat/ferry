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

## A file declared with size 0 is verified by nothing

`RepoDownloader` guards its size check with `remote.sizeBytes > 0` so a hub that omits sizes is not
broken by it. `isSatisfiedBy` has no such guard. Two consequences for a hub that publishes an explicit
zero: a non-empty body is accepted on download and then cache-misses forever, and a genuinely empty
file with no published `sha256` is committed unverified.

Neither HuggingFace nor ModelScope publishes zero sizes for real files.

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

## Closed: a repo id containing `..` retargeting the request to an arbitrary path

Was: both `HuggingFace` and `ModelScope` build their listing and download URLs from a caller-supplied
`repoId` via `HttpUrl.Builder.addPathSegments`, which resolves a `.` or `..` path segment by popping
the segment before it rather than rejecting it or encoding it as literal text. A `repoId` of
`"../../etc/passwd"` popped `api/models` (or, partially, `api/v1/models`) off the path, retargeting
the request to `{baseUrl}/etc/passwd/...` instead of staying inside the models namespace — bounded to
the hub's own origin, but able to aim a `GET` at any path under it, not only ones inside that
namespace.

**Closed by a structural check on the output, not the input.** Each adapter now asserts, on the built
`HttpUrl` and before the request is issued, that `pathSegments` still starts with the adapter's own
literal namespace prefix (`api/models` for `HuggingFace`, `api/v1/models` for `ModelScope`) —
`HuggingFace.requireWithinNamespace` / `ModelScope.requireWithinNamespace`. If it does not, the
request is never sent and `manifest` returns `Result.failure`.

Applied at every URL site where a fixed adapter-owned prefix actually precedes `repoId` in the
builder chain: `HuggingFace`'s tree-listing URL, and both of `ModelScope`'s URLs (listing and
download — both build `api/v1/models` before `repoId`, so both carry the same exposure and the same
fix). **Not applied** to `HuggingFace`'s own download (`resolve/main`) URL: there, `repoId` is the
*first* thing appended to `baseUrl`, with no fixed literal prefix ahead of it for `..` to pop —
confirmed directly against okhttp 4.12.0, `"../../etc/passwd"` resolves there to
`{baseUrl}/etc/passwd/resolve/main/{path}`, never escaping outside a `{repoId}/resolve/main/{path}`
shape, because `resolve/main` and the file path are pushed by later, independent
`addPathSegments` calls that nothing processed earlier can reach back and pop. There is no prefix
there to assert; see the KDoc on `HuggingFace`'s private `downloadUrl` for the full argument.

**The original reasoning for not fixing this conflated two different kinds of check.** The `?`/`&`/`#`
half of that reasoning still stands, unchanged, in both adapters' KDoc: a client-side shape check on
`repoId` text is a denylist, and the hub alone is the authority on which ids are valid, so rejecting a
character up front goes stale the moment the hub widens its own rules. That argument is sound for
`?`/`&`/`#` — there genuinely is no way to check them except by inspecting `repoId`'s own text — and it
was wrong to extend to `..`, because `..` was never an input-shape question. `HttpUrl.Builder` doesn't
reject a request over `..`; it silently *resolves* it, popping real segments the way it always does.
The fix available for that isn't a better denylist, it's a check on what the builder produced: does
the URL this code built still point at the namespace this code meant it to. That says nothing about
which `repoId`s are legal — it cannot go stale as the hub's id rules evolve, because it was never a
claim about `repoId` at all.

**Residual:** a `repoId` containing `..` that resolves to a *legitimate*-looking path still inside the
namespace (vanishingly unlikely in practice, and would need to reconstruct a real two-or-more-segment
id via cancellation) is not distinguished from an ordinary id — this check only catches an escape from
the namespace, not a same-namespace collision. Not treated as a gap worth closing: it is exactly as
reachable, and exactly as consequential, as a caller directly passing that reconstructed id in the
first place.

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
