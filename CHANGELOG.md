# Changelog

## 0.2.0

`:ferry` is now a Kotlin Multiplatform library (JVM + iOS), published as `dev.thuat:ferry` with
target-specific artifacts (`ferry-jvm`, `ferry-iosarm64`, `ferry-iossimulatorarm64`, `ferry-iosx64`)
that Gradle resolves automatically. Two breaking changes for existing JVM/Android consumers:

- **`java.io.File` → `okio.Path`.** `RepoDownloader.download`/`abandonStaging`/`stagedBytes`,
  `ResumableDownloader.download`, and `RepoProgress.Complete.dir` all take or carry an `okio.Path`
  now, not a `java.io.File` — a plain `File` has no Kotlin/Native equivalent, and `okio.Path` is
  the multiplatform stand-in. Migration: `file.toOkioPath()` (`import okio.Path.Companion.toOkioPath`)
  going in; `path.toFile()` coming back out.
- **`OkHttpClient` → Ktor `HttpClient`.** `Ferry.huggingFace()`/`modelScope()`/`ollama()`,
  `HuggingFace`, and `ResumableDownloader` take a Ktor `HttpClient` now, not an `OkHttpClient` —
  Ktor is what has an engine for both JVM (OkHttp) and iOS (Darwin). Migration: wrap an existing
  `OkHttpClient` rather than replace it — `HttpClient(OkHttp) { engine { preconfigured = theirClient } }`
  (`io.ktor:ktor-client-okhttp`, already on `:ferry`'s own `api` configuration for a JVM/Android
  consumer, so no new dependency to add).

Additive, not breaking: `Ferry.huggingFace()`/`modelScope()`/`ollama()` also take a `fileSystem:
FileSystem` parameter now, defaulting to the platform's real filesystem — a caller that doesn't
already customize the client or dispatcher needs no code change.

Also: the whole repo (`:ferry`, `:ferry-work`, `:sample`) now builds on Kotlin 2.1.21, up from 2.0.21
— required for Ktor's native (iOS) klibs, which `:ferry` now depends on directly.

`:ferry-work` is now `0.2.0` too, bumped in lockstep: it depends on `:ferry`'s `okio.Path`-based API
above, and its own `0.1.0` release is immutably on Maven Central compiled against `:ferry` `0.1.0`'s
`java.io.File`-based API, so it cannot be reused against this release.

## 0.1.0

First release. HuggingFace, ModelScope and Ollama downloads on the JVM/Android, with resumable
transfers, SHA-256 verification, and a free-space preflight check. `:ferry-work` adds optional
WorkManager backgrounding.
