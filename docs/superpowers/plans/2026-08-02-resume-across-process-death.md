# Resume Across Process Death — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A download interrupted by a failure, a cancellation or the process dying resumes from the bytes already on disk instead of restarting from zero — without ever letting a partial repo be mistaken for a committed one.

**Architecture:** Staging stops being deleted in a `finally` and becomes durable per-repo scratch. No new persistence layer is added: `ResumableDownloader` already writes `<file>.part` and `<file>.validator` next to each target, those land inside `into/.staging/<repoId>`, and together they fully describe progress. The work is deciding when that scratch is trustworthy, when it must be discarded, and who is allowed to delete it.

**Tech Stack:** Kotlin 2.0.21, OkHttp 4.12.0, JUnit 4.13.2, MockWebServer 4.12.0. No new dependencies.

## Global Constraints

- Package `dev.thuat.ferry`; `allWarningsAsErrors` is ON for every module
- No Android APIs in `:ferry`; no WorkManager/Service/Compose/DI — `checkEmbeddable` enforces the last one and is wired into `check`
- Nothing throws across a public boundary; public entry points return `Result<T>`. Total functions that cannot fail return their value directly
- Builds need `export JAVA_HOME=/Users/admin/Library/Java/JavaVirtualMachines/temurin-21.0.11/Contents/Home` — Homebrew moved the default JDK past what Gradle 8.9 parses, and it fails with a bare `IllegalArgumentException: 26.0.2`
- `./gradlew clean :ferry:check :ferry-work:testDebugUnitTest :sample:assembleDebug :sample:testDebugUnitTest` green before every commit. Baseline: 147 / 9 / 15
- Commit format `<type>: <description>` from feat, fix, refactor, docs, test, chore, perf, ci

## The safety argument, and why this is not as dangerous as it looks

`RepoDownloader` has produced **eight defects**, every one a containment boundary or ordering admitting a case it should not, and two fixes each introduced the next. Read `docs/known-limitations.md` before starting.

This change is nonetheless narrower than it sounds, because the isolation it depends on is already proven and tested:

- Staging is `into/.staging/<repoId>`, resolved through `resolveInside(stagingRoot, repoId)`.
- A `target` is explicitly forbidden from colliding with `stagingRoot` (`RepoDownloader.kt:164`).
- Ownership lives at `target/.ferry`, written from inside staging so it commits and dies with the directory. Nesting lives in a separate shadow tree at `into/.ferry/<repoId>/`.

So durable staging **cannot** be mistaken for a committed repo, and a committed repo cannot be inside staging. Guarantee 1 — never a partial model — is a statement about the *target* directory, and staging was never in it. Deleting staging was protecting against a disk leak, not against corruption.

**The real risks this plan must close are staleness and leakage, not confusion:**

1. A `.part` whose remote file has since changed size or hash
2. A `.part` for a file no longer in the manifest at all
3. Staging for a repo the caller has abandoned and will never retry, held forever

Per-file staleness is already handled and tested: `ResumableDownloader` stores the ETag as a validator, replays it with `If-Range`, and **refuses to resume at all when the server publishes no validator** — a design decision already made because resuming blind risks a corrupt file that is exactly the right size.

---

### Task 1: Stop deleting staging on failure

**Files:**
- Modify: `ferry/src/main/java/dev/thuat/ferry/RepoDownloader.kt`
- Test: `ferry/src/test/java/dev/thuat/ferry/RepoDownloaderTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: no signature change. Behaviour change only.

**Background:**

The `finally` at the end of `download` currently reads:

```kotlin
} finally {
    staging?.deleteRecursively()
}
```

Its comment already names this as the trade being reversed here. Success does not depend on it — the commit path is `stagingDir.renameTo(target)`, which moves the directory away, so there is nothing left for the `finally` to delete on the happy path.

**What must still be deleted:** nothing, on any path. Removing the block entirely is the change. The rename consumes staging on success; a failure now leaves it deliberately.

**The one case to think about before deleting the line:** a failure *after* the rename succeeded. Walk the code and confirm whether any path can reach the `finally` with `staging` pointing at a directory that has already been renamed away. `deleteRecursively` on a non-existent directory is harmless, so this is about understanding rather than safety — but say what you found in the report.

- [ ] **Step 1: Write the failing test**

```kotlin
/**
 * The bytes already fetched are the whole point of resuming. Before this change the finally
 * block deleted them, so a second attempt re-downloaded a multi-gigabyte model from zero.
 */
@Test
fun `a failed download leaves its partial bytes in staging`() {
    val files = listOf(remote("model.bin", weightsBody.length.toLong(), shaOf(weightsBody)))
    // A body shorter than declared fails the size check after writing what it sent.
    server.enqueue(MockResponse().setBody(weightsBody.take(5)))

    val result = runBlocking { downloaderFor(files).download("a/b", temp.root) }

    assertTrue(result.isFailure)
    val staged = File(temp.root, ".staging/a/b/model.bin.part")
    assertTrue("the partial file is the resume point and must survive", staged.isFile)
    assertEquals(5, staged.length())
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :ferry:testDebugUnitTest --tests "*RepoDownloaderTest*"`
Expected: FAIL on `staged.isFile` — the `finally` deleted the directory.

- [ ] **Step 3: Delete the finally block**

Remove `staging?.deleteRecursively()` and its `finally`. Keep the `staging` variable if the code still reads it; delete it if nothing does, since `allWarningsAsErrors` will fail on an unused local.

Replace the removed comment with one stating the new contract: staging is durable scratch, it is consumed by the rename on success, and Task 3 is what removes it otherwise.

- [ ] **Step 4: Run again**

Expected: PASS. Every pre-existing test must still pass — if one asserted staging was cleaned up, it was asserting the behaviour being reversed, so read it before changing it and say in the report which one and why.

- [ ] **Step 5: Commit**

```bash
git add ferry/src/main/java/dev/thuat/ferry/RepoDownloader.kt \
        ferry/src/test/java/dev/thuat/ferry/RepoDownloaderTest.kt
git commit -m "feat: keep staging after a failed attempt

The partial bytes are the resume point. Deleting them in a finally guaranteed no
half-written repo survived a failure, but staging was never inside the target
directory that guarantee is about — it was protecting against a disk leak, which
Task 3 addresses directly instead."
```

---

### Task 2: Discard staged files the manifest no longer vouches for

**Files:**
- Modify: `ferry/src/main/java/dev/thuat/ferry/RepoDownloader.kt`
- Test: `ferry/src/test/java/dev/thuat/ferry/RepoDownloaderTest.kt`

**Interfaces:**
- Consumes: `RepoManifest`, `RemoteFile` from `ModelRepo.kt`.
- Produces: no public signature change.

**Background:**

Durable staging means a `.part` can outlive the manifest that produced it. Two shapes matter, and they are different problems:

**A file no longer in the manifest.** The hub removed or renamed it. Its `.part` is dead weight that will never be completed or committed. It must be deleted, or a long-lived repo accretes junk forever.

**A file still in the manifest but whose declared size or hash changed.** Resuming onto it would append new bytes to old ones. `ResumableDownloader`'s `If-Range` handling catches this *when the server publishes a validator*, and refuses to resume when it does not — but that is a per-request defence against the server changing under one attempt. It does not know the manifest declared something different last time, because nothing records what was declared last time.

Deciding what to record is this task's real work. The cheapest sufficient answer: **the `.part`'s own length is already checked against the manifest's declared size at commit time, and a hash change makes verification fail** — so a stale `.part` produces a failed verification and a re-download rather than a corrupt commit. That is correct but wasteful. Judge whether it is enough, and if you add a record of the previous declaration, keep it inside staging so it dies with the scratch it describes.

**Do not** add a database, a preferences file, or any state outside the staging directory. The filesystem is the state; that property is what makes this feature small.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `a staged file the manifest no longer lists is discarded`() {
    val orphan = File(temp.root, ".staging/a/b/gone.bin.part").apply {
        parentFile?.mkdirs()
        writeText("stale bytes from a manifest that no longer lists this file")
    }
    val files = listOf(remote("config.json", configBody.length.toLong()))
    server.enqueue(MockResponse().setBody(configBody))

    runBlocking { downloaderFor(files).download("a/b", temp.root) }

    assertFalse("an orphaned .part must not accrete forever", orphan.exists())
}

@Test
fun `a staged file the manifest still lists survives to be resumed`() {
    val partial = File(temp.root, ".staging/a/b/config.json.part").apply {
        parentFile?.mkdirs()
        writeText(configBody.take(4))
    }
    val files = listOf(remote("config.json", configBody.length.toLong()))
    server.enqueue(MockResponse().setResponseCode(206).setBody(configBody.drop(4))
        .addHeader("Content-Range", "bytes 4-${configBody.length - 1}/${configBody.length}"))

    val dir = runBlocking { downloaderFor(files).download("a/b", temp.root) }.getOrThrow()

    assertEquals(configBody, File(dir, "config.json").readText())
    assertFalse(partial.exists())
}
```

⚠ The second test resumes only if a validator exists — `ResumableDownloader` refuses to resume without one. Check `ResumableDownloaderTest`'s existing resume tests for the exact fixture shape (they write an `asset.bin.validator` alongside the `.part`) and mirror it, or the test will pass for the wrong reason: a restart that also produces the right bytes.

- [ ] **Step 2: Run and watch both fail**

Run: `./gradlew :ferry:testDebugUnitTest --tests "*RepoDownloaderTest*"`
Expected: the orphan test fails (nothing prunes it). The resume test may pass already — if it does, it is proving `ResumableDownloader`'s existing behaviour rather than this task's, which is fine as a regression pin but say so in its KDoc.

- [ ] **Step 3: Prune before downloading**

After the manifest resolves and staging is known, delete any `.part` or `.validator` in staging whose corresponding path is not in `manifest.files`. Walk staging, not the manifest — the orphans are exactly what the manifest cannot name.

Reuse `resolveInside` for every path you build. Do not construct a path from a staged filename without it: staging content is not attacker-controlled today, but the whole point of that helper is that nothing has to remember which paths are trusted.

- [ ] **Step 4: Run again**

Expected: PASS, and every pre-existing test still green.

- [ ] **Step 5: Commit**

```bash
git commit -m "feat: prune staged files the manifest no longer vouches for

Durable staging means a .part can outlive the manifest that produced it. One the
hub has since removed will never be completed or committed, so a long-lived repo
would accrete them forever."
```

---

### Task 3: An explicit way to abandon a download

**Files:**
- Modify: `ferry/src/main/java/dev/thuat/ferry/RepoDownloader.kt`
- Test: `ferry/src/test/java/dev/thuat/ferry/RepoDownloaderTest.kt`

**Interfaces:**
- Produces: `suspend fun abandon(repoId: String, into: File): Result<Unit>` on `RepoDownloader`.

**Background:**

Task 1 removed the only thing that ever deleted staging on a failure. Without a replacement, a caller who starts a 5 GB download, fails at 80%, and never retries has leaked 4 GB with no way to reclaim it — and `known-limitations.md` already records that a refused directory needing manual removal is a bad shape.

This is the second deliberate deletion capability added to a file whose worst defects were all deleting the wrong thing. It must be able to delete **only** `into/.staging/<repoId>`:

- resolved through `resolveInside(stagingRoot, repoId)`, exactly as `download` does
- never `into`, never `stagingRoot` itself, never a committed target
- and it must **not** touch `into/<repoId>` — abandoning an in-progress download says nothing about a previously committed copy, which may be complete and in use

State that last point in the KDoc. A method named `abandon` that silently deletes a working model would be the worst API in the library.

Absent staging is success, not an error — the caller asked for a state, and that state already holds.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `abandon removes only this repo's staging`() {
    val mine = File(temp.root, ".staging/a/b/model.bin.part").apply { parentFile?.mkdirs(); writeText("mine") }
    val other = File(temp.root, ".staging/c/d/model.bin.part").apply { parentFile?.mkdirs(); writeText("other") }

    val result = runBlocking { downloaderFor(emptyList()).abandon("a/b", temp.root) }

    assertTrue(result.isSuccess)
    assertFalse(mine.exists())
    assertTrue("another repo's staging is not this call's business", other.exists())
}

@Test
fun `abandon does not touch an already committed repo`() {
    val committed = File(temp.root, "a/b/model.bin").apply { parentFile?.mkdirs(); writeText("committed bytes") }
    File(temp.root, "a/b/.ferry").writeText("a/b")

    runBlocking { downloaderFor(emptyList()).abandon("a/b", temp.root) }

    assertTrue("abandoning a download says nothing about a completed one", committed.exists())
    assertEquals("committed bytes", committed.readText())
}

@Test
fun `abandon cannot escape into`() {
    val outside = File(temp.root, "outside.txt").apply { writeText("not yours") }

    val result = runBlocking { downloaderFor(emptyList()).abandon("../..", temp.root) }

    assertTrue(result.isFailure)
    assertTrue(outside.exists())
}

@Test
fun `abandoning a repo with no staging succeeds`() {
    assertTrue(runBlocking { downloaderFor(emptyList()).abandon("never/started", temp.root) }.isSuccess)
}
```

- [ ] **Step 2: Run and watch them fail**

Expected: compilation failure — `abandon` does not exist.

- [ ] **Step 3: Implement**

Mirror `download`'s guard sequence exactly rather than writing a new one. Any `IOException` becomes `Result.failure`.

- [ ] **Step 4: Run**

Expected: PASS.

- [ ] **Step 5: Prove the escape guard is load-bearing**

Remove the `resolveInside` call, confirm `abandon cannot escape into` goes red, restore. A guard whose test cannot fail is not a guard. Report exactly what you saw.

- [ ] **Step 6: Commit**

```bash
git commit -m "feat: abandon(repoId, into) to reclaim a download's staging

Task 1 removed the only thing that deleted staging on failure, so a caller who
never retries had no way to reclaim the bytes. Deletes only this repo's staging —
never into, never the staging root, and never a committed target, because
abandoning an in-progress download says nothing about a completed one."
```

---

### Task 4: Account for staged bytes in the space check

**Files:**
- Modify: `ferry/src/main/java/dev/thuat/ferry/RepoDownloader.kt`
- Test: `ferry/src/test/java/dev/thuat/ferry/RepoDownloaderTest.kt`

**Interfaces:**
- Consumes: `SpaceCheck`, `SpaceReport` — unchanged.

**Background:**

`SpaceCheck.check` reserves `manifest.totalBytes`. Resuming a download that is 90% staged only needs the remaining 10%, but the check still demands the whole amount — so a device with room to *finish* is told it has no room to *start*.

That is guarantee 3 producing a false refusal, and it is worse with resume than without: the more progress a user has made, the more likely they are to be refused.

Subtract the bytes already staged. Be careful about the direction of the error: over-reserving refuses a download that would have fit, under-reserving starts one that fills the disk. Only count a staged file's bytes when they are genuinely reusable — a `.part` with no validator will not be resumed, so its bytes are not credit.

`SpaceCheck`'s own signature stays as it is. This is `RepoDownloader` computing a more accurate `requiredBytes`, not a change to what the checker does with it.

- [ ] **Step 1: Write the failing test**

```kotlin
/**
 * A device with room to finish must not be told it has no room to start. The more progress a
 * resumable download has made, the more this matters — and the more likely a naive check is to
 * refuse it.
 */
@Test
fun `a mostly staged download only needs the remaining bytes`() {
    val body = "0123456789"
    File(temp.root, ".staging/a/b/model.bin.part").apply {
        parentFile?.mkdirs()
        writeText(body.take(8))
    }
    File(temp.root, ".staging/a/b/model.bin.validator").writeText("\"v1\"")
    val files = listOf(remote("model.bin", body.length.toLong(), shaOf(body)))
    server.enqueue(
        MockResponse().setResponseCode(206).setBody(body.drop(8))
            .addHeader("Content-Range", "bytes 8-9/10"),
    )

    // Room for the two remaining bytes, nowhere near room for all ten.
    val downloader = RepoDownloader(
        repo = fakeRepo(files),
        downloader = ResumableDownloader(OkHttpClient()),
        spaceCheck = SpaceCheck(probe = { 4 }, headroomBytes = 0L),
    )

    val result = runBlocking { downloader.download("a/b", temp.root) }

    assertTrue("8 of 10 bytes are already on disk", result.isSuccess)
}
```

- [ ] **Step 2: Run and watch it fail**

Expected: FAIL with `InsufficientSpaceException` — the check demanded all ten bytes.

- [ ] **Step 3: Implement**

- [ ] **Step 4: Run, and confirm the guarantee still holds**

Expected: PASS. Every existing space test must still pass — in particular the one asserting a genuinely-too-large repo is still refused, and the one asserting a refusal issues no network request. If either needs weakening, stop and report it: a space check that stopped refusing is worse than one that over-reserves.

- [ ] **Step 5: Commit**

---

### Task 5: Let a caller see there is progress to resume

**Files:**
- Modify: `ferry/src/main/java/dev/thuat/ferry/RepoDownloader.kt`
- Test: `ferry/src/test/java/dev/thuat/ferry/RepoDownloaderTest.kt`

**Interfaces:**
- Produces: `fun stagedBytes(repoId: String, into: File): Long` on `RepoDownloader`, or a small result type if you prefer — decide and defend it.

**Background:**

Without this, resume is invisible: a UI cannot distinguish "start" from "resume", so it cannot offer the Resume button that motivated this whole plan. The sample app is the consumer that proves the API is usable, and it is why this task exists rather than being deferred as polish.

Keep it cheap — this may be called to render a list. Sum the reusable staged bytes; do not hash anything, do not touch the network.

A total function: an unreadable or absent staging directory is zero, not a failure. Say so in the KDoc, and say that the number is a hint for presentation rather than a promise about what the next attempt will transfer — the hub may invalidate a `.part` the moment it is asked for.

- [ ] **Step 1: Write the failing test**
- [ ] **Step 2: Run and watch it fail**
- [ ] **Step 3: Implement**
- [ ] **Step 4: Run**
- [ ] **Step 5: Commit**

---

### Task 6: Show it in the sample

**Files:**
- Modify: `sample/src/main/java/dev/thuat/ferry/sample/DownloadState.kt`
- Modify: `sample/src/main/java/dev/thuat/ferry/sample/SampleViewModel.kt`
- Modify: `sample/src/main/java/dev/thuat/ferry/sample/SampleScreen.kt`
- Test: `sample/src/test/java/dev/thuat/ferry/sample/ProgressMappingTest.kt`

**Background:**

The sample is the failure harness, and it is what found two bugs the 106-test suite could not see. Resume is exactly the kind of feature whose API looks fine until something consumes it.

Add a state for "interrupted, N of M bytes on disk", offering **Resume** and **Discard**. Resume calls `download` again; Discard calls `abandon`.

The row must not change size between states — the same no-layout-shift rule the rest of this project holds to.

⚠ Resume genuinely restarting from zero, because the hub published no validator, is a real outcome and the UI should not claim otherwise. Decide how to present it and say why. Silently showing "Resuming…" while re-fetching a gigabyte is the kind of lie this project exists to avoid.

- [ ] **Step 1: Write the failing mapping test**
- [ ] **Step 2: Run and watch it fail**
- [ ] **Step 3: Implement state, view model, UI**
- [ ] **Step 4: Run**
- [ ] **Step 5: Verify on a device or emulator** — kill the app mid-download, reopen, confirm the row offers Resume and that resuming transfers less than the whole repo
- [ ] **Step 6: Commit**

---

## Out of Scope

- **Pause.** Distinct from resume: it needs cooperative cancellation plumbed through the download loop. Coroutine cancellation already stops a download; what does not exist is a way to stop it *deliberately* and record that it was intentional rather than failed.
- **Automatic cleanup of abandoned staging.** `abandon` is explicit. An age-based sweep is a policy decision — whose clock, what age, triggered by what — and it belongs to a host, not a library that has no lifecycle of its own.
- **Cross-process locking.** Two processes downloading one repo remains the caller's problem, as `known-limitations.md` records.
- **Migrating existing staging.** Any staging on disk today was written by a build that deleted it on failure, so none can exist across this change in practice.

## Docs

Update on the final task, not incrementally:

- `README.md` guarantee 4 currently reads "Resumable within one download attempt", and the paragraph beneath it explains the `finally` that forfeited the rest. Both become wrong.
- The status block says pause and resume-across-launch are not done. Half of that stops being true.
- `docs/known-limitations.md` — the "Resume does not survive the process" entry closes. Whatever residual remains gets stated with its condition, in the file's existing style.
