# File Filter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `fileFilter: Regex?` subset download to `RepoDownloader` per `docs/superpowers/specs/2026-08-11-file-filter-design.md`, keeping every existing guarantee correct for the subset and every published binary linking.

**Architecture:** A new 4-arg `download` overload (the existing 3-arg one delegates — binary compatibility with published `ferry-work:0.2.0` depends on it staying byte-identical). Selection happens once (`selected`), immediately after the manifest guard, and every downstream decision uses it. Filter identity = SHA-256 of an injective canonicalisation of (pattern, options); it keys the staging directory name and the commit marker. A filter-identity gate runs before the manifest fetch. `abandonStaging`/`stagedBytes` sweep all filter-keyed staging directories for a repo id.

**Tech Stack:** Kotlin Multiplatform (commonMain), okio (FakeFileSystem, ByteString sha256), Ktor MockEngine via existing `QueueClient`, kotlin.test.

## Global Constraints

- Every Gradle call: `export JAVA_HOME=/Users/admin/Library/Java/JavaVirtualMachines/temurin-21.0.11/Contents/Home` first (system JDK 26 breaks Gradle 8.9 with a bare version-number error).
- Test gates: `./gradlew :ferry:jvmTest` after every task; `./gradlew :ferry:iosX64Test` at the final task (this is an Intel Mac — `iosSimulatorArm64Test` SKIPs here).
- The full existing `RepoDownloaderTest` suite must pass **unchanged** after every task (spec test 14). Never edit an existing test to make it pass.
- Kotlin 2.1.21, Ktor 3.2.3, okio 3.9.1 — no version changes, no new dependencies.
- All production code in `ferry/src/commonMain/kotlin/dev/thuat/ferry/RepoDownloader.kt` except Task 4's jvm-only test. Common test code in `ferry/src/commonTest/kotlin/dev/thuat/ferry/RepoDownloaderTest.kt`.
- Test names: backtick style, one behavior per test, **no commas inside backtick names** (the native XCTest runner rejects them — use ` - ` instead).
- Commits: conventional format (`feat:`, `test:`, `docs:`). **No attribution footers** (no Co-Authored-By — disabled globally).
- Immutable patterns: `manifest.copy(...)`, never mutation.
- Error messages: name the path, name the cause, name the remedy (matches existing refusal wording discipline).
- Work on branch `feat/file-filter` (created at execution time, worktree per superpowers:using-git-worktrees).

**Pinned filter-key constants used across tasks** (precomputed; `canonicalIdentity` is `"${pattern.length}:${pattern}"` + sorted option names joined with `","`, then SHA-256 hex):

| Regex | canonicalIdentity | filterKey (sha256 hex) |
|---|---|---|
| `Regex("Q4_K_M")` | `6:Q4_K_M` | `84e8a49358769738436631f34724972c215ac5cf8a6e3019b642826553a316ce` |
| `Regex("q4_k_m")` | `6:q4_k_m` | `d6b5133a80b0fc611180cb80dc99d6f106cf4eb1cd52c5f22a26e6ae0827d0f5` |
| `Regex("q4_k_m", RegexOption.IGNORE_CASE)` | `6:q4_k_mIGNORE_CASE` | `e159c0f9b78e39554f1d236bb4cd7ec4d648fd0fbb25659ae3b20c1b33bbc3e7` |

---

### Task 1: Filter parameter, selection, and filter-keyed staging

**Files:**
- Modify: `ferry/src/commonMain/kotlin/dev/thuat/ferry/RepoDownloader.kt`
- Test: `ferry/src/commonTest/kotlin/dev/thuat/ferry/RepoDownloaderTest.kt`

**Interfaces:**
- Consumes: existing `RepoDownloader`, `RepoManifest`, `RemoteFile`, `resolveInside`, `stagingDirFor`, test helpers `downloaderFor`/`remote`/`writeText`/`await`/`queue`.
- Produces (later tasks rely on these exact signatures):
  - `suspend fun download(repoId: String, into: Path, fileFilter: Regex?, onProgress: (RepoProgress) -> Unit = {}): Result<Path>` — public 4-arg overload.
  - `private fun canonicalIdentity(filter: Regex): String`
  - `private fun filterKey(filter: Regex?): String` — `""` for null, else 64 lowercase hex.
  - `private fun stagingDirFor(stagingRoot: Path, repoId: String, filterKey: String): Path` — three args now; `""` reproduces today's path exactly.
  - Inside the 4-arg `download` body: `val key = filterKey(fileFilter)` and `val selected: RepoManifest` are the names Task 2 inserts code around.

- [ ] **Step 1: Write the failing tests** — append to `RepoDownloaderTest.kt`:

```kotlin
    // ---- file filter: selection (spec tests 1, 2, 3, 13) ----

    @Test
    fun `a filter downloads and commits only the matching subset`() {
        val files = listOf(
            remote("model-Q4_K_M.gguf", weightsBody.length.toLong(), shaOf(weightsBody)),
            remote("model-Q8_0.gguf", configBody.length.toLong()),
        )
        queue.enqueue(body = weightsBody)

        val dir = await { downloaderFor(files).download("o/m", root, Regex("Q4_K_M")) }.getOrThrow()

        assertEquals(weightsBody, readText(dir / "model-Q4_K_M.gguf"))
        assertFalse(fs.exists(dir / "model-Q8_0.gguf"))
        assertEquals(1, queue.requests.size)
    }

    @Test
    fun `a filter matching nothing fails - commits nothing - makes no file request`() {
        val files = listOf(remote("model-Q8_0.gguf", 10L))

        val result = await { downloaderFor(files).download("o/m", root, Regex("Q4_K_M")) }

        assertTrue(result.exceptionOrNull()!!.message!!.contains("no files matched the filter"))
        assertFalse(fs.exists(root / "o/m"))
        assertTrue(queue.requests.isEmpty())
    }

    @Test
    fun `space preflight sizes only the filtered subset`() {
        // Whole repo (1_000_000 + weights) cannot fit in freeBytes; the selected file alone can.
        val files = listOf(
            remote("model-Q4_K_M.gguf", weightsBody.length.toLong(), shaOf(weightsBody)),
            remote("huge-Q8_0.gguf", 1_000_000L),
        )
        queue.enqueue(body = weightsBody)

        val dir = await {
            downloaderFor(files, freeBytes = weightsBody.length.toLong())
                .download("o/m", root, Regex("Q4_K_M"))
        }.getOrThrow()

        assertEquals(weightsBody, readText(dir / "model-Q4_K_M.gguf"))
    }

    @Test
    fun `progress numbers each file within the filtered subset - not the manifest`() {
        val files = listOf(
            remote("a-Q4_K_M.gguf", configBody.length.toLong()),
            remote("b-Q8_0.gguf", 10L),
            remote("c-Q4_K_M.gguf", weightsBody.length.toLong()),
        )
        queue.enqueue(body = configBody)
        queue.enqueue(body = weightsBody)
        val events = mutableListOf<RepoProgress>()

        await {
            downloaderFor(files).download("o/m", root, Regex("Q4_K_M")) { events += it }
        }.getOrThrow()

        val downloading = events.filterIsInstance<RepoProgress.Downloading>()
        assertEquals(setOf(0, 1), downloading.map { it.fileIndex }.toSet())
        assertTrue(downloading.all { it.fileCount == 2 })
    }
```

- [ ] **Step 2: Run the new tests to verify they fail**

Run: `export JAVA_HOME=/Users/admin/Library/Java/JavaVirtualMachines/temurin-21.0.11/Contents/Home && ./gradlew :ferry:jvmTest --tests 'dev.thuat.ferry.RepoDownloaderTest' 2>&1 | tail -30`
Expected: compilation FAILS — no 4-arg `download` overload exists yet. That is the failing state for this cycle.

- [ ] **Step 3: Implement identity helpers and 3-arg `stagingDirFor`**

In `RepoDownloader.kt`, add import at top: `import okio.ByteString.Companion.encodeUtf8`.

Add these two private functions inside the class, directly above the existing `stagingDirFor`:

```kotlin
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
```

Change `stagingDirFor` to take the key (keep its entire existing KDoc, appending one paragraph):

```kotlin
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
```

KDoc paragraph to append to `stagingDirFor`'s existing doc comment:

```
     * A non-empty [filterKey] — always 64 lowercase hex characters, see [filterKey]'s own doc —
     * appends as `-<key>` after [STAGING_SUFFIX], so every filter, including the absence of one,
     * gets its own sibling scratch directory: `owner/model.d` unfiltered, `owner/model.d-<64 hex>`
     * per filter. Appended to the last segment rather than nested inside `model.d`, because nesting
     * would make the unfiltered directory a literal ancestor of every filtered one — reintroducing
     * exactly the Critical this function's own history above describes.
```

Update the two other call sites to compile (`abandonStaging` line ~536 and `stagedBytes` line ~603): pass `""` as the third argument for now — Task 3 replaces both with the sweep.

- [ ] **Step 4: Add the 4-arg overload and switch the body to `selected`**

Replace the existing `download` function (keep its full KDoc on the 4-arg form, and give the 3-arg form the new KDoc below). The 3-arg overload:

```kotlin
    /**
     * Downloads the whole of [repoId] — every manifest file — into a directory under [into].
     *
     * Exists as a distinct overload, not a default on the filtered form, **solely for binary
     * compatibility**: the published dev.thuat:ferry-work:0.2.0 was compiled against this exact
     * JVM descriptor (`download(String, Path, Function1, Continuation)`) and its synthetic
     * `download$default` bridge. Folding it into the 4-parameter function as `fileFilter: Regex? =
     * null` keeps every caller *compiling* but breaks every already-published caller at runtime
     * with NoSuchMethodError. See BinaryCompatTest in jvmTest, which pins this descriptor.
     */
    suspend fun download(
        repoId: String,
        into: Path,
        onProgress: (RepoProgress) -> Unit = {},
    ): Result<Path> = download(repoId, into, fileFilter = null, onProgress = onProgress)
```

The 4-arg overload keeps the original KDoc (concurrency warning etc.) plus this addition at the end of the doc:

```
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
```

Inside the body, in order (keep every existing comment block attached to the code it documents — they move with their code, none are deleted):

1. First line of the `try`: `val key = filterKey(fileFilter)`.
2. **Move** the path-resolution block (`stagingRoot`, `stagingDir`, `target`, `markerRoot`, `markerDir`, both `collidesWith` checks — currently lines ~208-228, together with their comments) **above** the `repo.manifest(repoId)` call. This is the spec's step-1-before-step-3 ordering; Task 2 inserts the gate between them. `stagingDir` becomes:
   ```kotlin
   val stagingDir = stagingDirFor(stagingRoot, repoId, key)
   ```
3. After the existing empty-manifest guard, compute the selection and its guard:
   ```kotlin
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
   ```
4. Replace every remaining use of `manifest` below that point with `selected`, one for one:
   - cache hit: `selected.isSatisfiedBy(target)`
   - `satisfiedPaths`: `selected.files.filter { it.isSatisfiedIn(stagingDir) }...`
   - space: `spaceCheck.check(selected.creditingStaged(stagingDir, satisfiedPaths), into)`
   - prune: `pruneOrphans(stagingDir, selected)`
   - loop: `selected.files.forEachIndexed { index, remote ->` and both `fileCount = selected.files.size` occurrences (in `Downloading` and `Skipped`)
   - pre-commit check: `selected.files.firstOrNull { ... }`

   The marker write (`writeUtf8(repoId)`) and the target-exists gate stay untouched in this task — Task 2 owns them.

- [ ] **Step 5: Run the four new tests — verify they pass**

Run: `export JAVA_HOME=/Users/admin/Library/Java/JavaVirtualMachines/temurin-21.0.11/Contents/Home && ./gradlew :ferry:jvmTest --tests 'dev.thuat.ferry.RepoDownloaderTest' 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL, all tests pass (new four included).

- [ ] **Step 6: Write the failing staging-keying tests** — append to `RepoDownloaderTest.kt`:

```kotlin
    // ---- file filter: filter-keyed staging (spec tests 4, 8, 12; pinned filterKey) ----

    /**
     * Pins filterKey's output for a known (pattern, options) pair — sha256 of "6:Q4_K_M".
     * The staging directory name is derived state: a refactor of canonicalIdentity that quietly
     * changes this hex silently abandons every already-staged byte rather than failing.
     */
    @Test
    fun `a filter's staging directory lands at the pinned filter key`() {
        val files = listOf(remote("model-Q4_K_M.gguf", 100L))
        queue.enqueue(status = HttpStatusCode.InternalServerError)

        await { downloaderFor(files).download("o/m", root, Regex("Q4_K_M")) }

        val keyed = root / ".staging" / "o" /
            "m.d-84e8a49358769738436631f34724972c215ac5cf8a6e3019b642826553a316ce"
        assertTrue(fs.metadataOrNull(keyed)?.isDirectory == true)
    }

    @Test
    fun `an equal filter built from a fresh Regex instance resumes the same staging`() {
        val files = listOf(remote("model-Q4_K_M.gguf", weightsBody.length.toLong(), shaOf(weightsBody)))
        writeText(
            root / ".staging" / "o" /
                "m.d-84e8a49358769738436631f34724972c215ac5cf8a6e3019b642826553a316ce" /
                "model-Q4_K_M.gguf",
            weightsBody,
        )

        val dir = await { downloaderFor(files).download("o/m", root, Regex("Q4_K_M")) }.getOrThrow()

        assertEquals(weightsBody, readText(dir / "model-Q4_K_M.gguf"))
        assertTrue(queue.requests.isEmpty())
    }

    @Test
    fun `a different filter stages independently and the first filter's bytes survive to be resumed`() {
        val files = listOf(
            remote("model-Q4_K_M.gguf", weightsBody.length.toLong(), shaOf(weightsBody)),
            remote("model-Q8_0.gguf", configBody.length.toLong(), shaOf(configBody)),
        )
        // First filter's progress: a satisfied bare file, staged by hand in Q4_K_M's own directory.
        val stagedA = root / ".staging" / "o" /
            "m.d-84e8a49358769738436631f34724972c215ac5cf8a6e3019b642826553a316ce" /
            "model-Q4_K_M.gguf"
        writeText(stagedA, weightsBody)

        // Second filter's attempt fails mid-flight — its staging is its own, not Q4_K_M's.
        queue.enqueue(status = HttpStatusCode.InternalServerError)
        val second = await { downloaderFor(files).download("o/m", root, Regex("Q8_0")) }
        assertTrue(second.isFailure)
        assertEquals(weightsBody, readText(stagedA))

        // A later Q4_K_M call resumes the surviving bytes: no new request needed.
        val requestsBefore = queue.requests.size
        val dir = await { downloaderFor(files).download("o/m", root, Regex("Q4_K_M")) }.getOrThrow()
        assertEquals(weightsBody, readText(dir / "model-Q4_K_M.gguf"))
        assertEquals(requestsBefore, queue.requests.size)
    }

    @Test
    fun `bytes staged by a pre-filter ferry are resumed by an unfiltered call`() {
        // A pre-filter ferry staged at exactly `<id>.d` — the path an unfiltered call resolves.
        val files = listOf(remote("model.bin", weightsBody.length.toLong(), shaOf(weightsBody)))
        writeText(root / ".staging" / "o" / "m.d" / "model.bin", weightsBody)

        val dir = await { downloaderFor(files).download("o/m", root) }.getOrThrow()

        assertEquals(weightsBody, readText(dir / "model.bin"))
        assertTrue(queue.requests.isEmpty())
    }
```

- [ ] **Step 7: Run the staging tests — verify current state**

Run: `export JAVA_HOME=/Users/admin/Library/Java/JavaVirtualMachines/temurin-21.0.11/Contents/Home && ./gradlew :ferry:jvmTest --tests 'dev.thuat.ferry.RepoDownloaderTest' 2>&1 | tail -15`
Expected: all four PASS already if Steps 3-4 were done correctly (the keying is implemented). If any fails, fix the implementation — not the test. (These are written after the first GREEN because they pin the same change; the RED state for this task was Step 2.)

- [ ] **Step 8: Run the full jvm suite — regression bar**

Run: `export JAVA_HOME=/Users/admin/Library/Java/JavaVirtualMachines/temurin-21.0.11/Contents/Home && ./gradlew :ferry:jvmTest 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL. Every pre-existing test untouched and green.

- [ ] **Step 9: Commit**

```bash
git add ferry/src/commonMain/kotlin/dev/thuat/ferry/RepoDownloader.kt ferry/src/commonTest/kotlin/dev/thuat/ferry/RepoDownloaderTest.kt
git commit -m "feat: fileFilter overload selects a manifest subset with filter-keyed staging"
```

---

### Task 2: Filter identity in the commit marker, gate before the manifest fetch

**Files:**
- Modify: `ferry/src/commonMain/kotlin/dev/thuat/ferry/RepoDownloader.kt`
- Test: `ferry/src/commonTest/kotlin/dev/thuat/ferry/RepoDownloaderTest.kt`

**Interfaces:**
- Consumes: Task 1's `val key = filterKey(fileFilter)` (in scope at the top of the 4-arg `download` body), the moved path-resolution block, `MARKER_FILE`, `resolveInside`.
- Produces: `private fun markerContent(repoId: String, filterKey: String): String` — Task 3 does not need it, but the marker write and both gates use it from here on.

- [ ] **Step 1: Write the failing tests** — append to `RepoDownloaderTest.kt`:

```kotlin
    // ---- file filter: commit identity (spec tests 5, 6, 7, 11 + the unfiltered-reverse case) ----

    @Test
    fun `a different filter after commit refuses with no network request at all`() {
        val files = listOf(remote("model-Q4_K_M.gguf", weightsBody.length.toLong(), shaOf(weightsBody)))
        queue.enqueue(body = weightsBody)
        await { downloaderFor(files).download("o/m", root, Regex("Q4_K_M")) }.getOrThrow()

        // A hub that must not be consulted: if the gate runs after the manifest fetch, the
        // failure message becomes this one instead of the filter refusal below.
        val silentHub = object : ModelHub {
            override suspend fun manifest(repoId: String): Result<RepoManifest> =
                Result.failure(okio.IOException("manifest must not be fetched"))
        }
        val second = RepoDownloader(
            repo = silentHub,
            downloader = ResumableDownloader(queue.client, fs, UnconfinedTestDispatcher()),
            spaceCheck = SpaceCheck(probe = { Long.MAX_VALUE }, headroomBytes = 0L),
            fileSystem = fs,
            dispatcher = UnconfinedTestDispatcher(),
        )
        val requestsBefore = queue.requests.size

        val result = await { second.download("o/m", root, Regex("Q5_K_M")) }

        assertTrue(result.exceptionOrNull()!!.message!!.contains("different file filter"))
        assertEquals(requestsBefore, queue.requests.size)
        assertEquals(weightsBody, readText(root / "o/m" / "model-Q4_K_M.gguf"))
    }

    @Test
    fun `a filtered call against a target committed unfiltered is refused - not returned as a cache hit`() {
        val files = listOf(
            remote("model-Q4_K_M.gguf", weightsBody.length.toLong(), shaOf(weightsBody)),
            remote("model-Q8_0.gguf", configBody.length.toLong(), shaOf(configBody)),
        )
        queue.enqueue(body = weightsBody)
        queue.enqueue(body = configBody)
        await { downloaderFor(files).download("o/m", root) }.getOrThrow()

        val result = await { downloaderFor(files).download("o/m", root, Regex("Q4_K_M")) }

        assertTrue(result.exceptionOrNull()!!.message!!.contains("different file filter"))
        assertEquals(weightsBody, readText(root / "o/m" / "model-Q4_K_M.gguf"))
        assertEquals(configBody, readText(root / "o/m" / "model-Q8_0.gguf"))
    }

    @Test
    fun `an unfiltered call against a target committed filtered is refused rather than replacing it`() {
        val files = listOf(
            remote("model-Q4_K_M.gguf", weightsBody.length.toLong(), shaOf(weightsBody)),
            remote("model-Q8_0.gguf", configBody.length.toLong(), shaOf(configBody)),
        )
        queue.enqueue(body = weightsBody)
        await { downloaderFor(files).download("o/m", root, Regex("Q4_K_M")) }.getOrThrow()

        val result = await { downloaderFor(files).download("o/m", root) }

        assertTrue(result.exceptionOrNull()!!.message!!.contains("different file filter"))
        assertEquals(weightsBody, readText(root / "o/m" / "model-Q4_K_M.gguf"))
    }

    @Test
    fun `filters differing only in RegexOption do not satisfy each other's commit gate`() {
        val files = listOf(remote("model-q4_k_m.gguf", weightsBody.length.toLong(), shaOf(weightsBody)))
        queue.enqueue(body = weightsBody)
        await { downloaderFor(files).download("o/m", root, Regex("q4_k_m")) }.getOrThrow()

        val result = await {
            downloaderFor(files).download("o/m", root, Regex("q4_k_m", RegexOption.IGNORE_CASE))
        }

        assertTrue(result.exceptionOrNull()!!.message!!.contains("different file filter"))
    }

    @Test
    fun `a marker written by a pre-filter ferry stays a cache hit for an unfiltered call`() {
        // Written by hand: no pre-filter ferry is available to produce it. Exactly repoId,
        // no trailing newline — byte for byte what every version of ferry has ever written.
        val files = listOf(remote("config.json", configBody.length.toLong()))
        writeText(root / "o/m" / "config.json", configBody)
        writeText(root / "o/m" / ".ferry", "o/m")

        val dir = await { downloaderFor(files).download("o/m", root) }.getOrThrow()

        assertEquals(root / "o" / "m", dir)
        assertTrue(queue.requests.isEmpty())
    }
```

- [ ] **Step 2: Run the new tests to verify they fail**

Run: `export JAVA_HOME=/Users/admin/Library/Java/JavaVirtualMachines/temurin-21.0.11/Contents/Home && ./gradlew :ferry:jvmTest --tests 'dev.thuat.ferry.RepoDownloaderTest' 2>&1 | tail -30`
Expected: the first four FAIL (no gate exists — the filtered-after-unfiltered case is returned as a cache hit; the others fall through to today's marker gate whose message says "was not committed by Ferry", not "different file filter"). The pre-filter-marker test may already pass — it pins behavior that must survive this task.

- [ ] **Step 3: Implement `markerContent`, the step-2 gate, and the two marker sites**

Add inside the class, directly above `stagingDirFor`:

```kotlin
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
```

Insert the gate in the 4-arg `download` body, after the two `collidesWith` checks and **before** `repo.manifest(repoId)`:

```kotlin
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
```

Change the marker **write** (currently `writeUtf8(repoId)`):

```kotlin
            fileSystem.write(stagingDir / MARKER_FILE) { writeUtf8(markerContent(repoId, key)) }
```

Change the commit-time gate's **comparison** (currently `!= repoId`; the surrounding refusal and its message stay exactly as they are):

```kotlin
                if (!markerIsFile || fileSystem.read(marker) { readUtf8() } != markerContent(repoId, key)) {
```

Note: the commit-time gate already declares `val marker = target / MARKER_FILE` in its own scope; with the step-2 gate's `marker` now in the outer scope, drop the inner redeclaration and reuse it.

- [ ] **Step 4: Run the new tests — verify they pass**

Run: `export JAVA_HOME=/Users/admin/Library/Java/JavaVirtualMachines/temurin-21.0.11/Contents/Home && ./gradlew :ferry:jvmTest --tests 'dev.thuat.ferry.RepoDownloaderTest' 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL, all five new tests pass.

- [ ] **Step 5: Run the full jvm suite — regression bar**

Run: `export JAVA_HOME=/Users/admin/Library/Java/JavaVirtualMachines/temurin-21.0.11/Contents/Home && ./gradlew :ferry:jvmTest 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL. In particular `a directory at the target path that ferry did not write is refused - not deleted`, `an already downloaded and verified repo is not fetched again`, and every nested-repo test must be untouched and green — the gate must not have moved the foreign-directory refusal earlier.

- [ ] **Step 6: Commit**

```bash
git add ferry/src/commonMain/kotlin/dev/thuat/ferry/RepoDownloader.kt ferry/src/commonTest/kotlin/dev/thuat/ferry/RepoDownloaderTest.kt
git commit -m "feat: commit marker carries filter identity - gate refuses a mismatch before any network request"
```

---

### Task 3: `abandonStaging` and `stagedBytes` sweep filter-keyed staging

**Files:**
- Modify: `ferry/src/commonMain/kotlin/dev/thuat/ferry/RepoDownloader.kt`
- Test: `ferry/src/commonTest/kotlin/dev/thuat/ferry/RepoDownloaderTest.kt`

**Interfaces:**
- Consumes: Task 1's 3-arg `stagingDirFor`, `STAGING_SUFFIX`, existing `stagedBytes` body.
- Produces: `private fun stagingDirsFor(stagingRoot: Path, repoId: String): List<Path>` and `private fun stagedBytesIn(stagingDir: Path): Long`. Public signatures of `abandonStaging`/`stagedBytes` unchanged.

- [ ] **Step 1: Write the failing tests** — append to `RepoDownloaderTest.kt`. The 64-hex literal is Task 1's pinned `Q4_K_M` key; the second uses a distinct arbitrary 64-hex value, which the sweep must also match (it sweeps by shape, not by knowing which filters exist):

```kotlin
    // ---- file filter: abandonStaging / stagedBytes sweep (spec tests 9, 10) ----

    private val keyA = "84e8a49358769738436631f34724972c215ac5cf8a6e3019b642826553a316ce"
    private val keyB = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    @Test
    fun `abandonStaging removes the unfiltered and every filter-keyed staging for the id - and no other id's`() {
        writeText(root / ".staging" / "o" / "m.d" / "plain.bin", "plain")
        writeText(root / ".staging" / "o" / "m.d-$keyA" / "a.bin", "aa")
        writeText(root / ".staging" / "o" / "m.d-$keyB" / "b.bin", "bb")
        // A different repo id whose staging directory is a naive-prefix trap: id "m.d-x" under
        // "o" stages at "o/m.d-x.d", which starts with "m.d-" but is not 64 hex — must survive.
        writeText(root / ".staging" / "o" / "m.d-x.d" / "other.bin", "other")

        await { downloaderFor(emptyList()).abandonStaging("o/m", root) }.getOrThrow()

        assertFalse(fs.exists(root / ".staging" / "o" / "m.d"))
        assertFalse(fs.exists(root / ".staging" / "o" / "m.d-$keyA"))
        assertFalse(fs.exists(root / ".staging" / "o" / "m.d-$keyB"))
        assertEquals("other", readText(root / ".staging" / "o" / "m.d-x.d" / "other.bin"))
    }

    @Test
    fun `stagedBytes sums across the unfiltered and every filter-keyed staging directory`() {
        // Reusable shapes only: a bare file in the unfiltered dir, a validated .part in a keyed
        // one, an unvalidated .part (counts zero) in another.
        writeText(root / ".staging" / "o" / "m.d" / "plain.bin", "12345")
        writeText(root / ".staging" / "o" / "m.d-$keyA" / "a.bin.part", "1234567")
        writeText(root / ".staging" / "o" / "m.d-$keyA" / "a.bin.validator", "etag")
        writeText(root / ".staging" / "o" / "m.d-$keyB" / "b.bin.part", "123")

        val bytes = await { downloaderFor(emptyList()).stagedBytes("o/m", root) }

        assertEquals(5L + 7L, bytes)
    }

    @Test
    fun `stagedBytes does not count a different repo id shaped like a filter-keyed sibling`() {
        writeText(root / ".staging" / "o" / "m.d-x.d" / "other.bin", "other")

        assertEquals(0L, await { downloaderFor(emptyList()).stagedBytes("o/m", root) })
    }
```

- [ ] **Step 2: Run the new tests to verify they fail**

Run: `export JAVA_HOME=/Users/admin/Library/Java/JavaVirtualMachines/temurin-21.0.11/Contents/Home && ./gradlew :ferry:jvmTest --tests 'dev.thuat.ferry.RepoDownloaderTest' 2>&1 | tail -30`
Expected: first two FAIL (only the unfiltered `m.d` is touched/summed today); the third may pass trivially — keep it as the shape pin.

- [ ] **Step 3: Implement the sweep**

Add inside the class, directly below `stagingDirFor`:

```kotlin
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
```

Rewrite `abandonStaging`'s body (keep its full KDoc; its contract text "wipes all staging for this repo id" is now literally true — append one sentence to the KDoc saying the sweep covers every filter's staging directory, unfiltered and keyed alike):

```kotlin
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
```

Rewrite `stagedBytes` (keep its full KDoc; append a sentence naming the union imprecision: for a repo id with two filters staged it returns their union, not what one particular filtered `download` would reuse — inside the existing "hint, not a promise" frame). Extract the existing per-directory sum into `stagedBytesIn` verbatim:

```kotlin
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
```

- [ ] **Step 4: Run the new tests — verify they pass**

Run: `export JAVA_HOME=/Users/admin/Library/Java/JavaVirtualMachines/temurin-21.0.11/Contents/Home && ./gradlew :ferry:jvmTest --tests 'dev.thuat.ferry.RepoDownloaderTest' 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL, all three pass.

- [ ] **Step 5: Run the full jvm suite — regression bar**

Run: `export JAVA_HOME=/Users/admin/Library/Java/JavaVirtualMachines/temurin-21.0.11/Contents/Home && ./gradlew :ferry:jvmTest 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL. In particular `abandonStaging removes only this repo's staging`, `abandoning staging for a prefix repo does not touch a nested repo's own staging`, `stagedBytes for a prefix repo does not count a nested repo's own staging`, and `stagedBytes is zero rather than throwing for an escaping repo id` must all be untouched and green.

- [ ] **Step 6: Commit**

```bash
git add ferry/src/commonMain/kotlin/dev/thuat/ferry/RepoDownloader.kt ferry/src/commonTest/kotlin/dev/thuat/ferry/RepoDownloaderTest.kt
git commit -m "feat: abandonStaging and stagedBytes sweep every filter-keyed staging directory"
```

---

### Task 4: Binary-compat pin, iOS gate, changelog, version

**Files:**
- Create: `ferry/src/jvmTest/kotlin/dev/thuat/ferry/BinaryCompatTest.kt`
- Modify: `CHANGELOG.md` (new `## 0.3.0` section above `## 0.2.0`)
- Modify: `ferry/build.gradle.kts:11` (`version = "0.2.0"` → `"0.3.0"`)
- Modify: `README.md` (one snippet after the "Three hubs, same call" block, line ~56)

**Interfaces:**
- Consumes: the 3-arg `download` overload from Task 1 (its JVM descriptor is what the test pins).
- Produces: nothing for later tasks — this is the release-readiness task.

- [ ] **Step 1: Write the binary-compat test** (jvmTest only — reflection on JVM descriptors has no meaning on `iosX64Test` and must not be written in commonTest):

```kotlin
package dev.thuat.ferry

import kotlin.coroutines.Continuation
import kotlin.test.Test
import kotlin.test.assertNotNull
import okio.Path

class BinaryCompatTest {

    /**
     * The published dev.thuat:ferry-work:0.2.0 was compiled against this exact full-arity
     * descriptor (RepoDownloadWorker calls `download(repoId, path) { ... }`, which supplies all
     * three parameters and links the non-default method directly). The fileFilter feature was
     * added as a separate overload precisely so this descriptor survives verbatim: deleting or
     * reshaping the 3-argument download is a runtime NoSuchMethodError for every consumer
     * resolving ferry-work 0.2.0 against a newer :ferry, even though all sources still compile.
     */
    @Test
    fun `the 3-argument download descriptor ferry-work 0-2-0 links against still exists`() {
        val method = RepoDownloader::class.java.getDeclaredMethod(
            "download",
            String::class.java,
            Path::class.java,
            Function1::class.java,
            Continuation::class.java,
        )
        assertNotNull(method)
    }
}
```

- [ ] **Step 2: Run it — verify it passes** (it pins existing state; RED here would mean Task 1 broke the descriptor):

Run: `export JAVA_HOME=/Users/admin/Library/Java/JavaVirtualMachines/temurin-21.0.11/Contents/Home && ./gradlew :ferry:jvmTest --tests 'dev.thuat.ferry.BinaryCompatTest' 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL, 1 test passed.

- [ ] **Step 3: Sanity-check the negative** — temporarily change `Path::class.java` to `String::class.java` in the test, run, confirm it FAILS with `NoSuchMethodException`, revert. (Proves the pin actually pins.)

- [ ] **Step 4: CHANGELOG entry** — insert above `## 0.2.0`:

````markdown
## 0.3.0

`RepoDownloader.download` can download a subset of a repo's files — one quantisation variant out
of a multi-variant GGUF repo, instead of all of them:

```kotlin
ferry.download(repoId, into, fileFilter = Regex("Q4_K_M")) { progress -> ... }
```

- **`fileFilter: Regex?`** — a new `download(repoId, into, fileFilter, onProgress)` overload
  selects the manifest files whose path matches (`containsMatchIn`, substring semantics; anchor
  with `^...$` for a whole-path match). Every guarantee — space preflight, resume, verification,
  atomic commit — applies to the selected subset. A filter matching nothing fails rather than
  committing an empty repo. Staging is keyed per filter, so switching filters mid-flight
  preserves each filter's progress; `abandonStaging` reclaims all of them, and `stagedBytes`
  sums across them. A `download` against a directory already committed under a *different*
  filter (or none) is refused before any network request — remove the directory to switch
  variants in place, or use a different `into` per variant.
- **Additive, not breaking — and here is the mechanism, because 0.2.0's lesson was that the
  label alone is not enough:** the existing 3-argument `download` keeps its exact JVM descriptor
  and synthetic `download$default` bridge; the filter arrived as a separate overload, not an
  inserted parameter. Unlike 0.2.0, this release therefore does **not** force a lockstep
  `:ferry-work` bump — the published `ferry-work:0.2.0` keeps linking and working. It cannot
  *pass* a filter (its worker has no input key for one), so background filtered downloads need a
  future `:ferry-work` release; that is a missing feature, not a break.
````

- [ ] **Step 5: Version bump** — `ferry/build.gradle.kts`: `version = "0.3.0"`. Leave `ferry-work` at 0.2.0 (the point of the overload). Leave README's dependency coordinates at 0.2.0 — they advance when 0.3.0 is actually published, not before.

- [ ] **Step 6: README snippet** — insert directly after the three-hubs code block (after line ~56, before the "Each takes a Ktor `HttpClient`" paragraph):

````markdown
A repo with many quantisation variants doesn't force downloading all of them — `fileFilter`
selects a subset by path, and every guarantee (space preflight, resume, verification, atomic
commit) applies to just that subset:

```kotlin
ferry.download("bartowski/Qwen2.5-1.5B-Instruct-GGUF", dir, fileFilter = Regex("Q4_K_M"))
```
````

- [ ] **Step 7: Full gates**

Run: `export JAVA_HOME=/Users/admin/Library/Java/JavaVirtualMachines/temurin-21.0.11/Contents/Home && ./gradlew :ferry:jvmTest :ferry:iosX64Test 2>&1 | tail -8`
Expected: BUILD SUCCESSFUL, both targets green. Every commonTest added in Tasks 1-3 runs on iOS here; this is spec test 14's cross-platform half.

- [ ] **Step 8: Commit**

```bash
git add ferry/src/jvmTest/kotlin/dev/thuat/ferry/BinaryCompatTest.kt CHANGELOG.md ferry/build.gradle.kts README.md
git commit -m "test: pin the 3-arg download descriptor ferry-work 0.2.0 links against; docs: 0.3.0 changelog"
```

---

## Spec coverage map (self-review)

| Spec section / test | Task |
|---|---|
| API change, overload table, no-default rationale | 1 |
| Filter identity (`canonicalIdentity`/`filterKey`, options included) | 1 |
| Ordering table steps 1-11; step-2 gate | 1 (reorder), 2 (gate) |
| Marker format, never parsed, prefix-chooses-message | 2 |
| Filter-keyed staging, sibling-not-nested shape | 1 |
| `abandonStaging`/`stagedBytes` sweep, 64-hex load-bearing | 3 |
| Empty match fails loudly | 1 |
| Space preflight on `selected` | 1 |
| Resume same filter (distinct instances) | 1 |
| Compatibility: markers, staging, binaries | 2 (test 11), 1 (test 12), 4 (test 15) |
| Binary compatibility mechanism + changelog wording | 1, 4 |
| Progress relative to `selected` | 1 |
| Spec tests 1-15 | 1: tests 1,2,3,4,8,12,13 + pinned key · 2: tests 5,6,7,11 + unfiltered-reverse (review nit #1) · 3: tests 9,10 · 4: tests 14 (iOS gate),15 |
| Risks: pinned `filterKey` output | 1 (pinned-hex test) |
| Risks: 3-arg overload KDoc names ferry-work | 1 (KDoc), 4 (test comment) |
