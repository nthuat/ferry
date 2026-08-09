# Ferry KMP Conversion — Design Spec

**Date:** 2026-08-09
**Status:** Approved design — implementation by ferry maintainer
**Driver:** MnnChat (github.com/nthuat/mnnchat), a KMP Android+iOS app, needs
ferry as its model downloader on BOTH platforms. Kotlin/Native cannot consume
JVM libraries, so `:ferry` must become a Kotlin Multiplatform module.

## Goal

`:ferry` compiles for `jvm` (Android/desktop, unchanged behavior), `iosArm64`,
`iosSimulatorArm64`, and `iosX64`, from a single `commonMain` codebase.
`:ferry-work` (WorkManager backgrounding) stays Android-only and unchanged
apart from following the core API change below.

## Non-goals

- iOS background downloads (`NSURLSession` background sessions). Foreground
  coroutine downloads are sufficient for the first consumer; a `ferry-darwin`
  backgrounding module can mirror `ferry-work` later.
- New features, new hubs, or API redesign beyond what the platform move forces.
- Publishing changes beyond adding KMP targets to the existing
  `dev.thuat:ferry` Maven coordinates (0.x line continues).

## Approach (decided)

Single-codebase conversion — port `commonMain` wholesale onto two
multiplatform libraries, rather than expect/actual per platform:

| Today (JVM-only) | After (commonMain) |
|---|---|
| OkHttp (`OkHttpClient`, `Request`, `Response`, `HttpUrl`) | **Ktor client** (`HttpClient`, streaming body reads, `Range` headers) |
| `java.io.File`, `FileOutputStream` | **Okio `FileSystem` / `Path` / `Sink`** |
| `java.security.MessageDigest` (SHA-256) | **Okio `HashingSink.sha256`** |

Rationale: Okio covers both file I/O and hashing multiplatform (one
dependency, battle-tested); Ktor is the only mainstream KMP HTTP client.
Engines: `OkHttp` engine on JVM (keeps current TLS/proxy behavior and
interceptor compatibility), `Darwin` engine on iOS.

expect/actual is reserved for the few true platform leaves (see below) —
everything with logic lives once in `commonMain`.

## Public API changes (breaking, allowed on 0.x)

1. `java.io.File` disappears from the API. `RepoDownloader.download(...)`
   takes/returns `okio.Path`; `RepoProgress.Complete(dir: File)` becomes
   `Complete(dir: Path)`. JVM consumers write
   `file.toOkioPath()` / `path.toFile()` (okio provides both).
2. `Ferry.huggingFace(client: OkHttpClient = ...)` and siblings change their
   `client` parameter to Ktor `HttpClient`. Default remains "a sensible
   client per platform": `HttpClient(OkHttp)` on JVM, `HttpClient(Darwin)`
   on iOS, via an internal `expect fun defaultHttpClient(): HttpClient`.
   Consumers who passed a configured `OkHttpClient` (interceptors, timeouts)
   pass `HttpClient(OkHttp) { engine { preconfigured = theirClient } }`.
3. Everything else — `RepoProgress` states, hub selection
   (`huggingFace`/`modelScope`/`ollama`), staging/atomic-commit/resume/
   checksum semantics, `SpaceCheck` behavior — keeps its current shape and
   contract.

`:ferry-work` updates its internal calls to the new signatures; its own
public surface (WorkManager scheduling) is unchanged.

## Module structure after conversion

```
ferry/
  build.gradle.kts          # kotlin("multiplatform"); targets: jvm, iosArm64,
                            # iosSimulatorArm64, iosX64
  src/commonMain/kotlin/dev/thuat/ferry/
    Ferry.kt                # factory; expect defaultHttpClient()
    RepoDownloader.kt       # orchestration + RepoProgress (Path, not File)
    ResumableDownloader.kt  # Ktor streaming + Range resume + Okio sinks
    HuggingFace.kt          # hub API calls via Ktor
    ModelScope.kt
    Ollama.kt
    ModelHub.kt             # interface, unchanged shape
    SpaceCheck.kt           # uses expect availableBytes(path)
    Sha256.kt               # thin wrapper over Okio HashingSink (or deleted
                            # if HashingSink is used inline)
  src/jvmMain/kotlin/       # actuals: defaultHttpClient()=OkHttp engine,
                            # availableBytes()=File.getUsableSpace
  src/appleMain/kotlin/     # actuals: defaultHttpClient()=Darwin engine,
                            # availableBytes()=NSFileManager
                            #   attributesOfFileSystemForPath (free space)
  src/commonTest/kotlin/    # ported test suite (see Testing)
  src/jvmTest/, appleTest/  # platform-leaf tests only
ferry-work/                 # unchanged Android library, bumps to new core API
sample/                     # JVM/Android sample, updated call sites
```

## Platform leaves (the only expect/actual surface)

| expect | jvm actual | apple actual |
|---|---|---|
| `defaultHttpClient(): HttpClient` | OkHttp engine | Darwin engine |
| `availableBytes(path: Path): Long` | `File.getUsableSpace()` | `NSFileManager.attributesOfFileSystemForPath` `NSFileSystemFreeSize` |
| `FileSystem` instance | `FileSystem.SYSTEM` | `FileSystem.SYSTEM` (okio provides both — likely zero actual needed; keep only if a sandbox-path quirk appears) |

Everything else must live in `commonMain`. A PR that adds an expect/actual
beyond these needs a recorded reason.

## Semantics that must survive the port (the contract)

These are ferry's selling points; each has existing tests that must keep
passing after the port:

1. **Staging + atomic commit** — files download to a staging location and
   move into place only after verification; a crashed download never leaves
   a partial file at the final path. (Okio `FileSystem.atomicMove`.)
2. **Resume** — interrupted downloads continue from the last byte via HTTP
   `Range`; re-running `download()` never re-fetches completed files.
3. **SHA-256 verification** — checksums validated before commit; mismatch
   surfaces as a typed failure, file not committed.
4. **Disk-space preflight** — refuses to start without space for the full
   repo (`CheckingSpace` progress state, typed failure).
5. **Progress reporting** — `RepoProgress` stream per current behavior
   (`CheckingSpace → Downloading(file, bytes, total) → Skipped/Verifying →
   Complete`).
6. **Cancellation** — coroutine cancellation stops the transfer promptly and
   leaves resumable staging state, not corruption.

## Testing

- Port the existing suite (`RepoDownloaderTest`, `ResumableDownloaderTest`,
  `Sha256Test`, `SpaceCheckTest`, `HuggingFaceTest`, `ModelScopeTest`,
  `OllamaTest`, `FerryTest`, `EmbeddabilityTest`) to `commonTest`.
- HTTP mocking: Ktor `MockEngine` replaces whatever OkHttp mocking exists
  today — hub tests inject it directly.
- Filesystem: okio `FakeFileSystem` for staging/atomic-commit/resume tests
  (this may *simplify* tests currently using temp dirs). This requires
  `RepoDownloader`/`ResumableDownloader` to take a `FileSystem` parameter
  (constructor, defaulted to the platform one) — acceptable API addition.
- Gate: full suite green on `jvmTest` AND `iosX64Test` (later
  `iosSimulatorArm64Test` on Apple-silicon CI). JVM behavior parity is the
  regression bar; iOS is the new ground.
- `EmbeddabilityTest`'s intent (no Android deps in core) gets a sharper
  enforcement for free: `commonMain` cannot import platform APIs at all.

## Consumer acceptance (definition of done)

MnnChat can, from `commonMain` of its own KMP module:

```kotlin
val downloader = Ferry.huggingFace()
downloader.download("taobao-mnn/Qwen3-0.6B-MNN", targetDir) { progress -> ... }
```

and this compiles and runs on Android (JVM) and iOS (Darwin) with resume,
verification, and atomic commit behaving per the contract section. A minimal
iOS smoke (download a small repo on the simulator) is part of acceptance.

## Risks / notes for the implementer

- Ktor streaming + Range on the Darwin engine: verify `Range` request
  headers and partial-content (206) handling behave identically to OkHttp —
  the resume tests will catch drift, run them against MockEngine with 206
  fixtures.
- Okio `HashingSink` computes hash while streaming — restructure
  `ResumableDownloader` so verification of resumed files re-hashes the
  existing prefix (current JVM code re-reads the file; keep that approach:
  hash-on-commit, not hash-on-stream, unless tests prove otherwise).
- Kotlin/Native concurrency: ferry is coroutine-based already; no shared
  mutable state should survive the port outside class instances. Avoid
  `@Volatile`/atomics unless a test forces one.
- Hub responses already use `kotlinx-serialization-json` (verified in
  `HuggingFace.kt`) — KMP-ready, no JSON work needed.
- The 0.x versioning note in the README already reserves the right to break
  sealed interfaces — this conversion is that break. Bump minor
  (e.g. 0.2.0), changelog the two API changes explicitly.
