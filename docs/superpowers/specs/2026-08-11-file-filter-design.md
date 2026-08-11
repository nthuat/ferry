# Ferry File Filter — Design Spec

**Date:** 2026-08-11
**Status:** Approved design — implementation by ferry maintainer
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
call site compiling and behaving identically.

## Non-goals

- Per-file parallelism changes.
- New hub implementations.
- Any API redesign beyond the filter parameter itself. `abandonStaging` and
  `stagedBytes` keep their current signatures — see "What does not change."

## The API change

```kotlin
suspend fun download(
    repoId: String,
    into: Path,
    fileFilter: Regex? = null,
    onProgress: (RepoProgress) -> Unit = {},
): Result<Path>
```

Inserted before `onProgress`, defaulted to `null` (whole repo — today's
behavior). Every existing call — including the trailing-lambda form
`ferry.download(repoId, into) { progress -> ... }` used throughout the README
and sample app — keeps compiling: Kotlin's trailing-lambda syntax binds to
the *last* parameter regardless of what defaulted parameters sit before it.
`fileFilter == null` takes the exact code path it takes today; there is a
single `null` sentinel, not a default `{ true }` lambda, so an unfiltered
call never even allocates a predicate and nothing downstream has to branch
on "was a real filter passed."

Matching: `fileFilter.containsMatchIn(remoteFile.path)`, against the file's
manifest-declared path (e.g. `"Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"`). A
pattern wanting a whole-path match anchors itself (`^...$`); the default is
substring, so `Regex("Q4_K_M")` is enough for the common case and callers
don't need to escape `.gguf` or think about path separators.

### Predicate vs. pattern — and why this picks a pattern

The obvious alternative is `fileFilter: (RemoteFile) -> Boolean` — a bare
Kotlin predicate. It's more flexible in the abstract (compose arbitrary
logic: size thresholds, multiple globs, exclusion lists) and needs no new
type. It was rejected for one concrete reason, not a style preference:
**ferry needs the filter to double as a stable identity**, and a closure
can't provide that.

The "Commit identity" section below is why. Two `download()` calls for the
same `repoId` into the same `into`, with two different selections, must not
let the second silently claim the first's committed directory — ferry has
to be able to tell "same selection as last time" from "a different one,"
recorded on disk, compared across process restarts. A `(RemoteFile) ->
Boolean` lambda has no such identity: two semantically-identical predicates
built from separately-written code are not `==`, and there's no way to
derive a stable string from an arbitrary closure. The only way to make a
predicate parameter safe here is to *also* require a second, separately
supplied identity string — two parameters that must be kept in sync by
every caller, with nothing stopping `fileFilter = { it.path.contains
("Q5_K_M") }` paired with `filterId = "Q4_K_M"` by a copy-paste mistake.

`Regex` sidesteps this: `regex.pattern` *is* the filter, and *is* already a
stable, comparable, serializable string — no second parameter, no
duplicated source of truth. It's also plain `kotlin.text.Regex`: fully
common-source, no new dependency, no custom glob engine to write
(`kotlin.text.Regex` already has actuals for both the JVM and Kotlin/Native
targets `:ferry` builds for). And it answers the prompt's other question
directly: Bothy's catalog is JSON, and a catalog entry storing `"variant":
"Q4_K_M"` needs zero translation to become `Regex("Q4_K_M")` at the call
site — the string that lives in the catalog *is* the filter, nothing in
between to drift out of sync.

A plain glob string (`*Q4_K_M.gguf`) was also considered and rejected only
for costing more than `Regex` to reach the same result: KMP has no built-in
glob matcher (`java.nio.file.PathMatcher` is JVM-only), so supporting it
means writing and testing a glob-to-regex translator in `commonMain` for
expressiveness `Regex` already has natively.

## Semantics that must not break

`selected` below means `manifest.copy(files = manifest.files.filter {
fileFilter == null || fileFilter.containsMatchIn(it.path) })` — computed
once, immediately after the existing empty-manifest guard, and used for
every downstream decision (space check, download loop, pruning, the
pre-commit re-verification, the cache-hit check) in place of `manifest`.
`manifest` itself is only consulted again for `repoId` and, where useful,
for messages naming the hub's full listing.

### Empty match fails loudly

The existing guard (`manifest.files.isEmpty()` → `Result.failure("no files
listed for $repoId")`) catches an empty *upstream* listing. It does not, on
its own, catch a filter that matched nothing — `selected.files` can be empty
even when `manifest.files` is not. Add a second guard, immediately after
computing `selected`, before any staging or space-check work:
`selected.files.isEmpty()` → `Result.failure("no files matched the filter
for $repoId")`. Same shape as the existing one, same reason: every
downstream check is "every file is correct," and zero files is trivially
correct — exactly what must not commit.

### Space preflight sizes only the selected files

`spaceCheck.check(...)` and the credit-for-already-staged-bytes computation
(`creditingStaged`) must run against `selected`, not `manifest`. This falls
out for free once every reference in `download()`'s body is switched from
`manifest` to `selected` — there's no separate "size the filter" step to
add. Get this wrong (check against the full manifest's `totalBytes`) and a
30 GB repo refuses a 1 GB filtered download for lack of space that was never
going to be spent.

### Commit identity: the filter is part of what "this directory" means

**The sharp hazard.** The commit marker at `target/.ferry` records only
`repoId`. Two `download()` calls for the same `repoId` and `into`, with two
different filters, resolve to the same `stagingDir` and the same `target`.
The first commits, writing a marker saying "Ferry committed this under
`repoId`." The second — a different filter, same repo id — reaches the
existing "target exists, check the marker" gate, the marker still says
`repoId`, the check passes, and the *existing* logic deletes what it just
decided was its own prior commit and replaces it with the second filter's
selection. No error, no refusal — the first download's content is silently
gone. This is the same "was this directory really mine" question the marker
already answers for a foreign, non-Ferry-written directory; a filter change
is a case of "mine, but not with this content," which the current marker
format can't express.

**Fix: extend the marker's identity, not the directory layout.** The marker
written into `stagingDir / MARKER_FILE` (and moved to `target` on commit)
records the filter's identity alongside `repoId` — e.g. `repoId + "\n" +
(fileFilter?.pattern ?: "")`. The "target exists" gate compares *both*
fields; a `repoId` match with a filter mismatch is refused with the same
severity and remedy as today's foreign-directory refusal:

> `$target was committed by Ferry under '$repoId' with a different file
> filter; refusing to replace it — remove the directory to retry`

This was chosen over giving each filter its own directory (folding the
pattern into an effective id and reusing the existing nested-repo-id
machinery — `MARKER_ROOT` — that already lets `"owner"` and `"owner/model"`
coexist as unrelated ids). Nesting-by-filter would let two variants of the
same repo coexist on disk automatically, which is real value — but it
reuses a mechanism built to protect *genuinely different* repos from each
other's commits for an unrelated purpose (variants of the *same* repo), and
it means the returned `Path` for a filtered download gains an extra,
filter-named directory level that has nothing to do with the manifest's own
structure. Refuse-and-let-the-caller-decide is smaller, and it's the
pattern this file already uses everywhere else a commit could destroy
something (`docs/known-limitations.md`: "A refused directory needs manual
removal" is already documented policy). A caller that wants two variants
resident at once already has the tool for it: pass a different `into` per
variant. This spec doesn't add a mechanism for that — it only makes the
same-`into`, different-filter case fail safely instead of silently.

**The unfiltered case is a filter too**, with identity `""`. This keeps the
protection symmetric: a filtered download can't silently claim a directory
an unfiltered download committed, or vice versa. See "Compatibility" below
for what this means for a marker written by a pre-filter version of ferry.

### Resume must not treat a partial directory as satisfied for the wrong filter

The commit-marker fix above only protects a *committed* `target`. Staging is
the gap: today, `MARKER_FILE` is written into staging **last**, only once
every file in the loop has verified (deliberately — a manifest entry
literally named `.ferry` must not be able to forge ownership, and a crash
mid-loop must not leave a marker describing content that was never fully
verified). That means an *interrupted* download leaves no marker in staging
at all today, filtered or not — nothing currently records which filter
produced whatever bytes are sitting under `into/.staging`.

Filtering makes this reachable in a new way: call `download(repoId, into,
filterA)`, let it stage some files and get interrupted, then call
`download(repoId, into, filterB)`. `filterB`'s `satisfiedPaths` computation
would credit any staged file that happens to be byte-correct for *either*
filter's selection (harmless on its own — a correct file is correct
regardless of which filter asked for it), but `pruneOrphans` — run against
`filterB`'s `selected` — would delete every file `filterA` staged that
`filterB` doesn't also select, silently discarding `filterA`'s progress
before `filterB`'s own commit-time check ever gets a chance to notice
anything is wrong. `filterB` can then go on to commit successfully (no
`target` existed yet, so the marker-mismatch gate above never fires),
publishing a repo with no error surfaced anywhere — not corruption, but a
silent loss of progress, exactly the class of thing this library's durable
staging exists to prevent.

**Required:** staging must record its own filter identity, independent of
and *earlier than* `MARKER_FILE`, checked before `pruneOrphans` or the
download loop touch anything. A small identity sidecar written into
`stagingDir` the first time any byte is staged for a given `(repoId, into)`
— e.g. `stagingDir / ".ferry-filter"`, holding the same identity string the
eventual commit marker will carry — read back and compared on every
`download()` call before proceeding. A mismatch refuses immediately, before
any network request:

> `staging for '$repoId' already holds progress under a different filter;
> call abandonStaging(repoId, into) before retrying with a new filter`

No new public method: the existing `abandonStaging(repoId, into)` is already
exactly "reclaim this repo id's staging bytes, unconditionally" — the right
tool for a caller that deliberately wants to switch filters.
`pruneOrphans` must exclude this sidecar from its own orphan sweep, the same
way `stagedBytes` already excludes `MARKER_FILE` from its walk.

Resume *with the same filter* is otherwise unchanged: `satisfiedPaths` and
`remainingBytes` already operate per file, and once everything above
operates on `selected` instead of `manifest`, a rerun with an identical
`Regex` (same `.pattern`) skips exactly the files it already skips today —
no new logic needed there.

### Cache hit (already-complete, already-committed repo)

The existing fast path — `target` exists and structurally satisfies the
manifest, return success without touching staging or the network — must
check `selected.isSatisfiedBy(target)`, not `manifest.isSatisfiedBy(target)`,
so a target holding only a filtered subset is recognized as complete for
that filter rather than judged "missing files" against the full listing.
This path deliberately does **not** consult the marker's filter identity: if
every file the caller's filter selects is already present and verified at
`target`, that's a legitimate cache hit regardless of which filter
originally produced the directory (there are no *other* files once
filtering is in effect — but even in principle, structural satisfaction is
the actual promise this check makes, and it already works this way for the
unfiltered case today).

### Compatibility

A directory committed by a pre-filter version of ferry has a marker
containing bare `repoId`, no second line. Read that as filter identity `""`
— the same identity an unfiltered call produces going forward — so an
existing consumer's already-downloaded repos remain valid cache hits and
valid commit targets after upgrading, with no migration step.

## Progress reporting

`RepoProgress.Downloading.fileIndex` / `.fileCount`, and the same fields on
`Skipped`, must be relative to `selected.files`, not `manifest.files`. This
isn't a separate feature to implement — once the download loop iterates
`selected.files.forEachIndexed { index, remote -> ... }` instead of
`manifest.files.forEachIndexed`, the counts are correct by construction. A
user who filtered a 27-file repo down to 3 files must see "file 1 of 3,"
never "file 14 of 27" for the one file that happened to survive filtering.

## Testing requirements

All of these run on both `jvmTest` and `iosX64Test`, against `MockEngine` and
`FakeFileSystem`, extending `RepoDownloaderTest`'s existing style
(backtick-named functions, one behavior per test):

- A filter matching some but not all files downloads and commits only the
  matching subset; the committed directory doesn't contain the excluded
  files.
- A filter matching nothing fails (`Result.failure`), commits nothing, and
  makes no network request beyond the manifest fetch — extends `a manifest
  with no files is refused rather than treated as satisfied`.
- `SpaceCheck`'s reported `requiredBytes` reflects only the filtered
  subset's total, not the full manifest's — a filter that would fail
  preflight against the whole repo succeeds when only the selected files are
  checked. Extends `refuses to start when space is insufficient`.
- A second `download()` call with the *same* `Regex` pattern (same
  `.pattern` string, need not be the same `Regex` instance) resumes and
  skips already-staged, already-verified files exactly as an unfiltered
  resume does today — extends `a mostly staged download only needs the
  remaining bytes`.
- A second `download()` call for the same `repoId` and `into`, with a
  *different* filter, after the first has **committed**: refuses, target
  untouched, error names the filter mismatch.
- A second `download()` call for the same `repoId` and `into`, with a
  *different* filter, while the first's staging is **incomplete** (no commit
  yet): refuses before any network request, staging untouched;
  `abandonStaging` followed by the second call then succeeds normally.
- `RepoProgress.Downloading`/`Skipped` counts (`fileIndex`, `fileCount`) are
  pinned against the filtered subset's size, not the full manifest's —
  extends `progress numbers each file within the repo`.
- The full existing `RepoDownloaderTest` suite passes unchanged with no
  `fileFilter` argument supplied — the regression bar proving source and
  behavior compatibility for every existing call site.

## What does not change

- `abandonStaging(repoId, into)` — same signature, same effect (wipes all
  staging for this repo id, regardless of which filter produced it).
- `stagedBytes(repoId, into)` — same signature. It already never consults a
  manifest (its own KDoc: "The enumeration above answers 'is this shape
  reusable', never 'does a current manifest still name this path at all'"),
  so it's filter-agnostic by construction; no change needed.
- `HuggingFace` / `ModelScope` / `Ollama` — the filter applies after
  `manifest()` returns, entirely inside `RepoDownloader`. No hub adapter
  changes.
- `Ferry.huggingFace()` / `modelScope()` / `ollama()` factory signatures.

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
if interrupted, and — if the user later picks `Q5_K_M` for the same model
into the same directory — fails with a clear, actionable error instead of
silently deleting the `Q4_K_M` model already on disk.

## Risks / notes for the implementer

- The staging-identity sidecar is new on-disk state with no prior art in
  this codebase (`MARKER_FILE` is the closest analog and is deliberately
  *not* reusable here — see "Resume" above for why). Give it its own tests
  the way `MARKER_FILE`/`MARKER_ROOT` each have dedicated coverage in
  `RepoDownloaderTest`.
- `RemoteFile.path` is what `Regex` matches against, not the on-disk staged
  path — same value, but confirm this against a repo with subdirectories
  (some GGUF repos ship `mmproj` files or split multi-part GGUFs under a
  subdirectory) so `containsMatchIn` behaves predictably against a path
  that contains `/`.
- `0.2.0`'s README already reserves the right to break sealed interfaces
  before `1.0`, but this addition does not use that license: it's a new
  defaulted parameter, source-compatible for every existing caller. State
  that explicitly in the changelog entry rather than bundling it with a
  future breaking release.
