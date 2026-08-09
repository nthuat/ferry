# Ferry KMP Conversion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `:ferry` compiles and passes its full test suite on `jvm`, `iosArm64`, `iosSimulatorArm64`, and `iosX64` from a single `commonMain` codebase, per `docs/superpowers/specs/2026-08-09-kmp-conversion-design.md`.

**Architecture:** Staged conversion. Tasks 1–6 swap platform APIs file-by-file (java.io/MessageDigest → Okio, OkHttp → Ktor) while the module stays `kotlin("jvm")` — every task ends with the whole JVM suite green. Task 7 flips the build to `kotlin("multiplatform")`, moves sources to `commonMain`, adds the two expect/actual leaves and the iOS targets. Task 8 updates consumers (`:ferry-work`, `:sample`), publishing, and docs. Tests are ported to `kotlin.test` + `runTest` + `FakeFileSystem`/`MockEngine` *as each file is converted* (tasks 1–6), so Task 7 moves them to `commonTest` unchanged.

**Tech Stack:** Kotlin 2.0.21 (already in root build), Ktor client 3.2.3 (`core` common, `okhttp` engine JVM, `darwin` engine iOS, `mock` for tests), Okio 3.9.1 (+ `okio-fakefilesystem`), kotlinx-coroutines 1.9.0, kotlinx-serialization-json 1.7.3, kotlin.test.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-09-kmp-conversion-design.md`. Its contract section (staging + atomic commit, resume, SHA-256, space preflight, progress, cancellation) must survive every task — the ported tests are the proof.
- expect/actual allowlist (spec): `defaultHttpClient(): HttpClient` and `availableBytes(path: Path): Long` ONLY. Anything more needs a recorded reason in the commit message. (One candidate is pre-approved below: `fsync` — see Task 3 Step 6.)
- `RepoProgress` case shapes unchanged except `Complete(repoId: String, dir: Path)` (keeps `repoId` — the spec's shorthand `Complete(dir)` omitted it; the real type has both fields).
- All error semantics unchanged: public methods return `Result`, failures are `okio.IOException` (typealias of `java.io.IOException` on JVM) or subtypes; `CancellationException` always rethrown.
- `version = "0.2.0"` lands in Task 7, not before. `group = "dev.thuat"` unchanged.
- `allWarningsAsErrors` stays on.
- Every task: run `./gradlew :ferry:test` (tasks 1–6) or `./gradlew :ferry:jvmTest :ferry:iosSimulatorArm64Test` (tasks 7–8) before its commit step. Commit messages use the repo's conventional format (`feat:`/`refactor:`/`test:`/`docs:`), no attribution footer.
- Before Task 1: check Maven Central for the latest stable Ktor 3.x and Okio 3.x patch versions and use those instead of the pins above if newer (majors fixed at 3).

## Translation Reference (used by Tasks 2–6)

Mechanical substitutions. `fs` is the `FileSystem` constructor parameter of the class being converted. Every task's "port" steps apply this table to lines not otherwise rewritten in that task.

### java.io → Okio

| java.io | Okio |
|---|---|
| `import java.io.File` / `java.io.IOException` | `import okio.Path`, `okio.Path.Companion.toPath`, `okio.FileSystem`, `okio.IOException` |
| `File(parent, "name")` | `parent / "name"` |
| `f.parentFile` | `f.parent` (nullable, same as before) |
| `f.name` | `f.name` |
| `f.exists()` | `fs.exists(f)` |
| `f.isFile` | `fs.metadataOrNull(f)?.isRegularFile == true` |
| `f.isDirectory` | `fs.metadataOrNull(f)?.isDirectory == true` |
| `f.length()` | `fs.sizeOf(f)` — helper, Task 4 Step 2 |
| `f.mkdirs()` (returns false on failure) | `fs.createDirectories(f)` (throws IOException — caught by the enclosing try in both public methods, same failure surface) |
| `f.delete()` (returns false) | `fs.delete(f, mustExist = false)` (throws; where the old code *relied* on false — pruneOrphans' empty-dir pass — wrap in try/catch, see Task 4 Step 4) |
| `f.deleteRecursively()` (returns false) | `fs.deleteRecursively(f)` (throws; replace `if (!x.deleteRecursively()) return failure` with direct call — the throw is caught and converted by the same enclosing try) |
| `f.renameTo(t)` (returns false) | `fs.atomicMove(f, t)` (throws; same conversion as above) |
| `f.writeText(s)` | `fs.write(f) { writeUtf8(s) }` |
| `f.readText()` | `fs.read(f) { readUtf8() }` |
| `f.walkTopDown()` | `fs.listRecursively(f)` (already top-down, already skips the root) |
| `f.walkBottomUp()` (dirs after content) | `fs.listRecursively(f).toList().asReversed()` |
| `f.relativeTo(dir).invariantSeparatorsPath` | `f.relativeTo(dir).toString()` (okio normalizes to `/` on the platforms we target) |
| `File.separator` | `"/"` |
| `f.canonicalPath` | `f.normalized()` — **lexical, does not resolve symlinks.** See Task 4 Step 3 for the rewritten `resolveInside` and the known-limitations entry this requires. |

### OkHttp → Ktor (client calls)

| OkHttp | Ktor |
|---|---|
| `OkHttpClient` | `io.ktor.client.HttpClient` |
| `client.newCall(Request.Builder().url(u).build()).execute().use { r -> ... }` | `val r = client.get(u); ...` (suspend — drop any surrounding `withContext` only if one existed purely for the blocking call) |
| `response.isSuccessful` | `response.status.isSuccess()` |
| `response.code` | `response.status.value` |
| `response.header("X")` | `response.headers["X"]` |
| `response.body.string()` | `response.bodyAsText()` |
| `response.body.byteStream()` | `response.bodyAsChannel()` (`ByteReadChannel`) — streaming requires `client.prepareGet(u).execute { r -> ... }` instead of `client.get`, see Task 3 |
| `response.body.contentLength()` (−1 when absent) | `response.contentLength()` (null when absent) |
| malformed URL: `IllegalArgumentException` from `Request.Builder.url` | `io.ktor.http.URLParserException` from request builders — catch and wrap identically |

### OkHttp HttpUrl → Ktor Url (hub adapters)

| OkHttp | Ktor |
|---|---|
| `str.toHttpUrlOrNull()` (null on garbage) | `io.ktor.http.parseUrl(str)` (null on garbage) |
| `HttpUrl` | `io.ktor.http.Url` |
| `base.newBuilder()...build()` | `URLBuilder(base)...build()` |
| `.addPathSegments("api/models")` | `.appendPathSegments("api", "models")` (one arg per segment; it percent-encodes each) |
| `.addEncodedPathSegments(...)` | `.appendEncodedPathSegments(...)` |
| `.addQueryParameter(k, v)` | `.parameters.append(k, v)` (encodes) |
| `url.encodedPath` | `url.encodedPath` |
| `url.pathSegments` (decoded) | `url.segments` (decoded) — origin/namespace-prefix checks compare decoded segments in both, so the check logic ports unchanged; verify each `requireWithinNamespace` call site while porting |
| same-origin check (scheme/host/port) | compare `url.protocol.name`, `url.host`, `url.port` |

### JUnit 4 → kotlin.test (+ coroutines-test, FakeFileSystem)

| JUnit 4 | Replacement |
|---|---|
| `import org.junit.Test` / `Assert.*` | `import kotlin.test.Test`, `kotlin.test.assertEquals`, `assertTrue`, `assertNull`, `assertIs`, `assertFailsWith` |
| `@Before fun setUp()` / `@After` | `@BeforeTest` / `@AfterTest` |
| `@get:Rule val temp = TemporaryFolder()` | `val fs = FakeFileSystem()` + `val root = "/downloads".toPath()` (create with `fs.createDirectories(root)` in `@BeforeTest`); assert `fs.checkNoOpenFiles()` in `@AfterTest` |
| `runBlocking { ... }` | `fun x() = runTest { ... }` (kotlinx-coroutines-test; pass `UnconfinedTestDispatcher()` as the class-under-test's `dispatcher` where the old test relied on `Dispatchers.IO` actually running) |
| `MockWebServer` + `enqueue(MockResponse...)` | `QueueClient` helper (Task 3 Step 1) wrapping Ktor `MockEngine` |
| `File(dir, "x").readText()` | `fs.read(dir / "x") { readUtf8() }` |
| `File(dir, "x").writeText(...)` etc. | table above, with `fs` = the test's `FakeFileSystem` |

---

### Task 1: Sha256 → Okio HashingSink

**Files:**
- Modify: `ferry/build.gradle.kts` (dependencies block only)
- Modify: `ferry/src/main/java/dev/thuat/ferry/Sha256.kt`
- Modify: `ferry/src/test/java/dev/thuat/ferry/Sha256Test.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `internal object Sha256 { fun of(fileSystem: FileSystem, file: Path): String; fun matches(fileSystem: FileSystem, file: Path, expectedHex: String): Boolean }` — Task 4 calls `matches` with `RepoDownloader`'s `fs`.

- [ ] **Step 1: Add dependencies**

In `ferry/build.gradle.kts` `dependencies {}`:

```kotlin
api("com.squareup.okio:okio:3.9.1")
testImplementation(kotlin("test"))
testImplementation("com.squareup.okio:okio-fakefilesystem:3.9.1")
```

`api`, not `implementation`: `okio.Path` enters public signatures in Task 2. Run `./gradlew :ferry:compileKotlin` to confirm resolution.

- [ ] **Step 2: Port Sha256Test to the new signature (RED)**

Rewrite `Sha256Test.kt` using the JUnit→kotlin.test table. Every existing test case keeps its name and assertion; file setup becomes FakeFileSystem writes. Shape:

```kotlin
package dev.thuat.ferry

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class Sha256Test {

    private val fs = FakeFileSystem()
    private val dir = "/work".toPath()

    @AfterTest
    fun tearDown() = fs.checkNoOpenFiles()

    @Test
    fun hashesKnownContent() {
        fs.createDirectories(dir)
        val file = dir / "hello.txt"
        fs.write(file) { writeUtf8("hello") }
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            Sha256.of(fs, file),
        )
    }
    // ... port each remaining case in Sha256Test the same way (matches / case-insensitivity /
    // trimming cases keep their exact expected strings from the current file)
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew :ferry:test --tests "dev.thuat.ferry.Sha256Test"`
Expected: compile error — `Sha256.of(FileSystem, Path)` does not exist yet.

- [ ] **Step 4: Rewrite Sha256.kt**

```kotlin
package dev.thuat.ferry

import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.blackholeSink
import okio.buffer

/**
 * Guarantee 2 — never a corrupt model.
 *
 * Streams through Okio's HashingSink because model files are gigabytes and reading one into memory
 * to hash it would exhaust the heap on exactly the devices that most need this to work.
 *
 * Internal primitive: I/O failures are caught by [RepoDownloader.download], the public boundary,
 * which converts them to [Result.failure].
 */
internal object Sha256 {

    fun of(fileSystem: FileSystem, file: Path): String =
        HashingSink.sha256(blackholeSink()).use { hashing ->
            fileSystem.source(file).use { source ->
                hashing.buffer().use { sink -> sink.writeAll(source) }
            }
            hashing.hash.hex()
        }

    /**
     * Case-insensitive because hubs are inconsistent about hex case, and the bug that causes is
     * indistinguishable from real corruption: a perfectly good file, reported broken.
     */
    fun matches(fileSystem: FileSystem, file: Path, expectedHex: String): Boolean =
        of(fileSystem, file).equals(expectedHex.trim(), ignoreCase = true)
}
```

(Compile will also flag `RepoDownloader`'s two `Sha256.matches(file, hash)` call sites — leave them until Task 4 by temporarily overloading? No: fix them now minimally with `Sha256.matches(FileSystem.SYSTEM, destination.toOkioPath(), remote.sha256)` using `import okio.Path.Companion.toOkioPath`. Task 4 replaces these lines wholesale.)

- [ ] **Step 5: Run the full module suite**

Run: `./gradlew :ferry:test`
Expected: PASS (all classes — the temporary `toOkioPath()` bridge keeps RepoDownloaderTest green).

- [ ] **Step 6: Commit**

```bash
git add ferry/build.gradle.kts ferry/src/main/java/dev/thuat/ferry/Sha256.kt ferry/src/test/java/dev/thuat/ferry/Sha256Test.kt ferry/src/main/java/dev/thuat/ferry/RepoDownloader.kt
git commit -m "refactor: Sha256 over Okio HashingSink, first step off java.security"
```

---

### Task 2: SpaceCheck → okio.Path

**Files:**
- Modify: `ferry/src/main/java/dev/thuat/ferry/SpaceCheck.kt`
- Modify: `ferry/src/test/java/dev/thuat/ferry/SpaceCheckTest.kt`
- Modify: `ferry/src/main/java/dev/thuat/ferry/RepoDownloader.kt` (one call site bridge)

**Interfaces:**
- Consumes: nothing new.
- Produces: `fun interface FreeSpaceProbe { fun freeBytes(dir: Path): Long }`; `class SpaceCheck(probe, headroomBytes) { fun check(manifest: RepoManifest, targetDir: Path): SpaceReport }`; `internal fun availableBytes(path: Path): Long` (plain JVM function for now — becomes the expect/actual leaf in Task 7). `SpaceReport` unchanged.

- [ ] **Step 1: Port SpaceCheckTest (RED)**

Apply the JUnit→kotlin.test table. Probes in these tests are fakes (`FreeSpaceProbe { 5L }`) so most cases only change `File` → `Path` literals (`"/downloads".toPath()`) and assertion imports. The one case exercising `DefaultFreeSpaceProbe`'s ancestor walk keeps using a real directory: keep it, but mark it with a comment `// jvm-only behavior of the default probe; moves to jvmTest in Task 7`.

Run: `./gradlew :ferry:test --tests "dev.thuat.ferry.SpaceCheckTest"` — expected: compile FAIL.

- [ ] **Step 2: Rewrite SpaceCheck.kt platform surface**

Keep `SpaceReport` byte-for-byte. Replace the `File` surface:

```kotlin
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import java.io.File

/** Indirection so tests can state a free-space figure instead of filling a real disk. */
fun interface FreeSpaceProbe {
    fun freeBytes(dir: Path): Long
}

/** Free bytes on the volume holding [path]. Becomes the expect/actual platform leaf in Task 7. */
internal fun availableBytes(path: Path): Long = path.toFile().usableSpace

/**
 * (keep the existing nearestExistingAncestor KDoc verbatim — the phantom-zero rationale is
 * unchanged; only the probing API moved from java.io.File to okio)
 */
private fun nearestExistingAncestor(fileSystem: FileSystem, dir: Path): Path =
    generateSequence(dir) { it.parent }.firstOrNull { fileSystem.exists(it) } ?: dir

/** (keep the existing DefaultFreeSpaceProbe KDoc verbatim) */
val DefaultFreeSpaceProbe = FreeSpaceProbe { dir ->
    availableBytes(nearestExistingAncestor(FileSystem.SYSTEM, dir))
}
```

`SpaceCheck.check(manifest: RepoManifest, targetDir: Path)` — body unchanged. Bridge the `RepoDownloader` call site: `spaceCheck.check(manifest.creditingStaged(...), into.toOkioPath())`.

- [ ] **Step 3: Run full suite**

Run: `./gradlew :ferry:test` — expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add -A ferry/src
git commit -m "refactor: SpaceCheck speaks okio.Path; free-space probe isolated as availableBytes()"
```

---

### Task 3: ResumableDownloader → Ktor + Okio

**Files:**
- Modify: `ferry/build.gradle.kts` (dependencies)
- Modify: `ferry/src/main/java/dev/thuat/ferry/ResumableDownloader.kt` (full rewrite)
- Create: `ferry/src/test/java/dev/thuat/ferry/QueueClient.kt` (test helper)
- Modify: `ferry/src/test/java/dev/thuat/ferry/ResumableDownloaderTest.kt`
- Modify: `ferry/src/main/java/dev/thuat/ferry/Ferry.kt`, `RepoDownloader.kt` (constructor bridges)

**Interfaces:**
- Consumes: nothing new.
- Produces: `class ResumableDownloader(client: HttpClient, fileSystem: FileSystem = FileSystem.SYSTEM, dispatcher: CoroutineDispatcher = Dispatchers.IO) { suspend fun download(url: String, target: Path, onProgress: (Long, Long?) -> Unit = { _, _ -> }): Result<Path> }`. Test helper `QueueClient` (used by every later test task): `class QueueClient { val client: HttpClient; val requests: List<HttpRequestData>; fun enqueue(body: String = "", status: HttpStatusCode = HttpStatusCode.OK, headers: Headers = headersOf(), bodyBytes: ByteArray? = null) }`.

- [ ] **Step 1: Add Ktor dependencies and write QueueClient**

`ferry/build.gradle.kts`:

```kotlin
api("io.ktor:ktor-client-core:3.2.3")
api("io.ktor:ktor-client-okhttp:3.2.3")   // JVM engine; moves to jvmMain in Task 7
testImplementation("io.ktor:ktor-client-mock:3.2.3")
```

`QueueClient.kt` — the MockWebServer replacement every test task reuses:

```kotlin
package dev.thuat.ferry

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

/** FIFO fake server: enqueue responses, fire requests, inspect what was asked. */
class QueueClient {
    private data class Canned(val body: ByteArray, val status: HttpStatusCode, val headers: Headers)

    private val queue = ArrayDeque<Canned>()
    val requests = mutableListOf<HttpRequestData>()

    val client: HttpClient = HttpClient(MockEngine { request ->
        requests += request
        val next = queue.removeFirstOrNull()
            ?: error("no response enqueued for ${request.url}")
        respond(next.body, next.status, next.headers)
    })

    fun enqueue(
        body: String = "",
        status: HttpStatusCode = HttpStatusCode.OK,
        headers: Headers = headersOf(),
        bodyBytes: ByteArray? = null,
    ) {
        queue += Canned(bodyBytes ?: body.encodeToByteArray(), status, headers)
    }
}
```

- [ ] **Step 2: Port ResumableDownloaderTest (RED)**

Apply both tables. `MockWebServer` scenarios map 1:1: a 206-with-Content-Range fixture becomes `queue.enqueue(bodyBytes = tail, status = HttpStatusCode.PartialContent, headers = headersOf("Content-Range", "bytes 4-9/10"))`. Request-side assertions (`server.takeRequest().getHeader("Range")`) become `queue.requests[i].headers[HttpHeaders.Range]`. Pre-seeded `.part`/`.validator` files become `fs.write(...)`. Every current test case ports; none are dropped — they are the resume contract.

Run: `./gradlew :ferry:test --tests "dev.thuat.ferry.ResumableDownloaderTest"` — expected: compile FAIL.

- [ ] **Step 3: Rewrite ResumableDownloader.kt**

Full replacement (keep the class-level and per-function KDocs, updated where the mechanism changed):

```kotlin
package dev.thuat.ferry

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLParserException
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.IOException
import okio.Path

class ResumableDownloader(
    private val client: HttpClient,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun download(
        url: String,
        target: Path,
        onProgress: (bytesWritten: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): Result<Path> = withContext(dispatcher) {
        val parent = target.parent
            ?: return@withContext Result.failure(IOException("target has no parent directory: $target"))
        val part = parent / "${target.name}.part"
        val validatorFile = parent / "${target.name}.validator"

        try {
            fileSystem.createDirectories(parent)
            val haveBytes = fileSystem.metadataOrNull(part)?.size ?: 0L
            val validator = if (haveBytes > 0 && fileSystem.exists(validatorFile)) {
                fileSystem.read(validatorFile) { readUtf8() }
            } else null

            // No validator means no way to ask "is this still the file I started?" — restart.
            val resumeFrom = if (validator != null) haveBytes else 0L

            client.prepareGet(url) {
                expectSuccess = false
                // Ranges are offsets into the *encoded* representation; identity keeps disk and
                // protocol talking about the same bytes. (The OkHttp engine would otherwise add
                // transparent gzip exactly like bare OkHttp did.)
                header(HttpHeaders.AcceptEncoding, "identity")
                if (resumeFrom > 0) {
                    header(HttpHeaders.Range, "bytes=$resumeFrom-")
                    validator?.let { header(HttpHeaders.IfRange, it) }
                }
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    return@execute Result.failure(
                        IOException("HTTP ${response.status.value} for $url"),
                    )
                }

                val append = response.continuesFrom(resumeFrom)
                val startFrom = if (append) resumeFrom else 0L

                response.validator()?.let { v ->
                    fileSystem.write(validatorFile) { writeUtf8(v) }
                }

                val total = totalBytes(response, startFrom)
                val written =
                    writeBody(response.bodyAsChannel(), part, append, total, onProgress)

                if (total != null && written != total) {
                    return@execute Result.failure(
                        IOException("incomplete: wrote $written of $total bytes"),
                    )
                }

                fileSystem.delete(target, mustExist = false)
                fileSystem.atomicMove(part, target)
                fileSystem.delete(validatorFile, mustExist = false)
                Result.success(target)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // The .part file is deliberately left behind — it is the resume point.
            Result.failure(e)
        } catch (e: URLParserException) {
            Result.failure(IOException("invalid url: $url", e))
        } catch (e: Exception) {
            // Engine-specific network failures (Darwin does not throw java.io types). Same
            // normalisation RepoDownloader.asDownloadFailure applies at its own boundary.
            Result.failure(IOException(e.message ?: e.toString(), e))
        }
    }

    private suspend fun writeBody(
        body: ByteReadChannel,
        part: Path,
        append: Boolean,
        total: Long?,
        onProgress: (Long, Long?) -> Unit,
    ): Long {
        fileSystem.openReadWrite(part).use { handle ->
            if (!append) handle.resize(0L)
            var position = handle.size()
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                // readAvailable is a suspending, cancellable read — the by-hand ensureActive()
                // the blocking OkHttp stream needed is now the channel's own job.
                val read = body.readAvailable(buffer, 0, buffer.size)
                if (read == -1) break
                handle.write(position, buffer, 0, read)
                position += read
                onProgress(position, total)
            }
            handle.flush()
            return position
        }
    }

    /** (keep existing KDoc) */
    private fun totalBytes(response: HttpResponse, startFrom: Long): Long? {
        response.headers[HttpHeaders.ContentRange]?.let { header ->
            return header.substringAfterLast('/').trim().toLongOrNull()
        }
        val length = response.contentLength() ?: return null
        return startFrom + length
    }

    private fun HttpResponse.validator(): String? =
        headers[HttpHeaders.ETag] ?: headers[HttpHeaders.LastModified]

    /** (keep existing KDoc — the ModelScope 200-with-Content-Range rationale is unchanged) */
    private fun HttpResponse.continuesFrom(resumeFrom: Long): Boolean {
        if (status.value == HTTP_PARTIAL_CONTENT) return true
        if (resumeFrom <= 0L) return false
        val start = headers[HttpHeaders.ContentRange]
            ?.substringAfter("bytes ")
            ?.substringBefore('-')
            ?.trim()
            ?.toLongOrNull()
            ?: return false
        return start == resumeFrom
    }

    private companion object {
        const val HTTP_PARTIAL_CONTENT = 206
        const val BUFFER_BYTES = 8 * 1024
    }
}
```

Note `writeBody`'s progress arithmetic: `position` starts at the resume offset (`handle.size()` after append-decision), so `onProgress(position, total)` reports cumulative bytes exactly as the old `written` did — the ported tests assert this.

- [ ] **Step 4: Bridge the two construction sites**

`Ferry.kt`: temporary bridge so the module compiles —

```kotlin
private fun bridgeClient(client: OkHttpClient): HttpClient =
    HttpClient(OkHttp) { engine { preconfigured = client } }
```

and pass `ResumableDownloader(bridgeClient(client))` in all three factories (hubs still take `OkHttpClient` until Task 5). `RepoDownloader`'s `downloader.download(url, destination)` call site passes `destination.toOkioPath()` and treats the returned `Result<Path>` accordingly. These bridges all disappear in Tasks 4–6.

- [ ] **Step 5: Run full suite**

Run: `./gradlew :ferry:test` — expected: PASS. If any resume test disagrees on 206 handling, the test is the contract — fix the port, not the test.

- [ ] **Step 6: Durability check (fsync)**

The old code called `FileOutputStream.fd.sync()`. The rewrite relies on `FileHandle.flush()`. Verify on JVM that okio's `JvmFileHandle.flush()` reaches `FileDescriptor.sync` (read the okio source in the Gradle cache). If it does not, add the third expect/actual `internal expect fun Path.fsyncIfPossible(fileSystem: FileSystem)` — this is the pre-approved allowlist exception; record it in the commit message.

- [ ] **Step 7: Commit**

```bash
git add -A ferry/src ferry/build.gradle.kts
git commit -m "refactor: ResumableDownloader on Ktor streaming + Okio FileHandle"
```

---### Task 4: RepoDownloader → Okio FileSystem

**Files:**
- Modify: `ferry/src/main/java/dev/thuat/ferry/RepoDownloader.kt` (full port)
- Modify: `ferry/src/test/java/dev/thuat/ferry/RepoDownloaderTest.kt` (full port)
- Modify: `docs/known-limitations.md` (symlink note)

**Interfaces:**
- Consumes: `ResumableDownloader` (Task 3), `Sha256.matches(fs, path, hex)` (Task 1), `SpaceCheck.check(manifest, Path)` (Task 2).
- Produces: `class RepoDownloader(repo: ModelHub, downloader: ResumableDownloader, spaceCheck: SpaceCheck = SpaceCheck(), fileSystem: FileSystem = FileSystem.SYSTEM, dispatcher: CoroutineDispatcher = Dispatchers.IO)`; `suspend fun download(repoId: String, into: Path, onProgress: (RepoProgress) -> Unit = {}): Result<Path>`; `suspend fun abandonStaging(repoId: String, into: Path): Result<Unit>`; `suspend fun stagedBytes(repoId: String, into: Path): Long`; `RepoProgress.Complete(repoId: String, dir: Path)`.

- [ ] **Step 1: Port RepoDownloaderTest (RED)**

The big one (~1500 lines) — but purely mechanical via the two tables: `TemporaryFolder` → `FakeFileSystem` + `"/downloads".toPath()`, `MockWebServer` → `QueueClient` where the test drives a real hub, fake `ModelHub` implementations unchanged (pure Kotlin already). Construct the class under test as `RepoDownloader(repo = fakeHub, downloader = ResumableDownloader(queue.client, fs), fileSystem = fs, spaceCheck = ...)`. Port every case; the staging/atomic-commit/marker/nesting/prune cases ARE the contract from the spec.

Run: `./gradlew :ferry:test --tests "dev.thuat.ferry.RepoDownloaderTest"` — expected: compile FAIL.

- [ ] **Step 2: Add the size helper and constructor**

At file top (after imports):

```kotlin
/** java.io.File.length() semantics: 0 for a missing path — call sites compare against it. */
private fun FileSystem.sizeOf(path: Path): Long = metadataOrNull(path)?.size ?: 0L
```

Constructor gains `private val fileSystem: FileSystem = FileSystem.SYSTEM` (fourth parameter, before `dispatcher`). `RepoProgress.Complete` becomes `data class Complete(val repoId: String, val dir: Path)`.

- [ ] **Step 3: Rewrite the three path helpers**

`resolveInside` — the one semantic change in this task. `canonicalPath` resolved symlinks and worked on non-existent paths; okio's `canonicalize` requires existence, so the port goes lexical:

```kotlin
/**
 * (keep existing KDoc, replacing the canonical-path paragraph with:)
 * Normalized lexically rather than canonicalized: okio can only canonicalize a path that already
 * exists, and most of what this method guards does not exist yet. ".." and redundant separators
 * are resolved by normalization; a symlink inside [parent] pointing outside it is no longer
 * resolved before the comparison — recorded in docs/known-limitations.md, acceptable because
 * every tree this method guards lives under an app-private directory Ferry itself created.
 */
private fun resolveInside(parent: Path, relative: String): Path {
    val candidate = parent / relative
    val root = parent.normalized()
    val resolved = candidate.normalized()
    if (relative.startsWith("/") || resolved == root ||
        !resolved.toString().startsWith("$root/")
    ) {
        throw IOException("path must resolve strictly inside $parent: $relative")
    }
    return candidate
}
```

(The explicit `startsWith("/")` guard: `parent / "/abs"` in okio returns the absolute path alone, which could then happen to sit inside `root` lexically — java.io joined it under parent instead. Reject rather than reinterpret.)

`collidesWith`:

```kotlin
private fun collidesWith(targetPath: Path, reservedRoot: Path): Boolean {
    val target = targetPath.normalized().toString()
    val reserved = reservedRoot.normalized().toString()
    return target == reserved || target.startsWith("$reserved/")
}
```

`stagingDirFor`: unchanged logic, `File` → `Path` types only (both statements already go through `resolveInside`).

- [ ] **Step 4: Port the two big method bodies**

`download()` and `pruneOrphans()`/`stagedBytes()`/`abandonStaging()` via the translation table. Non-obvious spots, in order of appearance:

- `target.canonicalPath` for the collide checks → `target` itself (collidesWith normalizes internally now).
- cache-hit check `target.isDirectory && manifest.isSatisfiedBy(target)` → `fileSystem.metadataOrNull(target)?.isDirectory == true && ...`; `isSatisfiedIn` uses `fs.sizeOf` + `Sha256.matches(fileSystem, onDisk, sha256)` (drop Task 1's `toOkioPath()` bridge).
- `stagingDir.mkdirs()` → `fileSystem.createDirectories(stagingDir)`.
- `if (!target.deleteRecursively()) return failure(...)` → `fileSystem.deleteRecursively(target)` bare — the throw lands in the enclosing catch and normalizes; same for `renameTo` → `fileSystem.atomicMove(stagingDir, target)` and `markerDir.mkdirs()` → `createDirectories`, dropping their manual `return failure` branches (delete the now-dead "cannot replace/commit/record" strings, the IOException from okio carries the path).
- marker check `marker.isFile || marker.readText()` → metadata + `fs.read`.
- nested check `markerDir.listFiles()?.firstOrNull { File(target, it.name).exists() }` → `(fileSystem.listOrNull(markerDir) ?: emptyList()).firstOrNull { fileSystem.exists(target / it.name) }`.
- `pruneOrphans` file pass: table as-is; directory pass (relied on `File.delete()` returning false for non-empty dirs) →

```kotlin
fileSystem.listRecursively(stagingDir).toList().asReversed()
    .filter { fileSystem.metadataOrNull(it)?.isDirectory == true }
    .forEach { directory ->
        val relativePath = directory.relativeTo(stagingDir).toString()
        val resolved = resolveInside(stagingDir, relativePath)
        if (fileSystem.listOrNull(resolved)?.isEmpty() == true) {
            fileSystem.delete(resolved)
        }
    }
```

- `stagedBytes` walk → `fileSystem.listRecursively(stagingDir)` filtered on `isRegularFile`, sizes via `fs.sizeOf`; sibling lookups (`File(staged.parentFile, ...)`) → `staged.parent!! / ...` (parent is non-null for anything listed under stagingDir).
- `remainingBytes`: same sibling pattern + `fs.sizeOf(part)`.

- [ ] **Step 5: Run full suite**

Run: `./gradlew :ferry:test` — expected: PASS, including RepoDownloaderTest's full contract battery. Debug port drift here file-by-file; the old java.io file is in git for reference.

- [ ] **Step 6: Record the symlink residual**

Append to `docs/known-limitations.md` under the path-resolution entry: resolveInside now normalizes lexically instead of canonicalizing, so a symlink planted inside a Ferry-managed tree pointing outside it is followed rather than rejected; reachable only by something that can already write inside Ferry's own app-private tree, which could damage that tree directly anyway.

- [ ] **Step 7: Commit**

```bash
git add -A ferry/src docs/known-limitations.md
git commit -m "refactor: RepoDownloader on okio FileSystem; lexical resolveInside recorded in known-limitations"
```

---

### Task 5: Hub adapters → Ktor (HuggingFace, ModelScope, Ollama)

**Files:**
- Modify: `ferry/src/main/java/dev/thuat/ferry/HuggingFace.kt`, `ModelScope.kt`, `Ollama.kt`
- Modify: `ferry/src/test/java/dev/thuat/ferry/HuggingFaceTest.kt`, `ModelScopeTest.kt`, `OllamaTest.kt`
- Modify: `ferry/src/main/java/dev/thuat/ferry/Ferry.kt` (drop hub-side bridge)

**Interfaces:**
- Consumes: `QueueClient` (Task 3).
- Produces: `class HuggingFace(client: HttpClient, baseUrl: String)`, `class ModelScope(client: HttpClient, baseUrl: String, revision: String)`, `class Ollama(client: HttpClient, baseUrl: String)` — constructor types change, `ModelHub.manifest` contract unchanged.

- [ ] **Step 1: Port the three hub tests (RED)** — one at a time, HuggingFace first. `MockWebServer` → `QueueClient`; `server.url("/")` base URLs become a literal `"http://hub.test"`; request-path assertions read `queue.requests[i].url.encodedPath`. Pagination cases (HuggingFace `Link` header) enqueue the header via `headersOf(HttpHeaders.Link, ...)`. Run each class: expected compile FAIL.

- [ ] **Step 2: Port each adapter** with the HttpUrl→Ktor and client-call tables. Per-adapter notes:
  - `manifest()` is already `suspend`; the OkHttp `execute()` blocking call becomes `client.get(url)` — remove any `withContext(Dispatchers.IO)` that existed only to host the blocking call (keep it if it also hosts JSON decoding of multi-MB listings; decoding is CPU-bound and fine either way).
  - Error mapping: non-2xx → `Result.failure(IOException("HTTP ${response.status.value} ..."))` exactly as the current code words it; `SerializationException` handling unchanged.
  - HuggingFace pagination: `sameOriginOrNull`-style origin comparison via `parseUrl(next)` + protocol/host/port equality; the KDoc paragraphs reasoning about okhttp 4.12.0's `Request.Builder.url` throw-behavior are obsolete — replace with one line: request builders throw `URLParserException`, caught at the adapter's boundary and returned as `Result.failure(IOException)`.
  - `requireWithinNamespace(url, prefix, subject)`: port against `url.segments` (decoded, like okhttp's `pathSegments`) — the existing tests for hostile repo ids are the check that this ported correctly.
  - `downloadUrl(...)` keeps returning `String` (`RemoteFile.url` is a String; `URLBuilder(...).buildString()`).

- [ ] **Step 3:** Drop `bridgeClient` from `Ferry.kt`: factories take `client: HttpClient = HttpClient(OkHttp)` for now (Task 7 swaps the default to `defaultHttpClient()`), pass it straight to hubs and `ResumableDownloader`.

- [ ] **Step 4: Run full suite** — `./gradlew :ferry:test`, expected PASS.

- [ ] **Step 5: Commit**

```bash
git add -A ferry/src
git commit -m "refactor: hub adapters on Ktor; OkHttp gone from ferry core signatures"
```

---

### Task 6: Ferry facade + FerryTest + EmbeddabilityTest

**Files:**
- Modify: `ferry/src/main/java/dev/thuat/ferry/Ferry.kt`
- Modify: `ferry/src/test/java/dev/thuat/ferry/FerryTest.kt`
- Modify: `ferry/src/test/java/dev/thuat/ferry/EmbeddabilityTest.kt`

**Interfaces:**
- Produces (final public factory shape, per spec):

```kotlin
object Ferry {
    fun huggingFace(
        client: HttpClient = defaultHttpClient(),
        fileSystem: FileSystem = FileSystem.SYSTEM,
        baseUrl: String = "https://huggingface.co",
    ): RepoDownloader
    // modelScope(+ revision: String = "master"), ollama — same shape
}
internal fun defaultHttpClient(): HttpClient   // plain JVM fn now; expect/actual in Task 7
```

- [ ] **Step 1: Port FerryTest (RED)** — `QueueClient` + `FakeFileSystem`; `Ferry.huggingFace(client = queue.client, fileSystem = fs, baseUrl = "http://hub.test")`. The `readText` assertion becomes `fs.read(dir / "config.json") { readUtf8() }`. Run: compile FAIL.

- [ ] **Step 2: Rewrite Ferry.kt** to the shape above; `defaultHttpClient()` returns `HttpClient(OkHttp)` in a small `internal fun` (not inline in the default arg — Task 7 relocates the body to `jvmMain` unchanged). Wire `fileSystem` through to `ResumableDownloader` and `RepoDownloader`.

- [ ] **Step 3: Port EmbeddabilityTest.** Two halves:
  - The three "every request goes through the caller's client" tests: port to `QueueClient` — `queue.requests` *is* the proof the host's client carried every request (manifest + file), stronger than the interceptor tally. Keep the per-hub path assertions (`/tree/main`, `/repo/files`, `/manifests/`).
  - The interceptor test's *other* value — a host-configured raw `OkHttpClient` still works — becomes one new test, `a preconfigured okhttp client's interceptors see every request`, using `MockWebServer` + `HttpClient(OkHttp) { engine { preconfigured = hostClient } }`. Mark it `// jvm-only: moves to jvmTest in Task 7`.
  - `the api is exercisable without any android object` ports as-is (QueueClient + FakeFileSystem).

- [ ] **Step 4: Run full suite** — `./gradlew :ferry:test`, expected PASS. Also `grep -rn "okhttp3\|java\.io\|java\.security" ferry/src/main/java/` — expected: hits only in `Ferry.kt` (`defaultHttpClient`) and `SpaceCheck.kt` (`availableBytes`), the two Task-7 leaves.

- [ ] **Step 5: Commit**

```bash
git add -A ferry/src
git commit -m "refactor: Ferry facade takes Ktor HttpClient and a FileSystem; core is platform-clean"
```

---

### Task 7: Flip to Kotlin Multiplatform

**Files:**
- Modify: root `build.gradle.kts` (add KMP plugin)
- Modify: `ferry/build.gradle.kts` (full rewrite)
- Move: `ferry/src/main/java/` → `ferry/src/commonMain/kotlin/`, `ferry/src/test/java/` → `ferry/src/commonTest/kotlin/`
- Create: `ferry/src/commonMain/kotlin/dev/thuat/ferry/Platform.kt`
- Create: `ferry/src/jvmMain/kotlin/dev/thuat/ferry/Platform.jvm.kt`
- Create: `ferry/src/appleMain/kotlin/dev/thuat/ferry/Platform.apple.kt`
- Create: `ferry/src/jvmTest/kotlin/dev/thuat/ferry/JvmEmbeddabilityTest.kt` (moved tests)

- [ ] **Step 1: Root plugin** — add to root `build.gradle.kts` plugins block:

```kotlin
id("org.jetbrains.kotlin.multiplatform") version "2.0.21" apply false
```

- [ ] **Step 2: Move sources**

```bash
git mv ferry/src/main/java ferry/src/commonMain/kotlin
git mv ferry/src/test/java ferry/src/commonTest/kotlin
```

- [ ] **Step 3: Rewrite ferry/build.gradle.kts**

```kotlin
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("maven-publish")
    id("signing")
}

group = "dev.thuat"
version = "0.2.0"

kotlin {
    jvmToolchain(17)
    compilerOptions { allWarningsAsErrors.set(true) }

    jvm()
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonMain.dependencies {
            // api: Path, HttpClient and CoroutineDispatcher are in public signatures —
            // same embeddability argument the OkHttp dependency carried before.
            api("com.squareup.okio:okio:3.9.1")
            api("io.ktor:ktor-client-core:3.2.3")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            implementation("io.ktor:ktor-client-mock:3.2.3")
            implementation("com.squareup.okio:okio-fakefilesystem:3.9.1")
        }
        jvmMain.dependencies {
            api("io.ktor:ktor-client-okhttp:3.2.3")
        }
        jvmTest.dependencies {
            implementation("com.squareup.okhttp3:mockwebserver:4.12.0")
        }
        appleMain.dependencies {
            api("io.ktor:ktor-client-darwin:3.2.3")
        }
    }
}

// checkEmbeddable: keep the task and the architecture-dictating list verbatim; update the
// api-configuration half — the guarded types moved:
//   okhttpApiDependency  →  listOf("io.ktor:ktor-client-core", "com.squareup.okio:okio")
//   checked against configurations.getByName("commonMainApi").allDependencies
//   (config names: RuntimeClasspath filter already matches jvmRuntimeClasspath via ignoreCase)

// Publishing: KMP creates one publication per target automatically. Central still wants a
// javadoc jar on each:
val javadocJar = tasks.register<org.gradle.jvm.tasks.Jar>("javadocJar") {
    archiveClassifier.set("javadoc")
}
publishing {
    publications.withType<MavenPublication>().configureEach {
        artifact(javadocJar)
        pom {
            name.set("ferry")
            description.set(
                "Resumable, verified downloads of AI model repositories from HuggingFace, " +
                    "ModelScope or Ollama — never a partial or corrupt model, never starts a " +
                    "download the device can't finish.",
            )
        }
    }
}
apply(from = "$rootDir/gradle/publishing.gradle.kts")
```

(Carry over the existing comment blocks — 0.x rationale, embeddability KDocs — into the new file; only the mechanics above change. Check `gradle/publishing.gradle.kts` still applies cleanly to per-target publications; adjust its publication references if it assumed the single `release` publication.)

- [ ] **Step 4: Extract the platform leaves**

`Platform.kt` (commonMain — and delete the JVM bodies from `Ferry.kt`/`SpaceCheck.kt`):

```kotlin
package dev.thuat.ferry

import io.ktor.client.HttpClient
import okio.Path

/** Per-platform default engine: OkHttp on JVM, Darwin on iOS. */
internal expect fun defaultHttpClient(): HttpClient

/** Free bytes on the volume holding [path]. [path] must exist (callers probe nearestExistingAncestor). */
internal expect fun availableBytes(path: Path): Long
```

`Platform.jvm.kt`:

```kotlin
package dev.thuat.ferry

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okio.Path

internal actual fun defaultHttpClient(): HttpClient = HttpClient(OkHttp)

internal actual fun availableBytes(path: Path): Long = path.toFile().usableSpace
```

`Platform.apple.kt`:

```kotlin
package dev.thuat.ferry

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber

internal actual fun defaultHttpClient(): HttpClient = HttpClient(Darwin)

@OptIn(ExperimentalForeignApi::class)
internal actual fun availableBytes(path: Path): Long {
    val attributes = NSFileManager.defaultManager
        .attributesOfFileSystemForPath(path.toString(), null)
        ?: return 0L
    return (attributes[NSFileSystemFreeSize] as? NSNumber)?.longLongValue ?: 0L
}
```

- [ ] **Step 5: Sweep the moved sources for JVM leftovers.** `./gradlew :ferry:compileKotlinIosSimulatorArm64` and fix what it flags — expected: only the two tests marked `// jvm-only` in Tasks 2/6. `git mv` those into `ferry/src/jvmTest/kotlin/dev/thuat/ferry/JvmEmbeddabilityTest.kt` (interceptor test) and fold the default-probe test into it. Delete `junit:junit` if nothing references it.

- [ ] **Step 6: Green on both platforms**

Run: `./gradlew :ferry:jvmTest :ferry:iosSimulatorArm64Test`
Expected: PASS on both. This is the spec's gate: JVM parity is the regression bar, iOS is the new ground.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: ferry core is Kotlin Multiplatform (jvm + iosArm64/iosSimulatorArm64/iosX64)"
```

---

### Task 8: Consumers, publishing sanity, docs

**Files:**
- Modify: `ferry-work/src/main/java/dev/thuat/ferry/work/*.kt` (call sites only)
- Modify: `sample/src/main/java/dev/thuat/ferry/sample/*.kt` (call sites only)
- Modify: `README.md`, `CHANGELOG.md` (create if absent), `docs/` pages naming OkHttp/File

- [ ] **Step 1: Fix `:ferry-work` call sites.** `grep -rn "download(\|Complete(\|OkHttpClient\|java.io.File" ferry-work/src` — convert each: `File` arguments → `file.toOkioPath()` (add `okio.Path.Companion.toOkioPath` import; okio is on ferry's api so it resolves), `RepoProgress.Complete(_, dir)` destructures now yield `Path` (convert back with `dir.toFile()` where WorkManager Data needs a String path, use `dir.toString()`). Run: `./gradlew :ferry-work:test` — PASS.

- [ ] **Step 2: Fix `:sample` the same way.** Its `SampleViewModel`/`ProgressMapping` touch `RepoProgress` and directories. Run: `./gradlew :sample:assembleDebug :sample:test` — PASS.

- [ ] **Step 3: Publishing dry run.** `./gradlew :ferry:publishToMavenLocal` — expected artifacts in `~/.m2/repository/dev/thuat/`: `ferry` (root/metadata), `ferry-jvm`, `ferry-iosarm64`, `ferry-iossimulatorarm64`, `ferry-iosx64`, each with sources + javadoc jars. Fix `gradle/publishing.gradle.kts` signing/POM wiring if any publication lacks them.

- [ ] **Step 4: Docs.** README install snippet: KMP consumers add `dev.thuat:ferry` to `commonMain` (Gradle resolves the target artifact); JVM-only consumers unchanged coordinates. CHANGELOG entry for 0.2.0 naming exactly the two breaking changes (Path-for-File, HttpClient-for-OkHttpClient) + the migration one-liners from the spec (`file.toOkioPath()`, `HttpClient(OkHttp) { engine { preconfigured = theirClient } }`). Sweep `docs/` for pages showing `OkHttpClient` or `File` in snippets.

- [ ] **Step 5: Full-repo gate.** `./gradlew build` (root) — everything green.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: ferry-work and sample on the 0.2.0 KMP API; changelog and docs updated"
```

---

## Acceptance (from the spec)

- [ ] `./gradlew :ferry:jvmTest :ferry:iosSimulatorArm64Test` green.
- [ ] From a KMP consumer's `commonMain`: `Ferry.huggingFace().download("taobao-mnn/Qwen3-0.6B-MNN", targetDir) { ... }` compiles for jvm + iOS (MnnChat is the real check; a scratch KMP module consuming mavenLocal 0.2.0 is the local stand-in).
- [ ] iOS simulator smoke: small real repo downloads with resume + verification (manual, part of MnnChat integration — not automated here).

## Risks the implementer must keep in view (from spec + this planning pass)

- Darwin engine Range/206 behavior: covered by MockEngine fixtures in commonTest, but MockEngine bypasses the engine — the simulator smoke is the only true Darwin check.
- `FileHandle.flush()` fsync semantics (Task 3 Step 6) — verify, don't assume.
- resolveInside went lexical (Task 4 Step 3) — the known-limitations entry is part of the port, not optional.
- Hash-on-commit stays (spec): do NOT wire HashingSink into the streaming path; `Sha256.of` re-reads on verify, same as today.
- Ktor `expectSuccess` default is false, but set it explicitly in ResumableDownloader anyway (Task 3) — a host-configured client may have flipped it globally, and a thrown `ResponseException` would bypass the `HTTP <code>` failure message contract.
