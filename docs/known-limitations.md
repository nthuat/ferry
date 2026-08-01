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

## A repo id containing `..` can retarget the request to an arbitrary path on the hub's origin

Both `HuggingFace` and `ModelScope` build their listing and download URLs from a caller-supplied
`repoId`, and OkHttp resolves `.`/`..` path segments the same way during `HttpUrl` string parsing
(`HuggingFace`) as it does during `HttpUrl.Builder.addPathSegments` (`ModelScope`). A `repoId` of
`"../../etc/passwd"` pops `api/models` or `api/v1/models` off the path entirely, retargeting the
request to `{baseUrl}/etc/passwd/...` instead of failing or staying inside the models namespace.

**Condition:** a `repoId` containing `..` segments, from any caller of either adapter. This predates
ModelScope — `HuggingFace` has always canonicalized `..` this way during its own URL parsing — and
affects both adapters equally; `ModelScope`'s `HttpUrl.Builder`-based construction neither introduces
nor worsens it relative to `HuggingFace`'s string interpolation.

**Consequence:** bounded to the hub's own origin — this cannot redirect the request to a different
host, only to a different path on the one the caller already configured as `baseUrl`, so it is not a
same-origin escalation. It does mean a caller (or a `repoId` sourced from somewhere less trusted than
the caller itself) can aim a `GET` at any path under that origin, not only ones inside the models
namespace.

**Not fixed here:** no `repoId`-shape check was added for this, or for `?`/`&`/`#` (see
`ModelScope.manifest`'s KDoc). Both fall out of the same reasoning: a client-side shape check on repo
ids is a denylist, and the hub alone is the authority on which ids are valid — a denylist goes stale
the moment the hub's own id rules change, and fails closed on a legitimate id rather than deferring to
the hub that actually knows. `..` is not a separate, narrower case; it is the same problem, made
acceptable to defer specifically because the traversal it enables is bounded to the hub's own origin
rather than reaching anywhere else.
