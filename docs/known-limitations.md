# Known limitations

Findings that were identified, understood, and deliberately not fixed. Each names the condition that
makes it reachable, so the next person can tell whether their change makes it worse.

This exists because the review record that produced it lives in a git-ignored scratch directory that
gets deleted. A limitation nobody wrote down is a limitation nobody knows they inherited.

## One repo id can delete another, in one specific order

Commit `owner/model`, then commit `owner`, then re-download `owner` after a cache miss. `owner`'s
target directory *contains* `owner/model`, its own `.ferry` marker matches, so the commit step's
`deleteRecursively()` takes the inner repo with it — and returns `Result.success`.

The reverse order is refused: an outer directory Ferry did not write has no marker, so it is not
deleted. That half is tested (`an outer repo id is refused when the inner repo was committed first`).

**Why it is not fixed:** unreachable through the shipped HuggingFace adapter. HuggingFace serves
models and owners from one namespace, so a bare id that resolves is a canonical model and no owner
shares the name — checked live: `Qwen`, `openai` and `google` all return 401, while `gpt2` redirects
to `openai-community/gpt2`. The precondition cannot be constructed, not merely is unlikely.

**Revisit before:** adding any second hub. ModelScope's namespace rules are different and unverified.
Also reachable without a prefix id at all if a caller ever passes an `into` directory that sits inside
another call's committed target.

**Known fix:** refuse the delete when any `.ferry` exists strictly below `target`.

## The next-page matcher is not anchored to the `rel=` attribute

`NEXT_REL` is matched with `containsMatchIn` against a whole Link segment including its URL, so a
`prev` link whose own URL carries an ordinary `?rel=next` query parameter is misidentified as the
next page.

**Bounded by:** the extracted URL still has to clear the scheme+host+port origin check, so this can
at most cause an out-of-turn same-origin fetch. A hostile same-origin hub already has that capability
for free by putting a genuine `rel="next"` on any URL it likes, so nothing new is granted.

**Known fix:** anchor the match to a `;`-or-segment-start boundary.

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

**Cheap partial fix:** assert in `checkEmbeddable` that the `api` configuration contains okhttp and
coroutines.
