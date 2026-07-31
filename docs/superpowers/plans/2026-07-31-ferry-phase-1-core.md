# Ferry Phase 1–2 Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Download a HuggingFace model repository to a directory, refusing to start if it cannot fit and refusing to commit if any file fails its SHA-256.

**Architecture:** A `ModelRepo` interface describes a hub; `HuggingFace` implements it by reading the public tree API. `RepoDownloader` orchestrates: check space, download each file through the existing `ResumableDownloader` into a staging directory, verify each hash, then move the whole directory into place. Nothing reaches the final path until every file verifies, so a reader never sees a partial or corrupt repo.

**Tech Stack:** Kotlin 2.0.21, OkHttp 4.12.0, kotlinx-serialization-json 1.7.3, kotlinx-coroutines 1.9.0, JUnit 4.13.2, MockWebServer 4.12.0.

## Global Constraints

- **TDD is not optional here, and step 2 is the point of it.** Write the test, run it, watch it
  fail, then implement. A test written after the code passes for reasons nobody checked.
- **A compilation error is a weak red.** It proves the class was missing, not that the assertion
  would catch a wrong implementation. Tasks 2 and 4 therefore carry an explicit mutation step:
  break the implementation on purpose, confirm the right tests go red, restore. An assertion that
  cannot fail is worse than no assertion, because it reports safety it never checked.
- **Never edit a test to match a failing implementation** unless the test's own setup is wrong —
  and if it is, say so out loud rather than quietly adjusting it.
- Package for all new code: `io.github.nthuat.ferry`
- Kotlin `2.0.21`, JVM target `17`, `minSdk 26`, `compileSdk 35`, AGP `8.7.3`
- `allWarningsAsErrors.set(true)` is already on in `ferry/build.gradle.kts`. Unused imports, unused parameters, and deprecation warnings **fail the build**. Do not add an import you do not use.
- **No Android APIs in any file in this plan.** Not `StatFs`, not `Context`, not `Log`. Every class here must be constructible and testable from a plain JVM unit test. `java.io.File.usableSpace` gives free space on Android and on the JVM, which is why `StatFs` is not needed.
- **No WorkManager, no Service, no Compose, no dependency injection framework.** The two apps this library targets background work differently (MNN uses a foreground `Service`, Google's Gallery uses `CoroutineWorker`), so Ferry must have no opinion about backgrounding.
- All suspend entry points take a `CoroutineDispatcher` parameter defaulting to `Dispatchers.IO`, so tests can inject a deterministic one.
- Public API returns `Result<T>`. Do not throw across the public boundary.
- Tests run with `./gradlew :ferry:testDebugUnitTest`.
- Every commit message follows `<type>: <description>` with types from: feat, fix, refactor, docs, test, chore, perf, ci.

## Already Exists — Do Not Rewrite

`ferry/src/main/java/io/github/nthuat/ferry/ResumableDownloader.kt` is written, committed, and covered by 11 tests in `ResumableDownloaderTest.kt`. Its public surface, which Task 4 consumes:

```kotlin
class ResumableDownloader(
    private val client: OkHttpClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun download(
        url: String,
        target: File,
        onProgress: (bytesWritten: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): Result<File>
}
```

## File Structure

| File | Responsibility |
|---|---|
| `ModelRepo.kt` | `RemoteFile`, `RepoManifest`, `ModelRepo` interface. No I/O. |
| `HuggingFace.kt` | `ModelRepo` for huggingface.co. Tree API parsing and file URL construction. |
| `SpaceCheck.kt` | `SpaceReport`, `FreeSpaceProbe`, `SpaceCheck`. Pure arithmetic over a probe. |
| `Sha256.kt` | Streaming hash of a file, hex comparison. |
| `RepoDownloader.kt` | Orchestration, staging directory, commit-or-nothing, `RepoProgress`, exceptions. |
| `Ferry.kt` | Public facade. Wires the default OkHttp client and dependencies together. |

Six small files rather than two large ones: `HuggingFace` will gain siblings (`ModelScope`) and `RepoDownloader` will gain an Android backgrounding wrapper, so the seams are placed where the growth is.

---

### Task 1: Repo model and HuggingFace tree API

**Files:**
- Create: `ferry/src/main/java/io/github/nthuat/ferry/ModelRepo.kt`
- Create: `ferry/src/main/java/io/github/nthuat/ferry/HuggingFace.kt`
- Test: `ferry/src/test/java/io/github/nthuat/ferry/HuggingFaceTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `data class RemoteFile(val path: String, val sizeBytes: Long, val sha256: String?)`
  - `data class RepoManifest(val repoId: String, val files: List<RemoteFile>)` with `val totalBytes: Long`
  - `interface ModelRepo { suspend fun manifest(repoId: String): Result<RepoManifest>; fun fileUrl(repoId: String, path: String): String }`
  - `class HuggingFace(client: OkHttpClient, baseUrl: String = "https://huggingface.co", dispatcher: CoroutineDispatcher = Dispatchers.IO) : ModelRepo`

**Background the implementer needs:**

`GET https://huggingface.co/api/models/{repoId}/tree/main` returns a JSON array. Real response, trimmed:

```json
[
  { "type": "file", "path": "config.json", "size": 659 },
  { "type": "file", "path": "model.safetensors", "size": 988097824,
    "oid": "d7db405a3f0d9bf1ba5bdd4e4211db8022ebe4eb",
    "lfs": { "oid": "fdf756fa7fcbe7404d5c60e26bff1a0c8b8aa1f72ced49e7dd0210fe288fb7fe", "size": 988097824 },
    "xetHash": "bb5ff7e71536bbce6378f6d4bb523a77f1e9455965702d18bec33f599d5851f7" }
]
```

Three things that are easy to get wrong:

1. **`lfs.oid` is the SHA-256. The top-level `oid` is a git blob SHA-1.** Using the top-level `oid` gives a hash that never matches the file's contents.
2. **Small files have no `lfs` block at all**, so `sha256` is nullable. Those files are verified by size only.
3. **`ignoreUnknownKeys = true` is mandatory, not hygiene.** `xetHash` did not exist in this response a year ago. HuggingFace adds fields; strict parsing turns that into a production outage.

- [ ] **Step 1: Write the failing test**

Create `ferry/src/test/java/io/github/nthuat/ferry/HuggingFaceTest.kt`:

```kotlin
package io.github.nthuat.ferry

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HuggingFaceTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: HuggingFace

    /** Shaped exactly like the live response, including a field this code does not know about. */
    private val treeJson = """
        [
          { "type": "directory", "path": "onnx" },
          { "type": "file", "path": "config.json", "size": 659 },
          { "type": "file", "path": "model.safetensors", "size": 988097824,
            "oid": "d7db405a3f0d9bf1ba5bdd4e4211db8022ebe4eb",
            "lfs": { "oid": "fdf756fa7fcbe7404d5c60e26bff1a0c8b8aa1f72ced49e7dd0210fe288fb7fe",
                     "size": 988097824 },
            "xetHash": "bb5ff7e71536bbce6378f6d4bb523a77f1e9455965702d18bec33f599d5851f7" }
        ]
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        repo = HuggingFace(OkHttpClient(), baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `manifest lists files and skips directories`() {
        server.enqueue(MockResponse().setBody(treeJson))

        val manifest = runBlocking { repo.manifest("Qwen/Qwen2.5-0.5B-Instruct") }.getOrThrow()

        assertEquals(listOf("config.json", "model.safetensors"), manifest.files.map { it.path })
    }

    @Test
    fun `sha256 comes from lfs oid, not the top level git oid`() {
        server.enqueue(MockResponse().setBody(treeJson))

        val manifest = runBlocking { repo.manifest("Qwen/Qwen2.5-0.5B-Instruct") }.getOrThrow()
        val weights = manifest.files.single { it.path == "model.safetensors" }

        assertEquals(
            "fdf756fa7fcbe7404d5c60e26bff1a0c8b8aa1f72ced49e7dd0210fe288fb7fe",
            weights.sha256,
        )
    }

    @Test
    fun `a small non-lfs file has no sha256`() {
        server.enqueue(MockResponse().setBody(treeJson))

        val manifest = runBlocking { repo.manifest("Qwen/Qwen2.5-0.5B-Instruct") }.getOrThrow()

        assertNull(manifest.files.single { it.path == "config.json" }.sha256)
    }

    @Test
    fun `total bytes sums every file`() {
        server.enqueue(MockResponse().setBody(treeJson))

        val manifest = runBlocking { repo.manifest("Qwen/Qwen2.5-0.5B-Instruct") }.getOrThrow()

        assertEquals(659L + 988097824L, manifest.totalBytes)
    }

    /** xetHash is in the fixture and unknown to the parser. Strict parsing would throw here. */
    @Test
    fun `unknown fields do not break parsing`() {
        server.enqueue(MockResponse().setBody(treeJson))

        assertTrue(runBlocking { repo.manifest("any/repo") }.isSuccess)
    }

    @Test
    fun `an http error is returned as a failure`() {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = runBlocking { repo.manifest("nope/nope") }

        assertTrue(result.isFailure)
    }

    @Test
    fun `malformed json is returned as a failure, not thrown`() {
        server.enqueue(MockResponse().setBody("not json at all"))

        val result = runBlocking { repo.manifest("any/repo") }

        assertTrue(result.isFailure)
    }

    @Test
    fun `manifest requests the tree endpoint`() {
        server.enqueue(MockResponse().setBody(treeJson))

        runBlocking { repo.manifest("Qwen/Qwen2.5-0.5B-Instruct") }

        assertEquals(
            "/api/models/Qwen/Qwen2.5-0.5B-Instruct/tree/main",
            server.takeRequest().path,
        )
    }

    @Test
    fun `file url points at the resolve endpoint`() {
        val url = repo.fileUrl("Qwen/Qwen2.5-0.5B-Instruct", "model.safetensors")

        assertTrue(url.endsWith("/Qwen/Qwen2.5-0.5B-Instruct/resolve/main/model.safetensors"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ferry:testDebugUnitTest --tests "*HuggingFaceTest*"`
Expected: FAIL at compilation — `Unresolved reference 'HuggingFace'`.

- [ ] **Step 3: Write the model types**

Create `ferry/src/main/java/io/github/nthuat/ferry/ModelRepo.kt`:

```kotlin
package io.github.nthuat.ferry

/**
 * One downloadable file in a model repository.
 *
 * [sha256] is null for files the hub does not track with a content hash — typically small config
 * and tokenizer files. Those are verified by size alone, which is weaker and unavoidable.
 */
data class RemoteFile(
    val path: String,
    val sizeBytes: Long,
    val sha256: String?,
)

/** Everything needed to decide whether a repo can be downloaded, before downloading any of it. */
data class RepoManifest(
    val repoId: String,
    val files: List<RemoteFile>,
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
}

/**
 * A model hub. Implemented per host, because HuggingFace and ModelScope describe repositories
 * differently while the download mechanics are identical.
 */
interface ModelRepo {

    suspend fun manifest(repoId: String): Result<RepoManifest>

    fun fileUrl(repoId: String, path: String): String
}
```

- [ ] **Step 4: Write the HuggingFace implementation**

Create `ferry/src/main/java/io/github/nthuat/ferry/HuggingFace.kt`:

```kotlin
package io.github.nthuat.ferry

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Reads repository listings from huggingface.co.
 *
 * The tree endpoint is public and needs no authentication for public models, which is the only
 * case this supports today.
 */
class HuggingFace(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://huggingface.co",
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ModelRepo {

    override suspend fun manifest(repoId: String): Result<RepoManifest> = withContext(dispatcher) {
        val request = Request.Builder().url("$baseUrl/api/models/$repoId/tree/main").build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("HTTP ${response.code} listing $repoId"),
                    )
                }
                val body = response.body?.string()
                    ?: return@withContext Result.failure(IOException("empty tree response for $repoId"))
                val files = json.decodeFromString<List<TreeEntry>>(body)
                    .filter { it.type == "file" }
                    .map { entry ->
                        RemoteFile(
                            path = entry.path,
                            // The lfs block carries the authoritative size for large files.
                            sizeBytes = entry.lfs?.size ?: entry.size,
                            // lfs.oid is the SHA-256. The sibling top-level `oid` is a git blob
                            // SHA-1 and will never match the file's contents.
                            sha256 = entry.lfs?.oid,
                        )
                    }
                Result.success(RepoManifest(repoId = repoId, files = files))
            }
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: SerializationException) {
            Result.failure(IOException("malformed tree response for $repoId", e))
        }
    }

    override fun fileUrl(repoId: String, path: String): String =
        "$baseUrl/$repoId/resolve/main/$path"

    @Serializable
    private data class TreeEntry(
        val type: String,
        val path: String,
        val size: Long = 0,
        val lfs: Lfs? = null,
    )

    @Serializable
    private data class Lfs(
        val oid: String,
        val size: Long,
    )

    private companion object {
        /**
         * ignoreUnknownKeys is load-bearing, not hygiene. HuggingFace adds fields to this response
         * without notice — `xetHash` is a recent one — and strict parsing would turn that into a
         * production outage on a day nobody deployed anything.
         */
        val json = Json { ignoreUnknownKeys = true }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :ferry:testDebugUnitTest --tests "*HuggingFaceTest*"`
Expected: PASS, 9 tests.

- [ ] **Step 6: Commit**

```bash
git add ferry/src/main/java/io/github/nthuat/ferry/ModelRepo.kt \
        ferry/src/main/java/io/github/nthuat/ferry/HuggingFace.kt \
        ferry/src/test/java/io/github/nthuat/ferry/HuggingFaceTest.kt
git commit -m "feat: read HuggingFace repo manifests

Takes sha256 from lfs.oid rather than the sibling top-level oid, which is a git
blob SHA-1 and never matches file contents. Parses leniently because HuggingFace
adds response fields without notice."
```

---

### Task 2: Free-space check

**Files:**
- Create: `ferry/src/main/java/io/github/nthuat/ferry/SpaceCheck.kt`
- Test: `ferry/src/test/java/io/github/nthuat/ferry/SpaceCheckTest.kt`

**Interfaces:**
- Consumes: `RepoManifest` and `RemoteFile` from Task 1.
- Produces:
  - `data class SpaceReport(val requiredBytes: Long, val freeBytes: Long, val headroomBytes: Long)` with `val sufficient: Boolean` and `val shortfallBytes: Long`
  - `fun interface FreeSpaceProbe { fun freeBytes(dir: File): Long }`
  - `class SpaceCheck(probe: FreeSpaceProbe = DefaultFreeSpaceProbe, headroomBytes: Long = 256L * 1024 * 1024)` with `fun check(manifest: RepoManifest, targetDir: File): SpaceReport`
  - `val DefaultFreeSpaceProbe: FreeSpaceProbe`

**Background the implementer needs:**

This is the guarantee neither MNN nor Google's AI Edge Gallery implements. Both start a multi-gigabyte download on a device that cannot hold it and fail near the end.

`java.io.File.usableSpace` reports free bytes on Android and on the JVM. Android's `StatFs` is the older API for the same number and is deliberately not used, because depending on it would make this class untestable from a plain JVM test.

Headroom exists because a filesystem at exactly zero free bytes misbehaves in ways unrelated to this download, and because the staging directory briefly holds a file while it is being moved.

- [ ] **Step 1: Write the failing test**

Create `ferry/src/test/java/io/github/nthuat/ferry/SpaceCheckTest.kt`:

```kotlin
package io.github.nthuat.ferry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SpaceCheckTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val oneGb = 1024L * 1024 * 1024

    private fun manifestOf(vararg sizes: Long) = RepoManifest(
        repoId = "test/repo",
        files = sizes.mapIndexed { i, size -> RemoteFile("file$i.bin", size, null) },
    )

    private fun checkWith(freeBytes: Long, headroom: Long = 0L) =
        SpaceCheck(probe = { freeBytes }, headroomBytes = headroom)

    @Test
    fun `sufficient when free space exceeds the total`() {
        val report = checkWith(freeBytes = 4 * oneGb).check(manifestOf(oneGb, oneGb), temp.root)

        assertTrue(report.sufficient)
        assertEquals(0L, report.shortfallBytes)
    }

    @Test
    fun `insufficient when the repo is larger than free space`() {
        val report = checkWith(freeBytes = 2 * oneGb).check(manifestOf(3 * oneGb), temp.root)

        assertFalse(report.sufficient)
    }

    @Test
    fun `shortfall reports exactly how many bytes are missing`() {
        val report = checkWith(freeBytes = 2 * oneGb).check(manifestOf(3 * oneGb), temp.root)

        assertEquals(oneGb, report.shortfallBytes)
    }

    @Test
    fun `headroom is required on top of the repo size`() {
        // Exactly enough for the files, nothing spare.
        val report = SpaceCheck(probe = { oneGb }, headroomBytes = oneGb)
            .check(manifestOf(oneGb), temp.root)

        assertFalse("a full disk must not count as sufficient", report.sufficient)
        assertEquals(oneGb, report.shortfallBytes)
    }

    @Test
    fun `an empty repo needs only headroom`() {
        val report = SpaceCheck(probe = { 10L }, headroomBytes = 0L)
            .check(RepoManifest("test/repo", emptyList()), temp.root)

        assertTrue(report.sufficient)
        assertEquals(0L, report.requiredBytes)
    }

    @Test
    fun `the report carries the numbers needed to explain the refusal`() {
        val report = checkWith(freeBytes = 2 * oneGb, headroom = 0L).check(manifestOf(3 * oneGb), temp.root)

        assertEquals(3 * oneGb, report.requiredBytes)
        assertEquals(2 * oneGb, report.freeBytes)
    }

    @Test
    fun `the default probe reports a positive figure for a real directory`() {
        assertTrue(DefaultFreeSpaceProbe.freeBytes(temp.root) > 0L)
    }

    @Test
    fun `the probe is asked about the target directory`() {
        var asked: File? = null
        SpaceCheck(probe = { dir -> asked = dir; oneGb }, headroomBytes = 0L)
            .check(manifestOf(1L), temp.root)

        assertEquals(temp.root, asked)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ferry:testDebugUnitTest --tests "*SpaceCheckTest*"`
Expected: FAIL at compilation — `Unresolved reference 'SpaceCheck'`.

- [ ] **Step 3: Write the implementation**

Create `ferry/src/main/java/io/github/nthuat/ferry/SpaceCheck.kt`:

```kotlin
package io.github.nthuat.ferry

import java.io.File

/**
 * The answer to "can this download finish?", produced before the first byte is requested.
 *
 * Carries the raw figures rather than a boolean alone so a caller can say *why* it refused —
 * "needs 4.1 GB, 2.3 GB free" is actionable, "download failed" is not.
 */
data class SpaceReport(
    val requiredBytes: Long,
    val freeBytes: Long,
    val headroomBytes: Long,
) {
    val sufficient: Boolean get() = freeBytes >= requiredBytes + headroomBytes

    val shortfallBytes: Long get() = maxOf(0L, requiredBytes + headroomBytes - freeBytes)
}

/** Indirection so tests can state a free-space figure instead of filling a real disk. */
fun interface FreeSpaceProbe {
    fun freeBytes(dir: File): Long
}

/**
 * `usableSpace` works on Android and on the JVM. Android's StatFs reports the same number and is
 * deliberately avoided: depending on it would make every caller of this class need an instrumented
 * test to exercise a branch of arithmetic.
 */
val DefaultFreeSpaceProbe = FreeSpaceProbe { dir -> dir.usableSpace }

/**
 * Guarantee 3 — never start what cannot finish.
 *
 * Neither of the two reference implementations of this problem checks free space, so both begin a
 * multi-gigabyte transfer on a device that cannot hold it and fail near the end, having spent the
 * user's data allowance to get there.
 */
class SpaceCheck(
    private val probe: FreeSpaceProbe = DefaultFreeSpaceProbe,
    private val headroomBytes: Long = DEFAULT_HEADROOM_BYTES,
) {

    fun check(manifest: RepoManifest, targetDir: File): SpaceReport = SpaceReport(
        requiredBytes = manifest.totalBytes,
        freeBytes = probe.freeBytes(targetDir),
        headroomBytes = headroomBytes,
    )

    companion object {
        /**
         * A filesystem at zero free bytes misbehaves in ways unrelated to this download, and the
         * staging directory briefly holds a file while it is being moved into place.
         */
        const val DEFAULT_HEADROOM_BYTES: Long = 256L * 1024 * 1024
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :ferry:testDebugUnitTest --tests "*SpaceCheckTest*"`
Expected: PASS, 8 tests.

- [ ] **Step 5: Prove the tests can actually fail**

Step 2's red was a compilation error, which proves only that the class was missing — not that these
assertions would catch a wrong implementation. An assertion that cannot fail is worse than no
assertion, because it reports safety it never checked.

Temporarily invert the comparison in `SpaceReport`:

```kotlin
val sufficient: Boolean get() = freeBytes <= requiredBytes + headroomBytes
```

Run: `./gradlew :ferry:testDebugUnitTest --tests "*SpaceCheckTest*"`
Expected: FAIL — `sufficient when free space exceeds the total`, `headroom is required on top of the
repo size`, and `an empty repo needs only headroom`.

If any of those still passes, that test is vacuous and must be fixed before continuing.

Then restore the original line and re-run. Expected: PASS, 8 tests. Do not commit the mutation.

- [ ] **Step 6: Commit**

```bash
git add ferry/src/main/java/io/github/nthuat/ferry/SpaceCheck.kt \
        ferry/src/test/java/io/github/nthuat/ferry/SpaceCheckTest.kt
git commit -m "feat: refuse downloads that cannot fit on disk

Neither MNN nor Google's AI Edge Gallery checks free space, so both start a
multi-gigabyte transfer on a device that cannot hold it and fail near the end.
The report carries the raw figures so a caller can say needs 4.1 GB, 2.3 GB free
rather than download failed."
```

---

### Task 3: SHA-256 verification

**Files:**
- Create: `ferry/src/main/java/io/github/nthuat/ferry/Sha256.kt`
- Test: `ferry/src/test/java/io/github/nthuat/ferry/Sha256Test.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `object Sha256` with `fun of(file: File): String` returning lowercase hex, and `fun matches(file: File, expectedHex: String): Boolean`.

**Background the implementer needs:**

Model files are gigabytes. Reading one into memory to hash it will run the device out of heap, so the hash must stream in fixed-size chunks.

Comparison is case-insensitive because hubs are inconsistent about hex case, and the failure that causes is invisible: a correct file reported as corrupt.

- [ ] **Step 1: Write the failing test**

Create `ferry/src/test/java/io/github/nthuat/ferry/Sha256Test.kt`:

```kotlin
package io.github.nthuat.ferry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Sha256Test {

    @get:Rule
    val temp = TemporaryFolder()

    /** Published SHA-256 of the empty input and of "abc" — fixed points, not values we computed. */
    private val emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private val abcHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    private fun fileOf(content: String): File =
        temp.newFile().apply { writeText(content) }

    @Test
    fun `hashes the empty file to the known digest`() {
        assertEquals(emptyHash, Sha256.of(fileOf("")))
    }

    @Test
    fun `hashes abc to the known digest`() {
        assertEquals(abcHash, Sha256.of(fileOf("abc")))
    }

    @Test
    fun `output is lowercase hex of the full digest`() {
        val hash = Sha256.of(fileOf("abc"))

        assertEquals(64, hash.length)
        assertEquals(hash.lowercase(), hash)
    }

    @Test
    fun `matches accepts the correct digest`() {
        assertTrue(Sha256.matches(fileOf("abc"), abcHash))
    }

    @Test
    fun `matches rejects a different digest`() {
        assertFalse(Sha256.matches(fileOf("abc"), emptyHash))
    }

    /** Hubs are inconsistent about hex case, and the resulting bug looks exactly like corruption. */
    @Test
    fun `matches ignores hex case`() {
        assertTrue(Sha256.matches(fileOf("abc"), abcHash.uppercase()))
    }

    @Test
    fun `hashes a file larger than one read buffer`() {
        val big = temp.newFile().apply { writeBytes(ByteArray(70_000) { (it % 251).toByte() }) }

        // Correctness here is that it completes and is stable, not a memorised constant.
        assertEquals(Sha256.of(big), Sha256.of(big))
        assertEquals(64, Sha256.of(big).length)
    }

    @Test
    fun `a one byte difference changes the digest`() {
        assertFalse(Sha256.of(fileOf("abc")) == Sha256.of(fileOf("abd")))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ferry:testDebugUnitTest --tests "*Sha256Test*"`
Expected: FAIL at compilation — `Unresolved reference 'Sha256'`.

- [ ] **Step 3: Write the implementation**

Create `ferry/src/main/java/io/github/nthuat/ferry/Sha256.kt`:

```kotlin
package io.github.nthuat.ferry

import java.io.File
import java.security.MessageDigest

/**
 * Guarantee 2 — never a corrupt model.
 *
 * Streams in fixed chunks because model files are gigabytes and reading one into memory to hash it
 * would exhaust the heap on exactly the devices that most need this to work.
 */
object Sha256 {

    private const val BUFFER_BYTES = 64 * 1024

    fun of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * Case-insensitive because hubs are inconsistent about hex case, and the bug that causes is
     * indistinguishable from real corruption: a perfectly good file, reported broken.
     */
    fun matches(file: File, expectedHex: String): Boolean =
        of(file).equals(expectedHex.trim(), ignoreCase = true)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :ferry:testDebugUnitTest --tests "*Sha256Test*"`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add ferry/src/main/java/io/github/nthuat/ferry/Sha256.kt \
        ferry/src/test/java/io/github/nthuat/ferry/Sha256Test.kt
git commit -m "feat: stream SHA-256 verification of downloaded files

Chunked because model files are gigabytes and hashing one in memory would exhaust
the heap on the devices that most need this. Comparison ignores hex case: hubs are
inconsistent about it and the resulting bug looks exactly like corruption."
```

---

### Task 4: Repo download orchestration

**Files:**
- Create: `ferry/src/main/java/io/github/nthuat/ferry/RepoDownloader.kt`
- Test: `ferry/src/test/java/io/github/nthuat/ferry/RepoDownloaderTest.kt`

**Interfaces:**
- Consumes: `ModelRepo`, `RepoManifest`, `RemoteFile` (Task 1); `SpaceCheck`, `SpaceReport`, `FreeSpaceProbe` (Task 2); `Sha256` (Task 3); `ResumableDownloader` (already exists).
- Produces:
  - `sealed interface RepoProgress` with `data class CheckingSpace(val repoId: String)`, `data class Downloading(val repoId: String, val path: String, val fileIndex: Int, val fileCount: Int, val bytesWritten: Long, val fileBytes: Long)`, `data class Verifying(val repoId: String, val path: String)`, `data class Complete(val repoId: String, val dir: File)`
  - `class InsufficientSpaceException(val report: SpaceReport) : IOException`
  - `class VerificationException(val path: String) : IOException`
  - `class RepoDownloader(repo: ModelRepo, downloader: ResumableDownloader, spaceCheck: SpaceCheck = SpaceCheck(), dispatcher: CoroutineDispatcher = Dispatchers.IO)` with `suspend fun download(repoId: String, into: File, onProgress: (RepoProgress) -> Unit = {}): Result<File>`

**Background the implementer needs:**

Guarantee 1 is that a reader never sees a partial repo. Files therefore download into `into/.staging/<safeId>/` and the directory is moved to `into/<safeId>/` only after every file has verified. A repo id contains a slash (`Qwen/Qwen2.5-0.5B-Instruct`), so it must be flattened before use as a directory name, or the second half silently becomes a nested directory.

Files with a null `sha256` skip hash verification — the hub did not publish one. They are still size-checked, because `ResumableDownloader` already fails a short body.

Sequential downloads only. Parallel is faster, multiplies peak memory, and makes progress and cancellation considerably harder. It is not in this plan.

- [ ] **Step 1: Write the failing test**

Create `ferry/src/test/java/io/github/nthuat/ferry/RepoDownloaderTest.kt`:

```kotlin
package io.github.nthuat.ferry

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RepoDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer

    private val configBody = """{"model_type":"qwen2"}"""
    private val weightsBody = "WEIGHTS-BYTES"

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** A repo whose files are served by MockWebServer, with hashes computed rather than guessed. */
    private fun fakeRepo(files: List<RemoteFile>) = object : ModelRepo {
        override suspend fun manifest(repoId: String) =
            Result.success(RepoManifest(repoId, files))

        override fun fileUrl(repoId: String, path: String) =
            server.url("/$repoId/resolve/main/$path").toString()
    }

    private fun downloaderFor(
        files: List<RemoteFile>,
        freeBytes: Long = Long.MAX_VALUE,
    ) = RepoDownloader(
        repo = fakeRepo(files),
        downloader = ResumableDownloader(OkHttpClient()),
        spaceCheck = SpaceCheck(probe = { freeBytes }, headroomBytes = 0L),
    )

    private fun shaOf(content: String): String =
        Sha256.of(temp.newFile().apply { writeText(content) })

    @Test
    fun `downloads every file and commits the directory`() {
        val files = listOf(
            RemoteFile("config.json", configBody.length.toLong(), null),
            RemoteFile("model.bin", weightsBody.length.toLong(), shaOf(weightsBody)),
        )
        server.enqueue(MockResponse().setBody(configBody))
        server.enqueue(MockResponse().setBody(weightsBody))

        val dir = runBlocking { downloaderFor(files).download("Qwen/Q-0.5B", temp.root) }.getOrThrow()

        assertEquals(configBody, File(dir, "config.json").readText())
        assertEquals(weightsBody, File(dir, "model.bin").readText())
    }

    @Test
    fun `a repo id containing a slash becomes one directory, not two`() {
        val files = listOf(RemoteFile("config.json", configBody.length.toLong(), null))
        server.enqueue(MockResponse().setBody(configBody))

        val dir = runBlocking { downloaderFor(files).download("Qwen/Q-0.5B", temp.root) }.getOrThrow()

        assertFalse("repo id must be flattened", dir.path.contains("Qwen/Q-0.5B"))
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `refuses to start when space is insufficient`() {
        val files = listOf(RemoteFile("model.bin", 10_000L, null))

        val result = runBlocking { downloaderFor(files, freeBytes = 5_000L).download("a/b", temp.root) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InsufficientSpaceException)
    }

    @Test
    fun `refusing on space makes no network request`() {
        val files = listOf(RemoteFile("model.bin", 10_000L, null))

        runBlocking { downloaderFor(files, freeBytes = 5_000L).download("a/b", temp.root) }

        assertEquals("must not spend the user's data to discover this", 0, server.requestCount)
    }

    @Test
    fun `the space failure carries the numbers needed to explain it`() {
        val files = listOf(RemoteFile("model.bin", 10_000L, null))

        val result = runBlocking { downloaderFor(files, freeBytes = 4_000L).download("a/b", temp.root) }
        val report = (result.exceptionOrNull() as InsufficientSpaceException).report

        assertEquals(10_000L, report.requiredBytes)
        assertEquals(4_000L, report.freeBytes)
        assertEquals(6_000L, report.shortfallBytes)
    }

    @Test
    fun `a file failing verification fails the whole repo`() {
        val files = listOf(RemoteFile("model.bin", weightsBody.length.toLong(), shaOf("SOMETHING ELSE")))
        server.enqueue(MockResponse().setBody(weightsBody))

        val result = runBlocking { downloaderFor(files).download("a/b", temp.root) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VerificationException)
    }

    @Test
    fun `nothing is committed when a file fails verification`() {
        val files = listOf(
            RemoteFile("config.json", configBody.length.toLong(), null),
            RemoteFile("model.bin", weightsBody.length.toLong(), shaOf("SOMETHING ELSE")),
        )
        server.enqueue(MockResponse().setBody(configBody))
        server.enqueue(MockResponse().setBody(weightsBody))

        runBlocking { downloaderFor(files).download("a/b", temp.root) }

        val committed = File(temp.root, "a--b")
        assertFalse("a half-verified repo must not be readable", committed.exists())
    }

    @Test
    fun `files without a published hash are accepted`() {
        val files = listOf(RemoteFile("config.json", configBody.length.toLong(), null))
        server.enqueue(MockResponse().setBody(configBody))

        assertTrue(runBlocking { downloaderFor(files).download("a/b", temp.root) }.isSuccess)
    }

    @Test
    fun `progress reports space check, every file, verification and completion`() {
        val files = listOf(RemoteFile("model.bin", weightsBody.length.toLong(), shaOf(weightsBody)))
        server.enqueue(MockResponse().setBody(weightsBody))

        val seen = mutableListOf<RepoProgress>()
        runBlocking { downloaderFor(files).download("a/b", temp.root) { seen += it } }

        assertTrue(seen.first() is RepoProgress.CheckingSpace)
        assertTrue(seen.any { it is RepoProgress.Downloading && it.path == "model.bin" })
        assertTrue(seen.any { it is RepoProgress.Verifying })
        assertTrue(seen.last() is RepoProgress.Complete)
    }

    @Test
    fun `progress numbers each file within the repo`() {
        val files = listOf(
            RemoteFile("config.json", configBody.length.toLong(), null),
            RemoteFile("model.bin", weightsBody.length.toLong(), null),
        )
        server.enqueue(MockResponse().setBody(configBody))
        server.enqueue(MockResponse().setBody(weightsBody))

        val seen = mutableListOf<RepoProgress.Downloading>()
        runBlocking {
            downloaderFor(files).download("a/b", temp.root) { if (it is RepoProgress.Downloading) seen += it }
        }

        assertEquals(2, seen.map { it.fileIndex }.distinct().size)
        assertTrue(seen.all { it.fileCount == 2 })
    }

    @Test
    fun `an http failure on one file fails the repo`() {
        val files = listOf(RemoteFile("model.bin", 100L, null))
        server.enqueue(MockResponse().setResponseCode(500))

        assertTrue(runBlocking { downloaderFor(files).download("a/b", temp.root) }.isFailure)
    }
}
```

Note: hashes in these tests are computed with `shaOf`, never written as literals. A hardcoded digest in a test is a value nobody can verify by reading it, and it silently encodes whichever implementation produced it.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ferry:testDebugUnitTest --tests "*RepoDownloaderTest*"`
Expected: FAIL at compilation — `Unresolved reference 'RepoDownloader'`.

- [ ] **Step 3: Write the implementation**

Create `ferry/src/main/java/io/github/nthuat/ferry/RepoDownloader.kt`:

```kotlin
package io.github.nthuat.ferry

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** What the download is doing, at a granularity a progress UI can render without guessing. */
sealed interface RepoProgress {

    data class CheckingSpace(val repoId: String) : RepoProgress

    data class Downloading(
        val repoId: String,
        val path: String,
        val fileIndex: Int,
        val fileCount: Int,
        val bytesWritten: Long,
        val fileBytes: Long,
    ) : RepoProgress

    data class Verifying(val repoId: String, val path: String) : RepoProgress

    data class Complete(val repoId: String, val dir: File) : RepoProgress
}

/** Carries the report so a caller can say how much space is missing, not merely that some is. */
class InsufficientSpaceException(val report: SpaceReport) : IOException(
    "needs ${report.requiredBytes} bytes, ${report.freeBytes} free, " +
        "short by ${report.shortfallBytes}",
)

class VerificationException(val path: String) : IOException("sha256 mismatch for $path")

/**
 * Downloads a whole model repository, or none of it.
 *
 * Files land in a staging directory and are moved into place only once every one of them has
 * verified, so a model loader pointed at the target directory can never observe a repo that is
 * half-written or half-correct.
 */
class RepoDownloader(
    private val repo: ModelRepo,
    private val downloader: ResumableDownloader,
    private val spaceCheck: SpaceCheck = SpaceCheck(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun download(
        repoId: String,
        into: File,
        onProgress: (RepoProgress) -> Unit = {},
    ): Result<File> = withContext(dispatcher) {
        onProgress(RepoProgress.CheckingSpace(repoId))

        val manifest = repo.manifest(repoId).getOrElse { return@withContext Result.failure(it) }

        // Before the first byte: spending a user's data allowance to discover the disk is full is
        // the failure this library exists to prevent.
        val report = spaceCheck.check(manifest, into)
        if (!report.sufficient) {
            return@withContext Result.failure(InsufficientSpaceException(report))
        }

        val dirName = flatten(repoId)
        val staging = File(into, ".staging/$dirName")
        val target = File(into, dirName)

        try {
            staging.mkdirs()

            manifest.files.forEachIndexed { index, remote ->
                val destination = File(staging, remote.path)
                destination.parentFile?.mkdirs()

                downloader.download(
                    url = repo.fileUrl(repoId, remote.path),
                    target = destination,
                ) { written, _ ->
                    onProgress(
                        RepoProgress.Downloading(
                            repoId = repoId,
                            path = remote.path,
                            fileIndex = index,
                            fileCount = manifest.files.size,
                            bytesWritten = written,
                            fileBytes = remote.sizeBytes,
                        ),
                    )
                }.getOrElse { return@withContext Result.failure(it) }

                // A null sha256 means the hub published none. Size is still enforced upstream by
                // ResumableDownloader, which fails a body shorter than the declared total.
                if (remote.sha256 != null) {
                    onProgress(RepoProgress.Verifying(repoId, remote.path))
                    if (!Sha256.matches(destination, remote.sha256)) {
                        return@withContext Result.failure(VerificationException(remote.path))
                    }
                }
            }

            if (target.exists() && !target.deleteRecursively()) {
                return@withContext Result.failure(IOException("cannot replace $target"))
            }
            target.parentFile?.mkdirs()
            if (!staging.renameTo(target)) {
                return@withContext Result.failure(IOException("cannot commit $target"))
            }

            onProgress(RepoProgress.Complete(repoId, target))
            Result.success(target)
        } catch (e: IOException) {
            Result.failure(e)
        } finally {
            // Staging survives only as long as the attempt. ResumableDownloader keeps its own
            // .part files inside it, so removing it here forfeits resume; that is the trade for
            // never leaving a half-repo on disk, and is revisited when resume-across-launch lands.
            staging.deleteRecursively()
        }
    }

    /**
     * "Qwen/Qwen2.5-0.5B-Instruct" is one repository, not a directory called Qwen containing one
     * called Qwen2.5-0.5B-Instruct. Flattening keeps a repo id addressable as a single directory.
     */
    private fun flatten(repoId: String): String = repoId.replace("/", "--")
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :ferry:testDebugUnitTest --tests "*RepoDownloaderTest*"`
Expected: PASS, 11 tests.

- [ ] **Step 5: Prove the guarantee tests can actually fail**

These are the assertions the library's promises rest on. Verify each one can go red.

Mutation A — remove the space guard. Delete these three lines from `download`:

```kotlin
        if (!report.sufficient) {
            return@withContext Result.failure(InsufficientSpaceException(report))
        }
```

Expected: FAIL — `refuses to start when space is insufficient`, `refusing on space makes no network
request`, and `the space failure carries the numbers needed to explain it`. Restore them.

Mutation B — commit without verifying. Change the hash check to:

```kotlin
                if (remote.sha256 != null) {
                    onProgress(RepoProgress.Verifying(repoId, remote.path))
                }
```

Expected: FAIL — `a file failing verification fails the whole repo` and `nothing is committed when a
file fails verification`. Restore it.

Mutation C — commit before verifying rather than after. Move the `staging.renameTo(target)` block
above the `forEachIndexed` loop.

Expected: FAIL — `nothing is committed when a file fails verification`. Restore it.

If a mutation leaves the suite green, that guarantee is untested regardless of how many tests exist.
Fix the test, not the mutation.

Then re-run. Expected: PASS, 11 tests. Do not commit any mutation.

- [ ] **Step 6: Commit**

```bash
git add ferry/src/main/java/io/github/nthuat/ferry/RepoDownloader.kt \
        ferry/src/test/java/io/github/nthuat/ferry/RepoDownloaderTest.kt
git commit -m "feat: download a whole repo or none of it

Files stage in a scratch directory and are committed only once every one has
verified, so a model loader pointed at the target can never see a half-written or
half-correct repo. Space is checked before the first request rather than after,
which is the difference between a refusal and a wasted data allowance.

Repo ids are flattened because Qwen/Qwen2.5-0.5B-Instruct is one repository, not a
directory containing another."
```

---

### Task 5: Public facade

**Files:**
- Create: `ferry/src/main/java/io/github/nthuat/ferry/Ferry.kt`
- Test: `ferry/src/test/java/io/github/nthuat/ferry/FerryTest.kt`
- Modify: `README.md` — replace the `Status: early` block

**Interfaces:**
- Consumes: everything from Tasks 1–4.
- Produces: `object Ferry` with `fun huggingFace(client: OkHttpClient = OkHttpClient(), baseUrl: String = "https://huggingface.co"): RepoDownloader`

**Background the implementer needs:**

One factory function, not a builder. The fluent form sketched during naming (`Ferry.to(...).from(...).fetch(...)`) is API polish and does not belong in the first working version; a builder written before there are two hubs to choose between is guessing at its own shape.

The facade must not construct anything the host might want to own — no dispatcher choice beyond the default, no storage location, no notification, no lifecycle. The target directory is a parameter of `download`, not of construction.

- [ ] **Step 1: Write the failing test**

Create `ferry/src/test/java/io/github/nthuat/ferry/FerryTest.kt`:

```kotlin
package io.github.nthuat.ferry

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FerryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer

    private val configBody = """{"model_type":"qwen2"}"""

    private val treeJson = """
        [ { "type": "file", "path": "config.json", "size": ${configBody.length} } ]
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `fetches a repo end to end through the facade`() {
        server.enqueue(MockResponse().setBody(treeJson))
        server.enqueue(MockResponse().setBody(configBody))

        val ferry = Ferry.huggingFace(baseUrl = server.url("/").toString().trimEnd('/'))
        val dir = runBlocking { ferry.download("Qwen/Q-0.5B", temp.root) }.getOrThrow()

        assertEquals(configBody, File(dir, "config.json").readText())
    }

    @Test
    fun `the facade reports progress`() {
        server.enqueue(MockResponse().setBody(treeJson))
        server.enqueue(MockResponse().setBody(configBody))

        val seen = mutableListOf<RepoProgress>()
        val ferry = Ferry.huggingFace(baseUrl = server.url("/").toString().trimEnd('/'))
        runBlocking { ferry.download("Qwen/Q-0.5B", temp.root) { seen += it } }

        assertTrue(seen.last() is RepoProgress.Complete)
    }

    @Test
    fun `a missing repo is a failure, not an exception`() {
        server.enqueue(MockResponse().setResponseCode(404))

        val ferry = Ferry.huggingFace(baseUrl = server.url("/").toString().trimEnd('/'))
        val result = runBlocking { ferry.download("nope/nope", temp.root) }

        assertTrue(result.isFailure)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ferry:testDebugUnitTest --tests "*FerryTest*"`
Expected: FAIL at compilation — `Unresolved reference 'Ferry'`.

- [ ] **Step 3: Write the implementation**

Create `ferry/src/main/java/io/github/nthuat/ferry/Ferry.kt`:

```kotlin
package io.github.nthuat.ferry

import okhttp3.OkHttpClient

/**
 * Entry point.
 *
 * Deliberately a factory rather than a builder: a fluent API written before there is a second hub
 * to choose between is guessing at its own shape.
 *
 * Nothing here decides how work is backgrounded, where files live, or what the user is shown. The
 * two applications this library is aimed at background downloads differently — one with a
 * foreground Service, one with a CoroutineWorker — so imposing either would rule out one of them.
 */
object Ferry {

    fun huggingFace(
        client: OkHttpClient = OkHttpClient(),
        baseUrl: String = "https://huggingface.co",
    ): RepoDownloader = RepoDownloader(
        repo = HuggingFace(client = client, baseUrl = baseUrl),
        downloader = ResumableDownloader(client),
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :ferry:testDebugUnitTest --tests "*FerryTest*"`
Expected: PASS, 3 tests.

- [ ] **Step 5: Run the whole suite**

Run: `./gradlew :ferry:testDebugUnitTest`
Expected: PASS. 11 pre-existing `ResumableDownloaderTest` tests plus 39 added by this plan.

- [ ] **Step 6: Update the README status block**

In `README.md`, replace:

```markdown
> **Status: early.** The transport layer is written and tested. Repository semantics, verification
> and the Android integration are not done yet. Not published to Maven, not ready to use.
```

with:

```markdown
> **Status: core works.** Fetches a HuggingFace repo, refuses to start without the disk space to
> finish it, and verifies every published SHA-256 before committing anything. Backgrounding, pause,
> resume-across-launch and a second hub are not done. Not published to Maven.
```

And replace the usage snippet at the top with the API that now exists:

```kotlin
val ferry = Ferry.huggingFace()

ferry.download("google/gemma-2-2b-it", context.filesDir) { progress ->
    when (progress) {
        is RepoProgress.CheckingSpace -> …
        is RepoProgress.Downloading -> …
        is RepoProgress.Verifying -> …
        is RepoProgress.Complete -> …
    }
}.onFailure { error ->
    if (error is InsufficientSpaceException) {
        // "needs 4.1 GB, 2.3 GB free" — before a single byte was transferred
    }
}
```

- [ ] **Step 7: Commit**

```bash
git add ferry/src/main/java/io/github/nthuat/ferry/Ferry.kt \
        ferry/src/test/java/io/github/nthuat/ferry/FerryTest.kt \
        README.md
git commit -m "feat: public entry point

A factory, not a builder. A fluent API written before there is a second hub to
choose between is guessing at its own shape.

The facade decides nothing about backgrounding, storage location, or what the user
is shown, because the two applications this targets background downloads
differently and imposing either would rule out one of them."
```

---

## Out of Scope

Named so they are not smuggled in:

- **Backgrounding.** No `CoroutineWorker`, no foreground `Service`, no notification. Separate plan.
- **Resume across launches.** `RepoDownloader` currently deletes staging in its `finally` block, which forfeits partial progress. Making that survive needs persisted state and is a separate plan; the trade is called out in the code.
- **Pause and cancel.** Coroutine cancellation works; an app-driven pause API does not exist.
- **ModelScope.** `ModelRepo` exists so it can be added; it is not added here.
- **Parallel file downloads.** Sequential only.
- **Private repos and auth tokens.** Public models only.
- **Maven publishing.**
- **The sample app.** Separate plan.
