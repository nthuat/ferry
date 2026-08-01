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
it; the error message says to remove the directory. Neither HuggingFace nor ModelScope publishes a
file named `.ferry`.

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
