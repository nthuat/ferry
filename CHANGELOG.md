# Changelog

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
  and synthetic default-argument bridge; the filter arrived as a separate overload, not an
  inserted parameter. Unlike 0.2.0, this release therefore does **not** force a lockstep
  `:ferry-work` bump — the published `ferry-work:0.2.0` keeps linking and working. It cannot
  *pass* a filter (its worker has no input key for one), so background filtered downloads need a
  future `:ferry-work` release; that is a missing feature, not a break.

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
