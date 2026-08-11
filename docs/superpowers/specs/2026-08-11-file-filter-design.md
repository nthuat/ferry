# Ferry File Filter — Design Spec

**Date:** 2026-08-11
**Status:** Revision 2 — reworked against maintainer review on PR #25 (10 findings).
Implementation by ferry maintainer.
**Driver:** Bothy (an on-device LLM chat app, ferry's consumer), adding GGUF
model support via llama.cpp. GGUF repositories on HuggingFace ship many
quantisation variants of the same model in one repo — e.g.
`bartowski/Qwen2.5-1.5B-Instruct-GGUF` contains Q2_K, Q3_K_S/M/L, Q4_K_S/M,
Q5_K_S/M, Q6_K, Q8_0 and more, often 10–30 GB total — of which a user wants
exactly one, typically ~1 GB. `RepoDownloader.download()` is whole-repo only
today; Bothy's GGUF support is blocked on ferry until a subset download
exists.

## Goal

Let a caller select a subset of a repo's files to download, while keeping
every one of ferry's existing guarantees (resume, verification, atomic
commit, space preflight) correct *for that subset* — and keep every existing
call site compiling **and every already-published binary linking**.

## Non-goals

- Per-file parallelism changes.
- New hub implementations.
- Any API redesign beyond the filter parameter itself. `abandonStaging` and
  `stagedBytes` keep their current signatures — see "What does not change"
  for the one semantic widening filter-keyed staging forces on them.
- `:ferry-work` changes. The design below is chosen specifically so
  `:ferry-work` needs none, published or rebuilt.

## The API change

Two overloads, not one function with an inserted parameter:

```kotlin
// Unchanged. Same source signature, same JVM descriptor, same synthetic
// `download$default` bridge as 0.2.0. One line, delegating.
suspend fun download(
    repoId: String,
    into: Path,
    onProgress: (RepoProgress) -> Unit = {},
): Result<Path> = download(repoId, into, fileFilter = null, onProgress = onProgress)

// New. `fileFilter` deliberately has NO default value — see below.
suspend fun download(
    repoId: String,
    into: Path,
    fileFilter: Regex?,
    onProgress: (RepoProgress) -> Unit = {},
): Result<Path>
```

The earlier revision of this spec inserted `fileFilter` into the existing
function with a `null` default. That is source-compatible but **not**
binary-compatible, and the published `dev.thuat:ferry-work:0.2.0` is a
binary caller. See "Binary compatibility" below for the full evaluation and
why the extra overload is the cheapest correct answer.

**`fileFilter` must not be given a default.** That absence is what makes
overload resolution unambiguous in every call shape, rather than something
to verify case by case:

| Call | Resolves to | Why the other candidate is inapplicable |
|---|---|---|
| `download(id, into)` | 3-arg | 4-arg needs `fileFilter`, which has no default |
| `download(id, into) { … }` | 3-arg | trailing lambda is not a `Regex?` |
| `download(id, into, onProgress = { … })` | 3-arg | 4-arg needs `fileFilter` |
| `download(id, into, Regex("x"))` | 4-arg | `Regex` is not a `(RepoProgress) -> Unit` |
| `download(id, into, Regex("x")) { … }` | 4-arg | 3-arg takes no fourth argument |
| `download(id, into, null)` | 4-arg | `(RepoProgress) -> Unit` is not nullable |

Every existing call — including the trailing-lambda form
`ferry.download(repoId, into) { progress -> ... }` used throughout the README
and sample app — keeps compiling unchanged, and now also keeps *linking*
unchanged. `fileFilter == null` takes the exact code path it takes today;
there is a single `null` sentinel, not a default `{ true }` lambda, so an
unfiltered call never allocates a predicate and nothing downstream has to
branch on "was a real filter passed."

Matching: `fileFilter.containsMatchIn(remoteFile.path)`, against the file's
manifest-declared path (e.g. `"Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"`). A
pattern wanting a whole-path match anchors itself (`^...$`); the default is
substring, so `Regex("Q4_K_M")` is enough for the common case and callers
don't need to escape `.gguf` or think about path separators.

### Predicate vs. pattern — and why this picks a pattern

The obvious alternative is `fileFilter: (RemoteFile) -> Boolean` — a bare
Kotlin predicate. It's more flexible in the abstract and needs no new type.
It was rejected for one concrete reason, not a style preference: **ferry
needs the filter to double as a stable identity**, and a closure can't
provide that.

The "Filter identity" section below is why. Ferry has to be able to tell
"same selection as last time" from "a different one," recorded on disk,
compared across process restarts. A `(RemoteFile) -> Boolean` lambda has no
such identity: two semantically-identical predicates built from separately
written code are not `==`, and there's no way to derive a stable string from
an arbitrary closure. The only way to make a predicate parameter safe here is
to *also* require a separately supplied identity string — two parameters kept
in sync by every caller, with nothing stopping `fileFilter = {
it.path.contains("Q5_K_M") }` paired with `filterId = "Q4_K_M"` by a
copy-paste mistake.

`Regex` sidesteps this: a `Regex`'s pattern *and* its option set are already
stable, comparable, serializable values — no second parameter, no duplicated
source of truth. It's also plain `kotlin.text.Regex`: fully common-source, no
new dependency, no custom glob engine to write. And Bothy's catalog is JSON:
a catalog entry storing `"variant": "Q4_K_M"` needs zero translation to
become `Regex("Q4_K_M")` at the call site.

A plain glob string (`*Q4_K_M.gguf`) was also considered and rejected only
for costing more than `Regex` to reach the same result: KMP has no built-in
glob matcher (`java.nio.file.PathMatcher` is JVM-only), so supporting it
means writing and testing a glob-to-regex translator in `commonMain` for
expressiveness `Regex` already has natively.

## Filter identity — one definition, two consumers

Everything below (the staging directory name, the commit marker) is keyed by
one derived value. Define it once:

```kotlin
/** Injective over (pattern, options): the length prefix delimits the pattern exactly. */
private fun canonicalIdentity(filter: Regex): String =
    "${filter.pattern.length}:${filter.pattern}" +
        filter.options.map { it.name }.sorted().joinToString(",")

/** "" for the unfiltered case; 64 lowercase hex characters otherwise. */
private fun filterKey(filter: Regex?): String =
    filter?.let { canonicalIdentity(it).encodeUtf8().sha256().hex() } ?: ""
```

**Options are part of the identity, not ignored and not rejected.**
`regex.pattern` alone is an incomplete identity: `Regex("q4_k_m",
RegexOption.IGNORE_CASE)` and `Regex("q4_k_m")` select different files and
have identical `.pattern`. Two genuinely different filters comparing equal is
how the commit gate below would come to `deleteRecursively(target)` a
directory committed under a different selection. Rejecting non-empty options
instead was considered and dropped: `IGNORE_CASE` is a reasonable thing to
want against hub filenames whose casing is not stable, and including options
costs one `map/sorted/joinToString`.

`Regex.options: Set<RegexOption>` and `Enum.name` are both common-source, so
this compiles for every target `:ferry` builds. Note that only `IGNORE_CASE`
and `MULTILINE` are common `RegexOption` entries — `LITERAL`, `UNIX_LINES`,
`COMMENTS`, `DOT_MATCHES_ALL`, `CANON_EQ` are JVM-only enum entries. That
costs nothing here: identity is only ever compared against a value derived on
the same device by the same platform, and `.name` serializes whatever entries
that platform actually has.

**Why hash rather than embed the pattern.** The same identity has to appear
in a filesystem *path segment*, where a raw pattern cannot go — it may
contain `/`, `\n`, arbitrary length, and case that a case-insensitive
filesystem would fold together. A fixed 64-character lowercase-hex digest is
usable as a path segment, as a fixed-shape marker field, and needs no
escaping scheme. `okio.ByteString.encodeUtf8().sha256().hex()` is already
available in `commonMain` via okio, a dependency `:ferry` already has;
`Sha256.of` is not reused because it hashes a file, not a string.

`canonicalIdentity` is never parsed, only hashed, so it needs injectivity and
nothing else — which the length prefix gives it outright.

## Semantics that must not break

`selected` below means `manifest.copy(files = manifest.files.filter {
fileFilter == null || fileFilter.containsMatchIn(it.path) })` — computed
once, immediately after the existing empty-manifest guard
(`RepoDownloader.kt:184`), and used for every downstream decision (space
check, `satisfiedPaths`, `creditingStaged`, download loop, `pruneOrphans`,
the pre-commit re-verification, the cache-hit check) in place of `manifest`.
`manifest` itself is only consulted again for `repoId` and, where useful, for
messages naming the hub's full listing.

### Where the filter identity is checked — the ordering is the fix

This is the spec's core guarantee, and the earlier revision put the check in
a place where it could not deliver it. `download()`'s body must run in this
order:

| # | Step | Notes |
|---|---|---|
| 1 | resolve `stagingRoot`, `stagingDir`, `target`, `markerRoot`, `markerDir`; reserved-namespace collision checks | `stagingDir` is now filter-keyed — see below |
| 2 | **filter-identity gate on `target`** | **new; before everything else** |
| 3 | `repo.manifest(repoId)` | first network request |
| 4 | empty-manifest guard; compute `selected`; empty-match guard | |
| 5 | cache-hit check, against `selected` | `RepoDownloader.kt:241` today |
| 6 | `spaceCheck.check(...)`, against `selected` | `:272-279` today |
| 7 | `createDirectories(stagingDir)`; `pruneOrphans(stagingDir, selected)` | |
| 8 | download loop over `selected.files` | |
| 9 | write marker into staging (last, unchanged position) | |
| 10 | existing "target exists" gate, nested check, `deleteRecursively` | `:379-421` today |
| 11 | pre-commit re-verification against `selected`; `atomicMove` | |

**Step 2 in full.** If `target` exists, `target / MARKER_FILE` is a regular
file, and its content `c` satisfies both

- `c != markerContent(repoId, filterKey)` — not this call's own identity, and
- `c == repoId || c.startsWith("$repoId\n")` — but recognisably this repo id,

refuse immediately:

> `$target was committed by Ferry under '$repoId' with a different file
> filter; refusing to replace it — remove the directory to retry`

Three properties this ordering buys, each of which the earlier revision
lacked:

- **It closes the cache-hit bypass (review finding 1).** The old design put
  the filter check only on the commit-time marker gate at `:379`, which runs
  *after* the cache-hit check at `:241`. A 22 GB unfiltered commit
  structurally satisfies `Regex("Q4_K_M")` — every selected file is present
  and verified — so `download(repoId, into, Regex("Q4_K_M"))` returned
  success pointing at a directory of 15 unselected GGUF variants. The earlier
  revision's justifying parenthetical ("there are no *other* files once
  filtering is in effect") is simply false for exactly the repos this feature
  exists for. With the gate at step 2 the mismatch is refused before the
  cache-hit check ever runs.
- **It refuses before spending anything (finding 5).** A mismatch knowable
  from one marker read was previously reported only after the entire filtered
  selection had been transferred and verified — in a library whose own
  comment at `:246` calls spending a user's data to discover a knowable
  failure "the failure this library exists to prevent."
- **It refuses before any network request at all (finding 10).** Step 2 needs
  only `repoId`, `into` and the filter identity — no manifest — so it sits
  above `repo.manifest()`, not below it. The earlier revision claimed "before
  any network request" for a check it described *after* the manifest fetch.

**Step 2 deliberately does not fire on an absent or foreign marker.** No
marker, or a marker naming a different `repoId`, falls straight through to
today's behavior: the cache-hit check may still hit, and the commit-time gate
at step 10 still produces today's foreign-directory refusal, unchanged. This
is not tidiness — moving the foreign-directory refusal earlier would change
what `a directory at the target path that ferry did not write is refused -
not deleted` and the cache-hit tests observe, and this spec's regression bar
is that the existing suite passes untouched.

**Step 10 keeps its own check, now against the full expected marker content.**
Step 2 is not a replacement for it: `target` can come into existence between
step 2 and step 11. Step 10 compares the whole marker string, as it does
today, against `markerContent(repoId, filterKey)`.

### Commit marker format — written, compared, never parsed

```kotlin
private fun markerContent(repoId: String, filterKey: String): String =
    if (filterKey.isEmpty()) repoId else "$repoId\n$filterKey"
```

- The **unfiltered** writer emits exactly `repoId`, byte for byte what every
  version of ferry has ever written — **no trailing separator**. That is what
  makes the promised "no migration" true rather than aspirational: a
  directory committed by a pre-filter ferry reads back equal to what an
  unfiltered call expects today, so it stays a valid cache hit and a valid
  commit target.
- The **filtered** writer emits `repoId`, one `\n`, and exactly 64 lowercase
  hex characters.
- **There is no parse.** The accept/reject decision is whole-string equality
  against `markerContent(repoId, filterKey)` — the same shape of comparison
  `:384` already performs. Review finding 9 is right that both `repoId` and a
  pattern may legally contain `\n` (`resolveInside` permits it in a repo id;
  a raw-string or `(?x)` pattern can carry one), and that a line-split parse
  would misattribute fields. No field is ever extracted, so there is nothing
  to misattribute. The hash is what makes the pattern's own newlines
  irrelevant: they never reach the file.
- **The prefix test at step 2 chooses the error message, never the verdict.**
  `content == repoId || content.startsWith("$repoId\n")` distinguishes "mine,
  different filter" from "not mine" for wording purposes only. A pathological
  repo id could in principle make that classification pick the wrong *message*
  — it cannot make it accept anything, because acceptance is the exact
  equality above.

### Staging is keyed by filter identity

This replaces the `.ferry-filter` sidecar the earlier revision specified. The
sidecar is gone from this design entirely — it is not retained as an
alternative-considered, because nothing in the implementation needs to know
it was ever proposed.

```kotlin
private fun stagingDirFor(stagingRoot: Path, repoId: String, filterKey: String): Path {
    resolveInside(stagingRoot, repoId)   // unchanged validation
    val suffix = if (filterKey.isEmpty()) STAGING_SUFFIX else "$STAGING_SUFFIX-$filterKey"
    return resolveInside(stagingRoot, "${repoId.trimEnd('/')}$suffix")
}
```

So `owner/model` stages at `into/.staging/owner/model.d` unfiltered — the
exact path it uses today — and at `into/.staging/owner/model.d-<64 hex>` for
each distinct filter. Every filter, including the absence of one, gets its
own private scratch directory.

**Appended to the existing last segment, not added as a new path segment.**
The review suggested "a filter-hash segment"; the substance is adopted but
the shape is not. `into/.staging/owner/model.d/<hash>` would make the
unfiltered staging directory a literal *ancestor* of every filtered one —
reintroducing exactly the Critical `stagingDirFor`'s own KDoc documents
closing: `pruneOrphans` walking the ancestor walks straight into a
descendant's live `.part` files and deletes them as orphans of a manifest
that was never theirs, and `stagedBytes` sums them. As siblings, no filter's
staging directory is inside any other's, and every existing recursive
operation stays scoped to exactly one filter's bytes.

**What this dissolves.** Four of the review's findings were properties of the
sidecar and have no counterpart here:

- *Finding 2 (absent-sidecar semantics; upgrade destroys staged progress).*
  There is no sidecar to be absent. Staging left by a pre-filter ferry sits
  at `<id>.d`, which is precisely the path an unfiltered call resolves, so it
  resumes normally; a filtered call resolves elsewhere and cannot touch it.
  No "absent means what?" default to get wrong in either direction.
- *Finding 3 (sidecar written first, forgeable and brickable).* Identity now
  lives in the directory's *name*, which no manifest entry can address:
  `resolveInside(stagingDir, remote.path)` is confined to `stagingDir`'s own
  subtree and cannot name `stagingDir` itself. Nothing has to be written
  before the download loop, so `MARKER_FILE`'s write-last rule stays the only
  ordering rule in the file.
- *Finding 6 (sidecar rides the atomic commit into the published repo).* No
  file is added to staging, so `atomicMove` publishes exactly what it
  publishes today. `pruneOrphans` needs no exclusion list.
- *Finding 8 (`stagedBytes` counts the sidecar as resumable payload).*
  Staging holds only payload and, in the narrow pre-rename window,
  `MARKER_FILE` — which `stagedBytes` already excludes at `:604-609`. The
  drift `stagedBytes ignores the ownership marker written just before commit`
  exists to prevent never appears.

**And it is better behaved than a refusal.** Switching filters mid-flight no
longer destroys anything: `download(id, into, filterA)` interrupted, then
`download(id, into, filterB)`, leaves `filterA`'s bytes untouched in
`filterA`'s own directory, and a later call with `filterA` resumes them. The
earlier revision's refuse-and-require-`abandonStaging` protocol is deleted
along with its error message; there is nothing left to refuse.

### `abandonStaging` and `stagedBytes` under filter-keyed staging

Both take `(repoId, into)` with no filter, and both keep those signatures.
Both must therefore sweep every filter's staging for that repo id — a single
filter's worth would be a `stagedBytes` that under-reports and, far worse, an
`abandonStaging` that silently leaks whatever it did not resolve.

The sweep lists the parent of the unfiltered staging path and takes every
entry whose name is either `"$last$STAGING_SUFFIX"` or
`"$last$STAGING_SUFFIX-"` followed by **exactly 64 lowercase hex
characters**, where `last` is `repoId`'s trimmed final segment.

The hex-shape requirement is load-bearing, not decoration. A bare
`startsWith("$last$STAGING_SUFFIX-")` would match a *different* repo id's
staging: an id literally named `owner.d-x` stages at `owner.d-x.d`, which
starts with `owner.d-`. Requiring the remainder to be exactly 64 hex
characters excludes that outright and, in fact, excludes it for every
possible id: a real staging directory name always ends in `STAGING_SUFFIX`
(`.d`), and `.` is not a hex character, so no other id's staging directory
can ever satisfy the test. Unlike `stagingDirFor`'s documented
deliberately-constructed-collision residual, this one has no residual at all.

`abandonStaging`'s contract text ("wipes all staging for this repo id,
regardless of which filter produced it") is unchanged and now literally true
rather than true by there only being one directory.

`stagedBytes` gains an imprecision worth stating rather than hiding: for a
repo id with two filters staged, it returns their **union**, not the number a
particular filtered `download` would actually reuse. Its KDoc already frames
the result as "a hint for what to show, not a promise of what `download` will
actually transfer next," and this stays inside that frame — but it is a real
loss of precision, and per-filter granularity would need a `fileFilter`
parameter this spec does not add. Left open; see "What this does not
resolve."

### Empty match fails loudly

The existing guard (`manifest.files.isEmpty()` → `Result.failure("no files
listed for $repoId")`) catches an empty *upstream* listing. It does not, on
its own, catch a filter that matched nothing — `selected.files` can be empty
even when `manifest.files` is not. Add a second guard immediately after
computing `selected`, before any staging or space-check work:
`selected.files.isEmpty()` → `Result.failure("no files matched the filter for
$repoId")`. Same shape as the existing one, same reason: every downstream
check is "every file is correct," and zero files is trivially correct —
exactly what must not commit.

### Space preflight sizes only the selected files

`spaceCheck.check(...)`, `satisfiedPaths`, and `creditingStaged` must all run
against `selected`, not `manifest`. This falls out for free once every
reference in `download()`'s body is switched — there is no separate "size the
filter" step to add. Get it wrong and a 30 GB repo refuses a 1 GB filtered
download for lack of space that was never going to be spent.

### Resume with the same filter

Unchanged, and now trivially so: an identical filter produces an identical
`filterKey`, hence the identical staging directory, so `satisfiedPaths` and
`remainingBytes` skip exactly the files they skip today. "Identical" means
same `.pattern` *and* same option set — two distinct `Regex` instances built
from the same pattern and options share staging; the same pattern with
different options does not, and must not.

### Compatibility

Nothing to migrate, in both directions:

- **Committed directories.** A marker written by a pre-filter ferry contains
  bare `repoId`, which is byte-identical to what an unfiltered call writes and
  expects going forward. Already-downloaded repos stay valid cache hits and
  valid commit targets.
- **Staging.** A pre-filter staging directory sits at `<id>.d`, the
  unfiltered path, and resumes normally.
- **Binaries.** See the next section.

A *filtered* call against a directory committed unfiltered is refused, by
design — that is finding 1's fix, and it is a real behavior the consumer must
plan for. See "What this does not resolve."

## Binary compatibility

The earlier revision's claim that this "is not a breaking release" was wrong
for the reason the review gives, and relabelling the release would have been
the wrong fix.

**The break.** `download(repoId, into) { … }` supplies all three parameters,
so it compiles to a direct call on the full-arity descriptor
`download(String, Path, Function1, Continuation)` — not through
`download$default`. Inserting `fileFilter` before `onProgress` changes that
descriptor to `download(String, Path, Regex, Function1, Continuation)` and
changes the synthetic bridge alongside it. Published
`dev.thuat:ferry-work:0.2.0` contains exactly such a call
(`RepoDownloadWorker.kt:176`), compiled against `:ferry` 0.2.0.

**Why that is worse than an ordinary breaking change.** It fails at *runtime*,
not at build time. An app declaring `ferry-work:0.2.0` and `ferry:0.3.0`
resolves cleanly — Gradle picks the higher `ferry` version and `ferry-work`
0.2.0 stays put — builds green, ships, and throws `NoSuchMethodError` the
first time a user starts a background download. The 0.2.0 CHANGELOG entry
documents this coupling precisely because it bit this project once already.

**Three options evaluated.**

1. **`@JvmOverloads` on the 4-parameter function.** Does not fix it.
   `@JvmOverloads` generates *prefix truncations*: `(String, Path)`,
   `(String, Path, Regex)`, and the full `(String, Path, Regex, Function1)`.
   The descriptor ferry-work 0.2.0 actually calls — `(String, Path,
   Function1)` — is not a prefix of the new parameter list and is not among
   them. It is also a JVM-only mechanism bolted onto a KMP library's common
   API. Rejected: it does not solve the problem it would be added for.
2. **Accept a breaking release.** Requires `:ferry-work` bumped and
   republished in lockstep, plus a CHANGELOG breaking-change entry — and,
   because the failure mode is runtime-only, the entry is the *only* thing
   standing between a consumer and a crash in production. Rejected as
   disproportionate: the break buys nothing except one fewer overload.
3. **Separate overload, adopted.** Keep the 3-parameter `download` exactly
   as it is and add the filtered form beside it, the old one delegating in a
   single line. The old full-arity descriptor and its `download$default`
   bridge are preserved verbatim; the new overload's own descriptor and
   bridge differ in parameter list, so there is no platform declaration
   clash. Cost: one delegating line and one extra entry in the public API.

**Consequences, stated explicitly.**

- **`:ferry-work` needs no change and no republish.** `0.2.0` keeps linking
  and keeps working against the filter release. It cannot *pass* a filter —
  `RepoDownloadWorker` has no input key for one — so background filtered
  downloads remain unavailable until a future `:ferry-work` release adds one.
  That is a missing feature, not a break, and it is out of scope here.
- **The CHANGELOG entry says "additive, not breaking" and says why**: the
  3-argument `download` keeps its exact JVM descriptor and synthetic
  `download$default` bridge, so unlike 0.2.0 this release does *not* force a
  lockstep `:ferry-work` bump. Naming the mechanism matters — "additive"
  unqualified is what the earlier revision asserted while the change was in
  fact binary-breaking.
- The README's existing note reserving the right to break sealed interfaces
  before 1.0 is not being spent here.

## Progress reporting

`RepoProgress.Downloading.fileIndex` / `.fileCount`, and the same fields on
`Skipped`, must be relative to `selected.files`, not `manifest.files`. This
isn't a separate feature: once the loop iterates
`selected.files.forEachIndexed { … }`, the counts are correct by
construction. A user who filtered a 27-file repo down to 3 files must see
"file 1 of 3," never "file 14 of 27" for the one file that survived
filtering.

## Testing requirements

All of these run on both `jvmTest` and `iosX64Test`, against `MockEngine` and
`FakeFileSystem`, extending `RepoDownloaderTest`'s existing style
(backtick-named functions, one behavior per test) — **except the last, which
is `jvmTest`-only and says so.** Every test below has been re-checked against
the design above; the earlier revision listed one that its own design could
not satisfy.

1. A filter matching some but not all files downloads and commits only the
   matching subset; the committed directory doesn't contain the excluded
   files.
2. A filter matching nothing fails (`Result.failure`), commits nothing, and
   makes no network request beyond the manifest fetch — extends `a manifest
   with no files is refused rather than treated as satisfied`.
3. `SpaceCheck`'s reported `requiredBytes` reflects only the filtered
   subset's total: a filter that would fail preflight against the whole repo
   succeeds when only the selected files are checked. Extends `refuses to
   start when space is insufficient`.
4. A second `download()` call with an equal filter (same `.pattern`, same
   options, need not be the same `Regex` instance) resumes and skips
   already-staged, already-verified files exactly as an unfiltered resume does
   — extends `a mostly staged download only needs the remaining bytes`.
5. Two filters differing **only** in `RegexOption` — `Regex("q4_k_m")` and
   `Regex("q4_k_m", RegexOption.IGNORE_CASE)` — resolve to different staging
   directories and do not satisfy each other's commit gate. This is the test
   `regex.pattern`-only identity fails.
6. A `download()` call with a *different* filter for the same `repoId` and
   `into`, after the first has **committed**: refuses, target untouched, error
   names the filter mismatch, and **makes no network request at all** — not
   even the manifest fetch. Satisfiable only because the gate sits at step 2;
   under the earlier revision this test could not pass in either respect.
7. A filtered `download()` against a target committed **unfiltered** is
   refused the same way — the case a broader selection structurally satisfies
   the narrower one, which the old cache-hit path returned as success.
8. A `download()` call with a *different* filter while the first's staging is
   **incomplete** proceeds normally in its own staging directory; the first
   filter's staged bytes are still present afterward, and a later call with
   the first filter resumes them rather than re-fetching. *(This replaces the
   earlier revision's "refuses before any network request, staging untouched;
   `abandonStaging` then succeeds" — that protocol no longer exists.)*
9. `abandonStaging(repoId, into)` removes the unfiltered staging directory
   **and** every filter-keyed one for that id, and touches no other id's —
   extends `abandonStaging removes only this repo's staging` and `abandoning
   staging for a prefix repo does not touch a nested repo's own staging`.
   Include an id shaped like `<other>.d-x`, which a naive prefix sweep would
   wrongly delete.
10. `stagedBytes(repoId, into)` sums across filter-keyed staging directories —
    extends `stagedBytes sums every staged file's reusable bytes together -
    touching no network`.
11. Marker compatibility: a `target/.ferry` containing exactly `repoId` and
    no trailing newline — what a pre-filter ferry wrote — is still a cache hit
    and still a valid commit target for an unfiltered call, with no migration
    step. Written by hand in the test, since no pre-filter ferry is available
    to produce it.
12. Staging compatibility: bytes staged at `into/.staging/<id>.d` are resumed
    by an unfiltered call after the upgrade.
13. `RepoProgress.Downloading`/`Skipped` counts (`fileIndex`, `fileCount`) are
    pinned against the filtered subset's size, not the full manifest's —
    extends `progress numbers each file within the repo`.
14. The full existing `RepoDownloaderTest` suite passes unchanged with no
    `fileFilter` argument supplied — the regression bar for source and
    behavior compatibility at every existing call site.
15. **`jvmTest` only:** reflection asserts that
    `RepoDownloader::class.java.getDeclaredMethod("download", String::class.java,
    Path::class.java, Function1::class.java, Continuation::class.java)`
    still exists. This is the only executable check that the descriptor
    `ferry-work:0.2.0` links against survives; it has no meaning on
    `iosX64Test` and must not be written there. A comment on it should name
    ferry-work 0.2.0 as what it protects, so a future maintainer deleting the
    3-argument overload learns why it is there.

## What does not change

- `abandonStaging(repoId, into)` — same signature, same contract; sweeps all
  of the repo id's filter-keyed staging directories, as its contract already
  said.
- `stagedBytes(repoId, into)` — same signature; same "hint, not a promise"
  contract; sums across filter-keyed staging directories, with the
  union-not-per-filter imprecision noted above.
- `MARKER_FILE`'s write-last ordering in staging, and its co-location with
  the directory it describes.
- `MARKER_ROOT` and the nested-repo shadow tree. Filtering does not interact
  with the nested-repo question at all.
- `HuggingFace` / `ModelScope` / `Ollama` — the filter applies after
  `manifest()` returns, entirely inside `RepoDownloader`. No hub adapter
  changes.
- `Ferry.huggingFace()` / `modelScope()` / `ollama()` factory signatures.
- `:ferry-work` — no source change, no republish, no lockstep bump.

## What this does not resolve

Stated here rather than left for a reader to discover:

- **A committed target holds one filter's selection at a time.** Asking for a
  different filter into the same `into` is refused and needs manual removal —
  the same remedy `docs/known-limitations.md`'s "A refused directory needs
  manual removal" already documents for every other refusal. Deliberate:
  the alternative (let a narrow filter claim a broad commit) is finding 1,
  which ends with `deleteRecursively` on a 22 GB directory at the next cache
  miss. A caller wanting two variants resident at once passes a different
  `into` per variant; this spec adds no mechanism for that. Note the concrete
  cost: a user holding an unfiltered copy who then asks for one variant gets
  a refusal, not a free cache hit, even though every selected file is
  physically present and verified.
- **`stagedBytes` has no per-filter granularity.** It returns the union
  across filters. Closing it needs a `fileFilter` parameter, which this spec
  does not add — and which would face its own binary-compatibility question,
  though a smaller one (`stagedBytes` has no published binary caller).
- **Staging accretes across filters until abandoned.** A caller that switches
  filters repeatedly and never calls `abandonStaging` keeps every filter's
  partial bytes on disk. That is the same durable-staging trade the library
  already makes, now multiplied by the number of filters tried;
  `abandonStaging` reclaims all of them in one call, and `stagedBytes` is how
  a caller notices.
- **A foreign (non-Ferry) directory that structurally satisfies the filter is
  still a cache hit.** Unchanged pre-existing behavior — the cache-hit check
  is a structural promise and never consulted a marker — and out of scope
  here. Worth knowing that filtering makes it *easier* to satisfy, since
  fewer files have to match.
- **Concurrent `download` calls for the same repo id and the same filter**
  remain the caller's problem, exactly as today. Different filters for the
  same repo id are now genuinely independent in staging, but still race each
  other at the shared `target`.

## Consumer acceptance (definition of done)

Bothy can do, for a HuggingFace GGUF repo with 15 quantisation variants
totalling 22 GB:

```kotlin
val ferry = Ferry.huggingFace()
ferry.download(
    repoId = "bartowski/Qwen2.5-1.5B-Instruct-GGUF",
    into = filesDir,
    fileFilter = Regex("Q4_K_M"),
) { progress -> ... }
```

and this refuses only if the *device* can't hold ~1 GB (not 22 GB), reports
progress as "file N of (however many files match `Q4_K_M`)," resumes cleanly
if interrupted, never returns success pointing at a directory holding the
other 14 variants, and — if the user later picks `Q5_K_M` for the same model
into the same directory — fails with a clear, actionable error, before any
network request, instead of silently deleting the `Q4_K_M` model already on
disk.

## Risks / notes for the implementer

- **The staging directory name is now derived state.** Get `filterKey`
  wrong — an unsorted option set, a different digest, a truncation — and every
  call after the change stages somewhere new, silently abandoning bytes rather
  than failing. Pin `filterKey`'s output for at least one known
  `(pattern, options)` pair in a test, so a refactor of `canonicalIdentity`
  can't quietly change where things land.
- `RemoteFile.path` is what `Regex` matches against, not the on-disk staged
  path — same value, but confirm against a repo with subdirectories (some
  GGUF repos ship `mmproj` files or split multi-part GGUFs under a
  subdirectory) so `containsMatchIn` behaves predictably against a path
  containing `/`.
- The 3-argument `download` overload exists solely for binary compatibility
  with `ferry-work:0.2.0`. It has no other reason to exist and reads like
  redundancy without that context — say so in its KDoc, next to test 15.
- Every refusal this spec adds is permanent until the caller removes a
  directory. Match the existing refusals' wording discipline: name the path,
  name the cause, name the remedy.
